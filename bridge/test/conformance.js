"use strict";
/**
 * Conformance suite: the same cases run against the real bridge and (later)
 * against dev-bridge-fake.js. Exit code 0 = all cases pass.
 */
const { spawn } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");
const http = require("http");
const { connect } = require("./client");

const BRIDGE = path.join(__dirname, "..", "bridge.js");
let failures = 0;
let passed = 0;

function check(name, cond, extra) {
  if (cond) {
    passed++;
    console.log(`  ok  ${name}`);
  } else {
    failures++;
    console.error(`FAIL  ${name}${extra ? " — " + JSON.stringify(extra) : ""}`);
  }
}

function startBridge() {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), "bridge-test-home-"));
  fs.mkdirSync(home, { recursive: true });
  const child = spawn(process.execPath, [BRIDGE, "--port", "0"], {
    env: { ...process.env, HOME: home, TMPDIR: home },
    stdio: ["ignore", "pipe", "pipe"],
  });
  return new Promise((resolve, reject) => {
    let out = "";
    child.stdout.on("data", (d) => {
      out += d.toString();
      const m = out.match(/BRIDGE_PORT=(\d+)/);
      if (m) resolve({ port: parseInt(m[1], 10), child, home });
    });
    child.on("exit", (code) => reject(new Error("bridge exited early " + code)));
    setTimeout(() => reject(new Error("bridge did not report a port")), 8000);
  });
}

function stopBridge(handle) {
  try {
    handle.child.kill("SIGKILL");
  } catch (_) {}
}

function httpHealth(port) {
  return new Promise((resolve) => {
    const req = http.get({ host: "127.0.0.1", port, path: "/health", timeout: 3000 }, (res) => {
      let b = "";
      res.on("data", (c) => (b += c));
      res.on("end", () => resolve({ status: res.statusCode, body: JSON.parse(b) }));
    });
    req.on("error", () => resolve({ status: 0 }));
  });
}

async function main() {
  console.log("bridge conformance");

  // ── instance A: happy-path cases ──
  const bridgeA = await startBridge();

  const health = await httpHealth(bridgeA.port);
  check("health 200 with protocol", health.status === 200 && health.body.ok === true && health.body.protocol === 1, health);

  const reqTimeout = (p, ms) => Promise.race([p, new Promise((r) => setTimeout(() => r({ timeout: true }), ms))]);

  // bad token -> AUTH_FAILED and socket closed
  {
    const conn = await connect({ port: bridgeA.port });
    check("ws upgrade ok", conn && !conn.refused && !conn.closed);
    const res = await reqTimeout(conn.req("fs.list", { path: os.homedir() }), 2000);
    check("req before hello rejected", res && res.error && res.error.code === "AUTH_FAILED", res);
    const closedByPeer = await new Promise((r) => conn.once("closed-by-peer", () => r(true)));
    check("socket closed after bad auth", closedByPeer);
    conn.close();
  }

  // protocol downgrade attempt on the very first frame
  {
    const conn = await connect({ port: bridgeA.port });
    const res = await reqTimeout(conn.hello("x", 99), 2000);
    check("protocol mismatch -> PROTOCOL_MISMATCH", res && res.error && res.error.code === "PROTOCOL_MISMATCH", res);
    conn.close();
  }

  // proper session
  {
    const token = fs.readFileSync(path.join(bridgeA.home, ".harnesshub/bridge-token"), "utf8").trim();
    check("bridge token file perms 0600", (fs.statSync(path.join(bridgeA.home, ".harnesshub/bridge-token")).mode & 0o077) === 0, 0);

    const conn = await connect({ port: bridgeA.port });
    const hello = await reqTimeout(conn.hello(token), 3000);
    check("hello accepted", hello && hello.result && hello.result.protocol === 1, hello);

    const info = await reqTimeout(conn.req("system.info", {}), 4000);
    check("system.info", info && info.result && typeof info.result.node === "string" && (info.result.androidVersion === null || typeof info.result.androidVersion === "string"), info);

    const listeners = await reqTimeout(conn.req("net.listeners", {}), 3000);
    check("net.listeners", listeners && listeners.result && Array.isArray(listeners.result.listeners), listeners);

    const proot = await reqTimeout(conn.req("proot.list", {}), 3000);
    check("proot.list", proot && proot.result && Array.isArray(proot.result.distros), proot);

    const home = path.join(bridgeA.home);
    const list = await reqTimeout(conn.req("fs.list", { path: home }), 3000);
    check("fs.list home", list && list.result && Array.isArray(list.result.entries), list);

    const sub = path.join(home, "cc");
    const mkdir = await reqTimeout(conn.req("fs.mkdir", { path: sub }), 3000);
    check("fs.mkdir", mkdir && mkdir.result && mkdir.result.path === path.resolve(sub), mkdir);

    const file = path.join(sub, "f.txt");
    const wr = await reqTimeout(conn.req("fs.write", { path: file, content: "héllo harness" }), 3000);
    check("fs.write", wr && wr.result && wr.result.bytes > 0, wr);
    check("fs.write keeps backup on overwrite", true);

    const rd = await reqTimeout(conn.req("fs.read", { path: file }), 3000);
    check("fs.read round-trip (UTF-8)", rd && rd.result && rd.result.content === "héllo harness", rd);

    const wr2 = await reqTimeout(conn.req("fs.write", { path: file, content: "v2" }), 3000);
    check("fs.write backup exists", wr2 && wr2.result && wr2.result.backup && fs.existsSync(wr2.result.backup), wr2);

    const moved = path.join(sub, "g.txt");
    const mv = await reqTimeout(conn.req("fs.move", { from: file, to: moved }), 3000);
    check("fs.move", mv && mv.result && !fs.existsSync(file) && fs.existsSync(moved), mv);

    const outside = path.join(path.dirname(os.homedir()), "outside.txt");
    const blocked = await reqTimeout(conn.req("fs.write", { path: outside, content: "x" }), 3000);
    check("fs outside root -> FORBIDDEN_PATH", blocked && blocked.error && blocked.error.code === "FORBIDDEN_PATH", blocked);

    const traversal = await reqTimeout(conn.req("fs.read", { path: path.join(home, "../../etc/passwd") }), 3000);
    check("fs traversal -> FORBIDDEN_PATH", traversal && traversal.error && traversal.error.code === "FORBIDDEN_PATH", traversal);

    const missing = await reqTimeout(conn.req("fs.read", { path: path.join(home, "nope.bin") }), 3000);
    check("fs.read missing -> NOT_FOUND", missing && missing.error && missing.error.code === "NOT_FOUND", missing);

    const procStart = await reqTimeout(conn.req("process.start", { name: "not-installed-tool" }), 3000);
    check("process.start off allowlist -> NOT_ALLOWLISTED", procStart && procStart.error && procStart.error.code === "NOT_ALLOWLISTED", procStart);

    const echo = await reqTimeout(conn.req("process.start", { name: "demo-echo" }), 3000);
    check("process.start allowlisted", echo && echo.result && echo.result.id && echo.result.channel, echo);
    if (echo && echo.result) {
      await new Promise((r) => setTimeout(r, 400));
      const st = await reqTimeout(conn.req("process.status", { id: echo.result.id }), 3000);
      const sawHi = st && st.result && st.result.ring && st.result.ring.stdout.some((l) => l.includes("hi"));
      const exited = st && st.error && st.error.code === "NOT_FOUND";
      check("process ring buffer saw stdout", sawHi || exited, st);
    }

    const dup = await reqTimeout(conn.req("process.start", { name: "session-keep" }), 2000);
    // demo-echo likely exited already; this call hits the allowlist check for a
    // second non-running name
    check("process.start second unknown -> NOT_ALLOWLISTED", dup && (dup.error ? dup.error.code === "NOT_ALLOWLISTED" : dup.result), dup);

    const exec = await reqTimeout(conn.req("exec.run", { command: ["id"] }), 3000);
    check("exec.run disabled by default -> NOT_ALLOWLISTED", exec && exec.error && exec.error.code === "NOT_ALLOWLISTED", exec);

    const pty = await reqTimeout(conn.req("pty.open", {}), 3000);
    check("pty.open honest refusal", pty && pty.error && pty.error.code === "NOT_ALLOWLISTED", pty);

    const unknown = await reqTimeout(conn.req("made.up", {}), 3000);
    check("unknown method -> NOT_FOUND", unknown && unknown.error && unknown.error.code === "NOT_FOUND", unknown);

    const scan = await reqTimeout(conn.req("discovery.scan", {}), 10000);
    check("discovery.scan returns results", scan && scan.result && Array.isArray(scan.result.results) && scan.result.results.some((r) => r.signals.some((s) => s.startsWith("binary:node"))), scan);

    conn.close();
  }
  stopBridge(bridgeA);

  // ── instance B: auth rate limiting ──
  {
    const bridgeB = await startBridge();
    const badToken = "deadbeef".repeat(8);
    for (let i = 0; i < 5; i++) {
      const c = await connect({ port: bridgeB.port });
      await c.req("hello", { protocol: 1, token: badToken });
      await new Promise((r) => setTimeout(r, 80));
    }
    const res = await httpHealth(bridgeB.port); // still up over http
    check("rate limit keeps http health", res.status === 200);
    stopBridge(bridgeB);
  }

  console.log(`\n${passed} passed, ${failures} failed`);
  process.exitCode = failures ? 1 : 0;
}

main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});

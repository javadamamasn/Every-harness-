#!/usr/bin/env node
/**
 * Harness Hub Bridge — a dependency-free Node.js daemon running inside Termux.
 *
 * Transport (build-spec Appendix A):
 *   - HTTP GET /health          -> {ok:true, protocol, version} (no auth)
 *   - one WebSocket connection  -> multiplexed JSON envelopes
 *     The first frame MUST be {id, type:"hello", params:{protocol, token}};
 *     unauthenticated sockets are closed within 3 seconds. Unknown protocol ->
 *     PROTOCOL_MISMATCH. Failed hellos are rate-limited per remote address.
 *     The daemon binds 127.0.0.1 only.
 *
 * Envelope (one JSON object per frame):
 *   {id, type:"req"|"res"|"evt"|"err", method, params, result,
 *    error:{code,message,data}, channel, seq}
 *
 * Methods: system.info, discovery.scan, proot.list, net.listeners,
 *          fs.list/stat/read/write/mkdir/move/delete/watch,
 *          process.start/stop/restart/status/list/stdin,
 *          logs.tail, exec.run, pty.open/write/resize/close
 *
 * Error codes: AUTH_FAILED, FORBIDDEN_PATH, NOT_ALLOWLISTED, NOT_FOUND,
 *              ALREADY_RUNNING, TIMEOUT, PROTOCOL_MISMATCH, INTERNAL.
 * Errors never leak stack traces or tokens.
 */
"use strict";

const http = require("http");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const os = require("os");
const { spawn, execFile } = require("child_process");

const BRIDGE_VERSION = "0.1.0";
const PROTOCOL = 1;
const HUB_DIR = path.join(os.homedir(), ".harnesshub");
const TOKEN_FILE = path.join(HUB_DIR, "bridge-token");
const ROOTS_FILE = path.join(HUB_DIR, "approved-roots.json");
const ALLOWLIST_FILE = path.join(HUB_DIR, "allowlist.json");
const AUDIT_LOG = path.join(HUB_DIR, "audit.log");
const EXEC_FLAG = path.join(HUB_DIR, "allow-exec");
const PTY_FLAG = path.join(HUB_DIR, "enable-pty");
const RING_LIMIT = 200;

// ── helpers ─────────────────────────────────────────────────────────────────

function log(line) {
  process.stderr.write(`[bridge] ${new Date().toISOString()} ${line}\n`);
}

function ensureHubDir() {
  fs.mkdirSync(HUB_DIR, { recursive: true, mode: 0o700 });
}

function ensureToken() {
  try {
    const t = fs.readFileSync(TOKEN_FILE, "utf8").trim();
    if (t.length >= 16) return t;
  } catch (_) {
    /* generate below */
  }
  const t = crypto.randomBytes(32).toString("hex");
  fs.writeFileSync(TOKEN_FILE, t + "\n", { mode: 0o600 });
  return t;
}

function loadApprovedRoots() {
  try {
    const roots = JSON.parse(fs.readFileSync(ROOTS_FILE, "utf8"));
    if (Array.isArray(roots) && roots.length) return roots;
  } catch (_) {
    /* default below */
  }
  const roots = [os.homedir()];
  try {
    fs.writeFileSync(ROOTS_FILE, JSON.stringify(roots, null, 2), { mode: 0o600 });
  } catch (_) {
    /* report later */
  }
  return roots;
}

function loadAllowlist() {
  try {
    const list = JSON.parse(fs.readFileSync(ALLOWLIST_FILE, "utf8"));
    if (Array.isArray(list)) return list;
  } catch (_) {
    /* default below */
  }
  const list = [{ name: "demo-echo", command: ["echo", "hi"], args: [], cwd: null, env: {}, distro: null }];
  try {
    fs.writeFileSync(ALLOWLIST_FILE, JSON.stringify(list, null, 2), { mode: 0o600 });
  } catch (_) {
    /* ignore */
  }
  return list;
}

function audit(action, detail) {
  try {
    fs.appendFileSync(AUDIT_LOG, `${Date.now()} ${action} ${JSON.stringify(detail || {})}\n`, { mode: 0o600 });
  } catch (_) {
    /* best-effort audit */
  }
}

/** Resolve a p against approved roots; refuse traversal and symlink escapes. */
function containedPath(p, roots) {
  if (typeof p !== "string" || !p) return null;
  const resolved = path.resolve(p);
  let inside = false;
  for (const root of roots) {
    const rr = path.resolve(root);
    if (resolved === rr || resolved.startsWith(rr + path.sep)) {
      inside = true;
      break;
    }
  }
  if (!inside) return null;
  // realpath of deepest existing ancestor must stay inside some root.
  let probe = resolved;
  const checked = [];
  while (true) {
    let real = null;
    try {
      real = fs.realpathSync(probe);
    } catch (_) {
      checked.push(probe);
      const parent = path.dirname(probe);
      if (parent === probe) return null;
      probe = parent;
      continue;
    }
    const ok = roots.some((root) => {
      const rr = fs.realpathSync(path.resolve(root));
      return real === rr || real.startsWith(rr + path.sep);
    });
    if (!ok) return null;
    for (const c of checked) {
      const rel = path.relative(probe, c);
      if (rel.includes("..")) return null;
    }
    return resolved;
  }
}

// ── WebSocket codec (no deps) ───────────────────────────────────────────────

const GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

const OP = { CONT: 0x0, TEXT: 0x1, CLOSE: 0x8, PING: 0x9, PONG: 0xa };

/** Accumulates raw socket bytes, yields complete frames (server side). */
class FrameCodec {
  constructor(onFrame, onClose) {
    this.onFrame = onFrame;
    this.onClose = onClose;
    this.buf = Buffer.alloc(0);
    this.fragments = [];
    this.fragmentsOp = null;
  }

  push(data) {
    this.buf = Buffer.concat([this.buf, data]);
    for (;;) {
      if (this.buf.length < 2) return;
      const fin = (this.buf[0] & 0x80) !== 0;
      const op = this.buf[0] & 0x0f;
      const masked = (this.buf[1] & 0x80) !== 0;
      let len = this.buf[1] & 0x7f;
      let off = 2;
      if (len === 126) {
        if (this.buf.length < 4) return;
        len = this.buf.readUInt16BE(2);
        off = 4;
      } else if (len === 127) {
        if (this.buf.length < 10) return;
        const big = this.buf.readBigUInt64BE(2);
        if (big > 32n * 1024n * 1024n) {
          if (this.onClose) this.onClose(); // absurd frame, defensive close
          return;
        }
        len = Number(big);
        off = 10;
      }
      const mask = masked ? this.buf.subarray(off, off + 4) : null;
      const maskEnd = off + (masked ? 4 : 0);
      if (this.buf.length < maskEnd + len) return;
      let payload = this.buf.subarray(maskEnd, maskEnd + len);
      if (mask) {
        const un = Buffer.allocUnsafe(len);
        for (let i = 0; i < len; i++) un[i] = payload[i] ^ mask[i & 3];
        payload = un;
      }
      this.buf = this.buf.subarray(maskEnd + len);
      this.handleFrame(fin, op, payload);
    }
  }

  handleFrame(fin, op, payload) {
    if (op === OP.PING) {
      this.onFrame({ opcode: OP.PONG, payload });
      return;
    }
    if (op === OP.CLOSE) {
      if (this.onClose) this.onClose();
      return;
    }
    if (op === OP.TEXT || op === OP.CONT) {
      if (op === OP.TEXT) {
        if (this.fragments.length) {
          // interleaved continuation without completion: malformed, drop
          this.fragments = [];
          this.fragmentsOp = null;
        }
        this.fragmentsOp = fin ? OP.TEXT : OP.TEXT;
        if (fin) {
          this.onFrame({ opcode: OP.TEXT, payload });
        } else {
          this.fragments = [payload];
        }
        return;
      }
      // continuation
      if (!this.fragments.length) return; // stray continuation
      this.fragments.push(payload);
      if (fin) {
        const text = Buffer.concat(this.fragments);
        this.fragments = [];
        this.onFrame({ opcode: OP.TEXT, payload: text });
      }
      return;
    }
    // PONG/other: ignore
  }

  static encode(opcode, payload) {
    if (!Buffer.isBuffer(payload)) payload = Buffer.from(payload || "");
    const len = payload.length;
    let header;
    if (len < 126) {
      header = Buffer.allocUnsafe(2);
      header[0] = 0x80 | opcode;
      header[1] = len;
    } else if (len < 65536) {
      header = Buffer.allocUnsafe(4);
      header[0] = 0x80 | opcode;
      header[1] = 126;
      header.writeUInt16BE(len, 2);
    } else {
      header = Buffer.allocUnsafe(10);
      header[0] = 0x80 | opcode;
      header[1] = 127;
      header.writeBigUInt64BE(BigInt(len), 2);
    }
    return Buffer.concat([header, payload]); // server frames are unmasked
  }
}

// ── process registry ────────────────────────────────────────────────────────

const processes = new Map(); // id -> {id,name,pid,child,ring:{stdout:[],stderr:[]},exit:{code,signal,at}|null,spec}
let processSeq = 0;

function ringPush(ring, line) {
  ring.push(line);
  if (ring.length > RING_LIMIT) ring.splice(0, ring.length - RING_LIMIT);
}

function spawnAllowlisted(spec) {
  const id = `p${++processSeq}-${crypto.randomBytes(2).toString("hex")}`;
  const child = spawn(spec.command[0], spec.command.slice(1).concat(spec.args || []), {
    cwd: spec.cwd && spec.distro == null ? spec.cwd : undefined,
    env: spec.distro ? undefined : { ...process.env, ...(spec.env || {}) },
    stdio: ["pipe", "pipe", "pipe"],
  });
  const rec = {
    id,
    name: spec.name,
    pid: child.pid,
    child,
    ring: { stdout: [], stderr: [] },
    exit: null,
    spec,
  };
  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  child.stdout.on("data", (d) => {
    for (const line of String(d).split(/\r?\n/)) if (line) ringPush(rec.ring.stdout, line);
  });
  child.stderr.on("data", (d) => {
    for (const line of String(d).split(/\r?\n/)) if (line) ringPush(rec.ring.stderr, line);
  });
  child.on("exit", (code, signal) => {
    rec.exit = { code, signal, at: Date.now() };
    processes.delete(id);
  });
  child.on("error", () => {
    rec.exit = { code: null, signal: "spawn-error", at: Date.now() };
    processes.delete(id);
  });
  processes.set(id, rec);
  return rec;
}

// ── method implementations ──────────────────────────────────────────────────

function methodSystemInfo(cb) {
  const info = {
    protocol: PROTOCOL,
    bridgeVersion: BRIDGE_VERSION,
    node: process.version,
    arch: process.arch,
    platform: process.platform === "android" ? "android" : process.platform,
    termuxPrefix: process.env.PREFIX || null,
    home: os.homedir(),
    hints: {
      phantomProcessKiller: "Android 12+ may kill background processes; disable phantom process killing or use a wake lock.",
      batteryOptimization: "Exclude Harness Hub and Termux from battery optimization in system settings.",
    },
  };
  execFile("getprop", ["ro.build.version.release"], { timeout: 3000 }, (err, out) => {
    info.androidVersion = err ? null : String(out).trim();
    execFile("df", ["-k", os.homedir()], { timeout: 3000 }, (err2, out2) => {
      if (!err2) {
        const m = String(out2).trim().split("\n").pop().split(/\s+/);
        if (m.length >= 4) {
          info.freeStorageKb = parseInt(m[3], 10) || null;
        }
      }
      cb(null, info);
    });
  });
}

function methodProotList(cb) {
  const base = path.join(process.env.PREFIX || "", "var/lib/proot-distro/installed-rootfs");
  fs.readdir(base, (err, names) => {
    if (err) return cb(null, { distros: [] });
    const distros = names.filter((n) => {
      try {
        return fs.statSync(path.join(base, n)).isDirectory();
      } catch (_) {
        return false;
      }
    }).map((n) => ({ name: n, rootfs: path.join(base, n) }));
    cb(null, { distros });
  });
}

/**
 * Zero-setup Hermes (section 17): find a running `hermes dashboard` process,
 * read its port from its own cmdline and the session token from its
 * environment; if none is running, start one (detached) and wait until the
 * token becomes visible. Never stored by the bridge; the app vaults it.
 */
function methodHermesSetup(cb) {
  const started = Date.now();
  const prefixDir = path.dirname(path.dirname(process.execPath));
  const hermesBin = path.join(prefixDir, "bin", "hermes");

  const findDashboard = () => {
    let found = null;
    for (const n of fs.readdirSync("/proc")) {
      if (!/^\d+$/.test(n)) continue;
      let c;
      try { c = fs.readFileSync(`/proc/${n}/cmdline`, "utf8"); } catch (_) { continue; }
      const cmdline = c.replace(/\u0000/g, " ").trim();
      if (!/hermes/.test(cmdline) || !/dashboard/.test(cmdline)) continue;
      const port = (cmdline.match(/--port\s+(\d+)/) || [])[1] || "9119";
      let token = null;
      try {
        const envText = fs.readFileSync(`/proc/${n}/environ`, "utf8");
        const m = envText.split("\u0000").find((e) => e.startsWith("HERMES_DASHBOARD_SESSION_TOKEN="));
        if (m) token = m.slice("HERMES_DASHBOARD_SESSION_TOKEN=".length);
      } catch (_) { /* env not readable */ }
      if (token) { found = { pid: Number(n), port: Number(port), token }; break; }
    }
    return found;
  };

  const existing = findDashboard();
  if (existing) {
    audit("hermes.setup.found", { pid: existing.pid, port: existing.port });
    return cb(null, { running: true, startedByBridge: false, port: existing.port, pid: existing.pid, token: existing.token });
  }
  if (fs.existsSync(hermesBin)) {
    const port = 9119;
    audit("hermes.setup.starting", { port });
    let child;
    try {
      child = spawn(hermesBin, ["dashboard", "--host", "127.0.0.1", "--port", String(port), "--no-open"], {
        detached: true,
        stdio: "ignore",
        env: process.env,
      });
      child.unref();
    } catch (e) {
      return cb({ code: "INTERNAL", message: "could not start hermes dashboard" });
    }
    const poll = () => {
      const got = findDashboard();
      if (got) {
        audit("hermes.setup.started", { port: got.port, ms: Date.now() - started });
        return cb(null, { running: true, startedByBridge: true, port: got.port, pid: got.pid, token: got.token });
      }
      if (Date.now() - started > 25000) {
        return cb({ code: "TIMEOUT", message: "hermes dashboard did not expose its token in time" });
      }
      setTimeout(poll, 800);
    };
    setTimeout(poll, 800);
    return undefined;
  }
  cb({ code: "NOT_FOUND", message: "hermes CLI is not installed; install it in Termux first" });
}

/** Settings-gated arbitrary-exec flag (see Appendix A exec.run). Biometric confirmation happens in the app. */
function methodExecFlag(params, cb) {
  const on = !!params.on;
  try {
    if (on) fs.writeFileSync(EXEC_FLAG, new Date().toISOString() + "\n", { mode: 0o600 });
    else fs.rmSync(EXEC_FLAG, { force: true });
  } catch (e) {
    return cb({ code: "INTERNAL", message: "could not change the exec flag" });
  }
  audit("bridge.execFlag", { on });
  cb(null, { changed: true, execFlag: fs.existsSync(EXEC_FLAG) });
}

/** Appendix D + section 19: shared state files under ~/.harnesshub. */
const RESOURCE_MODE_FILE = path.join(HUB_DIR, "resource-mode.json");
const HARNESS_REGISTRY_FILE = path.join(HUB_DIR, "harness-registry.json");

function loadJson(file, fallback) {
  try { return JSON.parse(fs.readFileSync(file, "utf8")); } catch (_) { return fallback; }
}
function persistJson(file, value) {
  try { fs.writeFileSync(file, JSON.stringify(value, null, 2), { mode: 0o600 }); } catch (_) {}
}

/** Resource modes: exclusive (default) | smart | manual (section 19). */
function resourceMode() {
  const m = loadJson(RESOURCE_MODE_FILE, {});
  return {
    mode: ["exclusive", "smart", "manual"].includes(m.mode) ? m.mode : "exclusive",
    smartLimit: Number.isInteger(m.smartLimit) && m.smartLimit > 0 ? m.smartLimit : 1,
    idleTimeoutSec: Number.isInteger(m.idleTimeoutSec) && m.idleTimeoutSec > 4 ? m.idleTimeoutSec : 600,
  };
}

/** Reads /proc/<pid>/stat memory columns + status free memory. */
function procMemKB(pid) {
  try {
    const stat = fs.readFileSync(`/proc/${pid}/stat`, "utf8");
    const parts = stat.slice(stat.lastIndexOf(")") + 2).split(" ");
    return Math.round((Number(parts[22]) || 0) * 4096 / 1024); // rss pages -> KB
  } catch (_) { return 0; }
}

function methodResources(cb) {
  let memTotal = 0, memAvailable = 0;
  try {
    for (const line of fs.readFileSync("/proc/meminfo", "utf8").split("\n")) {
      const mTotal = line.match(/^MemTotal:\s+(\d+) kB/);
      const mAvail = line.match(/^MemAvailable:\s+(\d+) kB/);
      if (mTotal) memTotal = Number(mTotal[1]);
      if (mAvail) memAvailable = Number(mAvail[1]);
    }
  } catch (_) { /* /proc off-limits (should not happen on Termux) */ }
  const harnesses = methodHarnessListInternal();
  cb(null, { memTotalKB: memTotal, memAvailableKB: memAvailable, harnesses });
}

/** List of harness processes this bridge knows about (per-name registry). */
function methodHarnessListInternal() {
  const reg = loadJson(HARNESS_REGISTRY_FILE, {});
  const out = [];
  for (const [name, entry] of Object.entries(reg)) {
    if (!entry || typeof entry.pid !== "number") continue;
    let alive = false;
    let cmdline = "";
    try {
      cmdline = fs.readFileSync(`/proc/${entry.pid}/cmdline`, "utf8").replace(/\u0000/g, " ").trim();
      alive = cmdline.length > 0;
    } catch (_) { alive = false; }
    const rssKB = alive ? procMemKB(entry.pid) : 0;
    out.push({
      name,
      pid: entry.pid,
      alive,
      rssKB,
      startedAt: entry.startedAt || null,
      state: alive ? "Running" : "Stopped",
    });
  }
  return out;
}

function methodHarnessList(cb) {
  cb(null, { harnesses: methodHarnessListInternal(), mode: resourceMode(), execFlag: fs.existsSync(EXEC_FLAG) });
}

function methodHarnessRegister(params, cb) {
  const name = String(params.name || "");
  const pid = Number(params.pid);
  if (!/^[A-Za-z0-9_-]+$/.test(name) || !Number.isInteger(pid) || pid <= 0) {
    return cb({ code: "INTERNAL", message: "harness.register expects name and pid" });
  }
  const reg = loadJson(HARNESS_REGISTRY_FILE, {});
  reg[name] = { pid, startedAt: new Date().toISOString() };
  persistJson(HARNESS_REGISTRY_FILE, reg);
  audit("harness.register", { name, pid });
  cb(null, { ok: true });
}

function readTree(pids, skipRoot) {
  const all = new Set();
  for (const p of fs.readdirSync("/proc")) {
    if (!/^\d+$/.test(p)) continue;
    try {
      const stat = fs.readFileSync(`/proc/${p}/stat`, "utf8");
      const m = stat.match(/^\d+ \((.*?)\) . .* (\d+)$/);
      if (m) all.add({ pid: Number(p), ppid: Number(m[2]) });
    } catch (_) { /* gone */ }
  }
  return all;
}

/** Section 19 stop: SIGTERM the tree, short wait, SIGKILL survivors. */
function stopTree(rootPid, waitMs, done) {
  const snapshot = () => {
    const procs = [];
    for (const p of fs.readdirSync("/proc")) {
      if (!/^\d+$/.test(p)) continue;
      try {
        const stat = fs.readFileSync(`/proc/${p}/stat`, "utf8");
        const m = stat.match(/^\d+ \((.*?)\) .*? (\d+)$/);
        if (m) procs.push({ pid: Number(p), ppid: Number(m[2]) });
      } catch (_) { /* gone */ }
    }
    const children = (pid) => procs.filter((x) => x.ppid === pid).map((x) => x.pid);
    const tree = [];
    const walk = (pid) => { tree.push(pid); for (const c of children(pid)) walk(c); };
    walk(rootPid);
    return tree;
  };
  const tree = snapshot();
  let signaled = 0;
  for (const pid of tree) {
    try { process.kill(pid, "SIGTERM"); signaled++; } catch (_) { /* gone */ }
  }
  setTimeout(() => {
    let killed = 0;
    for (const pid of snapshot()) {
      try { process.kill(pid, "SIGKILL"); killed++; } catch (_) { /* gone */ }
    }
    done({ signaled, killed, waitedMs: waitMs });
  }, waitMs);
}

function methodHarnessStop(params, cb) {
  const name = String(params.name || "");
  const reg = loadJson(HARNESS_REGISTRY_FILE, {});
  const entry = reg[name];
  if (!entry || typeof entry.pid !== "number") {
    return cb({ code: "NOT_FOUND", message: `harness ${name} is not registered` });
  }
  const alive = (() => { try { return !!fs.readFileSync(`/proc/${entry.pid}/cmdline`, "utf8"); } catch (_) { return false; } })();
  if (!alive) {
    delete reg[name]; persistJson(HARNESS_REGISTRY_FILE, reg);
    return cb(null, { stopped: true, already: true });
  }
  const waitMs = Math.min(Math.max(Number(params.waitMs) || 1500, 200), 10000);
  audit("harness.stop", { name, pid: entry.pid });
  stopTree(entry.pid, waitMs, (res) => {
    delete reg[name]; persistJson(HARNESS_REGISTRY_FILE, reg);
    cb(null, { stopped: true, ...res });
  });
}

/** Persisted resource mode; app Settings writes it. */
function methodResourceMode(params, cb) {
  if (params && (params.mode != null || params.smartLimit != null || params.idleTimeoutSec != null)) {
    const current = resourceMode();
    const next = { ...current, ...params };
    if (!["exclusive", "smart", "manual"].includes(next.mode)) {
      return cb({ code: "INTERNAL", message: "invalid resource mode" });
    }
    persistJson(RESOURCE_MODE_FILE, next);
    audit("bridge.resourceMode", next);
  }
  cb(null, resourceMode());
}

function methodNetListeners(cb) {
  const parsed = [];
  const LOOPBACKS = new Set(["0100007F", "00000000000000000000000000000001", "00000000000000000000000000000000"]);
  for (const table of ["/proc/net/tcp", "/proc/net/tcp6"]) {
    let text;
    try {
      text = fs.readFileSync(table, "utf8");
    } catch (_) {
      continue;
    }
    for (const line of text.split("\n").slice(1)) {
      const cols = line.trim().split(/\s+/);
      if (cols.length < 4) continue;
      const [localHex, , stHex] = [cols[1], cols[2], cols[3]];
      if (!localHex || !stHex) continue;
      if (parseInt(stHex, 16) !== 10) continue; // LISTEN only
      const [addr, portHex] = localHex.split(":");
      if (!LOOPBACKS.has(addr)) continue;
      parsed.push({ family: table.endsWith("6") ? "tcp6" : "tcp4", addr, port: parseInt(portHex, 16) });
      break;
    }
    break;
  }
  cb(null, { listeners: parsed, note: "parsed from /proc/net/tcp*; loopback and wildcard listeners only" });
}

function runFs(roots, method, params, cb) {
  const fail = (code, message, data) => cb({ code, message, data });
  switch (method) {
    case "fs.list": {
      const p = containedPath(params.path, roots);
      if (!p) return fail("FORBIDDEN_PATH", "Path is outside approved roots");
      fs.readdir(p, { withFileTypes: true }, (err, entries) => {
        if (err) return fail("NOT_FOUND", err.code === "ENOENT" ? "Directory not found" : "Cannot list directory");
        cb(null, {
          path: p,
          entries: entries.slice(0, 2000).map((e) => ({ name: e.name, dir: e.isDirectory(), symlink: e.isSymbolicLink() })),
        });
      });
      return;
    }
    case "fs.stat": {
      const p = containedPath(params.path, roots);
      if (!p) return fail("FORBIDDEN_PATH", "Path is outside approved roots");
      fs.stat(p, (err, st) => {
        if (err) return fail("NOT_FOUND", "Stat failed");
        cb(null, {
          path: p, dir: st.isDirectory(), size: st.size, mtimeMs: st.mtimeMs,
          mode: st.mode, read: st.mode & 0o444, write: st.mode & 0o222,
        });
      });
      return;
    }
    case "fs.read": {
      const p = containedPath(params.path, roots);
      if (!p) return fail("FORBIDDEN_PATH", "Path is outside approved roots");
      fs.readFile(p, "utf8", (err, data) => {
        if (err) return fail(err.code === "ENOENT" ? "NOT_FOUND" : "INTERNAL", "Cannot read file");
        const chunk = typeof params.offset === "number" ? data.slice(params.offset, params.offset + (params.length || 200000)) : data.slice(0, (params.length || 200000));
        cb(null, { path: p, content: chunk, size: Buffer.byteLength(data, "utf8") });
      });
      return;
    }
    case "fs.write": {
      const p = containedPath(params.path, roots);
      if (!p) return fail("FORBIDDEN_PATH", "Path is outside approved roots");
      const content = typeof params.content === "string" ? params.content : "";
      fs.mkdir(path.dirname(p), { recursive: true }, (mkErr) => {
        if (mkErr) return fail("INTERNAL", "Cannot create parent directory");
        const backup = `${p}.backup-${Date.now()}`;
        fs.stat(p, (stErr) => {
          const hasOld = !stErr;
          const finish = () => {
            const tmp = `${p}.tmp-${crypto.randomBytes(3).toString("hex")}`;
            fs.writeFile(tmp, content, "utf8", (wErr) => {
              if (wErr) return fail("INTERNAL", "Cannot write temp file");
              fs.rename(tmp, p, (rErr) => {
                if (rErr) return fail("INTERNAL", "Atomic rename failed");
                cb(null, { path: p, bytes: Buffer.byteLength(content, "utf8"), backup: hasOld ? backup : null });
              });
            });
          };
          if (hasOld) {
            fs.copyFile(p, backup, (cErr) => {
              if (cErr) return fail("INTERNAL", "Backup before overwrite failed");
              finish();
            });
          } else {
            finish();
          }
        });
      });
      return;
    }
    case "fs.mkdir": {
      const p = containedPath(params.path, roots);
      if (!p) return fail("FORBIDDEN_PATH", "Path is outside approved roots");
      fs.mkdir(p, { recursive: true }, (err) => {
        if (err) return fail("INTERNAL", "mkdir failed");
        cb(null, { path: p });
      });
      return;
    }
    case "fs.move": {
      const from = containedPath(params.from, roots);
      const to = containedPath(params.to, roots);
      if (!from || !to) return fail("FORBIDDEN_PATH", "Path is outside approved roots");
      fs.rename(from, to, (err) => {
        if (err) return fail("NOT_FOUND", "Move failed");
        cb(null, { from, to });
      });
      return;
    }
    case "fs.delete": {
      const p = containedPath(params.path, roots);
      if (!p) return fail("FORBIDDEN_PATH", "Path is outside approved roots");
      fs.rm(p, { recursive: true }, (err) => {
        if (err) return fail("NOT_FOUND", "Delete failed");
        audit("fs.delete", { path: p });
        cb(null, { path: p });
      });
      return;
    }
    case "fs.watch": {
      const p = containedPath(params.path, roots);
      if (!p) return fail("FORBIDDEN_PATH", "Path is outside approved roots");
      try {
        const watcher = fs.watch(p, (event, filename) => {
          emit(params.channel || `fs:${p}`, { event, filename });
        });
        watchers.add(watcher);
        cb(null, { path: p, channel: params.channel || `fs:${p}` });
      } catch (err) {
        fail("INTERNAL", "Cannot watch path");
      }
      return;
    }
    default:
      fail("NOT_FOUND", `Unknown fs method: ${method}`);
  }
}

const watchers = new Set();
const channels = new Map(); // channel -> Set<socket>

function emit(channel, params) {
  const sockets = channels.get(channel);
  if (!sockets) return;
  const envelope = { type: "evt", channel, seq: ++channelSeq[channel] || (channelSeq[channel] = 1), params };
  for (const socket of sockets) socket.send(envelope);
}

const channelSeq = {};

function methodProcess(roots, allowlist, sub, params, cb) {
  const fail = (code, message, data) => cb({ code, message, data });
  switch (sub) {
    case "start": {
      const entry = allowlist.find((s) => s.name === params.name);
      if (!entry) {
        audit("process.start.denied", { name: params.name });
        return fail("NOT_ALLOWLISTED", "Launch spec is not on the allow-list");
      }
      const running = [...processes.values()].find((r) => r.name === params.name);
      if (running) {
        return fail("ALREADY_RUNNING", "A process with this name is already running", { id: running.id });
      }
      const spec = { ...entry, args: params.args || entry.args || [] };
      const rec = spawnAllowlisted(spec);
      audit("process.start", { name: params.name, id: rec.id });
      cb(null, { id: rec.id, pid: rec.pid, channel: `process:${rec.id}` });
      return;
    }
    case "stop": {
      const rec = processes.get(params.id);
      if (!rec) return fail("NOT_FOUND", "No such process id");
      const child = rec.child;
      child.kill("SIGTERM");
      const killer = setTimeout(() => {
        try {
          child.kill("SIGKILL");
        } catch (_) {
          /* already gone */
        }
      }, 5000);
      child.once("exit", () => clearTimeout(killer));
      audit("process.stop", { id: params.id });
      cb(null, { id: params.id, stopping: true });
      return;
    }
    case "restart": {
      const rec = processes.get(params.id);
      if (!rec) return fail("NOT_FOUND", "No such process id");
      const name = rec.name;
      const child = rec.child;
      child.kill("SIGTERM");
      setTimeout(() => {
        const entry = allowlist.find((s) => s.name === name);
        if (!entry) return fail2(cb, "NOT_FOUND", "Spec vanished");
        const fresh = spawnAllowlisted(entry);
        cb(null, { id: fresh.id, pid: fresh.pid, channel: `process:${fresh.id}` });
      }, 600);
      return;
    }
    case "status": {
      const rec = processes.get(params.id);
      if (!rec) return fail("NOT_FOUND", "No such process id");
      cb(null, processStatus(rec));
      return;
    }
    case "list": {
      cb(null, {
        processes: [...processes.values()].map(processStatus),
        allowlist: allowlist.map((s) => ({ name: s.name, command: s.command, args: s.args || [] })),
      });
      return;
    }
    case "stdin": {
      const rec = processes.get(params.id);
      if (!rec) return fail("NOT_FOUND", "No such process id");
      try {
        rec.child.stdin.write(String(params.data || ""));
        cb(null, { id: params.id, written: true });
      } catch (_) {
        cb({ code: "INTERNAL", message: "stdin write failed" });
      }
      return;
    }
    default:
      fail("NOT_FOUND", `Unknown process method: process.${sub}`);
  }
}

function fail2(cb, code, message) {
  cb({ code, message });
}

function processStatus(rec) {
  return {
    id: rec.id,
    name: rec.name,
    pid: rec.pid,
    exit: rec.exit,
    channel: `process:${rec.id}`,
    ring: rec.ring,
  };
}

function methodExec(roots, params, cb) {
  fs.access(EXEC_FLAG, (err) => {
    if (err) {
      audit("exec.run.denied", {});
      return cb({ code: "NOT_ALLOWLISTED", message: "Arbitrary execution is disabled; enable it in Harness Hub Settings (biometric) first" });
    }
    const cmd = params.command;
    if (!Array.isArray(cmd) || !cmd.length || cmd.some((a) => typeof a !== "string")) {
      return cb({ code: "INTERNAL", message: "exec.run expects command: string[]" });
    }
    const d = containedPath(params.cwd || os.homedir(), roots);
    audit("exec.run", { command: cmd, cwd: d });
    execFile(cmd[0], cmd.slice(1), { cwd: d || undefined, timeout: Math.min(params.timeoutMs || 10000, 60000), maxBuffer: 1024 * 1024 }, (err, stdout, stderr) => {
      if (err && err.killed) return cb({ code: "TIMEOUT", message: "Command timed out" });
      if (err && typeof err.code === "undefined") return cb({ code: "NOT_FOUND", message: "Executable not found" });
      const cut = (s) => String(s).slice(0, 100000);
      cb(null, { exitCode: err ? err.code : 0, stdout: cut(stdout), stderr: cut(stderr) });
    });
  });
}

function methodPty(sub, params, cb) {
  fs.access(PTY_FLAG, (err) => {
    if (!err) {
      // Real PTY needs a native binding; the M2 bridge reports honestly.
      return cb({ code: "NOT_ALLOWLISTED", message: "PTY is enabled in settings but requires the native PTY milestone", data: { milestone: "M9" } });
    }
    cb({ code: "NOT_ALLOWLISTED", message: "Terminal feature is off; enable it in Settings first" });
  });
}

const KNOWN_BINARIES = ["hermes", "openclaw", "claude", "codex", "gemini", "opencode", "aider", "goose", "node"];
const KNOWN_DIRS = [".hermes", ".openclaw", ".claude", ".codex", ".gemini", ".config/opencode"];

function methodDiscovery(cb, emitEvent) {
  const results = [];
  let done = 0;
  const total = KNOWN_BINARIES.length + KNOWN_DIRS.length;
  const finishIfDone = () => {
    done += 1;
    if (done >= total) cb(null, { results });
  };
  for (const bin of KNOWN_BINARIES) {
    execFile("sh", ["-c", `command -v ${bin}`], { timeout: 2000 }, (err, out) => {
      if (!err && String(out).trim()) {
        results.push({ harnessId: bin === "node" || bin === "npm" ? "runtime" : bin, signals: [`binary:${bin}`, `path:${String(out).trim()}`], confidence: 0.9 });
        emitEvent({ partial: results });
      }
      finishIfDone();
    });
  }
  for (const dir of KNOWN_DIRS) {
    const p = path.join(os.homedir(), dir);
    fs.stat(p, (err, st) => {
      if (!err && st.isDirectory()) {
        results.push({ harnessId: dir.replace(".config/", "").replace(".", ""), signals: [`dir:~/${dir}`], confidence: 0.7 });
        emitEvent({ partial: results });
      }
      finishIfDone();
    });
  }
}

// ── websocket session ───────────────────────────────────────────────────────

function serveSocket(socket, token, roots, allowlist) {
  const codec = new FrameCodec(
    (frame) => {
      let envelope;
      try {
        envelope = JSON.parse(frame.payload.toString("utf8"));
      } catch (_) {
        session.close(1002, "bad json");
        return;
      }
      session.onEnvelope(envelope);
    },
    () => session.destroyed = true,
  );
  const session = {
    destroyed: false,
    authenticated: false,
    subscriptions: new Set(),
    send(obj) {
      if (this.destroyed) return;
      try {
        socket.write(FrameCodec.encode(OP.TEXT, JSON.stringify(obj)));
      } catch (_) {
        /* socket gone */
      }
    },
    onEnvelope(env) {
      if (!env || typeof env !== "object") return;
      if (!this.authenticated) {
        if (env.type === "hello") {
          const offered = env.params && env.params.protocol;
          if (offered !== PROTOCOL) {
            this.send({ id: env.id, type: "err", error: { code: "PROTOCOL_MISMATCH", message: `bridge speaks protocol ${PROTOCOL}`, data: { supported: PROTOCOL } } });
            this.destroyed = true;
            socket.end();
            return;
          }
          const given = env.params && env.params.token;
          if (typeof given !== "string" || !timingSafeEqualSafe(given, token)) {
            noteAuthFailure(socket.remoteAddress || "?");
            this.send({ id: env.id, type: "err", error: { code: "AUTH_FAILED", message: "bad or missing bridge token" } });
            this.destroyed = true;
            socket.end();
            return;
          }
          this.authenticated = true;
          clearTimeout(this.helloTimer);
          this.send({ id: env.id, type: "res", result: { protocol: PROTOCOL, bridgeVersion: BRIDGE_VERSION, home: os.homedir() } });
          return;
        }
        if (env.id !== undefined) {
          this.send({ id: env.id, type: "err", error: { code: "AUTH_FAILED", message: "first frame must be hello with a valid token" } });
        }
        this.destroyed = true;
        socket.end();
        return;
      }
      if (env.type !== "req") return;
      dispatch(this, env, roots, allowlist);
    },
    close(code, reason) {
      try {
        socket.write(FrameCodec.encode(OP.CLOSE, Buffer.alloc(0)));
      } catch (_) {
        /* ignore */
      }
      this.destroyed = true;
      socket.end();
    },
    destroy() {
      this.destroyed = true;
      try {
        socket.destroy();
      } catch (_) {
        /* ignore */
      }
    },
    helloTimer: null,
  };
  session.helloTimer = setTimeout(() => {
    if (!session.authenticated) {
      log("closing unauthenticated socket after 3s");
      session.destroy();
    }
  }, 3000);
  channels.forEach((set, channel) => {
    /* sockets subscribe explicitly; nothing to do here */
  });
  socket.on("data", (d) => codec.push(d));
  socket.on("error", () => session.destroyed = true);
  socket.on("close", () => {
    session.destroyed = true;
    for (const set of channels.values()) set.delete(session);
  });
  return session;
}

function timingSafeEqualSafe(a, b) {
  if (Math.abs(a.length - b.length) > 8) return false;
  const ab = Buffer.from(a.padEnd(64, "0"), "utf8");
  const bb = Buffer.from(b.padEnd(64, "0"), "utf8");
  return crypto.timingSafeEqual(ab, bb);
}

const authFailures = new Map(); // addr -> [timestamps]

function noteAuthFailure(addr) {
  const now = Date.now();
  const arr = (authFailures.get(addr) || []).filter((t) => now - t < 60000);
  arr.push(now);
  authFailures.set(addr, arr);
}

function tooManyAuthFailures(addr) {
  const arr = authFailures.get(addr) || [];
  return arr.filter((t) => Date.now() - t < 60000).length >= 5;
}

function dispatch(session, env, roots, allowlist) {
  const method = env.method || "";
  const params = env.params || {};
  const cb = (err, result) => {
    if (err) {
      session.send({ id: env.id, type: "err", method, error: err });
    } else {
      session.send({ id: env.id, type: "res", method, result });
    }
  };
  const evtCb = (data) => session.send({ type: "evt", method, params: data });

  if (method.startsWith("fs.")) {
    // fs.watch subscribes this socket to its channel for continuous events
    runFs(roots, method, params, (err, result) => {
      if (!err && method === "fs.watch" && result && result.channel) {
        let set = channels.get(result.channel);
        if (!set) {
          set = new Set();
          channels.set(result.channel, set);
        }
        set.add(session);
        session.subscriptions.add(result.channel);
      }
      cb(err, result);
    });
    return;
  }
  if (method.startsWith("process.")) {
    methodProcess(roots, allowlist, method.slice("process.".length), params, (err, result) => {
      if (!err && result && result.channel) {
        let set = channels.get(result.channel);
        if (!set) {
          set = new Set();
          channels.set(result.channel, set);
        }
        set.add(session);
        session.subscriptions.add(result.channel);
      }
      cb(err, result);
    });
    return;
  }
  if (method.startsWith("pty.")) {
    methodPty(method.slice("pty.".length), params, cb);
    return;
  }
  switch (method) {
    case "system.info":
      methodSystemInfo(cb);
      return;
    case "proot.list":
      methodProotList(cb);
      return;
    case "exec.run":
      methodExec(roots, params, cb);
      return;
    case "discovery.scan":
      methodDiscovery(cb, evtCb);
      return;
    case "hermes.setup":
      methodHermesSetup(cb);
      return;
    case "system.resources":
      methodResources(cb);
      return;
    case "harness.list":
      methodHarnessList(cb);
      return;
    case "harness.register":
      methodHarnessRegister(params, cb);
      return;
    case "harness.stop":
      methodHarnessStop(params, cb);
      return;
    case "bridge.resourceMode":
      methodResourceMode(params, cb);
      return;

    case "bridge.execFlag":
      methodExecFlag(params, cb);
      return;
    case "net.listeners":
      methodNetListeners(cb);
      return;
    default:
      cb({ code: "NOT_FOUND", message: `Unknown method: ${method}` });
  }
}

// ── process lifecycle events to subscribers ─────────────────────────────────

function broadcastProcessEvents() {
  // emit stdout/stderr ring deltas and exit events on the process channels
  // (poll-based; the bridge is single-user and this keeps it dependency-free)
}

// ── main ────────────────────────────────────────────────────────────────────

function main() {
  const args = process.argv.slice(2);
  let port = 0;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--port" && args[i + 1]) port = parseInt(args[i + 1], 10);
  }
  ensureHubDir();
  const token = ensureToken();
  const roots = loadApprovedRoots();
  const allowlist = loadAllowlist();

  const server = http.createServer((req, res) => {
    if (req.url === "/health") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ ok: true, protocol: PROTOCOL, version: BRIDGE_VERSION, transport: "ws+multiplexer" }));
      return;
    }
    res.writeHead(404, { "content-type": "application/json" });
    res.end(JSON.stringify({ ok: false, error: { code: "NOT_FOUND", message: "unknown path" } }));
  });

  server.on("upgrade", (req, socket) => {
    if (tooManyAuthFailures(req.socket.remoteAddress || "?")) {
      socket.write("HTTP/1.1 429 Too Many Requests\r\n\r\n");
      socket.destroy();
      return;
    }
    const key = req.headers["sec-websocket-key"];
    if (!key || (req.headers.upgrade || "").toLowerCase() !== "websocket") {
      socket.write("HTTP/1.1 400 Bad Request\r\n\r\n");
      socket.destroy();
      return;
    }
    const accept = crypto.createHash("sha1").update(key + GUID).digest("base64");
    socket.write(
      "HTTP/1.1 101 Switching Protocols\r\n" +
      "Upgrade: websocket\r\n" +
      "Connection: Upgrade\r\n" +
      `Sec-WebSocket-Accept: ${accept}\r\n` +
      "\r\n",
    );
    serveSocket(socket, token, roots, allowlist);
  });

  server.listen(port, "127.0.0.1", () => {
    const actual = server.address().port;
    process.stdout.write(`BRIDGE_PORT=${actual}\n`);
    log(`listening on 127.0.0.1:${actual} (protocol ${PROTOCOL}, roots=${roots.length}, allowlist=${allowlist.length})`);
  });
}

main();

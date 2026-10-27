"use strict";
/**
 * Minimal WebSocket client for the bridge conformance suite (no deps).
 * Speaks the same envelope protocol as bridge.js.
 */
const net = require("net");
const crypto = require("crypto");
const { EventEmitter } = require("events");

const OP = { TEXT: 0x1, CLOSE: 0x8, PING: 0x9 };

class ClientParser {
  constructor(onFrame) {
    this.onFrame = onFrame;
    this.buf = Buffer.alloc(0);
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
        len = Number(this.buf.readBigUInt64BE(2));
        off = 10;
      }
      const maskEnd = off + (masked ? 4 : 0);
      if (this.buf.length < maskEnd + len) return;
      let payload = this.buf.subarray(maskEnd, maskEnd + len);
      if (masked) {
        const mask = this.buf.subarray(off, maskEnd);
        const un = Buffer.allocUnsafe(len);
        for (let i = 0; i < len; i++) un[i] = payload[i] ^ mask[i & 3];
        payload = un;
      }
      this.buf = this.buf.subarray(maskEnd + len);
      if (!fin && op === OP.TEXT) continue; // fragments unsupported (server never fragments)
      this.onFrame({ opcode: op, payload });
    }
  }
}

function encodeFrame(opcode, payload) {
  payload = Buffer.from(payload || "");
  const mask = crypto.randomBytes(4);
  let header;
  const len = payload.length;
  if (len < 126) {
    header = Buffer.allocUnsafe(2);
    header[0] = 0x80 | opcode;
    header[1] = 0x80 | len;
  } else if (len < 65536) {
    header = Buffer.allocUnsafe(4);
    header[0] = 0x80 | opcode;
    header[1] = 0x80 | 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.allocUnsafe(10);
    header[0] = 0x80 | opcode;
    header[1] = 0x80 | 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  const masked = Buffer.allocUnsafe(len);
  for (let i = 0; i < len; i++) masked[i] = payload[i] ^ mask[i & 3];
  return Buffer.concat([header, mask, masked]);
}

/**
 * connect({port, token, protocol, expectRefused}) -> Promise<conn | {refused:true, status}>
 * conn: events 'close'; req(method, params) -> Promise<{result}|{error}>;
 *       rawSend(obj); close()
 */
function connect(opts) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(opts.port, "127.0.0.1");
    let phase = "handshake";
    let handshakeBuf = Buffer.alloc(0);
    let parser = null;
    let idSeq = 0;
    const pending = new Map();
    const events = [];
    const conn = new EventEmitter();
    conn.events = events;
    conn.rawSend = (obj) => socket.write(encodeFrame(OP.TEXT, JSON.stringify(obj)));
    conn.close = () => {
      try {
        socket.write(encodeFrame(OP.CLOSE, ""));
      } catch (_) {}
      socket.end();
    };
    conn.req = (method, params) =>
      new Promise((resolve2) => {
        const id = `c${++idSeq}`;
        pending.set(id, resolve2);
        conn.rawSend({ id, type: "req", method, params: params || {} });
      });
    conn.hello = (token, protocol) =>
      new Promise((resolve2) => {
        const id = `c${++idSeq}`;
        pending.set(id, resolve2);
        conn.rawSend({ id, type: "hello", params: { protocol: protocol === undefined ? 1 : protocol, token } });
      });

    socket.on("error", (err) => reject(err));
    socket.on("close", () => {
      if (phase === "open") {
        conn.emit("closed-by-peer");
        conn.emit("close");
        for (const r of pending.values()) r({ disconnected: true });
        pending.clear();
      }
    });

    socket.on("connect", () => {
      const key = crypto.randomBytes(16).toString("base64");
      socket.write(
        `GET / HTTP/1.1\r\n` +
          `Host: 127.0.0.1:${opts.port}\r\n` +
          `Upgrade: websocket\r\n` +
          `Connection: Upgrade\r\n` +
          `Sec-WebSocket-Key: ${key}\r\n` +
          `Sec-WebSocket-Version: 13\r\n\r\n`,
      );
    });

    socket.on("data", (d) => {
      if (phase === "handshake") {
        handshakeBuf = Buffer.concat([handshakeBuf, d]);
        const idx = handshakeBuf.indexOf("\r\n\r\n");
        if (idx < 0) return;
        const head = handshakeBuf.subarray(0, idx).toString("utf8");
        const rest = handshakeBuf.subarray(idx + 4);
        const status = parseInt(head.split(" ")[1], 10) || 0;
        if (status !== 101) {
          resolve({ refused: true, status, reason: head.split(/\r?\n/)[1] || head });
          socket.destroy();
          return;
        }
        phase = "open";
        parser = new ClientParser((frame) => {
          if (frame.opcode === OP.CLOSE) {
            conn.emit("closed-by-peer");
            conn.close();
            resolve({ closed: true });
            return;
          }
          if (frame.opcode === OP.PING) {
            socket.write(encodeFrame(OP.PONG, frame.payload));
            return;
          }
          let env;
          try {
            env = JSON.parse(frame.payload.toString("utf8"));
          } catch (_) {
            return;
          }
          if (env.type === "evt") {
            events.push(env);
            conn.emit("event", env);
            return;
          }
          const resolver = pending.get(env.id);
          if (resolver) {
            pending.delete(env.id);
            resolver(env);
          }
        });
        parser.push(rest);
        resolve(conn);
        return;
      }
      if (parser) parser.push(d);
    });
  });
}

module.exports = { connect };

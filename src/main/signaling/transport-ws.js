// Signaling transport through a self-hosted PairDesk server (see /server).
// The server routes messages between registered IDs and may hand out TURN
// relay credentials. It never sees passwords nor decrypted session data.

import { EventEmitter } from 'node:events';
import { randomBytes } from 'node:crypto';
import WebSocket from 'ws';
import { PROTOCOL_VERSION } from '../../shared/protocol.js';

const SEND_TIMEOUT_MS = 10_000;
const PING_INTERVAL_MS = 25_000;

export class WsTransport extends EventEmitter {
  constructor({ url, log }) {
    super();
    this.kind = 'server';
    this.url = url;
    this.log = log;
    this.ws = null;
    this.state = 'offline';
    this.pending = new Map();
    this.retryMs = 1000;
    this.stopped = true;
    this.iceServers = [];
    this.lastError = null;
  }

  start(myId, deviceKey) {
    this.myId = myId;
    this.deviceKey = deviceKey;
    this.stopped = false;
    this.#open();
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.retryTimer);
    clearInterval(this.pingTimer);
    if (this.ws) {
      try { this.ws.terminate(); } catch { /* ignore */ }
    }
    this.ws = null;
    this.#failPending('network');
    this.#setState('offline');
  }

  #open() {
    if (this.stopped) return;
    this.#setState('connecting');
    let ws;
    try {
      ws = new WebSocket(this.url, { handshakeTimeout: 10_000, maxPayload: 256 * 1024 });
    } catch (err) {
      this.lastError = err.message;
      this.log?.warn(`[ws] invalid server URL ${this.url}: ${err.message}`);
      this.#setState('error');
      return;
    }
    this.ws = ws;
    let alive = true;
    ws.on('open', () => {
      ws.send(JSON.stringify({ t: 'register', id: this.myId, key: this.deviceKey, v: PROTOCOL_VERSION }));
      clearInterval(this.pingTimer);
      this.pingTimer = setInterval(() => {
        if (!alive) {
          ws.terminate();
          return;
        }
        alive = false;
        try { ws.ping(); } catch { /* ignore */ }
      }, PING_INTERVAL_MS);
    });
    ws.on('pong', () => { alive = true; });
    ws.on('message', (raw) => {
      alive = true;
      let msg;
      try {
        msg = JSON.parse(raw.toString('utf8'));
      } catch {
        return;
      }
      this.#onServerMessage(msg);
    });
    ws.on('error', (err) => {
      this.lastError = err.message;
      this.log?.warn(`[ws] ${err.message}`);
    });
    ws.on('close', () => {
      clearInterval(this.pingTimer);
      if (this.ws === ws) this.ws = null;
      this.#failPending('network');
      if (this.stopped) return;
      if (this.state !== 'error') this.#setState('connecting');
      const delay = this.retryMs;
      this.retryMs = Math.min(this.retryMs * 2, 30_000);
      this.retryTimer = setTimeout(() => this.#open(), delay);
    });
  }

  #onServerMessage(msg) {
    switch (msg?.t) {
      case 'registered':
        this.retryMs = 1000;
        this.lastError = null;
        this.iceServers = Array.isArray(msg.iceServers) ? msg.iceServers : [];
        this.emit('ice-servers', this.iceServers);
        this.#setState('online');
        break;
      case 'error':
        if (msg.mid && this.pending.has(msg.mid)) {
          this.#settle(msg.mid, Object.assign(new Error(msg.code), { code: msg.code }));
        } else {
          this.lastError = msg.code;
          this.log?.warn(`[ws] server error: ${msg.code}`);
          if (msg.code === 'id-taken') this.emit('id-taken');
        }
        break;
      case 'ack':
        this.#settle(msg.mid, null);
        break;
      case 'msg':
        if (typeof msg.from === 'string' && msg.data && typeof msg.data === 'object') {
          this.emit('message', { from: msg.from, data: msg.data });
        }
        break;
      case 'ice-servers':
        this.iceServers = Array.isArray(msg.iceServers) ? msg.iceServers : [];
        this.emit('ice-servers', this.iceServers);
        break;
      default:
        break;
    }
  }

  #settle(mid, err) {
    const p = this.pending.get(mid);
    if (!p) return;
    this.pending.delete(mid);
    clearTimeout(p.timer);
    if (err) p.reject(err);
    else p.resolve();
  }

  #failPending(code) {
    for (const mid of [...this.pending.keys()]) {
      this.#settle(mid, Object.assign(new Error(code), { code }));
    }
  }

  #setState(state) {
    if (state === this.state) return;
    this.state = state;
    this.emit('state', state);
  }

  send(to, data) {
    const ws = this.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN || this.state !== 'online') {
      return Promise.reject(Object.assign(new Error('Not connected to the PairDesk server'), { code: 'network' }));
    }
    const mid = randomBytes(9).toString('base64url');
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => this.#settle(mid, Object.assign(new Error('timeout'), { code: 'network' })), SEND_TIMEOUT_MS);
      this.pending.set(mid, { resolve, reject, timer });
      ws.send(JSON.stringify({ t: 'send', to, mid, data }), (err) => {
        if (err) this.#settle(mid, Object.assign(err, { code: 'network' }));
      });
    });
  }
}

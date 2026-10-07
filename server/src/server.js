// PairDesk signaling server.
//
// A small WebSocket relay: devices register their 9-digit ID and exchange
// signaling messages (which are end-to-end encrypted by the clients). It can
// also hand out TURN relay credentials so that connections work even behind
// strict NATs/firewalls. Configuration is done with environment variables,
// see server/README.md.

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { createHash, createHmac } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

const VERSION = '1.0.0';
const ID_RE = /^[1-9]\d{8}$/;
const DAY = 86_400_000;

function log(...args) {
  console.log(new Date().toISOString(), ...args);
}

class BindingStore {
  constructor(file) {
    this.file = file;
    this.map = new Map();
    this.dirty = false;
    if (file) {
      try {
        const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
        for (const [id, v] of Object.entries(raw)) this.map.set(id, v);
      } catch {
        /* first start */
      }
      this.flushTimer = setInterval(() => this.flush(), 10_000);
      this.flushTimer.unref();
    }
  }

  /** Returns true if `keyHash` may use `id` (binding it on first use). */
  claim(id, keyHash) {
    const now = Math.floor(Date.now() / DAY);
    const entry = this.map.get(id);
    if (entry && entry.k !== keyHash && now - entry.s < 180) return false;
    this.map.set(id, { k: keyHash, s: now });
    this.dirty = true;
    return true;
  }

  flush() {
    if (!this.file || !this.dirty) return;
    this.dirty = false;
    const tmp = `${this.file}.tmp`;
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      fs.writeFileSync(tmp, JSON.stringify(Object.fromEntries(this.map)));
      fs.renameSync(tmp, this.file);
    } catch (err) {
      log('cannot save bindings:', err.message);
    }
  }

  close() {
    clearInterval(this.flushTimer);
    this.flush();
  }
}

function splitList(value) {
  return String(value || '').split(',').map((s) => s.trim()).filter(Boolean);
}

export function createIceProvider(env = process.env, fetchImpl = globalThis.fetch) {
  const turnUrls = splitList(env.TURN_URLS);
  const stunUrls = splitList(env.STUN_URLS);
  const ttl = Number(env.TURN_TTL || 86_400);
  let cfCache = null;

  return async function iceServersFor(id) {
    const servers = [];
    if (stunUrls.length) servers.push({ urls: stunUrls });
    if (turnUrls.length && env.TURN_SECRET) {
      // coturn "use-auth-secret" (TURN REST API) time-limited credentials.
      const username = `${Math.floor(Date.now() / 1000) + ttl}:${id}`;
      const credential = createHmac('sha1', env.TURN_SECRET).update(username).digest('base64');
      servers.push({ urls: turnUrls, username, credential });
    } else if (turnUrls.length && env.TURN_USERNAME) {
      servers.push({ urls: turnUrls, username: env.TURN_USERNAME, credential: env.TURN_CREDENTIAL || '' });
    } else if (env.CF_TURN_KEY_ID && env.CF_TURN_API_TOKEN && fetchImpl) {
      // Cloudflare Realtime TURN (generous free tier).
      if (!cfCache || cfCache.expires < Date.now()) {
        try {
          const base = `https://rtc.live.cloudflare.com/v1/turn/keys/${env.CF_TURN_KEY_ID}/credentials`;
          const request = (suffix) => fetchImpl(`${base}/${suffix}`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${env.CF_TURN_API_TOKEN}`, 'Content-Type': 'application/json' },
            body: JSON.stringify({ ttl }),
          });
          let res = await request('generate-ice-servers');
          if (res.status === 404) res = await request('generate');
          if (!res.ok) throw new Error(`HTTP ${res.status}`);
          const body = await res.json();
          const list = Array.isArray(body.iceServers) ? body.iceServers : [body.iceServers].filter(Boolean);
          cfCache = { servers: list, expires: Date.now() + Math.min(ttl * 1000, 3_600_000) };
        } catch (err) {
          log('Cloudflare TURN error:', err.message);
        }
      }
      if (cfCache) servers.push(...cfCache.servers);
    }
    return servers;
  };
}

export function createServer({
  port = Number(process.env.PORT || 8080),
  host = process.env.HOST || '0.0.0.0',
  dataDir = process.env.DATA_DIR || '',
  env = process.env,
  maxClientsPerIp = Number(process.env.MAX_CLIENTS_PER_IP || 50),
  trustProxy = process.env.TRUST_PROXY === '1',
} = {}) {
  const bindings = new BindingStore(dataDir ? path.join(dataDir, 'bindings.json') : '');
  const iceServersFor = createIceProvider(env);
  const clients = new Map();
  const perIp = new Map();

  const server = http.createServer((req, res) => {
    if (req.url === '/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, version: VERSION, online: clients.size }));
      return;
    }
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(`PairDesk signaling server ${VERSION}\nWebSocket endpoint: /ws\n`);
  });

  const wss = new WebSocketServer({ noServer: true, maxPayload: 64 * 1024 });

  server.on('upgrade', (req, socket, head) => {
    const { pathname } = new URL(req.url, 'http://localhost');
    if (pathname !== '/ws' && pathname !== '/') {
      socket.destroy();
      return;
    }
    const ip = (trustProxy && String(req.headers['x-forwarded-for'] || '').split(',')[0].trim()) || req.socket.remoteAddress || '?';
    if ((perIp.get(ip) || 0) >= maxClientsPerIp) {
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, ip));
  });

  wss.on('connection', (ws, ip) => {
    perIp.set(ip, (perIp.get(ip) || 0) + 1);
    const conn = { ws, id: null, alive: true, tokens: 60, last: Date.now() };
    ws.pdConn = conn;
    const send = (obj) => {
      if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
    };

    ws.on('pong', () => { conn.alive = true; });
    ws.on('message', async (raw) => {
      conn.alive = true;
      // Token bucket: 30 messages/s sustained, bursts of 60.
      const now = Date.now();
      conn.tokens = Math.min(60, conn.tokens + ((now - conn.last) / 1000) * 30);
      conn.last = now;
      if (conn.tokens < 1) return;
      conn.tokens -= 1;

      let msg;
      try {
        msg = JSON.parse(raw.toString('utf8'));
      } catch {
        return;
      }
      if (msg?.t === 'register') {
        if (conn.id || !ID_RE.test(msg.id) || typeof msg.key !== 'string' || msg.key.length < 16 || msg.key.length > 128) {
          send({ t: 'error', code: 'bad-register' });
          return;
        }
        const keyHash = createHash('sha256').update(msg.key).digest('hex');
        if (!bindings.claim(msg.id, keyHash)) {
          send({ t: 'error', code: 'id-taken' });
          ws.close(4003, 'id-taken');
          return;
        }
        const previous = clients.get(msg.id);
        if (previous && previous !== conn) {
          previous.id = null;
          previous.ws.close(4001, 'replaced');
        }
        conn.id = msg.id;
        clients.set(msg.id, conn);
        send({ t: 'registered', iceServers: await iceServersFor(msg.id) });
        return;
      }
      if (msg?.t === 'send') {
        const mid = typeof msg.mid === 'string' ? msg.mid.slice(0, 32) : undefined;
        if (!conn.id) {
          send({ t: 'error', mid, code: 'not-registered' });
          return;
        }
        if (!ID_RE.test(msg.to) || !msg.data || typeof msg.data !== 'object') {
          send({ t: 'error', mid, code: 'bad-request' });
          return;
        }
        const target = clients.get(msg.to);
        if (!target) {
          send({ t: 'error', mid, code: 'offline' });
          return;
        }
        if (target.ws.readyState === target.ws.OPEN) {
          target.ws.send(JSON.stringify({ t: 'msg', from: conn.id, data: msg.data }));
        }
        send({ t: 'ack', mid });
      }
    });

    ws.on('close', () => {
      const n = (perIp.get(ip) || 1) - 1;
      if (n <= 0) perIp.delete(ip);
      else perIp.set(ip, n);
      if (conn.id && clients.get(conn.id) === conn) clients.delete(conn.id);
    });
    ws.on('error', () => {});
  });

  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      const conn = ws.pdConn;
      if (!conn.alive) {
        ws.terminate();
        continue;
      }
      conn.alive = false;
      try { ws.ping(); } catch { /* ignore */ }
    }
  }, 30_000);
  heartbeat.unref();

  return {
    server,
    clients,
    listen() {
      return new Promise((resolve) => server.listen(port, host, () => resolve(server.address().port)));
    },
    async close() {
      clearInterval(heartbeat);
      for (const ws of wss.clients) ws.terminate();
      await new Promise((resolve) => server.close(resolve));
      bindings.close();
    },
  };
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (isMain) {
  const instance = createServer({ dataDir: process.env.DATA_DIR || path.resolve('data') });
  instance.listen().then((port) => log(`PairDesk signaling server ${VERSION} listening on port ${port}`));
  const shutdown = () => {
    log('shutting down');
    instance.close().finally(() => process.exit(0));
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

// Interoperability peer for the Android port's tests (android/core, InteropTest).
//
// Runs the desktop signaling code (src/main/signaling) against a Kotlin peer
// through a local relay started here: two aedes MQTT brokers over WebSocket
// (like the public relays, so de-duplication is exercised too) or the
// PairDesk server (server/src/server.js).
//
//   node scripts/android-interop-peer.mjs --role host       [--transport mqtt|ws] [--expect auth]
//   node scripts/android-interop-peer.mjs --role controller [--transport mqtt|ws] [--expect auth]
//   node scripts/android-interop-peer.mjs --role broker     [--transport mqtt|ws]
//     relay only: a "kick" line on stdin drops every client connection (like a
//     network change) and answers {"kicked": n}; stops when stdin closes.
//
// stdout carries JSON lines only (diagnostics go to stderr):
//   1. {"ready":true, "transport", "brokers":[urls]|null, "serverUrl":url|null, "id", "password"?}
//      host: its ID and temporary password, already online.
//      controller: its ID; then reads one JSON line on stdin:
//      {"hostId": "...", "password": "..."} once the Kotlin host is online.
//   2. {"ok":true, ...} then exit 0, or {"ok":false, "error"} and exit 1.
//
// Session script (the host is the offerer, like a real session):
//   host → ctrl  sealed {type:'waiting'} then {type:'accepted', …}
//   host → ctrl  {type:'signal', data:{description:{type:'offer', sdp: OFFER_SDP}}}
//   ctrl → host  {type:'signal', data:{description:{type:'answer', sdp: ANSWER_SDP}}}
//   host → ctrl  {type:'bye', reason:'interop-done'}
// With --expect auth the controller uses a wrong password: the controller must
// fail with code 'auth' and the host must report 'auth-failed'.

import http from 'node:http';
import readline from 'node:readline';
import { randomBytes } from 'node:crypto';
import { Aedes } from 'aedes';
import { WebSocketServer, createWebSocketStream } from 'ws';
import { Signaling } from '../src/main/signaling/signaling.js';
import { MqttTransport } from '../src/main/signaling/transport-mqtt.js';
import { WsTransport } from '../src/main/signaling/transport-ws.js';
import { derivePrs } from '../src/main/crypto/prs.js';
import { generateDeviceId, generatePassword, PROTOCOL_VERSION } from '../src/shared/protocol.js';
import { createServer } from '../server/src/server.js';

const OFFER_SDP = 'v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\ns=PairDesk interop é✓😀\r\n';
const ANSWER_SDP = 'v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=Android answer ü€😀\r\n';
const TIMEOUT_MS = Number(process.env.PAIRDESK_INTEROP_TIMEOUT || 45_000);

function arg(name, fallback = null) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : fallback;
}

const role = arg('role', 'host');
const transportKind = arg('transport', 'mqtt');
const expect = arg('expect', 'session');
const cleanups = [];

const out = (obj) => process.stdout.write(`${JSON.stringify(obj)}\n`);
const log = (...args) => process.stderr.write(`[interop-${role}] ${args.join(' ')}\n`);
const logger = { info: (m) => log(m), warn: (m) => log(m), error: (m) => log(m) };

let finished = false;

async function finish(result) {
  if (finished) return;
  finished = true;
  clearTimeout(timer);
  out(result);
  for (const fn of cleanups.reverse()) {
    try { await fn(); } catch { /* ignore */ }
  }
  process.exit(result.ok ? 0 : 1);
}

function fail(error) {
  log(`FAILED: ${error}`);
  return finish({ ok: false, error: String(error) });
}

// The relay-only mode lives as long as its stdin.
const timer = role === 'broker' ? null : setTimeout(() => fail('timeout'), TIMEOUT_MS);

async function startBroker() {
  const aedes = await Aedes.createBroker();
  const httpServer = http.createServer();
  const wss = new WebSocketServer({ server: httpServer });
  wss.on('connection', (socket, req) => aedes.handle(createWebSocketStream(socket), req));
  await new Promise((r) => httpServer.listen(0, '127.0.0.1', r));
  return {
    url: `ws://127.0.0.1:${httpServer.address().port}/mqtt`,
    /** Drops every client connection (like a network change); returns how many. */
    kick() {
      let n = 0;
      for (const c of wss.clients) {
        c.terminate();
        n++;
      }
      return n;
    },
    async close() {
      for (const c of wss.clients) c.terminate();
      await new Promise((r) => httpServer.close(r));
      await new Promise((r) => aedes.close(r));
    },
  };
}

async function startRelay() {
  if (transportKind === 'ws') {
    const server = createServer({ port: 0, host: '127.0.0.1', env: {} });
    const port = await server.listen();
    cleanups.push(() => server.close());
    const kick = () => {
      let n = 0;
      for (const c of [...server.clients.values()]) {
        c.ws.terminate();
        n++;
      }
      return n;
    };
    return { relay: { brokers: null, serverUrl: `ws://127.0.0.1:${port}/ws` }, kick };
  }
  if (transportKind !== 'mqtt') throw new Error(`unknown transport ${transportKind}`);
  const brokers = [];
  for (let i = 0; i < 2; i++) {
    const b = await startBroker();
    cleanups.push(() => b.close());
    brokers.push(b);
  }
  return {
    relay: { brokers: brokers.map((b) => b.url), serverUrl: null },
    kick: () => brokers.reduce((n, b) => n + b.kick(), 0),
  };
}

function online(transport) {
  return new Promise((resolve) => {
    if (transport.state === 'online') return resolve();
    const h = (s) => {
      if (s === 'online') {
        transport.off('state', h);
        resolve();
      }
    };
    transport.on('state', h);
  });
}

async function startTransport(relay, myId) {
  let transport;
  if (relay.serverUrl) {
    transport = new WsTransport({ url: relay.serverUrl, log: logger });
    transport.start(myId, randomBytes(32).toString('base64url'));
  } else {
    transport = new MqttTransport({ brokers: relay.brokers, log: logger });
    transport.start(myId);
  }
  cleanups.push(() => transport.stop());
  await online(transport);
  if (relay.brokers) {
    // Wait for every broker: the Kotlin side publishes on all of them.
    for (let i = 0; i < 100 && transport.connectedBrokers.length < relay.brokers.length; i++) {
      await new Promise((r) => setTimeout(r, 50));
    }
  }
  return transport;
}

function readLine() {
  return new Promise((resolve, reject) => {
    const rl = readline.createInterface({ input: process.stdin });
    const onClose = () => reject(new Error('stdin closed'));
    rl.once('line', (line) => {
      rl.off('close', onClose);
      rl.close();
      resolve(line);
    });
    rl.once('close', onClose);
  });
}

const sdpOf = (msg, type) => (msg?.type === 'signal' && msg.data?.description?.type === type ? msg.data.description.sdp : null);

async function runHost(relay) {
  const myId = generateDeviceId();
  const password = generatePassword();
  const prs = await derivePrs(password, myId);
  const transport = await startTransport(relay, myId);
  const signaling = new Signaling({
    transport,
    myId,
    getCredentials: () => [{ kind: 'temp', prs }],
    canAccept: () => null,
    log: logger,
  });
  cleanups.push(() => signaling.dispose());

  signaling.on('auth-failed', ({ peerId, failures }) => {
    if (expect === 'auth') finish({ ok: true, authFailed: true, peerId, failures });
    else fail(`unexpected auth failure from ${peerId}`);
  });
  signaling.on('incoming', async (session) => {
    if (expect === 'auth') return fail('a session was established with a wrong password');
    log(`incoming from ${session.peerId}: ${session.peerName} (${session.peerPlatform} ${session.peerVersion})`);
    if (session.peerPlatform !== 'android') return fail(`unexpected intro platform ${session.peerPlatform}`);
    if (session.peerName !== 'Kotlin controller ✓') return fail(`unexpected intro name ${session.peerName}`);
    await signaling.notifyWaiting(session.sid);
    await signaling.accept(session.sid, {
      name: 'JS host',
      caps: { control: true, files: true, clipboard: false, audio: false },
      platform: 'linux',
      appVersion: '1.2.0',
    });
    await signaling.send(session.sid, { type: 'signal', data: { description: { type: 'offer', sdp: OFFER_SDP } } });
  });
  signaling.on('message', async (sid, msg) => {
    const sdp = sdpOf(msg, 'answer');
    if (sdp === null) return fail(`unexpected message ${JSON.stringify(msg)}`);
    if (sdp !== ANSWER_SDP) return fail(`answer mismatch: ${JSON.stringify(sdp)}`);
    await signaling.send(sid, { type: 'bye', reason: 'interop-done' });
    finish({ ok: true, sid });
  });
  out({ ready: true, transport: transportKind, ...relay, id: myId, password, version: PROTOCOL_VERSION });
}

async function runController(relay) {
  const myId = generateDeviceId();
  const transport = await startTransport(relay, myId);
  const signaling = new Signaling({ transport, myId, getCredentials: () => [], log: logger });
  cleanups.push(() => signaling.dispose());
  out({ ready: true, transport: transportKind, ...relay, id: myId });

  const { hostId, password } = JSON.parse(await readLine());
  const probe = await signaling.probe(hostId);
  if (probe.version !== PROTOCOL_VERSION) return fail(`probe version ${probe.version}`);
  const typed = expect === 'auth' ? `${password}-wrong` : password;
  const prs = await derivePrs(typed, hostId);
  const statuses = [];
  let session;
  try {
    session = await signaling.connect(hostId, prs, {
      intro: { name: 'JS controller', platform: 'linux', appVersion: '1.2.0' },
      onStatus: (s) => statuses.push(s),
    });
  } catch (err) {
    if (expect === 'auth' && err.code === 'auth') {
      // The 'abort' to the host is sent without waiting: let it leave before stopping.
      await new Promise((r) => setTimeout(r, 500));
      return finish({ ok: true, error: 'auth' });
    }
    return fail(`connect failed: ${err.code || err.message}`);
  }
  if (expect === 'auth') return fail('connected with a wrong password');
  if (!statuses.includes('waiting-approval')) return fail(`statuses ${statuses.join(',')}`);
  if (session.info?.platform !== 'android' || session.peerName !== 'Kotlin host ✓') {
    return fail(`unexpected accepted message ${JSON.stringify(session.info)}`);
  }
  if (session.info?.caps?.control !== true) return fail('caps.control missing');

  signaling.on('message', async (sid, msg) => {
    if (sid !== session.sid) return;
    const sdp = sdpOf(msg, 'offer');
    if (sdp === null) return fail(`unexpected message ${JSON.stringify(msg)}`);
    if (sdp !== OFFER_SDP) return fail(`offer mismatch: ${JSON.stringify(sdp)}`);
    await signaling.send(sid, { type: 'signal', data: { description: { type: 'answer', sdp: ANSWER_SDP } } });
  });
  signaling.on('closed', (sid, reason) => {
    if (sid !== session.sid) return;
    if (reason !== 'interop-done') return fail(`closed with ${reason}`);
    finish({ ok: true, sid, statuses });
  });
}

async function runBroker(relay, kick) {
  out({ ready: true, transport: transportKind, ...relay });
  // Commands on stdin: "kick" drops every client connection.
  const rl = readline.createInterface({ input: process.stdin });
  rl.on('line', (line) => {
    if (line.trim() === 'kick') out({ kicked: kick() });
  });
  rl.on('close', () => finish({ ok: true }));
}

try {
  const { relay, kick } = await startRelay();
  if (role === 'host') await runHost(relay);
  else if (role === 'controller') await runController(relay);
  else if (role === 'broker') await runBroker(relay, kick);
  else await fail(`unknown role ${role}`);
} catch (err) {
  await fail(err.stack || err.message);
}

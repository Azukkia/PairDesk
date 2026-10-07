import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { randomBytes } from 'node:crypto';
import { createServer, createIceProvider } from '../../server/src/server.js';
import { WsTransport } from '../../src/main/signaling/transport-ws.js';
import { Signaling } from '../../src/main/signaling/signaling.js';

const once = (emitter, event, pred = () => true) => new Promise((resolve) => {
  const h = (...args) => {
    if (!pred(...args)) return;
    emitter.off(event, h);
    resolve(args);
  };
  emitter.on(event, h);
});

async function startServer(env = {}) {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pd-srv-'));
  const srv = createServer({ port: 0, host: '127.0.0.1', dataDir, env });
  const port = await srv.listen();
  return { srv, url: `ws://127.0.0.1:${port}/ws`, port, dataDir };
}

test('server relays messages between registered devices', async (t) => {
  const { srv, url, port } = await startServer({ TURN_URLS: 'turn:turn.example.com:3478', TURN_SECRET: 'shh' });
  t.after(() => srv.close());
  const a = new WsTransport({ url });
  const b = new WsTransport({ url });
  t.after(() => { a.stop(); b.stop(); });
  const iceA = once(a, 'ice-servers');
  a.start('111111111', randomBytes(24).toString('base64url'));
  b.start('222222222', randomBytes(24).toString('base64url'));
  await Promise.all([once(a, 'state', (s) => s === 'online'), once(b, 'state', (s) => s === 'online')]);
  const [servers] = await iceA;
  assert.equal(servers[0].urls[0], 'turn:turn.example.com:3478');
  assert.match(servers[0].username, /^\d+:111111111$/);

  const got = once(b, 'message');
  await a.send('222222222', { t: 'probe', sid: 'abcdefgh' });
  const [{ from, data }] = await got;
  assert.equal(from, '111111111');
  assert.equal(data.sid, 'abcdefgh');

  await assert.rejects(a.send('333333333', { t: 'probe', sid: 'abcdefgh' }), { code: 'offline' });

  const health = await fetch(`http://127.0.0.1:${port}/health`).then((r) => r.json());
  assert.equal(health.online, 2);
});

test('an ID stays bound to the device that registered it first', async (t) => {
  const { srv, url } = await startServer();
  t.after(() => srv.close());
  const owner = new WsTransport({ url });
  owner.start('444444444', 'owner-key-0123456789');
  await once(owner, 'state', (s) => s === 'online');
  owner.stop();
  const thief = new WsTransport({ url });
  t.after(() => thief.stop());
  const taken = once(thief, 'id-taken');
  thief.start('444444444', 'thief-key-0123456789');
  await taken;
  // The legitimate owner can come back.
  const again = new WsTransport({ url });
  t.after(() => again.stop());
  again.start('444444444', 'owner-key-0123456789');
  await once(again, 'state', (s) => s === 'online');
});

test('full PAKE handshake through the server', async (t) => {
  const { srv, url } = await startServer();
  t.after(() => srv.close());
  const ht = new WsTransport({ url });
  const ct = new WsTransport({ url });
  t.after(() => { ht.stop(); ct.stop(); });
  ht.start('555555555', randomBytes(24).toString('base64url'));
  ct.start('666666666', randomBytes(24).toString('base64url'));
  await Promise.all([once(ht, 'state', (s) => s === 'online'), once(ct, 'state', (s) => s === 'online')]);
  const prs = randomBytes(32);
  const host = new Signaling({ transport: ht, myId: '555555555', getCredentials: () => [{ kind: 'temp', prs }] });
  const ctrl = new Signaling({ transport: ct, myId: '666666666', getCredentials: () => [] });
  host.on('incoming', (s) => host.accept(s.sid, { name: 'H' }));
  await ctrl.probe('555555555');
  const session = await ctrl.connect('555555555', prs, { intro: { name: 'C' } });
  assert.equal(session.peerName, 'H');
  await assert.rejects(ctrl.probe('777777777'), { code: 'offline' });
});

test('Cloudflare TURN credentials are fetched and cached', async () => {
  let calls = 0;
  const fakeFetch = async (url) => {
    calls++;
    assert.match(url, /rtc\.live\.cloudflare\.com\/v1\/turn\/keys\/KEY\/credentials\/generate-ice-servers/);
    return { ok: true, status: 200, json: async () => ({ iceServers: [{ urls: ['turn:turn.cloudflare.com:3478'], username: 'u', credential: 'c' }] }) };
  };
  const provider = createIceProvider({ CF_TURN_KEY_ID: 'KEY', CF_TURN_API_TOKEN: 'TOKEN' }, fakeFetch);
  const first = await provider('123456789');
  const second = await provider('123456789');
  assert.equal(first[0].username, 'u');
  assert.deepEqual(first, second);
  assert.equal(calls, 1);
});

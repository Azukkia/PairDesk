import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { randomBytes } from 'node:crypto';
import { Aedes } from 'aedes';
import { WebSocketServer, createWebSocketStream } from 'ws';
import { MqttTransport } from '../../src/main/signaling/transport-mqtt.js';
import { Signaling } from '../../src/main/signaling/signaling.js';

async function startBroker() {
  const aedes = await Aedes.createBroker();
  const httpServer = http.createServer();
  const wss = new WebSocketServer({ server: httpServer });
  wss.on('connection', (socket, req) => aedes.handle(createWebSocketStream(socket), req));
  await new Promise((r) => httpServer.listen(0, '127.0.0.1', r));
  return {
    url: `ws://127.0.0.1:${httpServer.address().port}/mqtt`,
    async close() {
      for (const c of wss.clients) c.terminate();
      await new Promise((r) => httpServer.close(r));
      await new Promise((r) => aedes.close(r));
    },
  };
}

const online = (t) => new Promise((resolve) => {
  if (t.state === 'online') return resolve();
  const h = (s) => { if (s === 'online') { t.off('state', h); resolve(); } };
  t.on('state', h);
});

test('MQTT transport: redundant brokers, de-duplication and a full handshake', async (t) => {
  const b1 = await startBroker();
  const b2 = await startBroker();
  t.after(async () => { await b1.close(); await b2.close(); });
  const brokers = [b1.url, b2.url, 'ws://127.0.0.1:9/unreachable'];
  const ht = new MqttTransport({ brokers, prefix: 'test/' });
  const ct = new MqttTransport({ brokers, prefix: 'test/' });
  t.after(() => { ht.stop(); ct.stop(); });
  ht.start('123123123');
  ct.start('321321321');
  await Promise.all([online(ht), online(ct)]);
  // wait for both brokers on both sides
  for (let i = 0; i < 50 && (ht.connectedBrokers.length < 2 || ct.connectedBrokers.length < 2); i++) await new Promise((r) => setTimeout(r, 50));

  const received = [];
  ht.on('message', (m) => received.push(m));
  await ct.send('123123123', { t: 'probe', sid: 'mqtt-test-1' });
  await new Promise((r) => setTimeout(r, 300));
  assert.equal(received.length, 1, 'message delivered once despite two brokers');
  assert.equal(received[0].from, '321321321');

  const prs = randomBytes(32);
  const host = new Signaling({ transport: ht, myId: '123123123', getCredentials: () => [{ kind: 'temp', prs }] });
  const ctrl = new Signaling({ transport: ct, myId: '321321321', getCredentials: () => [] });
  host.on('incoming', (s) => host.accept(s.sid, { name: 'mqtt-host' }));
  const session = await ctrl.connect('123123123', prs);
  assert.equal(session.peerName, 'mqtt-host');
});

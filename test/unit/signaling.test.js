import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { Signaling, AuthLimiter } from '../../src/main/signaling/signaling.js';
import { MemoryBus, tick } from './helpers.js';

const HOST = '123456789';
const CTRL = '987654321';

function setup({ hostCreds, canAccept, limiter, autoAccept = true, timeouts } = {}) {
  const bus = new MemoryBus();
  const ht = bus.transport();
  const ct = bus.transport();
  ht.start(HOST);
  ct.start(CTRL);
  const temp = randomBytes(32);
  const perm = randomBytes(32);
  const creds = hostCreds ?? [{ kind: 'temp', prs: temp }, { kind: 'perm', prs: perm }];
  const host = new Signaling({ transport: ht, myId: HOST, getCredentials: () => creds, canAccept, limiter, timeouts });
  const ctrl = new Signaling({ transport: ct, myId: CTRL, getCredentials: () => [], timeouts });
  const incoming = [];
  host.on('incoming', (s) => {
    incoming.push(s);
    if (autoAccept) host.accept(s.sid, { name: 'Host PC' });
  });
  return { bus, host, ctrl, temp, perm, incoming };
}

test('probe answers when the partner is online, fails otherwise', async () => {
  const { ctrl } = setup();
  const res = await ctrl.probe(HOST);
  assert.equal(res.version, 1);
  await assert.rejects(ctrl.probe('555555555'), { code: 'offline' });
});

test('connects with the temporary password and exchanges encrypted messages', async () => {
  const { host, ctrl, temp, incoming, bus } = setup();
  const statuses = [];
  const session = await ctrl.connect(HOST, temp, { intro: { name: 'Laptop' }, onStatus: (s) => statuses.push(s) });
  assert.equal(session.role, 'controller');
  assert.equal(session.peerName, 'Host PC');
  assert.equal(incoming.length, 1);
  assert.equal(incoming[0].credential, 'temp');
  assert.equal(incoming[0].peerName, 'Laptop');
  assert.deepEqual(statuses, ['authenticating', 'authenticated']);

  const got = new Promise((r) => host.once('message', (sid, msg) => r({ sid, msg })));
  await ctrl.send(session.sid, { type: 'signal', data: { sdp: 'v=0 secret-sdp' } });
  const { sid, msg } = await got;
  assert.equal(sid, session.sid);
  assert.equal(msg.data.sdp, 'v=0 secret-sdp');
  // Nothing sensitive travels in clear on the relay.
  const wire = JSON.stringify(bus.log);
  assert.ok(!wire.includes('secret-sdp'));
  assert.ok(!wire.includes('Laptop'));

  const closed = new Promise((r) => host.once('closed', (s, reason) => r(reason)));
  ctrl.close(session.sid, 'user');
  assert.equal(await closed, 'user');
});

test('connects with the permanent password', async () => {
  const { ctrl, perm, incoming } = setup();
  await ctrl.connect(HOST, perm);
  assert.equal(incoming[0].credential, 'perm');
});

test('wrong password is rejected and counted', async () => {
  const limiter = new AuthLimiter();
  const { ctrl, host } = setup({ limiter, timeouts: { grace: 100 } });
  const failures = [];
  host.on('auth-failed', (e) => failures.push(e));
  await assert.rejects(ctrl.connect(HOST, randomBytes(32)), { code: 'auth' });
  await tick(20);
  // The controller detects the mismatch itself and aborts: the attempt still
  // counts on the host side, so guessing is rate limited.
  assert.equal(limiter.recentFailures, 1);
  assert.equal(failures.length, 1);
});

test('a forged confirmation is counted as a failure and locks out brute force', async () => {
  let now = 1_000_000;
  const limiter = new AuthLimiter({ threshold: 3, baseLockMs: 60_000, now: () => now });
  const { bus, ctrl, temp } = setup({ limiter });
  const attacker = bus.transport();
  attacker.start('555555555');
  const replies = [];
  attacker.on('message', ({ data }) => replies.push(data));
  for (let i = 0; i < 3; i++) {
    const sid = `forged-session-${i}`;
    const { cpaceShare, channelIdentifier } = await import('../../src/main/crypto/cpace.js');
    const { share } = cpaceShare(randomBytes(32), channelIdentifier('555555555', HOST), sid);
    await attacker.send(HOST, { t: 'hello', sid, v: 1, ya: Buffer.from(share).toString('base64url') });
    await tick(20);
    await attacker.send(HOST, { t: 'confirm', sid, idx: 0, tag: Buffer.alloc(32).toString('base64url'), intro: {} });
    await tick(20);
  }
  assert.equal(limiter.recentFailures, 3);
  assert.ok(limiter.lockedFor() > 0);
  // Even the right password is refused while locked.
  await assert.rejects(ctrl.connect(HOST, temp), { code: 'locked' });
  now += 61_000;
  await ctrl.connect(HOST, temp);
  assert.equal(limiter.recentFailures, 0);
  assert.ok(replies.filter((r) => r.t === 'denied' && r.reason === 'auth').length === 3);
});

test('busy / disabled hosts refuse before authentication', async () => {
  const busy = setup({ canAccept: () => 'busy' });
  await assert.rejects(busy.ctrl.connect(HOST, busy.temp), { code: 'busy' });
  const none = setup({ hostCreds: [] });
  await assert.rejects(none.ctrl.connect(HOST, randomBytes(32)), { code: 'disabled' });
});

test('host can ask its user and then reject', async () => {
  const { host, ctrl, temp } = setup({ autoAccept: false });
  host.on('incoming', async (s) => {
    await host.notifyWaiting(s.sid);
    await tick(10);
    host.reject(s.sid, 'denied-by-user');
  });
  const statuses = [];
  await assert.rejects(ctrl.connect(HOST, temp, { onStatus: (s) => statuses.push(s) }), { code: 'denied-by-user' });
  assert.ok(statuses.includes('waiting-approval'));
});

test('an impostor answering on a public relay cannot hijack the session', async () => {
  const { bus, ctrl, temp, incoming } = setup();
  // A second listener on the host ID that answers every hello with garbage.
  const impostor = bus.transport();
  impostor.start(HOST);
  impostor.on('message', ({ from, data }) => {
    if (data.t === 'hello') {
      impostor.send(from, { t: 'challenge', sid: data.sid, ybs: [randomBytes(32).toString('base64url')], tags: [randomBytes(32).toString('base64url')] });
    }
  });
  const session = await ctrl.connect(HOST, temp);
  assert.ok(session.sid);
  assert.equal(incoming.length, 1);
});

test('connect can be cancelled', async () => {
  const { ctrl } = setup({ autoAccept: false });
  const ac = new AbortController();
  const p = ctrl.connect(HOST, randomBytes(32), { signal: ac.signal });
  ac.abort();
  await assert.rejects(p, { code: 'cancelled' });
});

test('version mismatch is reported', async () => {
  const { bus, temp } = setup();
  const t = bus.transport();
  t.start('444444444');
  const replies = [];
  t.on('message', ({ data }) => replies.push(data));
  await t.send(HOST, { t: 'hello', sid: 'some-session-x', v: 99, ya: Buffer.from(temp).toString('base64url') });
  await tick(20);
  assert.equal(replies[0].t, 'denied');
  assert.equal(replies[0].reason, 'version');
});

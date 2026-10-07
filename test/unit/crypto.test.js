import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import {
  channelIdentifier, cpaceShare, cpaceSecret, deriveSessionKeys, confirmTag, tagsEqual, lv,
} from '../../src/main/crypto/cpace.js';
import { SecureChannel } from '../../src/main/crypto/channel.js';
import { derivePrs } from '../../src/main/crypto/prs.js';

function handshake(prsA, prsB) {
  const sid = 'session-identifier-1';
  const ci = channelIdentifier('111111111', '222222222');
  const a = cpaceShare(prsA, ci, sid);
  const b = cpaceShare(prsB, ci, sid);
  const ka = cpaceSecret(a.scalar, b.share);
  const kb = cpaceSecret(b.scalar, a.share);
  return { ka, kb, a, b, sid };
}

test('lv encodes lengths so that concatenations are unambiguous', () => {
  assert.notDeepEqual(lv('ab', 'c'), lv('a', 'bc'));
  assert.equal(lv('abc').length, 7);
});

test('CPace: same password gives the same shared secret', () => {
  const prs = randomBytes(32);
  const { ka, kb } = handshake(prs, prs);
  assert.deepEqual(Buffer.from(ka), Buffer.from(kb));
});

test('CPace: different passwords give different secrets', () => {
  const { ka, kb } = handshake(randomBytes(32), randomBytes(32));
  assert.notDeepEqual(Buffer.from(ka), Buffer.from(kb));
});

test('CPace: invalid or identity shares are rejected', () => {
  const { a } = handshake(randomBytes(32), randomBytes(32));
  assert.throws(() => cpaceSecret(a.scalar, new Uint8Array(32)));
  assert.throws(() => cpaceSecret(a.scalar, new Uint8Array(32).fill(0xff)));
  assert.throws(() => cpaceSecret(a.scalar, new Uint8Array(31)));
});

test('key confirmation tags only match with the same keys', () => {
  const prs = randomBytes(32);
  const { ka, kb, a, b, sid } = handshake(prs, prs);
  const kA = deriveSessionKeys({ sid, ctrlId: '1', hostId: '2', ya: a.share, yb: b.share, k: ka });
  const kB = deriveSessionKeys({ sid, ctrlId: '1', hostId: '2', ya: a.share, yb: b.share, k: kb });
  assert.ok(tagsEqual(confirmTag(kA.tagKeyHost, 'host-confirm', kA.transcript), confirmTag(kB.tagKeyHost, 'host-confirm', kB.transcript)));
  assert.ok(!tagsEqual(confirmTag(kA.tagKeyHost, 'host-confirm', kA.transcript), confirmTag(kA.tagKeyCtrl, 'host-confirm', kA.transcript)));
});

test('SecureChannel round-trips and rejects tampering and replays', () => {
  const k1 = randomBytes(32);
  const k2 = randomBytes(32);
  const host = new SecureChannel({ sendKey: k1, recvKey: k2, sendDir: 1, sid: 'abc' });
  const ctrl = new SecureChannel({ sendKey: k2, recvKey: k1, sendDir: 2, sid: 'abc' });
  const m1 = host.seal({ hello: 'world', n: 1 });
  assert.deepEqual(ctrl.open(m1), { hello: 'world', n: 1 });
  assert.throws(() => ctrl.open(m1), /replayed/);
  const m2 = ctrl.seal({ x: 2 });
  assert.deepEqual(host.open(m2), { x: 2 });
  const m3 = host.seal({ y: 3 });
  const tampered = { ...m3, ct: Buffer.from(Buffer.from(m3.ct, 'base64url').map((b, i) => (i === 0 ? b ^ 1 : b))).toString('base64url') };
  assert.throws(() => ctrl.open(tampered));
  // a message cannot be reflected back to its sender
  const m4 = host.seal({ z: 4 });
  assert.throws(() => host.open(m4));
  // a different session id breaks authentication
  const other = new SecureChannel({ sendKey: k2, recvKey: k1, sendDir: 2, sid: 'xyz' });
  assert.throws(() => other.open(host.seal({ w: 5 })));
});

test('PRS derivation depends on password and host id', async () => {
  const a = await derivePrs('secret', '123456789');
  const b = await derivePrs('  secret ', '123456789');
  const c = await derivePrs('secret', '987654321');
  const d = await derivePrs('Secret', '123456789');
  assert.deepEqual(a, b);
  assert.notDeepEqual(a, c);
  assert.notDeepEqual(a, d);
  assert.equal(a.length, 32);
});

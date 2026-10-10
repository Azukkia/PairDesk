// Prints interoperability test vectors of the PairDesk protocol (JSON), used
// by the Android port's unit tests: node scripts/protocol-vectors.mjs > file
import { createHash } from 'node:crypto';
import { ristretto255 } from '@noble/curves/ed25519.js';
import { lv, channelIdentifier, generator, cpaceSecret, deriveSessionKeys, confirmTag } from '../src/main/crypto/cpace.js';
import { derivePrs } from '../src/main/crypto/prs.js';
import { SecureChannel } from '../src/main/crypto/channel.js';
import { topicFor } from '../src/main/signaling/transport-mqtt.js';

const hex = (u8) => Buffer.from(u8).toString('hex');
const b64u = (u8) => Buffer.from(u8).toString('base64url');
const ORDER = ristretto255.Point.Fn.ORDER;
// Deterministic "random" scalars for the vectors.
const scalarFrom = (label) => {
  const h = createHash('sha512').update(label).digest();
  let x = 0n;
  for (let i = h.length - 1; i >= 0; i--) x = (x << 8n) | BigInt(h[i]);
  return x % ORDER;
};
const scalarHex = (s) => s.toString(16).padStart(64, '0');

const cases = [];
for (const [password, ctrlId, hostId, sid] of [
  ['abc234', '123456789', '987654321', 'c2lkLXZlY3Rvci0wMDAwMDE'],
  ['  Mot de passé ', '555000111', '400200300', 'AAAAAAAAAAAAAAAAAAAAAAAA'],
]) {
  const prs = await derivePrs(password, hostId);
  const ci = channelIdentifier(ctrlId, hostId);
  const g = generator(prs, ci, sid);
  const a = scalarFrom(`a|${sid}`);
  const b = scalarFrom(`b|${sid}`);
  const ya = g.multiply(a).toBytes();
  const yb = g.multiply(b).toBytes();
  const k = cpaceSecret(a, yb);
  const k2 = cpaceSecret(b, ya);
  if (hex(k) !== hex(k2)) throw new Error('mismatch');
  const keys = deriveSessionKeys({ sid, ctrlId, hostId, ya, yb, k });
  const host = new SecureChannel({ sendKey: keys.encHostToCtrl, recvKey: keys.encCtrlToHost, sendDir: 1, sid });
  const ctrl = new SecureChannel({ sendKey: keys.encCtrlToHost, recvKey: keys.encHostToCtrl, sendDir: 2, sid });
  const intro = ctrl.seal({ type: 'intro', name: 'Pixel', platform: 'android', appVersion: '1.2.0' });
  const accepted = host.seal({ type: 'accepted', name: 'PC', caps: { control: true } });
  cases.push({
    password, ctrlId, hostId, sid,
    prs: hex(prs),
    ci: hex(ci),
    generatorInput: hex(lv(prs, ci, sid)),
    generator: hex(g.toBytes()),
    scalarA: scalarHex(a), scalarB: scalarHex(b),
    ya: hex(ya), yb: hex(yb), yaB64u: b64u(ya),
    k: hex(k),
    transcript: hex(keys.transcript),
    tagKeyHost: hex(keys.tagKeyHost), tagKeyCtrl: hex(keys.tagKeyCtrl),
    encHostToCtrl: hex(keys.encHostToCtrl), encCtrlToHost: hex(keys.encCtrlToHost),
    hostTag: hex(confirmTag(keys.tagKeyHost, 'host-confirm', keys.transcript)),
    ctrlTag: hex(confirmTag(keys.tagKeyCtrl, 'ctrl-confirm', keys.transcript)),
    sealedIntro: { ...intro, plaintext: JSON.stringify({ type: 'intro', name: 'Pixel', platform: 'android', appVersion: '1.2.0' }) },
    sealedAccepted: { ...accepted, plaintext: JSON.stringify({ type: 'accepted', name: 'PC', caps: { control: true } }) },
  });
}

// hash_to_ristretto255 alone (RFC 9380 expand_message_xmd SHA-512).
const { ristretto255_hasher } = await import('@noble/curves/ed25519.js');
const h2c = ['', 'abc', 'PairDesk'].map((m) => ({
  msg: m,
  dst: 'PairDesk-v1-CPace-ristretto255',
  point: hex(ristretto255_hasher.hashToCurve(new TextEncoder().encode(m), { DST: 'PairDesk-v1-CPace-ristretto255' }).toBytes()),
}));

const topics = ['123456789', '987654321'].map((id) => ({ id, topic: topicFor('pairdesk/v1/', id) }));

process.stdout.write(`${JSON.stringify({ cpace: cases, hashToRistretto: h2c, mqttTopics: topics }, null, 2)}\n`);

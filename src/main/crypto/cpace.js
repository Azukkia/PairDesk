// CPace balanced PAKE over ristretto255 (draft-irtf-cfrg-cpace style).
//
// Both peers derive a password-dependent generator G = H2C(PRS, CI, sid) and
// exchange Y = y·G. The shared secret K = y·Y' only matches when both sides
// used the same password. A passive observer (including the signaling relay)
// learns nothing about the password and cannot run an offline dictionary
// attack; an active attacker gets exactly one password guess per handshake.

import { ristretto255, ristretto255_hasher } from '@noble/curves/ed25519.js';
import { createHash, createHmac, hkdfSync, randomBytes, timingSafeEqual } from 'node:crypto';

const Point = ristretto255.Point;
const ORDER = Point.Fn.ORDER;
const DST = 'PairDesk-v1-CPace-ristretto255';
const encoder = new TextEncoder();

function bytes(x) {
  if (x instanceof Uint8Array) return x;
  if (typeof x === 'string') return encoder.encode(x);
  throw new TypeError('expected string or Uint8Array');
}

/** Length-prefixed concatenation (4-byte big-endian length + value). */
export function lv(...parts) {
  const items = parts.map(bytes);
  const total = items.reduce((n, b) => n + 4 + b.length, 0);
  const out = new Uint8Array(total);
  const view = new DataView(out.buffer);
  let off = 0;
  for (const b of items) {
    view.setUint32(off, b.length);
    out.set(b, off + 4);
    off += 4 + b.length;
  }
  return out;
}

export function channelIdentifier(ctrlId, hostId) {
  return lv('ctrl', ctrlId, 'host', hostId);
}

export function generator(prs, ci, sid) {
  return ristretto255_hasher.hashToCurve(lv(prs, ci, sid), { DST });
}

export function randomScalar() {
  for (;;) {
    const buf = randomBytes(64);
    let x = 0n;
    for (let i = buf.length - 1; i >= 0; i--) x = (x << 8n) | BigInt(buf[i]);
    x %= ORDER;
    if (x !== 0n) return x;
  }
}

/** Returns our secret scalar and the public share to send to the peer. */
export function cpaceShare(prs, ci, sid) {
  const scalar = randomScalar();
  const share = generator(prs, ci, sid).multiply(scalar).toBytes();
  return { scalar, share };
}

/** Computes K from our scalar and the peer's share; throws on invalid input. */
export function cpaceSecret(scalar, peerShare) {
  if (!(peerShare instanceof Uint8Array) || peerShare.length !== 32) throw new Error('invalid share length');
  const point = Point.fromBytes(peerShare); // throws on non-canonical encodings
  if (point.is0()) throw new Error('identity share');
  const k = point.multiply(scalar);
  if (k.is0()) throw new Error('identity secret');
  return k.toBytes();
}

/**
 * Derives the confirmation and encryption keys of a session.
 * ya: controller share, yb: host share.
 */
export function deriveSessionKeys({ sid, ctrlId, hostId, ya, yb, k }) {
  const transcript = lv('PairDesk-v1-transcript', sid, ctrlId, hostId, ya, yb);
  const isk = createHash('sha512').update(lv('PairDesk-v1-CPace-ISK', sid, k, ya, yb)).digest();
  const okm = Buffer.from(hkdfSync('sha256', isk, bytes(sid), 'PairDesk v1 key schedule', 128));
  return {
    transcript,
    tagKeyHost: okm.subarray(0, 32),
    tagKeyCtrl: okm.subarray(32, 64),
    encHostToCtrl: okm.subarray(64, 96),
    encCtrlToHost: okm.subarray(96, 128),
  };
}

export function confirmTag(key, label, transcript, extra = '') {
  return createHmac('sha256', key).update(lv(label, transcript, extra)).digest();
}

export function tagsEqual(a, b) {
  if (!(a instanceof Uint8Array) || !(b instanceof Uint8Array) || a.length !== b.length) return false;
  return timingSafeEqual(a, b);
}

// Authenticated encryption of signaling messages once a session key exists.
// AES-256-GCM, one key per direction, 96-bit nonce = direction || counter.

import { createCipheriv, createDecipheriv } from 'node:crypto';

const MAX_COUNTER = 2 ** 48;

function nonce(direction, counter) {
  const iv = Buffer.alloc(12);
  iv.writeUInt32BE(direction, 0);
  iv.writeUIntBE(counter, 6, 6);
  return iv;
}

export class SecureChannel {
  /**
   * @param {object} o
   * @param {Buffer} o.sendKey
   * @param {Buffer} o.recvKey
   * @param {number} o.sendDir 1 (host→controller) or 2 (controller→host)
   * @param {string} o.sid session id, bound into every message
   */
  constructor({ sendKey, recvKey, sendDir, sid }) {
    this.sendKey = sendKey;
    this.recvKey = recvKey;
    this.sendDir = sendDir;
    this.recvDir = sendDir === 1 ? 2 : 1;
    this.aad = Buffer.from(`PairDesk-v1|${sid}`);
    this.counter = 0;
    this.seen = new Set();
  }

  seal(message) {
    const n = ++this.counter;
    if (n >= MAX_COUNTER) throw new Error('counter exhausted');
    const cipher = createCipheriv('aes-256-gcm', this.sendKey, nonce(this.sendDir, n));
    cipher.setAAD(this.aad);
    const body = Buffer.concat([cipher.update(JSON.stringify(message), 'utf8'), cipher.final(), cipher.getAuthTag()]);
    return { n, ct: body.toString('base64url') };
  }

  open({ n, ct }) {
    if (!Number.isSafeInteger(n) || n <= 0 || n >= MAX_COUNTER) throw new Error('bad counter');
    if (this.seen.has(n)) throw new Error('replayed message');
    const body = Buffer.from(String(ct), 'base64url');
    if (body.length < 17) throw new Error('truncated message');
    const decipher = createDecipheriv('aes-256-gcm', this.recvKey, nonce(this.recvDir, n));
    decipher.setAAD(this.aad);
    decipher.setAuthTag(body.subarray(body.length - 16));
    const plain = Buffer.concat([decipher.update(body.subarray(0, body.length - 16)), decipher.final()]);
    this.seen.add(n);
    return JSON.parse(plain.toString('utf8'));
  }
}

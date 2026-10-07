// Password-related string (PRS) derivation. Both peers derive the same value
// from the password and the host ID, so the host never needs to store the
// password itself (the permanent password is stored as its PRS only).

import { pbkdf2 } from 'node:crypto';
import { normalizePassword } from '../../shared/protocol.js';

export const PRS_ITERATIONS = 200_000;

export function derivePrs(password, hostId) {
  const pw = normalizePassword(password);
  return new Promise((resolve, reject) => {
    pbkdf2(pw, `PairDesk-PRS-v1|${hostId}`, PRS_ITERATIONS, 32, 'sha256', (err, key) => {
      if (err) reject(err);
      else resolve(key);
    });
  });
}

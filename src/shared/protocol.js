// Constants and helpers shared by the main process and the renderers.
// Must stay free of Node/DOM specific APIs (only globalThis.crypto is used).

export const APP_NAME = 'PairDesk';

// Bump when the signaling / session protocol changes in an incompatible way.
export const PROTOCOL_VERSION = 1;

// Readable alphabet for generated passwords: no 0/o/1/l/i to avoid confusion.
export const PASSWORD_ALPHABET = 'abcdefghjkmnpqrstuvwxyz23456789';

export const MIN_PERMANENT_PASSWORD_LENGTH = 8;

function randomInt(maxExclusive) {
  // Rejection sampling for an unbiased integer in [0, maxExclusive).
  const buf = new Uint32Array(1);
  const limit = Math.floor(0x100000000 / maxExclusive) * maxExclusive;
  for (;;) {
    globalThis.crypto.getRandomValues(buf);
    if (buf[0] < limit) return buf[0] % maxExclusive;
  }
}

/** Generates a 9-digit device ID that never starts with 0. */
export function generateDeviceId() {
  let id = String(1 + randomInt(9));
  for (let i = 0; i < 8; i++) id += String(randomInt(10));
  return id;
}

export function generatePassword(length = 6) {
  let out = '';
  for (let i = 0; i < length; i++) out += PASSWORD_ALPHABET[randomInt(PASSWORD_ALPHABET.length)];
  return out;
}

/** Keeps only digits: lets users paste "123 456 789" or "123-456-789". */
export function normalizeId(input) {
  return String(input ?? '').replace(/\D+/g, '');
}

export function isValidId(id) {
  return /^[1-9]\d{8}$/.test(String(id ?? ''));
}

export function formatId(id) {
  const digits = normalizeId(id);
  return digits.replace(/(\d{3})(?=\d)/g, '$1 ').trim();
}

/** Passwords are compared exactly, apart from surrounding whitespace. */
export function normalizePassword(password) {
  return String(password ?? '').normalize('NFC').trim();
}

// Encoder settings per viewer quality mode. `scale` is the resolution
// downscale factor for normal screens and for large (> 2560×1600) screens.
export const QUALITY_PRESETS = {
  speed: { maxBitrate: 1_500_000, maxFramerate: 30, scale: [1.5, 2.5], contentHint: 'motion', degradation: 'maintain-framerate' },
  balanced: { maxBitrate: 5_000_000, maxFramerate: 30, scale: [1, 1.5], contentHint: 'detail', degradation: 'balanced' },
  quality: { maxBitrate: 15_000_000, maxFramerate: 30, scale: [1, 1], contentHint: 'detail', degradation: 'maintain-resolution' },
};

export const MOUSE_BUTTONS = { left: 0, middle: 1, right: 2, back: 3, forward: 4 };

// Persistent settings (userData/settings.json), written atomically.

import fs from 'node:fs';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import { randomBytes } from 'node:crypto';
import { generateDeviceId, isValidId } from '../shared/protocol.js';

export const DEFAULTS = Object.freeze({
  deviceId: null,
  deviceKey: null,
  displayName: '',
  language: 'auto',
  theme: 'system',
  launchAtStartup: false,
  closeToTray: true,
  trayHintShown: false,
  // Incoming connections
  allowIncoming: true,
  useRandomPassword: true,
  passwordLength: 6,
  regeneratePasswordAfterSession: false,
  permanentPrs: null,
  confirmIncoming: false,
  allowControl: true,
  allowFileTransfer: true,
  allowClipboard: true,
  shareAudio: true,
  // Network
  networkMode: 'public',
  serverUrl: '',
  customIce: { urls: '', username: '', credential: '' },
  // Updates
  autoCheckUpdates: true,
  // Viewer preferences
  viewerQuality: 'balanced',
  viewerScale: 'fit',
  viewerClipboard: true,
  // Recent partners: { id, name, lastAt, count, prs? }
  recents: [],
});

const bool = (v) => typeof v === 'boolean';
const oneOf = (...values) => (v) => values.includes(v);
const str = (max) => (v) => typeof v === 'string' && v.length <= max;

// Keys the renderer is allowed to change, with their validators.
const EDITABLE = {
  displayName: str(64),
  language: oneOf('auto', 'fr', 'en'),
  theme: oneOf('system', 'light', 'dark'),
  launchAtStartup: bool,
  closeToTray: bool,
  allowIncoming: bool,
  useRandomPassword: bool,
  passwordLength: oneOf(6, 8, 10, 12),
  regeneratePasswordAfterSession: bool,
  confirmIncoming: bool,
  allowControl: bool,
  allowFileTransfer: bool,
  allowClipboard: bool,
  shareAudio: bool,
  networkMode: oneOf('public', 'server'),
  serverUrl: (v) => typeof v === 'string' && v.length <= 300 && (v === '' || /^wss?:\/\/[^\s]+$/i.test(v)),
  customIce: (v) => v && typeof v === 'object' && str(1000)(v.urls ?? '') && str(256)(v.username ?? '') && str(256)(v.credential ?? ''),
  autoCheckUpdates: bool,
  viewerQuality: oneOf('speed', 'balanced', 'quality'),
  viewerScale: oneOf('fit', 'original'),
  viewerClipboard: bool,
};

export class Settings extends EventEmitter {
  /**
   * @param {string} file
   * @param {{available(): boolean, encrypt(s: string): Buffer, decrypt(b: Buffer): string}} [crypter]
   */
  constructor(file, crypter = null) {
    super();
    this.file = file;
    this.crypter = crypter;
    this.data = structuredClone(DEFAULTS);
    let loaded = {};
    try {
      loaded = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
      /* first launch or unreadable file */
    }
    for (const key of Object.keys(DEFAULTS)) {
      if (loaded[key] !== undefined) this.data[key] = loaded[key];
    }
    let dirty = false;
    if (!isValidId(this.data.deviceId)) {
      this.data.deviceId = generateDeviceId();
      dirty = true;
    }
    if (typeof this.data.deviceKey !== 'string' || this.data.deviceKey.length < 32) {
      this.data.deviceKey = randomBytes(32).toString('base64url');
      dirty = true;
    }
    if (!Array.isArray(this.data.recents)) this.data.recents = [];
    if (dirty) this.saveNow();
  }

  get(key) {
    return this.data[key];
  }

  /** Applies a patch coming from the UI; unknown/invalid keys are ignored. */
  update(patch) {
    const applied = {};
    for (const [key, value] of Object.entries(patch || {})) {
      const valid = EDITABLE[key];
      if (!valid || !valid(value)) continue;
      this.data[key] = key === 'customIce'
        ? { urls: value.urls || '', username: value.username || '', credential: value.credential || '' }
        : value;
      applied[key] = this.data[key];
    }
    if (Object.keys(applied).length) {
      this.save();
      this.emit('change', applied);
    }
    return applied;
  }

  /** Internal update without validation (main process only). */
  set(key, value) {
    this.data[key] = value;
    this.save();
    this.emit('change', { [key]: value });
  }

  // Secrets are encrypted with the OS keychain (DPAPI on Windows) when possible.
  seal(buffer) {
    if (!buffer) return null;
    const b64 = Buffer.from(buffer).toString('base64');
    if (this.crypter?.available()) {
      try {
        return { enc: 'os', data: this.crypter.encrypt(b64).toString('base64') };
      } catch {
        /* fall back to plain storage */
      }
    }
    return { enc: 'none', data: b64 };
  }

  unseal(sealed) {
    if (!sealed || typeof sealed.data !== 'string') return null;
    try {
      if (sealed.enc === 'os') return Buffer.from(this.crypter.decrypt(Buffer.from(sealed.data, 'base64')), 'base64');
      return Buffer.from(sealed.data, 'base64');
    } catch {
      return null;
    }
  }

  save() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.saveNow(), 250);
  }

  saveNow() {
    clearTimeout(this.timer);
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      const tmp = `${this.file}.tmp`;
      fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2));
      fs.renameSync(tmp, this.file);
    } catch (err) {
      this.emit('error', err);
    }
  }

  // ───────────── recent partners ─────────────

  touchRecent(id, { name, prs } = {}) {
    const recents = this.data.recents.filter((r) => r.id !== id);
    const previous = this.data.recents.find((r) => r.id === id);
    const entry = {
      id,
      name: name || previous?.name || '',
      lastAt: Date.now(),
      count: (previous?.count || 0) + 1,
      prs: prs === undefined ? previous?.prs ?? null : prs ? this.seal(prs) : null,
    };
    recents.unshift(entry);
    this.set('recents', recents.slice(0, 30));
  }

  forgetRecent(id) {
    this.set('recents', this.data.recents.filter((r) => r.id !== id));
  }

  forgetRecentPassword(id) {
    this.set('recents', this.data.recents.map((r) => (r.id === id ? { ...r, prs: null } : r)));
  }

  savedPrs(id) {
    const entry = this.data.recents.find((r) => r.id === id);
    return entry?.prs ? this.unseal(entry.prs) : null;
  }

  /** Recents without secrets, for the UI. */
  publicRecents() {
    return this.data.recents.map(({ id, name, lastAt, count, prs }) => ({ id, name, lastAt, count, hasPassword: Boolean(prs) }));
  }
}

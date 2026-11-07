// Automatic update detection (GitHub Releases through electron-updater).
// Updates are never installed silently: the user is offered to download and
// then to restart, from the main window or a system notification.
//
// macOS: an application can only replace itself when it carries an Apple
// Developer ID signature, which PairDesk has not. There, PairDesk only looks
// at the latest GitHub release and offers its .dmg, downloaded in the browser
// and installed by hand (state.manual).

import { EventEmitter } from 'node:events';
import updaterPkg from 'electron-updater';

// Created on first use: never on macOS, and not when tests import this file.
const autoUpdater = () => updaterPkg.autoUpdater;

const CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000;
const LATEST_RELEASE_API = 'https://api.github.com/repos/Azukkia/PairDesk/releases/latest';
const RELEASES_PAGE = 'https://github.com/Azukkia/PairDesk/releases/latest';

/** Compares two "x.y.z" versions (pre-release suffixes ignored): <0, 0 or >0. */
export function compareVersions(a, b) {
  const parts = (v) => String(v).replace(/^v/, '').split(/[-+]/)[0].split('.').map((n) => Number.parseInt(n, 10) || 0);
  const x = parts(a);
  const y = parts(b);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] || 0) - (y[i] || 0);
    if (d) return d;
  }
  return 0;
}

/** The download of a GitHub release (API answer) for a Mac: its .dmg, else the release page. */
export function macDownload(release) {
  const assets = Array.isArray(release?.assets) ? release.assets : [];
  const dmg = assets.find((a) => a?.name === 'PairDesk-mac.dmg') || assets.find((a) => /\.dmg$/i.test(a?.name || ''));
  const url = dmg?.browser_download_url || release?.html_url || RELEASES_PAGE;
  return /^https:\/\/github\.com\//.test(url) ? url : RELEASES_PAGE;
}

function notesToText(notes) {
  if (!notes) return '';
  const raw = Array.isArray(notes) ? notes.map((n) => n.note || '').join('\n\n') : String(notes);
  return raw
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|li|h\d)>/gi, '\n')
    .replace(/<li>/gi, '• ')
    .replace(/<[^>]+>/g, '')
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/\n{3,}/g, '\n\n')
    .trim()
    .slice(0, 4000);
}

export class Updater extends EventEmitter {
  /**
   * `fetch` (Electron's net.fetch) and `openExternal` are used on macOS, where
   * updates are installed by hand.
   */
  constructor({ log, isPackaged, currentVersion, platform = process.platform, fetch = globalThis.fetch, openExternal = null }) {
    super();
    this.log = log;
    this.currentVersion = currentVersion;
    this.supported = isPackaged || process.env.PAIRDESK_UPDATE_DEV === '1';
    this.byHand = platform === 'darwin';
    this.fetch = fetch;
    this.openExternal = openExternal;
    this.state = {
      status: this.supported ? 'idle' : 'unsupported', version: null, notes: '', progress: 0, error: null, checkedAt: null, manual: this.byHand,
    };
    this.manual = false;
    if (!this.supported || this.byHand) return;

    const updater = autoUpdater();
    if (!isPackaged) updater.forceDevUpdateConfig = true;
    updater.autoDownload = false;
    updater.autoInstallOnAppQuit = true;
    updater.allowPrerelease = false;
    updater.logger = {
      info: (m) => log.info(`[updater] ${m}`),
      warn: (m) => log.warn(`[updater] ${m}`),
      error: (m) => log.error(`[updater] ${m}`),
      debug: () => {},
    };

    updater.on('checking-for-update', () => this.#set({ status: 'checking', error: null }));
    updater.on('update-not-available', () => this.#set({ status: 'up-to-date', checkedAt: Date.now() }));
    updater.on('update-available', (info) => {
      const already = this.state.version === info.version && ['available', 'downloading', 'downloaded'].includes(this.state.status);
      if (already && this.state.status !== 'available') return;
      this.#set({ status: 'available', version: info.version, notes: notesToText(info.releaseNotes), checkedAt: Date.now() });
      this.emit('available', info.version);
    });
    updater.on('download-progress', (p) => this.#set({ status: 'downloading', progress: Math.round(p.percent || 0) }));
    updater.on('update-downloaded', (info) => {
      this.#set({ status: 'downloaded', version: info.version, progress: 100 });
      this.emit('downloaded', info.version);
    });
    updater.on('error', (err) => {
      this.log.warn(`[updater] ${err?.message || err}`);
      // Background checks fail quietly (offline…); manual checks report it.
      const status = this.state.status === 'downloading' ? 'available' : this.manual ? 'error' : 'idle';
      this.#set({ status, error: String(err?.message || err).split('\n')[0].slice(0, 300) });
    });
  }

  #set(patch) {
    this.state = { ...this.state, ...patch };
    this.emit('state', this.state);
  }

  async check({ manual = false } = {}) {
    if (!this.supported) return this.state;
    if (['checking', 'downloading', 'downloaded'].includes(this.state.status)) return this.state;
    this.manual = manual;
    if (this.byHand) return this.#checkByHand();
    try {
      await autoUpdater().checkForUpdates();
    } catch (err) {
      /* reported through the error event */
    }
    return this.state;
  }

  /** macOS: the latest release from the GitHub API. */
  async #checkByHand() {
    const previous = this.state;
    this.#set({ status: 'checking', error: null });
    try {
      const res = await this.fetch(LATEST_RELEASE_API, {
        headers: { Accept: 'application/vnd.github+json' },
        signal: AbortSignal.timeout(15_000),
      });
      if (!res.ok) throw new Error(`GitHub answered ${res.status}`);
      const release = await res.json();
      const version = String(release.tag_name || '').replace(/^v/, '');
      if (!/^\d+\.\d+\.\d+/.test(version)) throw new Error('no version in the latest release');
      if (compareVersions(version, this.currentVersion) <= 0) {
        this.#set({ status: 'up-to-date', checkedAt: Date.now() });
        return this.state;
      }
      this.downloadUrl = macDownload(release);
      const fresh = previous.status !== 'available' || previous.version !== version;
      this.#set({ status: 'available', version, notes: notesToText(release.body), checkedAt: Date.now() });
      if (fresh) this.emit('available', version);
    } catch (err) {
      this.log.warn(`[updater] ${err.message}`);
      // Background checks fail quietly (offline…); manual checks report it.
      this.#set(previous.status === 'available' && !this.manual
        ? { status: 'available' }
        : { status: this.manual ? 'error' : 'idle', error: String(err.message).slice(0, 300) });
    }
    return this.state;
  }

  async download() {
    if (this.state.status !== 'available') return this.state;
    if (this.byHand) {
      // The .dmg opens in the browser; the user installs it (update.manualHint).
      await this.openExternal?.(this.downloadUrl || RELEASES_PAGE);
      return this.state;
    }
    this.#set({ status: 'downloading', progress: 0, error: null });
    try {
      await autoUpdater().downloadUpdate();
    } catch {
      /* reported through the error event */
    }
    return this.state;
  }

  install() {
    if (this.state.status !== 'downloaded' || this.byHand) return false;
    this.emit('installing');
    // isSilent = true: no installer wizard, isForceRunAfter = true: relaunch.
    setImmediate(() => autoUpdater().quitAndInstall(true, true));
    return true;
  }

  schedule(enabled) {
    clearTimeout(this.firstTimer);
    clearInterval(this.interval);
    if (!this.supported || !enabled) return;
    this.firstTimer = setTimeout(() => this.check(), 8000);
    this.interval = setInterval(() => this.check(), CHECK_INTERVAL_MS);
  }
}

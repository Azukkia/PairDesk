// Automatic update detection (GitHub Releases through electron-updater).
// Updates are never installed silently: the user is offered to download and
// then to restart, from the main window or a system notification.

import { EventEmitter } from 'node:events';
import updaterPkg from 'electron-updater';

const { autoUpdater } = updaterPkg;

const CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000;

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
  constructor({ log, isPackaged, currentVersion }) {
    super();
    this.log = log;
    this.currentVersion = currentVersion;
    this.supported = isPackaged || process.env.PAIRDESK_UPDATE_DEV === '1';
    this.state = { status: this.supported ? 'idle' : 'unsupported', version: null, notes: '', progress: 0, error: null, checkedAt: null };
    this.manual = false;
    if (!this.supported) return;

    if (!isPackaged) autoUpdater.forceDevUpdateConfig = true;
    autoUpdater.autoDownload = false;
    autoUpdater.autoInstallOnAppQuit = true;
    autoUpdater.allowPrerelease = false;
    autoUpdater.logger = {
      info: (m) => log.info(`[updater] ${m}`),
      warn: (m) => log.warn(`[updater] ${m}`),
      error: (m) => log.error(`[updater] ${m}`),
      debug: () => {},
    };

    autoUpdater.on('checking-for-update', () => this.#set({ status: 'checking', error: null }));
    autoUpdater.on('update-not-available', () => this.#set({ status: 'up-to-date', checkedAt: Date.now() }));
    autoUpdater.on('update-available', (info) => {
      const already = this.state.version === info.version && ['available', 'downloading', 'downloaded'].includes(this.state.status);
      if (already && this.state.status !== 'available') return;
      this.#set({ status: 'available', version: info.version, notes: notesToText(info.releaseNotes), checkedAt: Date.now() });
      this.emit('available', info.version);
    });
    autoUpdater.on('download-progress', (p) => this.#set({ status: 'downloading', progress: Math.round(p.percent || 0) }));
    autoUpdater.on('update-downloaded', (info) => {
      this.#set({ status: 'downloaded', version: info.version, progress: 100 });
      this.emit('downloaded', info.version);
    });
    autoUpdater.on('error', (err) => {
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
    try {
      await autoUpdater.checkForUpdates();
    } catch (err) {
      /* reported through the error event */
    }
    return this.state;
  }

  async download() {
    if (this.state.status !== 'available') return this.state;
    this.#set({ status: 'downloading', progress: 0, error: null });
    try {
      await autoUpdater.downloadUpdate();
    } catch {
      /* reported through the error event */
    }
    return this.state;
  }

  install() {
    if (this.state.status !== 'downloaded') return false;
    this.emit('installing');
    // isSilent = true: no installer wizard, isForceRunAfter = true: relaunch.
    setImmediate(() => autoUpdater.quitAndInstall(true, true));
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

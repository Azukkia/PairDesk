// Orchestrates remote sessions: outgoing connections (viewer windows) and the
// incoming session (host panel), routes WebRTC signaling between the windows
// and the encrypted signaling channel, and performs input injection.

import { EventEmitter } from 'node:events';
import os from 'node:os';
import { screen, desktopCapturer, powerSaveBlocker } from 'electron';
import { PROTOCOL_VERSION, isValidId } from '../shared/protocol.js';
import { derivePrs } from './crypto/prs.js';
import { ClipboardSync } from './clipboard.js';
import { SignalingError } from './signaling/signaling.js';

export function physicalRect(display) {
  if (process.platform === 'win32') return screen.dipToScreenRect(null, display.bounds);
  if (process.platform === 'linux') {
    const s = display.scaleFactor || 1;
    const b = display.bounds;
    return { x: Math.round(b.x * s), y: Math.round(b.y * s), width: Math.round(b.width * s), height: Math.round(b.height * s) };
  }
  return display.bounds;
}

export class SessionManager extends EventEmitter {
  constructor({ network, settings, input, clipboard, clipTransfers, files, windows, log, appVersion, notify, t }) {
    super();
    Object.assign(this, { network, settings, input, clipboard, clipTransfers, files, windows, log, appVersion, notify, t });
    this.outgoing = new Map();
    this.host = null;
    this.connecting = null;
    this.contexts = new Map();
    this.displayRectCache = new Map();

    clipboard.on('change', async (content) => {
      const targets = this.#clipboardTargets();
      if (!targets.length) return;
      const payload = await clipTransfers.announce(content);
      if (!payload) return;
      for (const entry of targets) if (!entry.win.isDestroyed()) entry.win.webContents.send('clipboard:local', payload);
    });
    const invalidate = () => {
      this.displayRectCache.clear();
      this.syncInput();
    };
    screen.on('display-metrics-changed', invalidate);
    screen.on('display-added', invalidate);
    screen.on('display-removed', invalidate);
  }

  attach(signaling) {
    signaling.on('incoming', (session) => this.#onIncoming(session));
    signaling.on('message', (sid, msg) => this.#onSignal(sid, msg));
    signaling.on('closed', (sid, reason) => this.#onRemoteClosed(sid, reason));
    signaling.on('auth-failed', ({ peerId }) => this.emit('auth-failed', peerId));
  }

  get signaling() {
    return this.network.signaling;
  }

  displayName() {
    return this.settings.get('displayName') || os.hostname();
  }

  canAccept() {
    if (!this.settings.get('allowIncoming')) return 'disabled';
    if (this.host) return 'busy';
    return null;
  }

  hasActiveSessions() {
    return this.outgoing.size > 0 || Boolean(this.host);
  }

  contextOf(webContents) {
    return this.contexts.get(webContents.id) || null;
  }

  summary() {
    return {
      incoming: this.host
        ? { peerId: this.host.peerId, peerName: this.host.peerName, state: this.host.state, control: this.host.perms.control }
        : null,
      outgoing: [...this.outgoing.values()].map((e) => ({ sid: e.sid, peerId: e.peerId, peerName: e.peerName })),
    };
  }

  #changed() {
    this.emit('changed', this.summary());
  }

  // ───────────────────────── outgoing sessions ─────────────────────────

  async probe(peerId) {
    if (!isValidId(peerId)) return { ok: false, error: 'invalid' };
    if (peerId === this.network.myId) return { ok: false, error: 'self' };
    try {
      const res = await this.signaling.probe(peerId);
      if (res.version !== PROTOCOL_VERSION) return { ok: false, error: 'version' };
      return { ok: true, hasSavedPassword: Boolean(this.settings.savedPrs(peerId)) };
    } catch (err) {
      return { ok: false, error: err.code || 'unknown' };
    }
  }

  cancelConnect() {
    this.connecting?.abort.abort();
  }

  async connect(peerId, { password = null, remember = false } = {}, onStatus = () => {}) {
    if (!isValidId(peerId)) return { ok: false, error: 'invalid' };
    if (peerId === this.network.myId) return { ok: false, error: 'self' };
    this.cancelConnect();
    const abort = new AbortController();
    this.connecting = { peerId, abort };
    let fromSaved = false;
    try {
      let prs;
      if (password) {
        onStatus('authenticating');
        prs = await derivePrs(password, peerId);
      } else {
        prs = this.settings.savedPrs(peerId);
        fromSaved = true;
        if (!prs) throw new SignalingError('need-password');
      }
      if (abort.signal.aborted) throw new SignalingError('cancelled');
      const session = await this.signaling.connect(peerId, prs, {
        intro: { name: this.displayName(), platform: process.platform, appVersion: this.appVersion },
        signal: abort.signal,
        onStatus,
      });
      this.settings.touchRecent(peerId, {
        name: session.peerName,
        prs: fromSaved ? undefined : remember ? prs : null,
      });
      onStatus('opening');
      this.#openViewer(session, prs);
      return { ok: true, sid: session.sid };
    } catch (err) {
      if (fromSaved && err.code === 'auth') this.settings.forgetRecentPassword(peerId);
      this.log.info(`[session] connection to ${peerId} failed: ${err.code || err.message}`);
      return { ok: false, error: err.code || 'unknown', retryIn: err.retryIn };
    } finally {
      if (this.connecting?.abort === abort) this.connecting = null;
    }
  }

  #openViewer(session, prs) {
    const caps = session.info?.caps || {};
    const win = this.windows.createViewer({ peerName: session.peerName, peerId: session.peerId });
    const entry = {
      kind: 'viewer',
      sid: session.sid,
      peerId: session.peerId,
      peerName: session.peerName,
      caps,
      peerPlatform: session.info?.platform || '',
      peerVersion: session.info?.appVersion || '',
      win,
      queue: [],
      ready: false,
      ended: null,
      prs,
      clipboard: false,
    };
    this.outgoing.set(entry.sid, entry);
    const wcId = win.webContents.id;
    this.contexts.set(wcId, entry);
    win.on('closed', () => {
      this.contexts.delete(wcId);
      this.endSession(entry.sid, 'closed');
    });
    this.#setClipboard(entry, Boolean(caps.clipboard) && this.settings.get('viewerClipboard'));
    this.#changed();
  }

  /** Reconnects a viewer whose session ended, reusing the same window. */
  async reconnect(entry) {
    const peerId = entry.peerId;
    try {
      const session = await this.signaling.connect(peerId, entry.prs, {
        intro: { name: this.displayName(), platform: process.platform, appVersion: this.appVersion },
      });
      this.outgoing.delete(entry.sid);
      Object.assign(entry, {
        sid: session.sid,
        peerName: session.peerName,
        caps: session.info?.caps || {},
        queue: [],
        ready: false,
        ended: null,
      });
      this.outgoing.set(entry.sid, entry);
      this.#setClipboard(entry, Boolean(entry.caps.clipboard) && this.settings.get('viewerClipboard'));
      this.#changed();
      return { ok: true };
    } catch (err) {
      return { ok: false, error: err.code || 'unknown', retryIn: err.retryIn };
    }
  }

  // ───────────────────────── incoming session ─────────────────────────

  #hostPerms() {
    return {
      control: this.settings.get('allowControl') && this.input.available,
      files: this.settings.get('allowFileTransfer'),
      clipboard: this.settings.get('allowClipboard'),
      audio: this.settings.get('shareAudio') && process.platform === 'win32',
    };
  }

  #onIncoming(session) {
    if (this.host) {
      this.signaling.reject(session.sid, 'busy');
      return;
    }
    const needConsent = this.settings.get('confirmIncoming');
    const host = {
      kind: 'host',
      sid: session.sid,
      peerId: session.peerId,
      peerName: session.peerName || this.t('host.unknownName'),
      peerPlatform: session.peerPlatform,
      peerVersion: session.peerVersion || '',
      credential: session.credential,
      state: needConsent ? 'pending' : 'active',
      perms: this.#hostPerms(),
      queue: [],
      ready: false,
      displayId: null,
      win: null,
      startedAt: null,
      powerBlocker: null,
      clipboard: false,
    };
    this.host = host;
    this.log.info(`[session] incoming session from ${host.peerId} (${host.credential} password)`);
    host.win = this.windows.createHost();
    const wcId = host.win.webContents.id;
    this.contexts.set(wcId, host);
    host.win.on('closed', () => {
      this.contexts.delete(wcId);
      if (this.host === host) this.endSession(host.sid, 'host-closed');
    });
    if (needConsent) {
      this.signaling.notifyWaiting(host.sid);
      host.consentTimer = setTimeout(() => this.hostConsent(host.sid, false, 'timeout'), 45_000);
      this.notify(this.t('notify.requestTitle'), this.t('notify.requestBody', { name: host.peerName, id: host.peerId }), () => host.win?.show());
    } else {
      this.#activateHost(host);
    }
    this.#changed();
  }

  #activateHost(host) {
    host.state = 'active';
    host.startedAt = Date.now();
    host.perms = this.#hostPerms();
    this.signaling.accept(host.sid, {
      name: this.displayName(),
      caps: host.perms,
      platform: process.platform,
      appVersion: this.appVersion,
    }).catch((err) => this.log.warn(`[session] accept failed: ${err.message}`));
    try {
      host.powerBlocker = powerSaveBlocker.start('prevent-display-sleep');
    } catch {
      host.powerBlocker = null;
    }
    this.#setClipboard(host, host.perms.clipboard);
    this.syncInput();
    if (!this.windows.isMainVisible()) {
      this.notify(this.t('notify.incomingTitle'), this.t('notify.incomingBody', { name: host.peerName, id: host.peerId }));
    }
  }

  hostConsent(sid, accept, reason = 'declined') {
    const host = this.host;
    if (!host || host.sid !== sid || host.state !== 'pending') return false;
    clearTimeout(host.consentTimer);
    if (accept) {
      this.#activateHost(host);
      host.win?.webContents.send('session:state', { state: 'active', perms: host.perms });
      this.#changed();
      return true;
    }
    this.signaling.reject(sid, reason === 'timeout' ? 'timeout' : 'declined');
    this.#teardownHost(host, { notifyPeer: false });
    return true;
  }

  async listDisplays() {
    const sources = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: 0, height: 0 } });
    const displays = screen.getAllDisplays();
    const primaryId = screen.getPrimaryDisplay().id;
    return sources.map((src, index) => {
      const display = displays.find((d) => String(d.id) === String(src.display_id)) || displays[index] || displays[0];
      const rect = physicalRect(display);
      return {
        sourceId: src.id,
        displayId: String(display.id),
        index,
        width: rect.width,
        height: rect.height,
        primary: display.id === primaryId,
      };
    });
  }

  #displayRect(displayId) {
    if (this.displayRectCache.has(displayId)) return this.displayRectCache.get(displayId);
    const displays = screen.getAllDisplays();
    const display = displays.find((d) => String(d.id) === String(displayId)) || screen.getPrimaryDisplay();
    const rect = physicalRect(display);
    this.displayRectCache.set(displayId, rect);
    return rect;
  }

  setHostDisplay(ctx, displayId) {
    if (ctx !== this.host) return;
    ctx.displayId = String(displayId);
    this.syncInput();
  }

  /**
   * Sends the incoming session's state to the input helper: whether remote
   * input may be injected right now and where (the shared display).
   */
  syncInput() {
    const host = this.host;
    if (!host || host.state !== 'active') {
      this.input.setSession(null);
      return;
    }
    const displays = screen.getAllDisplays();
    const display = displays.find((d) => String(d.id) === String(host.displayId)) || screen.getPrimaryDisplay();
    this.input.setSession({
      id: host.sid,
      control: host.perms.control && this.settings.get('allowControl'),
      rect: this.#displayRect(host.displayId),
      scale: display.scaleFactor || 1,
    });
    if (host.ready && !host.inputLinked && host.win && !host.win.isDestroyed()) {
      host.inputLinked = true;
      this.input.attachRenderer(host.win.webContents, host.sid);
    }
  }

  /**
   * Where files dropped by the controller can be replayed (drops.js): the
   * shared display of the incoming session, when remote control and file
   * transfers are allowed. Null otherwise.
   */
  dropTarget() {
    const host = this.host;
    if (!host || host.state !== 'active' || !host.perms.control || !host.perms.files) return null;
    if (!this.settings.get('allowControl') || !this.settings.get('allowFileTransfer')) return null;
    const display = screen.getAllDisplays().find((d) => String(d.id) === String(host.displayId)) || screen.getPrimaryDisplay();
    const scale = display.scaleFactor || 1;
    return {
      sid: host.sid,
      rect: this.#displayRect(host.displayId),
      scale,
      toDip: (p) => (process.platform === 'win32' ? screen.screenToDipPoint(p) : { x: p.x / scale, y: p.y / scale }),
    };
  }

  /** Input that reached the main process (host window without its port yet). */
  injectInput(ctx, events) {
    if (ctx !== this.host || ctx.state !== 'active' || !Array.isArray(events)) return;
    this.input.inject(ctx.sid, events);
  }

  // ───────────────────────── renderer plumbing ─────────────────────────

  initPayload(ctx) {
    const common = {
      sid: ctx.sid,
      peer: { id: ctx.peerId, name: ctx.peerName, platform: ctx.peerPlatform, version: ctx.peerVersion },
      iceServers: this.network.iceServers,
      platform: process.platform,
    };
    if (ctx.kind === 'viewer') {
      return {
        ...common,
        role: 'viewer',
        caps: ctx.caps,
        prefs: {
          quality: this.settings.get('viewerQuality'),
          scale: this.settings.get('viewerScale'),
          clipboard: this.settings.get('viewerClipboard'),
        },
        ended: ctx.ended,
      };
    }
    return { ...common, role: 'host', state: ctx.state, perms: ctx.perms, credential: ctx.credential };
  }

  rendererReady(ctx) {
    ctx.ready = true;
    if (ctx === this.host) {
      ctx.inputLinked = false; // new or reloaded host window: give it a new port
      this.syncInput();
    }
    for (const data of ctx.queue.splice(0)) ctx.win.webContents.send('session:signal', data);
  }

  sendSignal(ctx, data) {
    if (ctx.kind === 'host' && ctx.state !== 'active') return;
    this.signaling.send(ctx.sid, { type: 'signal', data }).catch((err) => {
      this.log.warn(`[session] signal to ${ctx.peerId} failed: ${err.message}`);
    });
  }

  #onSignal(sid, msg) {
    const ctx = this.outgoing.get(sid) || (this.host?.sid === sid ? this.host : null);
    if (!ctx || msg?.type !== 'signal' || !msg.data) return;
    if (ctx.ready && !ctx.win.isDestroyed()) ctx.win.webContents.send('session:signal', msg.data);
    else ctx.queue.push(msg.data);
  }

  #onRemoteClosed(sid, reason) {
    this.log.info(`[session] ${sid} closed by peer (${reason})`);
    this.endSession(sid, 'peer', { notifyPeer: false });
  }

  endSession(sid, reason = 'user', { notifyPeer = true } = {}) {
    const entry = this.outgoing.get(sid);
    if (entry) {
      this.outgoing.delete(sid);
      if (notifyPeer) this.signaling.close(sid, reason);
      this.#setClipboard(entry, false);
      this.clipTransfers.endSession(entry);
      this.files.abortAll(sid);
      entry.ended = reason;
      if (!entry.win.isDestroyed()) {
        if (reason === 'closed') entry.win.destroy();
        else entry.win.webContents.send('session:ended', { reason });
      }
      this.#changed();
      return;
    }
    if (this.host && this.host.sid === sid) this.#teardownHost(this.host, { notifyPeer, reason });
  }

  #teardownHost(host, { notifyPeer = true, reason = 'user' } = {}) {
    if (this.host !== host) return;
    this.host = null;
    clearTimeout(host.consentTimer);
    this.input.setSession(null);
    if (notifyPeer) this.signaling.close(host.sid, reason);
    if (host.powerBlocker != null) powerSaveBlocker.stop(host.powerBlocker);
    this.#setClipboard(host, false);
    this.clipTransfers.endSession(host);
    this.files.abortAll(host.sid);
    if (host.win && !host.win.isDestroyed()) host.win.destroy();
    if (host.state === 'active') {
      this.notify(this.t('notify.endedTitle'), this.t('notify.endedBody', { name: host.peerName }));
      if (this.settings.get('regeneratePasswordAfterSession')) this.network.regeneratePassword();
    }
    this.#changed();
  }

  endAll() {
    for (const sid of [...this.outgoing.keys()]) this.endSession(sid, 'quit');
    if (this.host) this.#teardownHost(this.host, { reason: 'quit' });
  }

  // ───────────────────────── clipboard & files ─────────────────────────

  #setClipboard(ctx, enabled) {
    if (ctx.clipboard === enabled) return;
    ctx.clipboard = enabled;
    if (enabled) this.clipboard.acquire();
    else this.clipboard.release();
  }

  #clipboardTargets() {
    const list = [...this.outgoing.values()].filter((e) => e.clipboard && e.ready && !e.win.isDestroyed());
    if (this.host?.clipboard && this.host.ready && this.host.state === 'active' && !this.host.win.isDestroyed()) list.push(this.host);
    return list;
  }

  setViewerClipboard(ctx, enabled) {
    if (ctx.kind !== 'viewer') return;
    this.settings.set('viewerClipboard', Boolean(enabled));
    this.#setClipboard(ctx, Boolean(enabled) && Boolean(ctx.caps.clipboard) && !ctx.ended);
  }

  remoteClipboard(ctx, payload) {
    if (!ctx.clipboard) return;
    this.clipTransfers.remote(ctx, payload);
  }

  /** Current local clipboard as a payload for the partner (session start). */
  async currentClipboard(ctx) {
    if (!ctx.clipboard) return null;
    const content = await this.clipboard.read();
    return ClipboardSync.isEmpty(content) ? null : this.clipTransfers.announce(content);
  }

  /**
   * Clipboard files may be exchanged in this session. A copied image only
   * goes through the clipboard (never left on disk): the clipboard
   * permission is enough for it.
   */
  clipboardFilesAllowed(ctx, kind = 'file') {
    if (!ctx.clipboard) return false;
    if (ctx.kind !== 'host') return !ctx.ended;
    return ctx.state === 'active' && (kind === 'image' || this.settings.get('allowFileTransfer'));
  }

  canReceiveFiles(ctx) {
    if (ctx.kind === 'host') return ctx.state === 'active' && this.settings.get('allowFileTransfer');
    return !ctx.ended;
  }
}

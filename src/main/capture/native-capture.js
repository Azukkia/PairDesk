// Screen capture without the mouse cursor, for the incoming session.
//
// Chromium's screen capture (getUserMedia with chromeMediaSource 'desktop')
// always paints the cursor into the picture. The person controlling already
// sees their own cursor at once (src/renderer/viewer/remote-cursor.js), so the
// painted one only showed up as a second cursor trailing behind it, one video
// delay late. Here the picture comes from a "capture helper" utility process
// (src/main/helper/capture-helper.js) that grabs the screen itself, without
// the cursor: Desktop Duplication on Windows (native/capture, PairDeskCapture.dll),
// MIT-SHM on X11. The host window turns the pictures into a video track
// (src/renderer/host/native-capture.js) and falls back to Chromium's capture
// when this one is unavailable or fails.

import fs from 'node:fs';
import path from 'node:path';
import { utilityProcess, MessageChannelMain } from 'electron';

export class NativeCapture {
  constructor({ appPath, resourcesPath, packaged, log }) {
    this.entry = path.join(appPath, 'src', 'main', 'helper', 'capture-helper.js');
    this.dll = process.env.PAIRDESK_CAPTURE_DLL
      || (packaged
        ? path.join(resourcesPath, 'capture', 'PairDeskCapture.dll')
        : path.join(appPath, 'native', 'bin', 'win32-x64', 'PairDeskCapture.dll'));
    this.log = log;
    this.child = null;
    this.ready = null;
    this.waiters = new Map();
    this.nextId = 1;
    this.statsWaiter = null;
  }

  /** Whether this computer can try the native capture (it may still fail and fall back). */
  get supported() {
    if (process.env.PAIRDESK_NATIVE_CAPTURE === '0') return false;
    if (process.platform === 'win32') return fs.existsSync(this.dll);
    if (process.platform === 'linux') return process.env.XDG_SESSION_TYPE !== 'wayland' && Boolean(process.env.DISPLAY);
    return false;
  }

  #spawn() {
    this.ready ??= new Promise((resolve, reject) => {
      const child = utilityProcess.fork(this.entry, [], { serviceName: 'PairDesk Capture', stdio: 'pipe' });
      this.child = child;
      const timer = setTimeout(() => reject(new Error('helper timeout')), 10_000);
      child.stdout?.on('data', (d) => this.log.info(`[capture-helper] ${String(d).trim()}`));
      child.stderr?.on('data', (d) => this.log.warn(`[capture-helper] ${String(d).trim()}`));
      child.on('message', (msg) => {
        if (msg?.type === 'ready') {
          clearTimeout(timer);
          resolve(child);
        } else if (msg?.type === 'log') {
          const level = ['info', 'warn', 'error'].includes(msg.level) ? msg.level : 'info';
          this.log[level](msg.msg);
        } else if (msg?.type === 'started') {
          this.waiters.get(msg.id)?.(msg);
        } else if (msg?.type === 'stats') {
          this.statsWaiter?.(msg);
        }
      });
      child.on('exit', (code) => {
        clearTimeout(timer);
        reject(new Error(`helper exited (${code})`));
        if (this.child !== child) return;
        this.child = null;
        this.ready = null;
        for (const w of this.waiters.values()) w({ ok: false, error: `helper exited (${code})` });
        this.waiters.clear();
        if (code) this.log.warn(`[capture] helper exited (${code})`);
      });
    });
    return this.ready;
  }

  /**
   * Starts capturing `rect` (physical pixels) for the host window
   * `webContents`, which receives the pictures on a port ('capture:port').
   * Resolves { ok, token, width, height } or { ok: false, reason }.
   */
  async start(webContents, { rect, fps }) {
    if (!this.supported) return { ok: false, reason: 'unsupported' };
    let child;
    try {
      child = await this.#spawn();
    } catch (err) {
      this.ready = null;
      return { ok: false, reason: err.message };
    }
    const id = this.nextId++;
    const { port1, port2 } = new MessageChannelMain();
    const started = new Promise((resolve) => {
      const timer = setTimeout(() => resolve({ ok: false, error: 'timeout' }), 8000);
      this.waiters.set(id, (msg) => {
        clearTimeout(timer);
        this.waiters.delete(id);
        resolve(msg);
      });
    });
    child.postMessage({ type: 'start', id, rect, fps, dll: this.dll }, [port1]);
    const res = await started;
    if (!res.ok || webContents.isDestroyed()) {
      port2.close();
      if (!res.ok) this.log.warn(`[capture] native capture unavailable: ${res.error}`);
      return { ok: false, reason: res.error || 'closed' };
    }
    webContents.postMessage('capture:port', { token: id }, [port2]);
    return { ok: true, token: id, width: res.width, height: res.height };
  }

  /** Diagnostics (tests): pictures sent by the current capture. */
  stats() {
    if (!this.child) return Promise.resolve(null);
    return new Promise((resolve) => {
      const timer = setTimeout(() => resolve(null), 3000);
      this.statsWaiter = (msg) => {
        clearTimeout(timer);
        this.statsWaiter = null;
        resolve({ frames: msg.frames, active: msg.active });
      };
      this.child.postMessage({ type: 'stats' });
    });
  }

  stop() {
    try {
      this.child?.kill();
    } catch {
      /* already gone */
    }
    this.child = null;
    this.ready = null;
  }
}

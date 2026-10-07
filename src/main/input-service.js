// Main-process side of the input helper (src/main/helper/input-helper.js): a
// utility process that injects the remote mouse and keyboard. The host window
// talks to it directly through a MessagePort, so a modal loop on the main
// thread (window dragged, tray menu, native dialog) can no longer freeze the
// remote mouse. The main process only sends the session state (who may
// control, which display) and restarts the helper if it dies.

import path from 'node:path';
import { EventEmitter } from 'node:events';
import { utilityProcess, MessageChannelMain } from 'electron';

export class InputService extends EventEmitter {
  constructor({ appPath, log }) {
    super();
    this.entry = path.join(appPath, 'src', 'main', 'helper', 'input-helper.js');
    this.log = log;
    this.child = null;
    this.available = false;
    this.reason = 'starting';
    this.blocked = null;
    this.session = null; // last state sent, replayed after a restart
    this.renderer = null; // { webContents, id } of the host window
    this.queries = new Map();
    this.nextRid = 1;
    this.stopping = false;
    this.restarts = 0;
    this.readyPromise = null;
  }

  /** Starts the helper; resolves once it reported whether injection works. */
  start() {
    this.stopping = false;
    this.readyPromise = new Promise((resolve) => {
      const child = utilityProcess.fork(this.entry, [], { serviceName: 'PairDesk Input', stdio: 'pipe' });
      this.child = child;
      const timer = setTimeout(() => {
        this.reason = 'timeout';
        resolve();
      }, 10_000);
      child.stdout?.on('data', (d) => this.log.info(`[input-helper] ${String(d).trim()}`));
      child.stderr?.on('data', (d) => this.log.warn(`[input-helper] ${String(d).trim()}`));
      child.on('message', (msg) => {
        if (msg?.type === 'ready') {
          clearTimeout(timer);
          this.available = Boolean(msg.available);
          this.reason = msg.reason || null;
          this.log.info(`[input] helper ready (${this.available ? 'remote control available' : `unavailable: ${this.reason}`})`);
          // After a restart: restore the session and the host window link.
          if (this.session) this.#post({ type: 'session', ...this.session });
          if (this.renderer && !this.renderer.webContents.isDestroyed()) this.attachRenderer(this.renderer.webContents, this.renderer.id);
          resolve();
          this.emit('ready');
          return;
        }
        this.#onMessage(msg);
      });
      child.on('exit', (code) => {
        clearTimeout(timer);
        resolve();
        if (this.child !== child) return;
        this.child = null;
        for (const q of this.queries.values()) q.resolve(null);
        this.queries.clear();
        if (this.stopping) return;
        this.log.warn(`[input] helper exited (${code}), restarting`);
        const delay = Math.min(10_000, 500 * 2 ** Math.min(this.restarts++, 5));
        setTimeout(() => {
          if (!this.stopping) this.start();
        }, delay);
      });
    });
    return this.readyPromise;
  }

  ready() {
    return this.readyPromise || this.start();
  }

  /** No restart from now on (the app is quitting); the helper may still finish its work. */
  beginShutdown() {
    this.stopping = true;
  }

  stop() {
    this.stopping = true;
    this.child?.kill();
    this.child = null;
  }

  #post(msg, transfer) {
    if (!this.child) return false;
    try {
      this.child.postMessage(msg, transfer);
      return true;
    } catch (err) {
      this.log.warn(`[input] post to helper failed: ${err.message}`);
      return false;
    }
  }

  #onMessage(msg) {
    switch (msg?.type) {
      case 'log': {
        const level = ['info', 'warn', 'error'].includes(msg.level) ? msg.level : 'info';
        this.log[level](msg.msg);
        break;
      }
      case 'reply': {
        const q = this.queries.get(msg.rid);
        if (q) {
          this.queries.delete(msg.rid);
          clearTimeout(q.timer);
          q.resolve(msg.value);
        }
        break;
      }
      case 'state':
        this.blocked = msg.blocked || null;
        this.emit('blocked', this.blocked);
        break;
      default:
        break;
    }
  }

  /**
   * Incoming session state. `id` null ends it (the helper releases every
   * pressed key and button). `control`: input may be injected now.
   * `rect`: physical bounds of the shared display; `scale`: its scale factor.
   */
  setSession(state) {
    if (!state?.id) {
      this.session = null;
      this.renderer = null;
      this.#post({ type: 'session', id: null });
      return;
    }
    this.session = { id: state.id, control: Boolean(state.control), rect: state.rect, scale: state.scale || 1 };
    this.#post({ type: 'session', ...this.session });
  }

  /** Links the host window of session `id` to the helper with a new port. */
  attachRenderer(webContents, id) {
    this.renderer = { webContents, id };
    if (!this.child || !this.session || this.session.id !== id || webContents.isDestroyed()) return;
    const { port1, port2 } = new MessageChannelMain();
    if (!this.#post({ type: 'port', id }, [port1])) return;
    webContents.postMessage('input:port', null, [port2]);
  }

  /** Fallback path (host window without a port yet). */
  inject(id, events) {
    this.#post({ type: 'input', id, events });
  }

  releaseAll() {
    this.#post({ type: 'release' });
  }

  #query(what) {
    if (!this.child) return Promise.resolve(null);
    const rid = this.nextRid++;
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.queries.delete(rid);
        resolve(null);
      }, 3000);
      this.queries.set(rid, { resolve, timer });
      this.#post({ type: 'query', rid, what });
    });
  }

  async cursorPos() {
    return (await this.#query('cursor-pos')) || { x: 0, y: 0 };
  }

  cursorDebug() {
    return this.#query('cursor-debug');
  }
}

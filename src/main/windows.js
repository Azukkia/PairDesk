// BrowserWindow factory. All renderers are sandboxed, use context isolation
// and only talk to the main process through the preload bridge.

import path from 'node:path';
import { app, BrowserWindow, nativeTheme, screen } from 'electron';

const HOST_WIDTH = 380;
// Collapsed host panel: a small tab on the right edge of the screen.
const TAB_WIDTH = 30;
const TAB_HEIGHT = 76;
const expandedBounds = new WeakMap();

export class Windows {
  constructor({ appPath, log, shouldHideOnClose, onMainHidden }) {
    this.appPath = appPath;
    this.log = log;
    this.preload = path.join(appPath, 'src', 'preload', 'preload.cjs');
    this.icon = path.join(appPath, 'assets', process.platform === 'win32' ? 'icon.ico' : 'icon.png');
    this.shouldHideOnClose = shouldHideOnClose;
    this.onMainHidden = onMainHidden;
    this.main = null;
  }

  #background() {
    return nativeTheme.shouldUseDarkColors ? '#0e1322' : '#f4f6fb';
  }

  #create(kind, options) {
    const win = new BrowserWindow({
      show: false,
      icon: this.icon,
      autoHideMenuBar: true,
      backgroundColor: this.#background(),
      ...options,
      webPreferences: {
        preload: this.preload,
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
        spellcheck: false,
        backgroundThrottling: kind === 'main',
        devTools: !app.isPackaged || process.env.PAIRDESK_DEVTOOLS === '1',
      },
    });
    win.removeMenu();
    const wc = win.webContents;
    wc.setWindowOpenHandler(() => ({ action: 'deny' }));
    wc.on('will-navigate', (event, url) => {
      if (!url.startsWith('app://pairdesk/')) event.preventDefault();
    });
    wc.on('render-process-gone', (_e, details) => this.log.error(`[window:${kind}] renderer gone: ${details.reason}`));
    wc.on('console-message', (event) => {
      const level = event.level ?? 'info';
      if (level === 'error' || level === 'warning' || process.env.PAIRDESK_DEBUG) {
        this.log.info(`[renderer:${kind}] ${event.message}`);
      }
    });
    if (!app.isPackaged) {
      wc.on('before-input-event', (_e, input) => {
        if (input.type === 'keyDown' && input.key === 'F12' && input.control && input.shift) wc.toggleDevTools();
      });
    }
    win.loadURL(`app://pairdesk/${kind}/index.html`);
    return win;
  }

  // ───────────── main window ─────────────

  createMain({ show = true } = {}) {
    const win = this.#create('main', {
      width: 1000,
      height: 680,
      minWidth: 880,
      minHeight: 600,
      title: 'PairDesk',
    });
    win.once('ready-to-show', () => {
      if (show) win.show();
    });
    win.on('close', (event) => {
      if (this.shouldHideOnClose()) {
        event.preventDefault();
        win.hide();
        this.onMainHidden?.();
      }
    });
    win.on('closed', () => {
      if (this.main === win) this.main = null;
    });
    this.main = win;
    return win;
  }

  showMain() {
    if (!this.main || this.main.isDestroyed()) this.createMain({ show: true });
    const win = this.main;
    if (win.isMinimized()) win.restore();
    if (!win.isVisible()) win.show();
    win.focus();
  }

  isMainVisible() {
    return Boolean(this.main && !this.main.isDestroyed() && this.main.isVisible() && !this.main.isMinimized());
  }

  sendToMain(channel, payload) {
    if (this.main && !this.main.isDestroyed()) this.main.webContents.send(channel, payload);
  }

  // ───────────── viewer window (controller side) ─────────────

  createViewer({ peerName, peerId }) {
    const work = screen.getPrimaryDisplay().workAreaSize;
    const win = this.#create('viewer', {
      width: Math.min(1440, Math.round(work.width * 0.85)),
      height: Math.min(900, Math.round(work.height * 0.85)),
      minWidth: 640,
      minHeight: 420,
      title: `${peerName || peerId} — PairDesk`,
      backgroundColor: '#05070d',
    });
    win.once('ready-to-show', () => {
      win.show();
      win.focus();
    });
    // Alt+F4 must close the remote window, not the viewer: remember when it
    // was typed and cancel the close that Windows triggers right after.
    let altF4At = 0;
    win.webContents.on('before-input-event', (_e, input) => {
      if (input.type === 'keyDown' && input.alt && input.code === 'F4') altF4At = Date.now();
    });
    win.on('close', (event) => {
      if (Date.now() - altF4At < 400) event.preventDefault();
    });
    return win;
  }

  // ───────────── camera window (a phone's camera on this computer) ─────────────

  createCamera({ peerName, peerId }) {
    const win = this.#create('camera', {
      width: 760,
      height: 560,
      minWidth: 420,
      minHeight: 320,
      title: `${peerName || peerId} — PairDesk Camera`,
      backgroundColor: '#05070d',
    });
    win.once('ready-to-show', () => win.show());
    return win;
  }

  // ───────────── host panel (controlled side) ─────────────

  createHost() {
    const win = this.#create('host', {
      width: HOST_WIDTH,
      height: 200,
      frame: false,
      resizable: false,
      maximizable: false,
      fullscreenable: false,
      alwaysOnTop: true,
      skipTaskbar: false,
      title: 'PairDesk — Session',
    });
    win.setAlwaysOnTop(true, 'floating');
    this.placeHost(win, 200);
    win.once('ready-to-show', () => win.showInactive());
    return win;
  }

  placeHost(win, height) {
    const area = screen.getPrimaryDisplay().workArea;
    const h = Math.max(120, Math.min(Math.round(height), area.height - 40));
    win.setBounds({ x: area.x + area.width - HOST_WIDTH - 16, y: area.y + area.height - h - 16, width: HOST_WIDTH, height: h });
  }

  /** Tiny always-on-top window centred on `point` (DIP): source of a replayed file drop. */
  createDropSource(point) {
    const size = 24;
    const win = this.#create('drop', {
      x: Math.round(point.x - size / 2),
      y: Math.round(point.y - size / 2),
      width: size,
      height: size,
      frame: false,
      resizable: false,
      movable: false,
      minimizable: false,
      maximizable: false,
      fullscreenable: false,
      focusable: false,
      skipTaskbar: true,
      alwaysOnTop: true,
      hasShadow: false,
      title: 'PairDesk',
    });
    win.setAlwaysOnTop(true, 'screen-saver');
    return win;
  }

  collapseHost(win, collapsed) {
    if (!win || win.isDestroyed()) return;
    if (collapsed) {
      if (!expandedBounds.has(win)) expandedBounds.set(win, win.getBounds());
      const area = screen.getDisplayMatching(win.getBounds()).workArea;
      const right = area.x + area.width;
      win.setBounds({ x: right - TAB_WIDTH, y: area.y + area.height - TAB_HEIGHT - 24, width: TAB_WIDTH, height: TAB_HEIGHT });
      // Windows may enforce a slightly larger minimum width: keep the tab flush with the edge.
      const b = win.getBounds();
      if (b.x + b.width !== right) win.setPosition(right - b.width, b.y);
      return;
    }
    const previous = expandedBounds.get(win);
    expandedBounds.delete(win);
    if (previous) win.setBounds(previous);
    else this.placeHost(win, 200);
  }

  resizeHost(win, height) {
    if (!win || win.isDestroyed() || expandedBounds.has(win)) return;
    const b = win.getBounds();
    const area = screen.getDisplayMatching(b).workArea;
    const h = Math.max(120, Math.min(Math.round(height), area.height - 40));
    const bottom = b.y + b.height;
    win.setBounds({ x: b.x, y: Math.max(area.y, bottom - h), width: b.width, height: h });
  }
}

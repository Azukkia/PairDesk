// PairDesk — main process entry point.

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  app, ClipboardItem, Menu, Notification, clipboard, dialog, ipcMain, nativeImage, nativeTheme, net, protocol, safeStorage, session, shell,
} from 'electron';
import { log, initLogFile } from './log.js';
import { loadConfig } from './config.js';
import { Settings } from './settings.js';
import { Network } from './network.js';
import { SessionManager } from './sessions.js';
import { Windows } from './windows.js';
import { createTray } from './tray.js';
import { Updater } from './updater.js';
import { ClipboardSync } from './clipboard.js';
import { FileReceiver } from './files.js';
import { DropManager } from './drops.js';
import { InputService } from './input-service.js';
import { clipboardSequence, createClipboardFiles } from './clipboard-native.js';
import { ClipboardTransfers } from './clipboard-transfer.js';
import { VirtualCamera } from './camera/virtual-camera.js';
import { qrRows } from './qr.js';
import { createTranslator, resolveLanguage } from '../shared/i18n.js';
import { MIN_PERMANENT_PASSWORD_LENGTH, normalizeId, isValidId } from '../shared/protocol.js';

const APP_ID = 'com.azukkia.pairdesk';
const appPath = app.getAppPath();
const E2E = process.env.PAIRDESK_E2E === '1';

// ───────────── early setup (before "ready") ─────────────

const profileArg = process.argv.find((a) => a.startsWith('--pairdesk-profile='));
const profile = profileArg ? profileArg.split('=').slice(1).join('=') : process.env.PAIRDESK_PROFILE;
if (profile) app.setPath('userData', path.resolve(profile));

if (!app.requestSingleInstanceLock()) {
  app.quit();
  process.exit(0);
}

app.setAppUserModelId(APP_ID);
// Expose real local addresses to WebRTC (instead of mDNS names) so that LAN
// connections work everywhere.
app.commandLine.appendSwitch('disable-features', 'WebRtcHideLocalIpsWithMdns');
// Display each received frame as soon as it is decoded instead of buffering it
// to smooth out network jitter: measured −100 ms on a good link and the end of
// the 1–2 s delays caused by the jitter buffer on slower links. The "Send"
// variant also asks the other side to do so (useful with older PairDesk).
app.commandLine.appendSwitch(
  'force-fieldtrials',
  'WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:0/WebRTC-ForceSendPlayoutDelay/min_ms:0,max_ms:0/',
);

protocol.registerSchemesAsPrivileged([
  { scheme: 'app', privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true } },
]);

if (process.platform !== 'darwin') Menu.setApplicationMenu(null);

const startHidden = process.argv.includes('--hidden');
let quitting = false;

function extractDeepLink(argv) {
  const link = argv.find((a) => /^pairdesk:/i.test(a));
  if (!link) return null;
  const id = normalizeId(link.replace(/^pairdesk:(\/\/)?(connect\/)?/i, ''));
  return isValidId(id) ? id : null;
}

// ───────────── renderer files through app:// ─────────────

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.json': 'application/json',
  '.woff2': 'font/woff2',
};

function registerAppProtocol() {
  const roots = {
    main: path.join(appPath, 'src', 'renderer', 'main'),
    viewer: path.join(appPath, 'src', 'renderer', 'viewer'),
    host: path.join(appPath, 'src', 'renderer', 'host'),
    drop: path.join(appPath, 'src', 'renderer', 'drop'),
    camera: path.join(appPath, 'src', 'renderer', 'camera'),
    common: path.join(appPath, 'src', 'renderer', 'common'),
    shared: path.join(appPath, 'src', 'shared'),
    assets: path.join(appPath, 'assets'),
  };
  protocol.handle('app', async (request) => {
    try {
      const url = new URL(request.url);
      const parts = decodeURIComponent(url.pathname).split('/').filter(Boolean);
      const root = url.host === 'pairdesk' ? roots[parts[0]] : null;
      if (!root) return new Response('Not found', { status: 404 });
      const file = path.normalize(path.join(root, ...parts.slice(1)));
      if (!file.startsWith(root + path.sep)) return new Response('Forbidden', { status: 403 });
      const body = await fs.promises.readFile(file);
      return new Response(body, {
        headers: {
          'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream',
          'Cache-Control': 'no-cache',
        },
      });
    } catch {
      return new Response('Not found', { status: 404 });
    }
  });
}

// ───────────── application ─────────────

async function main() {
  await app.whenReady();
  initLogFile(path.join(app.getPath('userData'), 'logs'));
  log.info(`PairDesk ${app.getVersion()} starting (${process.platform} ${os.release()}, Electron ${process.versions.electron})`);

  const config = loadConfig(appPath);
  const settings = new Settings(path.join(app.getPath('userData'), 'settings.json'), {
    available: () => safeStorage.isEncryptionAvailable(),
    encrypt: (s) => safeStorage.encryptString(s),
    decrypt: (b) => safeStorage.decryptString(b),
  });
  settings.on('error', (err) => log.error(`[settings] ${err.message}`));

  let lang = resolveLanguage(settings.get('language'), app.getLocale());
  let t = createTranslator(lang);
  nativeTheme.themeSource = settings.get('theme');

  registerAppProtocol();

  const input = new InputService({ appPath, log });
  await input.start();
  const clipboardSync = new ClipboardSync({
    clipboard, ClipboardItem, files: createClipboardFiles({ log }), getSequence: clipboardSequence(log), log,
  });
  const clipTransfers = new ClipboardTransfers({ clipboard: clipboardSync, log });
  clipTransfers.cleanup();
  const downloadsDir = () => process.env.PAIRDESK_DOWNLOADS_DIR || path.join(app.getPath('downloads'), 'PairDesk');
  const files = new FileReceiver({ getDirectory: downloadsDir, log });

  let notifiedAt = 0;
  const notify = (title, body, onClick) => {
    if (E2E || !Notification.isSupported()) return;
    const n = new Notification({ title, body, icon: path.join(appPath, 'assets', 'icon.png') });
    n.on('click', onClick || (() => windows.showMain()));
    n.show();
  };

  const windows = new Windows({
    appPath,
    log,
    shouldHideOnClose: () => !quitting && settings.get('closeToTray'),
    onMainHidden: () => {
      if (!settings.get('trayHintShown')) {
        settings.set('trayHintShown', true);
        notify(t('tray.hintTitle'), t('tray.hintBody'));
      }
    },
  });

  let sessions;
  const network = new Network({ settings, config, log, canAccept: () => sessions.canAccept() });
  const virtualCamera = new VirtualCamera({ appPath, resourcesPath: process.resourcesPath, packaged: app.isPackaged, log });
  sessions = new SessionManager({
    network, settings, input, clipboard: clipboardSync, clipTransfers, files, windows, virtualCamera, log, appVersion: app.getVersion(), notify,
    // The translator changes with the language setting.
    t: (key, vars) => t(key, vars),
  });

  const drops = new DropManager({
    target: () => sessions.dropTarget(),
    windows,
    input,
    fallbackDir: downloadsDir,
    log,
  });
  drops.cleanup();

  const updater = new Updater({ log, isPackaged: app.isPackaged, currentVersion: app.getVersion() });

  // ───────────── state pushed to the main window ─────────────

  let idTaken = false;
  const publicSettings = () => {
    const { deviceKey, permanentPrs, recents, trayHintShown, ...rest } = settings.data;
    return { ...rest, hasPermanentPassword: Boolean(permanentPrs) };
  };
  const appState = () => ({
    id: network.myId,
    password: network.temp.password,
    version: app.getVersion(),
    platform: process.platform,
    lang,
    hostname: os.hostname(),
    network: { ...network.state, idTaken },
    settings: publicSettings(),
    recents: settings.publicRecents(),
    update: updater.state,
    sessions: sessions.summary(),
    input: { available: input.available, reason: input.reason || null },
    lockedFor: network.limiter.lockedFor(),
  });
  let pushTimer = null;
  const pushState = () => {
    if (pushTimer) return;
    pushTimer = setTimeout(() => {
      pushTimer = null;
      windows.sendToMain('app:state', appState());
    }, 30);
  };

  // ───────────── wiring ─────────────

  network.on('state', pushState);
  network.on('password', pushState);
  network.on('id-taken', () => {
    idTaken = true;
    pushState();
  });
  sessions.on('changed', pushState);
  let lastAuthNotice = 0;
  sessions.on('auth-failed', (peerId) => {
    pushState();
    if (Date.now() - lastAuthNotice > 15_000) {
      lastAuthNotice = Date.now();
      notify(t('notify.authFailedTitle'), t('notify.authFailedBody', { id: peerId }));
    }
  });
  updater.on('state', pushState);
  updater.on('available', (version) => {
    if (!windows.isMainVisible()) notify(t('notify.updateTitle'), t('notify.updateBody', { version }), () => windows.showMain());
  });
  updater.on('downloaded', (version) => {
    if (!windows.isMainVisible()) notify(t('notify.updateReadyTitle'), t('notify.updateReadyBody', { version }), () => windows.showMain());
  });
  updater.on('installing', () => {
    quitting = true;
    sessions.endAll();
    settings.saveNow();
  });

  const applyLaunchAtStartup = (enabled) => {
    if (!app.isPackaged || E2E) return;
    try {
      if (process.platform === 'linux') {
        const dir = path.join(app.getPath('appData'), 'autostart');
        const file = path.join(dir, 'pairdesk.desktop');
        if (enabled) {
          const exec = process.env.APPIMAGE || process.execPath;
          fs.mkdirSync(dir, { recursive: true });
          fs.writeFileSync(file, `[Desktop Entry]\nType=Application\nName=PairDesk\nExec="${exec}" --hidden\nX-GNOME-Autostart-enabled=true\n`);
        } else {
          fs.rmSync(file, { force: true });
        }
      } else {
        app.setLoginItemSettings({ openAtLogin: enabled, args: ['--hidden'] });
      }
    } catch (err) {
      log.warn(`[startup] ${err.message}`);
    }
  };

  let tray = null;
  settings.on('change', (changed) => {
    if ('language' in changed) {
      lang = resolveLanguage(settings.get('language'), app.getLocale());
      t = createTranslator(lang);
      tray?.refresh(t);
    }
    if ('theme' in changed) nativeTheme.themeSource = settings.get('theme');
    if ('launchAtStartup' in changed) applyLaunchAtStartup(settings.get('launchAtStartup'));
    if ('networkMode' in changed || 'serverUrl' in changed) {
      idTaken = false;
      network.restart();
    }
    if ('useRandomPassword' in changed || 'passwordLength' in changed) network.regeneratePassword();
    if ('autoCheckUpdates' in changed) updater.schedule(settings.get('autoCheckUpdates'));
    if ('allowControl' in changed) sessions.syncInput();
    pushState();
  });

  // Permissions: only the host panel may capture the screen.
  session.defaultSession.setPermissionRequestHandler((wc, permission, callback) => {
    const ctx = sessions.contextOf(wc);
    if (permission === 'media') return callback(ctx?.kind === 'host');
    callback(['fullscreen', 'clipboard-sanitized-write'].includes(permission));
  });
  session.defaultSession.setPermissionCheckHandler((wc, permission) => {
    const ctx = wc ? sessions.contextOf(wc) : null;
    if (permission === 'media') return ctx?.kind === 'host';
    return ['fullscreen', 'clipboard-sanitized-write'].includes(permission);
  });

  // ───────────── IPC ─────────────

  const contextOf = (sender) => {
    if (windows.main && !windows.main.isDestroyed() && sender === windows.main.webContents) return { kind: 'main' };
    return sessions.contextOf(sender);
  };
  const handle = (channel, kinds, fn) => {
    ipcMain.handle(channel, (event, ...args) => {
      const ctx = contextOf(event.sender);
      if (!ctx || !kinds.includes(ctx.kind)) throw new Error(`forbidden: ${channel}`);
      return fn(ctx, ...args);
    });
  };
  const on = (channel, kinds, fn) => {
    ipcMain.on(channel, (event, ...args) => {
      const ctx = contextOf(event.sender);
      if (!ctx || !kinds.includes(ctx.kind)) return;
      try {
        fn(ctx, ...args);
      } catch (err) {
        log.warn(`[ipc] ${channel}: ${err.message}`);
      }
    });
  };
  const SESSION = ['viewer', 'host'];
  const RTC = [...SESSION, 'camera']; // windows with a WebRTC session

  // Main window
  handle('app:state', ['main'], () => appState());
  handle('app:regenerate-password', ['main'], () => network.regeneratePassword());
  handle('app:copy', ['main', ...SESSION], (_ctx, text) => clipboard.writeText(String(text).slice(0, 10_000)));
  handle('app:open-external', ['main'], (_ctx, url) => {
    if (typeof url === 'string' && (url.startsWith(config.homepage) || url.startsWith('https://github.com/Azukkia/PairDesk'))) shell.openExternal(url);
  });
  // The Android app is offered once a release carries it (the link points to
  // the latest release's APK).
  let apkAvailable = false;
  handle('app:android', ['main'], async () => {
    if (!apkAvailable) {
      try {
        // One byte: GitHub's file servers refuse HEAD requests.
        const res = await net.fetch(config.androidApkUrl, { headers: { Range: 'bytes=0-0' }, signal: AbortSignal.timeout(8000) });
        apkAvailable = res.ok;
        res.body?.cancel().catch(() => {});
      } catch {
        apkAvailable = false;
      }
    }
    return { url: config.androidApkUrl, qr: qrRows(config.androidApkUrl), available: apkAvailable };
  });
  handle('app:open-logs', ['main'], () => shell.openPath(path.dirname(log.file || app.getPath('userData'))));
  handle('settings:update', ['main'], (_ctx, patch) => {
    settings.update(patch);
    return publicSettings();
  });
  handle('settings:set-permanent-password', ['main'], async (_ctx, password) => {
    if (password != null && (typeof password !== 'string' || password.trim().length < MIN_PERMANENT_PASSWORD_LENGTH)) {
      return { ok: false, error: 'too-short' };
    }
    await network.setPermanentPassword(password ? password.trim() : null);
    pushState();
    return { ok: true };
  });
  handle('recents:remove', ['main'], (_ctx, id) => settings.forgetRecent(String(id)));
  handle('recents:forget-password', ['main'], (_ctx, id) => settings.forgetRecentPassword(String(id)));
  handle('connect:probe', ['main'], (_ctx, id) => sessions.probe(normalizeId(id)));
  handle('connect:start', ['main'], (_ctx, { id, password, remember } = {}) => sessions.connect(
    normalizeId(id),
    { password: typeof password === 'string' && password ? password : null, remember: Boolean(remember) },
    (status) => windows.sendToMain('connect:status', { id: normalizeId(id), status }),
  ));
  handle('connect:cancel', ['main'], () => sessions.cancelConnect());
  handle('session:end-incoming', ['main'], () => {
    if (sessions.host) sessions.endSession(sessions.host.sid, 'host-ended');
  });
  handle('update:check', ['main'], () => updater.check({ manual: true }));
  handle('update:download', ['main'], () => updater.download());
  handle('update:install', ['main'], async () => {
    if (sessions.hasActiveSessions()) {
      const res = await dialog.showMessageBox(windows.main, {
        type: 'warning', buttons: [t('update.install'), t('common.cancel')], defaultId: 0, cancelId: 1, message: t('update.sessionWarning'),
      });
      if (res.response !== 0) return false;
    }
    return updater.install();
  });

  // Session windows (viewer = controller side, host = controlled side)
  handle('session:init', RTC, async (ctx) => {
    const payload = sessions.initPayload(ctx);
    if (ctx.kind === 'host') payload.displays = await sessions.listDisplays();
    payload.lang = lang;
    return payload;
  });
  on('session:ready', RTC, (ctx) => sessions.rendererReady(ctx));
  on('session:signal', RTC, (ctx, data) => sessions.sendSignal(ctx, data));
  on('session:failed', RTC, (ctx, reason) => {
    log.warn(`[session] ${ctx.kind} reported failure: ${reason}`);
    sessions.endSession(ctx.sid, 'failed');
  });
  handle('viewer:end', ['viewer'], (ctx) => {
    if (!ctx.win.isDestroyed()) ctx.win.destroy();
  });
  handle('viewer:reconnect', ['viewer'], (ctx) => sessions.reconnect(ctx));
  handle('viewer:fullscreen', ['viewer'], (ctx, enabled) => {
    ctx.win.setFullScreen(Boolean(enabled));
    return ctx.win.isFullScreen();
  });
  handle('viewer:set-clipboard', ['viewer'], (ctx, enabled) => sessions.setViewerClipboard(ctx, enabled));
  handle('viewer:set-prefs', ['viewer'], (_ctx, prefs = {}) => {
    const patch = {};
    if (prefs.quality) patch.viewerQuality = prefs.quality;
    if (prefs.scale) patch.viewerScale = prefs.scale;
    settings.update(patch);
  });
  handle('camera:attach', ['camera'], (ctx) => sessions.attachVirtualCamera(ctx));
  handle('camera:end', ['camera'], (ctx) => sessions.endSession(ctx.sid, 'camera-ended'));
  handle('host:consent', ['host'], (ctx, accept) => sessions.hostConsent(ctx.sid, Boolean(accept)));
  handle('host:displays', ['host'], () => sessions.listDisplays());
  on('host:set-display', ['host'], (ctx, displayId) => sessions.setHostDisplay(ctx, displayId));
  on('input:events', ['host'], (ctx, events) => sessions.injectInput(ctx, events));
  handle('host:end', ['host'], (ctx) => sessions.endSession(ctx.sid, 'host-ended'));
  on('host:resize', ['host'], (ctx, height) => windows.resizeHost(ctx.win, Number(height) || 200));
  on('host:collapse', ['host'], (ctx, collapsed) => windows.collapseHost(ctx.win, Boolean(collapsed)));
  on('clipboard:remote', SESSION, (ctx, payload) => sessions.remoteClipboard(ctx, payload));
  handle('clipboard:current', SESSION, (ctx) => sessions.currentClipboard(ctx));
  handle('clipboard:list', SESSION, (ctx, cid) => (sessions.clipboardFilesAllowed(ctx) ? clipTransfers.list(String(cid)) : null));
  handle('clipboard:read', SESSION, (ctx, cid, id, start, end) => {
    if (!sessions.clipboardFilesAllowed(ctx)) throw new Error('clipboard disabled');
    return clipTransfers.read(String(cid), id, start, end);
  });
  handle('clipboard:expect', SESSION, (ctx, { cid, n, folders } = {}) => {
    if (!sessions.clipboardFilesAllowed(ctx)) return null;
    return clipTransfers.expect(ctx, String(cid), n, folders);
  });

  // File transfers (received files go to Downloads/PairDesk)
  handle('files:begin', SESSION, (ctx, { name, size, drop, clip } = {}) => {
    if (clip) {
      // Clipboard content (image, copied files): into a staging folder.
      const accepted = sessions.clipboardFilesAllowed(ctx, clip.kind) ? clipTransfers.accept(ctx, clip, String(name)) : null;
      if (!accepted) return { ok: false, error: 'disabled' };
      try {
        const res = files.begin(ctx.sid, accepted.name, Number(size), { directory: accepted.directory });
        clipTransfers.track(res.token, ctx, clip.cid, accepted);
        return { ok: true, ...res, clip: accepted.kind };
      } catch (err) {
        return { ok: false, error: err.message };
      }
    }
    if (!sessions.canReceiveFiles(ctx)) return { ok: false, error: 'disabled' };
    try {
      // Dropped at a position of this screen: received in a staging folder,
      // then dropped there for real (drops.js).
      const staged = ctx.kind === 'host' ? drops.accept(ctx.sid, drop) : null;
      const res = files.begin(ctx.sid, String(name), Number(size), staged ? { directory: staged.directory } : {});
      if (staged) drops.track(res.token, staged.id);
      return { ok: true, ...res, dropped: Boolean(staged) };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });
  on('files:chunk', SESSION, (ctx, token, chunk) => files.write(ctx.sid, String(token), chunk));
  handle('files:end', SESSION, async (ctx, token) => {
    try {
      const file = await files.end(ctx.sid, String(token));
      drops.fileDone(String(token), file);
      const clip = await clipTransfers.fileDone(String(token), file);
      if (clip?.ready && !ctx.win.isDestroyed()) ctx.win.webContents.send('clipboard:ready', clip.ready);
      return { ok: true, path: file };
    } catch (err) {
      drops.fileDone(String(token), null);
      clipTransfers.fileDone(String(token), null);
      return { ok: false, error: err.message };
    }
  });
  on('files:abort', SESSION, (ctx, token) => {
    files.abort(ctx.sid, String(token));
    drops.fileDone(String(token), null);
    clipTransfers.fileDone(String(token), null);
  });
  // "dragstart" in the drop source window (checked against the window it opened).
  const dragIcon = nativeImage.createFromPath(path.join(appPath, 'assets', 'icon.png')).resize({ width: 32, height: 32 });
  ipcMain.on('drop:start', (event) => drops.onDragStart(event.sender, dragIcon));
  handle('files:show', [...SESSION, 'main'], (_ctx, file) => {
    const target = path.resolve(String(file));
    if (target.startsWith(downloadsDir() + path.sep)) shell.showItemInFolder(target);
  });

  if (E2E) {
    handle('e2e:cursor', ['main', ...SESSION], async () => ({ ...(await input.cursorPos()), shape: await input.cursorDebug() }));
    handle('e2e:vcam', ['main'], () => virtualCamera.stats());
  }

  // ───────────── start ─────────────

  network.start();
  sessions.attach(network.signaling);
  windows.createMain({ show: !startHidden });
  tray = createTray({
    appPath,
    t,
    id: network.myId,
    onOpen: () => windows.showMain(),
    onCheckUpdates: () => {
      windows.showMain();
      windows.sendToMain('app:navigate', 'about');
      updater.check({ manual: true });
    },
    onQuit: async () => {
      if (sessions.hasActiveSessions()) {
        const res = await dialog.showMessageBox({
          type: 'warning', buttons: [t('tray.quitConfirm'), t('common.cancel')], defaultId: 1, cancelId: 1, message: t('tray.quitWarning'),
        });
        if (res.response !== 0) return;
      }
      quitting = true;
      app.quit();
    },
  });
  applyLaunchAtStartup(settings.get('launchAtStartup'));
  updater.schedule(settings.get('autoCheckUpdates') && !E2E);
  if (app.isPackaged && !E2E) app.setAsDefaultProtocolClient('pairdesk');

  const openDeepLink = (id) => {
    if (!id) return;
    windows.showMain();
    const send = () => windows.sendToMain('app:deeplink', id);
    if (windows.main.webContents.isLoading()) windows.main.webContents.once('did-finish-load', send);
    else send();
  };
  openDeepLink(extractDeepLink(process.argv));

  app.on('second-instance', (_e, argv) => {
    windows.showMain();
    openDeepLink(extractDeepLink(argv));
  });
  app.on('open-url', (event, url) => {
    event.preventDefault();
    openDeepLink(extractDeepLink([url]));
  });
  app.on('activate', () => windows.showMain());
  app.on('before-quit', () => {
    quitting = true;
    sessions.endAll(); // also tells the input helper to release every key and button
    input.beginShutdown();
    settings.saveNow();
    // Give the "bye" messages a moment to leave before the transport closes.
    setTimeout(() => {
      network.stop();
      input.stop();
      virtualCamera.stop();
    }, 200);
  });
  app.on('window-all-closed', () => {
    if (!settings.get('closeToTray') || quitting) app.quit();
  });
}

main().catch((err) => {
  log.error(`fatal: ${err.stack || err.message}`);
  dialog.showErrorBox('PairDesk', `PairDesk could not start:\n${err.message}`);
  app.exit(1);
});

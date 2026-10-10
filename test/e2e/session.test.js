// End-to-end test: two PairDesk instances on the same machine connect through
// a local signaling server. Checks authentication, the video stream, remote
// mouse and keyboard injection, chat, file transfer and disconnection.
//
// Linux: run under Xvfb (xvfb-run -a -s "-screen 0 1600x1000x24" npm run test:e2e)
// Windows: run on an interactive desktop.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';
import electronPath from 'electron';
import { _electron as electron } from 'playwright-core';
import http from 'node:http';
import { WebSocketServer, createWebSocketStream } from 'ws';
import { Aedes } from 'aedes';
import { createServer } from '../../server/src/server.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const artifacts = process.env.PAIRDESK_E2E_ARTIFACTS || path.join(os.tmpdir(), 'pairdesk-e2e');
fs.mkdirSync(artifacts, { recursive: true });

// PAIRDESK_PACKAGED_EXE runs the test against a packaged build instead of
// the sources (e.g. dist/win-unpacked/PairDesk.exe).
const packaged = process.env.PAIRDESK_PACKAGED_EXE ? path.resolve(process.env.PAIRDESK_PACKAGED_EXE) : null;

// PAIRDESK_E2E_HOST_APP / PAIRDESK_E2E_CTRL_APP run one side from another
// source tree (e.g. a checkout of the previous release) to test compatibility.
const hostRoot = process.env.PAIRDESK_E2E_HOST_APP ? path.resolve(process.env.PAIRDESK_E2E_HOST_APP) : root;
const ctrlRoot = process.env.PAIRDESK_E2E_CTRL_APP ? path.resolve(process.env.PAIRDESK_E2E_CTRL_APP) : root;

// PAIRDESK_E2E_TRANSPORT=mqtt exercises the default "public relays" mode
// with a local MQTT broker instead of the PairDesk server.
const useMqtt = process.env.PAIRDESK_E2E_TRANSPORT === 'mqtt';

async function startSignaling() {
  if (!useMqtt) {
    const server = createServer({ port: 0, host: '127.0.0.1' });
    const port = await server.listen();
    return { env: { PAIRDESK_SERVER_URL: `ws://127.0.0.1:${port}/ws` }, close: () => server.close() };
  }
  const brokers = [];
  for (let i = 0; i < 2; i++) {
    const aedes = await Aedes.createBroker();
    const httpServer = http.createServer();
    const wss = new WebSocketServer({ server: httpServer });
    wss.on('connection', (socket, req) => aedes.handle(createWebSocketStream(socket), req));
    await new Promise((r) => httpServer.listen(0, '127.0.0.1', r));
    brokers.push({ aedes, httpServer, wss, url: `ws://127.0.0.1:${httpServer.address().port}/mqtt` });
  }
  return {
    env: { PAIRDESK_MQTT_BROKERS: brokers.map((b) => b.url).join(',') },
    async close() {
      for (const b of brokers) {
        for (const c of b.wss.clients) c.terminate();
        await new Promise((r) => b.httpServer.close(r));
        await new Promise((r) => b.aedes.close(r));
      }
    },
  };
}

async function launch(name, signalingEnv, appRoot = root) {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), `pd-${name}-`));
  const downloads = path.join(profile, 'downloads');
  const args = packaged ? [`--pairdesk-profile=${profile}`] : [appRoot, `--pairdesk-profile=${profile}`];
  if (process.platform === 'linux') args.push('--no-sandbox', '--disable-gpu');
  const app = await electron.launch({
    executablePath: packaged || electronPath,
    args,
    env: { ...process.env, ...signalingEnv, PAIRDESK_E2E: '1', PAIRDESK_DEBUG: '1', PAIRDESK_DOWNLOADS_DIR: downloads },
    timeout: 60_000,
  });
  app.process().stderr.on('data', (d) => {
    const line = String(d);
    if (/\[(warn|error)\]/.test(line)) process.stderr.write(`[${name}] ${line}`);
  });
  const main = await app.firstWindow();
  main.on('pageerror', (e) => process.stderr.write(`[${name} main pageerror] ${e.message}\n`));
  await main.waitForSelector('#my-id', { timeout: 30_000 });
  return { app, main, profile, downloads };
}

async function waitForWindow(app, fragment, timeout = 30_000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const found = app.windows().find((w) => w.url().includes(fragment));
    if (found) return found;
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error(`window ${fragment} did not open`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function poll(fn, { timeout = 15_000, interval = 200, message = 'condition' } = {}) {
  const deadline = Date.now() + timeout;
  let last;
  while (Date.now() < deadline) {
    last = await fn();
    if (last) return last;
    await sleep(interval);
  }
  throw new Error(`timed out waiting for ${message} (last: ${JSON.stringify(last)})`);
}

/** Places a window of `app` (selected by URL fragment) at the given bounds. */
function placeWindow(app, fragment, bounds, top = false) {
  return app.evaluate(({ BrowserWindow }, { fragment, bounds, top }) => {
    const win = BrowserWindow.getAllWindows().find((w) => w.webContents.getURL().includes(fragment));
    win.setMinimumSize(300, 200);
    win.setBounds(bounds);
    if (top) {
      // Windows does not let a background process raise its window: keep the
      // target window above the viewer so that injected clicks reach it.
      win.setAlwaysOnTop(true);
      win.moveTop();
    }
    // Content (client area) bounds: excludes the title bar and borders.
    return { outer: win.getBounds(), content: win.getContentBounds() };
  }, { fragment, bounds, top });
}

function setVisible(app, fragment, visible) {
  return app.evaluate(({ BrowserWindow }, { fragment, visible }) => {
    const win = BrowserWindow.getAllWindows().find((w) => w.webContents.getURL().includes(fragment));
    if (visible) win.show();
    else win.hide();
  }, { fragment, visible });
}

/** Client coordinates in the viewer for a physical point of the host screen. */
async function viewerPointFor(viewer, screenPoint, screenSize) {
  return viewer.evaluate(({ screenPoint, screenSize }) => {
    const v = document.querySelector('#remote-screen');
    const r = v.getBoundingClientRect();
    const s = Math.min(r.width / v.videoWidth, r.height / v.videoHeight);
    const dw = v.videoWidth * s;
    const dh = v.videoHeight * s;
    return {
      x: r.left + (r.width - dw) / 2 + ((screenPoint.x + 0.5) / screenSize.width) * dw,
      y: r.top + (r.height - dh) / 2 + ((screenPoint.y + 0.5) / screenSize.height) * dh,
      scale: dw / screenSize.width,
    };
  }, { screenPoint, screenSize });
}

/** 1.1+ on both sides: the controller's own cursor takes the host's cursor shape. */
async function checkLocalCursor(viewer, host) {
  const read = () => viewer.evaluate(() => document.querySelector('#remote-screen').style.cursor || null);
  // Windows: the I-beam is a system cursor, reported by name. Elsewhere it may
  // be a bitmap (Chromium cursors on X11 have no name).
  const want = process.platform === 'win32' ? (s) => s === 'text' : (s) => Boolean(s);
  let shape = null;
  try {
    await poll(async () => want((shape = await read())), { timeout: 8000, message: 'host cursor shape shown on the viewer' });
  } catch (err) {
    const debug = await host.main.evaluate(() => window.pairdesk.invoke('e2e:cursor')).catch((e) => e.message);
    throw new Error(`${err.message}: viewer has ${JSON.stringify(shape)}, host probe ${JSON.stringify(debug)}`);
  }
  process.stderr.write(`viewer cursor over the host text field: ${shape.slice(0, 80)}\n`);
}

async function dumpDiagnostics(instances) {
  for (const inst of instances) {
    for (const [i, page] of inst.app.windows().entries()) {
      const name = `${inst.name}-${i}-${page.url().split('/').slice(-2, -1)[0] || 'window'}`;
      await page.screenshot({ path: path.join(artifacts, `failure-${name}.png`), timeout: 5000 }).catch(() => {});
    }
    try {
      const lines = fs.readFileSync(path.join(inst.profile, 'logs', 'main.log'), 'utf8').trim().split('\n');
      process.stderr.write(`\n----- ${inst.name} main.log (last 80 lines) -----\n${lines.slice(-80).join('\n')}\n`);
    } catch (err) {
      process.stderr.write(`no log for ${inst.name}: ${err.message}\n`);
    }
  }
}

test('two PairDesk instances: connect, view, control, chat, files, disconnect', { timeout: 240_000 }, async (t) => {
  const signaling = await startSignaling();
  const host = await launch('host', signaling.env, hostRoot);
  const ctrl = await launch('ctrl', signaling.env, ctrlRoot);
  host.name = 'host';
  ctrl.name = 'ctrl';
  t.after(async () => {
    await host.app.close().catch(() => {});
    await ctrl.app.close().catch(() => {});
    await signaling.close();
  });
  try {
    await scenario(host, ctrl);
  } catch (err) {
    await dumpDiagnostics([host, ctrl]);
    throw err;
  }
});

async function scenario(host, ctrl) {
  const shot = async (page, name) => page.screenshot({ path: path.join(artifacts, `${name}.png`) }).catch(() => {});

  // Both instances reach the network.
  await poll(async () => (await host.main.$('.dot.online')) && (await ctrl.main.$('.dot.online')), { message: 'both online' });

  // Remote control must be available (native input injection loaded).
  const hostState = await host.main.evaluate(() => window.pairdesk.invoke('app:state'));
  assert.equal(hostState.input.available, true, `input injection unavailable: ${hostState.input.reason}`);

  const hostId = (await host.main.textContent('#my-id')).replace(/\s/g, '');
  const password = (await host.main.textContent('#my-password')).trim();
  assert.match(hostId, /^\d{9}$/);
  assert.match(password, /^[a-z0-9]{6}$/);

  // 1. Wrong password is refused.
  await ctrl.main.fill('#partner-id', hostId);
  await ctrl.main.click('#connect-button');
  await ctrl.main.waitForSelector('#connect-password', { timeout: 20_000 });
  await ctrl.main.fill('#connect-password', 'wrong1');
  await ctrl.main.click('#connect-submit');
  await ctrl.main.waitForSelector('#connect-password', { timeout: 20_000 });
  await poll(async () => (await ctrl.main.textContent('.connect-modal .error-text')).length > 0, { message: 'auth error shown' });

  // 2. Right password, remembered for the next connection.
  await ctrl.main.fill('#connect-password', password);
  await ctrl.main.check('#connect-remember');
  await ctrl.main.click('#connect-submit');
  const viewer = await waitForWindow(ctrl.app, '/viewer/');
  viewer.on('pageerror', (e) => process.stderr.write(`[viewer pageerror] ${e.message}\n`));
  const panel = await waitForWindow(host.app, '/host/');
  panel.on('pageerror', (e) => process.stderr.write(`[host panel pageerror] ${e.message}\n`));

  // 3. The remote screen is displayed.
  await poll(() => viewer.evaluate(() => {
    const v = document.querySelector('#remote-screen');
    const overlay = document.querySelector('.overlay');
    return v && v.videoWidth > 0 && v.currentTime > 0 && overlay.hidden;
  }), { timeout: 45_000, message: 'remote video' });
  await poll(async () => (await host.main.$('.banner.warning .btn.danger')) !== null, { message: 'host banner' });
  const screenInfo = await host.app.evaluate(({ screen }) => {
    const d = screen.getPrimaryDisplay();
    return { width: Math.round(d.bounds.width * d.scaleFactor), height: Math.round(d.bounds.height * d.scaleFactor), work: d.workArea, scale: d.scaleFactor };
  });

  // Layout: host main window top-left (on top), viewer on the right.
  const work = screenInfo.work;
  const placed = await placeWindow(host.app, '/main/', { x: work.x, y: work.y, width: 1000, height: 680 }, false);
  await placeWindow(ctrl.app, '/viewer/', { x: work.x + work.width - 700, y: work.y, width: 700, height: 480 }, false);
  const hostBounds = (await placeWindow(host.app, '/main/', placed.outer, true)).content;
  // The controller's main window would otherwise sit above the host's one.
  await setVisible(ctrl.app, '/main/', false);
  await sleep(1500);
  await shot(viewer, 'viewer-connected');

  // 4. Remote mouse: hover a point of the host main window, check the cursor.
  const target = { x: Math.round((hostBounds.x + 600) * screenInfo.scale), y: Math.round((hostBounds.y + 560) * screenInfo.scale) };
  const p = await viewerPointFor(viewer, target, screenInfo);
  await viewer.mouse.move(p.x - 20, p.y - 20);
  await viewer.mouse.move(p.x, p.y, { steps: 5 });
  const tolerance = Math.ceil(2 / p.scale) + 2;
  const cursor = await poll(async () => {
    const c = await host.main.evaluate(() => window.pairdesk.invoke('e2e:cursor'));
    return Math.abs(c.x - target.x) <= tolerance && Math.abs(c.y - target.y) <= tolerance ? c : null;
  }, { message: `host cursor near ${JSON.stringify(target)}` });
  assert.ok(cursor);

  // 5. Remote click + keyboard: type into the host's partner ID field.
  const box = await host.main.evaluate(() => {
    const r = document.querySelector('#partner-id').getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
  const field = { x: Math.round((hostBounds.x + box.x) * screenInfo.scale), y: Math.round((hostBounds.y + box.y) * screenInfo.scale) };
  await host.main.evaluate(() => {
    document.querySelector('#partner-id').value = '';
    document.activeElement?.blur();
  });
  const fp = await viewerPointFor(viewer, field, screenInfo);
  await viewer.mouse.move(fp.x, fp.y, { steps: 3 });
  await viewer.mouse.click(fp.x, fp.y);
  await poll(() => host.main.evaluate(() => document.activeElement?.id === 'partner-id'), {
    message: `remote click focuses the field at ${JSON.stringify(field)}`,
  });
  // The controller's own cursor takes the host's cursor shape (I-beam over a text field).
  if (hostRoot === root && ctrlRoot === root) await checkLocalCursor(viewer, host);
  if (ctrlRoot === root) {
    const delay = await poll(() => viewer.evaluate(() => document.querySelector('.topbar .delay')?.textContent || null), {
      message: 'delay estimate shown',
    });
    process.stderr.write(`viewer ${delay}\n`);
  }
  await viewer.keyboard.type('4815');
  await poll(async () => (await host.main.inputValue('#partner-id')).replace(/\s/g, '') === '4815', { message: 'remote typing' });
  await viewer.keyboard.press('Backspace');
  await poll(async () => (await host.main.inputValue('#partner-id')).replace(/\s/g, '') === '481', { message: 'remote backspace' });

  // 5b. Quick quality changes (the 60 fps preset recaptures the screen): the
  // picture keeps coming and the mouse still lands where expected.
  if (hostRoot === root && ctrlRoot === root) {
    const pick = async (index) => {
      await viewer.click('#quality-button');
      await viewer.click(`.menu button:nth-of-type(${index + 1})`);
    };
    await pick(0); // speed
    await pick(1); // balanced, while the 60 fps capture may still be starting
    await sleep(1500);
    const frames = () => viewer.evaluate(() => document.querySelector('#remote-screen').getVideoPlaybackQuality().totalVideoFrames);
    const before = await frames();
    await poll(async () => (await frames()) > before + 5, { message: 'video still playing after quality changes' });
    const target2 = { x: Math.round((hostBounds.x + 300) * screenInfo.scale), y: Math.round((hostBounds.y + 400) * screenInfo.scale) };
    const p2 = await viewerPointFor(viewer, target2, screenInfo);
    await viewer.mouse.move(p2.x, p2.y, { steps: 4 });
    await poll(async () => {
      const c = await host.main.evaluate(() => window.pairdesk.invoke('e2e:cursor'));
      return Math.abs(c.x - target2.x) <= tolerance && Math.abs(c.y - target2.y) <= tolerance;
    }, { message: `host cursor near ${JSON.stringify(target2)} after quality changes` });
  }

  // 5c. Grab the host panel by its title bar with the remote mouse and drag
  // it: on Windows this starts a modal move loop in the host's main process,
  // which must not stop the remote mouse (it used to freeze the session).
  {
    await setVisible(host.app, '/main/', false);
    await sleep(500);
    const before = await host.app.evaluate(({ BrowserWindow }) => {
      const win = BrowserWindow.getAllWindows().find((w) => w.webContents.getURL().includes('/host/'));
      return win.getBounds();
    });
    const grab = { x: Math.round((before.x + 70) * screenInfo.scale), y: Math.round((before.y + 22) * screenInfo.scale) };
    const g = await viewerPointFor(viewer, grab, screenInfo);
    await viewer.mouse.move(g.x, g.y, { steps: 3 });
    await poll(async () => {
      const c = await host.main.evaluate(() => window.pairdesk.invoke('e2e:cursor'));
      return Math.abs(c.x - grab.x) <= tolerance && Math.abs(c.y - grab.y) <= tolerance;
    }, { message: 'host cursor on the panel title bar' });
    await viewer.mouse.down();
    await viewer.mouse.move(g.x - 30, g.y - 20, { steps: 6 });
    await viewer.mouse.move(g.x - 60, g.y - 40, { steps: 6 });
    await viewer.mouse.up();
    await sleep(500);
    const after = await host.app.evaluate(({ BrowserWindow }) => {
      const win = BrowserWindow.getAllWindows().find((w) => w.webContents.getURL().includes('/host/'));
      return win.getBounds();
    });
    process.stderr.write(`host panel dragged from ${JSON.stringify(before)} to ${JSON.stringify(after)}\n`);
    // The remote mouse still works afterwards.
    const target3 = { x: Math.round((before.x - 150) * screenInfo.scale), y: Math.round((before.y - 120) * screenInfo.scale) };
    const p3 = await viewerPointFor(viewer, target3, screenInfo);
    await viewer.mouse.move(p3.x, p3.y, { steps: 5 });
    await poll(async () => {
      const c = await host.main.evaluate(() => window.pairdesk.invoke('e2e:cursor'));
      return Math.abs(c.x - target3.x) <= tolerance && Math.abs(c.y - target3.y) <= tolerance;
    }, { timeout: 10_000, message: `remote mouse still working after dragging the host panel (${JSON.stringify(target3)})` });
    await setVisible(host.app, '/main/', true);
    await placeWindow(host.app, '/main/', placed.outer, true);
    await sleep(300);
  }

  // 5d. The host panel collapses into a small tab on the right edge, and comes back.
  {
    const bounds = () => host.app.evaluate(({ BrowserWindow, screen }) => {
      const win = BrowserWindow.getAllWindows().find((w) => w.webContents.getURL().includes('/host/'));
      return { b: win.getBounds(), area: screen.getDisplayMatching(win.getBounds()).workArea };
    });
    const open = await bounds();
    await panel.click('#host-collapse');
    const tab = await poll(async () => {
      const r = await bounds();
      return r.b.width < 60 ? r : null;
    }, { message: 'host panel collapsed into a tab' });
    assert.ok(Math.abs(tab.b.x + tab.b.width - (tab.area.x + tab.area.width)) <= 2, `tab on the right edge: ${JSON.stringify(tab)}`);
    await shot(panel, 'host-panel-collapsed');
    await panel.click('#host-expand');
    const back = await poll(async () => {
      const r = await bounds();
      return r.b.width === open.b.width ? r : null;
    }, { message: 'host panel expanded again' });
    assert.deepEqual(back.b, open.b);
  }

  // 5e. Files dropped on the remote screen land where they were dropped: here
  // in a window of the host computer that accepts file drops.
  {
    const zone = { x: work.x + 120, y: work.y + 120, width: 320, height: 220 };
    // The main window was made topmost above: out of the way.
    await setVisible(host.app, '/main/', false);
    await host.app.evaluate(({ BrowserWindow }, bounds) => {
      const win = new BrowserWindow({ ...bounds, frame: false, alwaysOnTop: true, show: true, title: 'drop-zone' });
      win.setAlwaysOnTop(true, 'floating');
      win.moveTop();
      const html = '<body style="margin:0;background:#2e7d32;height:100vh" ondragover="event.preventDefault()" '
        + 'ondrop="event.preventDefault();document.title=\'dropped:\'+[...event.dataTransfer.files].map(function(f){return f.name}).join(\'|\')"></body>';
      win.loadURL(`data:text/html,${encodeURIComponent(html)}`);
      globalThis.__dropZone = win;
    }, zone);
    await sleep(800);
    const point = { x: Math.round((zone.x + zone.width / 2) * screenInfo.scale), y: Math.round((zone.y + zone.height / 2) * screenInfo.scale) };
    const vp = await viewerPointFor(viewer, point, screenInfo);
    // Accented names on Windows (UTF-16 drops); between two Chromium windows
    // on X11 they arrive mangled, which is a Chromium issue, not ours.
    const dropName = process.platform === 'win32' ? 'déposé ici.txt' : 'depose ici.txt';
    await viewer.evaluate(({ x, y, dropName }) => {
      const dt = new DataTransfer();
      dt.items.add(new File(['bonjour depuis le contrôleur'], dropName, { type: 'text/plain' }));
      const target = document.elementFromPoint(x, y);
      target.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, clientX: x, clientY: y, bubbles: true, cancelable: true }));
    }, { ...vp, dropName });
    const title = await poll(() => host.app.evaluate(() => {
      const t = globalThis.__dropZone.getTitle();
      return t.startsWith('dropped:') ? t : null;
    }), { timeout: 20_000, message: 'file dropped in the host window under the drop point' });
    assert.equal(title, `dropped:${dropName}`);
    await host.app.evaluate(() => globalThis.__dropZone.destroy());
    await setVisible(host.app, '/main/', true);
  }

  // 5f. Files copied on the controller and pasted (Ctrl+V) in the viewer end up
  // in the host's clipboard. Both instances share one clipboard here, so the
  // check is that the host wrote a received copy (its staging folder).
  {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pd-copy-'));
    const copied = path.join(dir, 'copie test.txt');
    fs.writeFileSync(copied, 'contenu copié');
    const readFiles = async () => {
      if (process.platform === 'win32') {
        const out = execFileSync('powershell', ['-NoProfile', '-Command', 'Get-Clipboard -Format FileDropList | ForEach-Object { $_.FullName }'], { encoding: 'utf8' });
        return out.split(/\r?\n/).filter(Boolean);
      }
      const list = await host.app.evaluate(async ({ clipboard }) => {
        const [item] = await clipboard.read();
        return item?.types.includes('text/uri-list') ? (await item.getType('text/uri-list')).text() : '';
      });
      return list.split(/\r?\n/).filter((l) => l.startsWith('file://')).map((l) => fileURLToPath(l));
    };
    if (process.platform === 'win32') {
      execFileSync('powershell', ['-NoProfile', '-Command', `Set-Clipboard -Path '${copied.replace(/'/g, "''")}'`]);
    } else {
      await ctrl.app.evaluate(({ clipboard, ClipboardItem }, uri) => clipboard.write([
        new ClipboardItem({ 'electron application/osclipboard;format="text/uri-list"': uri }),
      ]), pathToFileURL(copied).href);
    }
    await sleep(1500); // announced to the partner
    await viewer.focus('#stage');
    await viewer.keyboard.down('Control');
    await viewer.keyboard.press('KeyV');
    await viewer.keyboard.up('Control');
    const received = await poll(async () => {
      const files = await readFiles();
      return files.find((f) => f.includes(`PairDesk${path.sep}clipboard`) && path.basename(f) === 'copie test.txt') || null;
    }, { timeout: 20_000, message: 'copied file in the host clipboard' });
    assert.equal(fs.readFileSync(received, 'utf8'), 'contenu copié');
  }

  // 6. Chat from the controller to the host panel.
  await viewer.click('#chat-button');
  await viewer.fill('.chat-panel input', 'Bonjour depuis PairDesk !');
  await viewer.press('.chat-panel input', 'Enter');
  await poll(async () => (await panel.textContent('.host-chat')).includes('Bonjour depuis PairDesk !'), { message: 'chat delivered' });

  // 7. File transfer from the controller to the host.
  const sample = path.join(ctrl.profile, 'rapport test.bin');
  const bytes = Buffer.alloc(300 * 1024 + 123);
  for (let i = 0; i < bytes.length; i++) bytes[i] = (i * 31) & 0xff;
  fs.writeFileSync(sample, bytes);
  await viewer.setInputFiles('.topbar input[type=file]', sample);
  const received = path.join(host.downloads, 'rapport test.bin');
  await poll(() => fs.existsSync(received) && fs.statSync(received).size === bytes.length, { timeout: 30_000, message: 'file received' });
  assert.ok(fs.readFileSync(received).equals(bytes), 'file content preserved');
  await shot(panel, 'host-panel');
  await shot(viewer, 'viewer-chat');

  // 8. Disconnect from the viewer: the host panel closes.
  await viewer.click('#disconnect-button');
  await poll(() => !host.app.windows().some((w) => w.url().includes('/host/')), { message: 'host panel closed' });
  await poll(async () => (await host.main.$('.banner.warning .btn.danger')) === null, { message: 'host banner removed' });

  // 9. Reconnect with the remembered password, host asks for confirmation.
  await host.main.evaluate(() => window.pairdesk.invoke('settings:update', { confirmIncoming: true }));
  await setVisible(ctrl.app, '/main/', true);
  await ctrl.main.click('.recent-chip');
  const panel2 = await waitForWindow(host.app, '/host/');
  await panel2.waitForSelector('#host-accept', { timeout: 20_000 });
  await poll(async () => (await ctrl.main.textContent('.connect-modal')).length > 0, { message: 'controller waiting' });
  await panel2.click('#host-accept');
  const viewer2 = await waitForWindow(ctrl.app, '/viewer/');
  await poll(() => viewer2.evaluate(() => document.querySelector('#remote-screen')?.videoWidth > 0), { timeout: 45_000, message: 'second session video' });

  // 10. Host ends the session: the viewer shows it.
  await panel2.click('#host-end');
  await poll(() => viewer2.evaluate(() => document.querySelector('#viewer-overlay')?.dataset.kind === 'ended'), { message: 'viewer notified of the end' });
  await shot(viewer2, 'viewer-ended');
}

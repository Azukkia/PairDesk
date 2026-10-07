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
import { fileURLToPath } from 'node:url';
import electronPath from 'electron';
import { _electron as electron } from 'playwright-core';
import { createServer } from '../../server/src/server.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const artifacts = process.env.PAIRDESK_E2E_ARTIFACTS || path.join(os.tmpdir(), 'pairdesk-e2e');
fs.mkdirSync(artifacts, { recursive: true });

async function launch(name, serverUrl, extraEnv = {}) {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), `pd-${name}-`));
  const downloads = path.join(profile, 'downloads');
  const args = [root, `--pairdesk-profile=${profile}`];
  if (process.platform === 'linux') args.push('--no-sandbox', '--disable-gpu');
  const app = await electron.launch({
    executablePath: electronPath,
    args,
    env: { ...process.env, PAIRDESK_SERVER_URL: serverUrl, PAIRDESK_E2E: '1', PAIRDESK_DOWNLOADS_DIR: downloads, ...extraEnv },
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
    if (top) win.moveTop();
    return win.getBounds();
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

test('two PairDesk instances: connect, view, control, chat, files, disconnect', { timeout: 240_000 }, async (t) => {
  const server = createServer({ port: 0, host: '127.0.0.1' });
  const port = await server.listen();
  const url = `ws://127.0.0.1:${port}/ws`;
  const host = await launch('host', url);
  const ctrl = await launch('ctrl', url);
  t.after(async () => {
    await host.app.close().catch(() => {});
    await ctrl.app.close().catch(() => {});
    await server.close();
  });
  const shot = async (page, name) => page.screenshot({ path: path.join(artifacts, `${name}.png`) }).catch(() => {});

  // Both instances reach the network.
  await poll(async () => (await host.main.textContent('.statusbar')).includes('127.0.0.1'), { message: 'host online' });
  await poll(async () => (await host.main.$('.dot.online')) && (await ctrl.main.$('.dot.online')), { message: 'both online' });

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
  const hostBounds = await placeWindow(host.app, '/main/', { x: work.x, y: work.y, width: 1000, height: 680 }, false);
  await placeWindow(ctrl.app, '/viewer/', { x: work.x + work.width - 700, y: work.y, width: 700, height: 480 }, false);
  await placeWindow(host.app, '/main/', hostBounds, true);
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
  await viewer.keyboard.type('4815');
  await poll(async () => (await host.main.inputValue('#partner-id')).replace(/\s/g, '') === '4815', { message: 'remote typing' });
  await viewer.keyboard.press('Backspace');
  await poll(async () => (await host.main.inputValue('#partner-id')).replace(/\s/g, '') === '481', { message: 'remote backspace' });

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
});

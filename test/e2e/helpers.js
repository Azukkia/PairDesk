// Shared helpers of the end-to-end tests: local signaling (PairDesk server or
// MQTT brokers), launching PairDesk instances, polling.

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import electronPath from 'electron';
import { _electron as electron } from 'playwright-core';
import http from 'node:http';
import { WebSocketServer, createWebSocketStream } from 'ws';
import { Aedes } from 'aedes';
import { createServer } from '../../server/src/server.js';

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
export const artifacts = process.env.PAIRDESK_E2E_ARTIFACTS || path.join(os.tmpdir(), 'pairdesk-e2e');
fs.mkdirSync(artifacts, { recursive: true });

// PAIRDESK_PACKAGED_EXE runs the test against a packaged build instead of
// the sources (e.g. dist/win-unpacked/PairDesk.exe).
export const packaged = process.env.PAIRDESK_PACKAGED_EXE ? path.resolve(process.env.PAIRDESK_PACKAGED_EXE) : null;

// PAIRDESK_E2E_HOST_APP / PAIRDESK_E2E_CTRL_APP run one side from another
// source tree (e.g. a checkout of the previous release) to test compatibility.
export const hostRoot = process.env.PAIRDESK_E2E_HOST_APP ? path.resolve(process.env.PAIRDESK_E2E_HOST_APP) : root;
export const ctrlRoot = process.env.PAIRDESK_E2E_CTRL_APP ? path.resolve(process.env.PAIRDESK_E2E_CTRL_APP) : root;

// PAIRDESK_E2E_TRANSPORT=mqtt exercises the default "public relays" mode
// with a local MQTT broker instead of the PairDesk server.
export const useMqtt = process.env.PAIRDESK_E2E_TRANSPORT === 'mqtt';

export async function startSignaling() {
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

export async function launch(name, signalingEnv, appRoot = root) {
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

export async function waitForWindow(app, fragment, timeout = 30_000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const found = app.windows().find((w) => w.url().includes(fragment));
    if (found) return found;
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error(`window ${fragment} did not open`);
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function poll(fn, { timeout = 15_000, interval = 200, message = 'condition' } = {}) {
  const deadline = Date.now() + timeout;
  let last;
  while (Date.now() < deadline) {
    last = await fn();
    if (last) return last;
    await sleep(interval);
  }
  throw new Error(`timed out waiting for ${message} (last: ${JSON.stringify(last)})`);
}

export async function dumpDiagnostics(instances) {
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


// Entry point of the "input helper" utility process (forked by
// src/main/input-service.js): injects the remote mouse and keyboard and
// tracks the cursor shape, independently of the main process. See
// input-core.js for why.

import { createInjector } from '../input/index.js';
import { createCursorTracker } from '../cursor.js';
import { pngDataUrl } from './png.js';
import { createInputCore } from './input-core.js';

const parent = process.parentPort;
const log = (level, msg) => parent.postMessage({ type: 'log', level, msg: String(msg) });
const logger = { info: (m) => log('info', m), warn: (m) => log('warn', m), error: (m) => log('error', m) };

process.on('uncaughtException', (err) => log('error', `[input-helper] ${err.stack || err.message}`));
process.on('unhandledRejection', (err) => log('error', `[input-helper] ${err?.stack || err}`));

const injector = await createInjector(logger);
let guard = null;
if (process.platform === 'win32' && injector.available) {
  try {
    guard = (await import('../input/win32-guard.js')).createWin32Guard({ log: logger });
  } catch (err) {
    log('warn', `[input] privilege guard unavailable: ${err.message}`);
  }
}

const core = createInputCore({
  injector,
  guard,
  log,
  toMain: (msg) => parent.postMessage(msg),
  createTracker: () => createCursorTracker({ log: logger, encodePng: pngDataUrl, scale: () => core.scale }),
});

parent.on('message', (e) => core.handleMain(e.data, e.ports));
parent.postMessage({ type: 'ready', available: injector.available, reason: injector.reason || null, platform: process.platform });

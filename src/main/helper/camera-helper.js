// Entry point of the "camera helper" utility process (forked by
// src/main/camera/virtual-camera.js): feeds the phone's camera to the
// "PairDesk Camera" virtual webcam (native/softcam), outside the main
// process. Frames come straight from the camera window through a
// MessagePort, as RGBA, and are handed to the DLL as top-down BGR24.

import koffi from 'koffi';
import { rgbaToBgr, fillBgr } from '../camera/frames.js';

const parent = process.parentPort;
const log = (level, msg) => parent.postMessage({ type: 'log', level, msg: String(msg) });

process.on('uncaughtException', (err) => log('error', `[camera-helper] ${err.stack || err.message}`));
process.on('unhandledRejection', (err) => log('error', `[camera-helper] ${err?.stack || err}`));

let api = null;
let camera = null;
let size = null; // { width, height }
let bgr = null;
let port = null;
let idleTimer = null;
let lastFrameAt = 0;
const stats = { frames: 0, rejected: 0, center: null, ms: 0 }; // what reached the webcam (logs, tests); ms: average handling time

// End-to-end tests without the DLL (Linux): the same path, minus the webcam.
const fakeApi = () => ({
  create: () => ({}),
  remove() {},
  send() {},
  connected: () => true,
});

function start({ dll, width, height, fake }) {
  if (camera) return { ok: true };
  if (fake) api ??= fakeApi();
  api ??= (() => {
    const lib = koffi.load(dll);
    return {
      create: lib.func('void * __cdecl scCreateCamera(int width, int height, float framerate)'),
      remove: lib.func('void __cdecl scDeleteCamera(void *camera)'),
      send: lib.func('void __cdecl scSendFrame(void *camera, const uint8_t *image)'),
      connected: lib.func('bool __cdecl scIsConnected(void *camera)'),
    };
  })();
  // Frame rate 0: every frame is delivered as soon as it arrives (live source).
  camera = api.create(width, height, 0);
  if (!camera) return { ok: false, error: 'another PairDesk Camera is already running' };
  size = { width, height };
  bgr = Buffer.alloc(width * height * 3);
  idle();
  return { ok: true };
}

// Without a phone, a dark picture once a second: applications showing the
// webcam do not freeze on the last frame of the previous session.
function idle() {
  clearInterval(idleTimer);
  idleTimer = setInterval(() => {
    if (!camera || Date.now() - lastFrameAt < 1500) return;
    fillBgr(bgr, 0x121624);
    api.send(camera, bgr);
  }, 1000);
}

function stop() {
  clearInterval(idleTimer);
  if (camera) api.remove(camera);
  camera = null;
}

function onFrame(msg) {
  const data = msg?.data;
  const rgba = data instanceof ArrayBuffer ? new Uint8Array(data) : ArrayBuffer.isView(data) ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength) : null;
  if (!camera || !rgba || msg.width !== size.width || msg.height !== size.height || rgba.length < size.width * size.height * 4) {
    if (stats.rejected++ === 0) log('warn', `[camera] unexpected frame: ${msg?.width}x${msg?.height}, ${Object.prototype.toString.call(data)} ${rgba?.length}`);
    return;
  }
  const t0 = performance.now();
  rgbaToBgr(rgba, bgr);
  api.send(camera, bgr);
  stats.ms = stats.ms * 0.9 + (performance.now() - t0) * 0.1;
  lastFrameAt = Date.now();
  if (stats.frames++ === 0) log('info', '[camera] first frame from the phone sent to the webcam');
  const c = ((size.height >> 1) * size.width + (size.width >> 1)) * 3;
  stats.center = [bgr[c + 2], bgr[c + 1], bgr[c]];
}

parent.on('message', (e) => {
  const msg = e.data;
  switch (msg?.type) {
    case 'start': {
      let res;
      try {
        res = start(msg);
      } catch (err) {
        res = { ok: false, error: err.message };
      }
      parent.postMessage({ type: 'started', ...res });
      break;
    }
    case 'port':
      try {
        port?.close();
      } catch {
        /* ignore */
      }
      port = e.ports[0] || null;
      if (!port) break;
      port.on('message', (m) => {
        onFrame(m.data);
        // One frame in flight: the window sends the next one after this.
        port?.postMessage({ type: 'ack', connected: Boolean(camera && api.connected(camera)) });
      });
      port.start();
      break;
    case 'stats':
      parent.postMessage({ type: 'stats', ...stats });
      break;
    case 'stop':
      stop();
      parent.postMessage({ type: 'stopped' });
      break;
    default:
      break;
  }
});
parent.postMessage({ type: 'ready' });

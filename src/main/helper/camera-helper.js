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

function start({ dll, width, height }) {
  if (camera) return { ok: true };
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
  if (!camera || !msg || msg.width !== size.width || msg.height !== size.height) return;
  const rgba = new Uint8Array(msg.data);
  if (rgba.length < size.width * size.height * 4) return;
  rgbaToBgr(rgba, bgr);
  api.send(camera, bgr);
  lastFrameAt = Date.now();
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
    case 'stop':
      stop();
      parent.postMessage({ type: 'stopped' });
      break;
    default:
      break;
  }
});
parent.postMessage({ type: 'ready' });

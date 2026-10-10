// The "PairDesk Camera" virtual webcam (Windows): applications see the
// phone's camera as a webcam during camera sessions.
//
// The webcam itself is a DirectShow filter (native/softcam) loaded by the
// applications that use it; it reads the frames from shared memory. PairDesk
// writes them from a "camera helper" utility process
// (src/main/helper/camera-helper.js), which the camera window feeds
// directly through a MessagePort. The helper and the webcam stay up until
// PairDesk quits once a camera session has started, so that applications
// keep their webcam between two sessions (a dark picture meanwhile).

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { utilityProcess, MessageChannelMain } from 'electron';
import { CAMERA_NAME, ensureRegistered } from './registration.js';

export const FRAME_WIDTH = 1280;
export const FRAME_HEIGHT = 720;

export class VirtualCamera {
  constructor({ appPath, resourcesPath, packaged, log }) {
    this.entry = path.join(appPath, 'src', 'main', 'helper', 'camera-helper.js');
    this.source = process.env.PAIRDESK_CAMERA_DLL
      || (packaged
        ? path.join(resourcesPath, 'camera', 'PairDeskCamera.dll')
        : path.join(appPath, 'native', 'bin', 'win32-x64', 'PairDeskCamera.dll'));
    this.dir = path.join(process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local'), 'PairDesk', 'camera');
    this.log = log;
    this.child = null;
    this.starting = null;
    this.state = { supported: process.platform === 'win32' && fs.existsSync(this.source), active: false, error: null };
  }

  /** What the camera window shows: { supported, active, error, name }. */
  status() {
    return { ...this.state, name: CAMERA_NAME };
  }

  /** Registers the webcam if needed and starts the helper (once). */
  start() {
    if (!this.state.supported) return Promise.resolve(this.status());
    this.starting ??= this.#start().catch((err) => {
      this.log.warn(`[camera] virtual webcam unavailable: ${err.message}`);
      this.state = { ...this.state, active: false, error: err.message };
      this.starting = null;
      this.#kill();
    });
    return this.starting.then(() => this.status());
  }

  async #start() {
    const dll = await ensureRegistered({ source: this.source, dir: this.dir, log: this.log });
    const child = utilityProcess.fork(this.entry, [], { serviceName: 'PairDesk Camera', stdio: 'pipe' });
    this.child = child;
    child.stdout?.on('data', (d) => this.log.info(`[camera-helper] ${String(d).trim()}`));
    child.stderr?.on('data', (d) => this.log.warn(`[camera-helper] ${String(d).trim()}`));
    const started = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('helper timeout')), 15_000);
      child.on('message', (msg) => {
        if (msg?.type === 'log') this.log[msg.level === 'error' ? 'error' : msg.level === 'warn' ? 'warn' : 'info'](msg.msg);
        else if (msg?.type === 'ready') child.postMessage({ type: 'start', dll, width: FRAME_WIDTH, height: FRAME_HEIGHT });
        else if (msg?.type === 'started') {
          clearTimeout(timer);
          if (msg.ok) resolve();
          else reject(new Error(msg.error || 'start failed'));
        }
      });
      child.on('exit', (code) => {
        clearTimeout(timer);
        reject(new Error(`helper exited (${code})`));
        if (this.child !== child) return;
        this.child = null;
        this.starting = null;
        if (this.state.active) this.log.warn(`[camera] helper exited (${code})`);
        this.state = { ...this.state, active: false };
      });
    });
    await started;
    this.state = { ...this.state, active: true, error: null };
    this.log.info(`[camera] "${CAMERA_NAME}" ready (${FRAME_WIDTH}x${FRAME_HEIGHT})`);
  }

  /** Gives the camera window a direct line to the helper for its frames. */
  attach(webContents) {
    if (!this.child || !this.state.active || webContents.isDestroyed()) return false;
    const { port1, port2 } = new MessageChannelMain();
    this.child.postMessage({ type: 'port' }, [port1]);
    webContents.postMessage('camera:port', { width: FRAME_WIDTH, height: FRAME_HEIGHT }, [port2]);
    return true;
  }

  #kill() {
    try {
      this.child?.kill();
    } catch {
      /* already gone */
    }
    this.child = null;
  }

  stop() {
    this.state = { ...this.state, active: false };
    this.#kill();
  }
}

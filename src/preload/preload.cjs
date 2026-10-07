// Bridge between the sandboxed renderers and the main process. Only the
// channels listed here can be used; the main process additionally checks
// which window is calling.

const { contextBridge, ipcRenderer, webUtils } = require('electron');

const INVOKE = new Set([
  'app:state', 'app:regenerate-password', 'app:copy', 'app:open-external', 'app:open-logs',
  'settings:update', 'settings:set-permanent-password', 'recents:remove', 'recents:forget-password',
  'connect:probe', 'connect:start', 'connect:cancel', 'session:end-incoming',
  'update:check', 'update:download', 'update:install',
  'session:init', 'viewer:end', 'viewer:reconnect', 'viewer:fullscreen', 'viewer:set-clipboard', 'viewer:set-prefs',
  'host:consent', 'host:displays', 'host:end',
  'files:begin', 'files:end', 'files:show',
  'e2e:cursor',
]);

const SEND = new Set([
  'session:ready', 'session:signal', 'session:failed', 'host:set-display', 'input:events', 'host:resize',
  'clipboard:remote', 'files:chunk', 'files:abort',
]);

const RECEIVE = new Set([
  'app:state', 'app:navigate', 'app:deeplink', 'connect:status',
  'session:signal', 'session:ended', 'session:state', 'clipboard:local',
]);

// Host window only: a direct line to the input helper process (remote mouse
// and keyboard). It does not go through the main process, whose thread can be
// stuck in a Windows modal loop (window being dragged, tray menu...).
let inputPort = null;
const inputListeners = new Set();
ipcRenderer.on('input:port', (event) => {
  try {
    inputPort?.close();
  } catch {
    /* ignore */
  }
  inputPort = event.ports[0] || null;
  if (!inputPort) return;
  inputPort.onmessage = (e) => {
    for (const cb of inputListeners) cb(e.data);
  };
  inputPort.start();
});

contextBridge.exposeInMainWorld('pairdesk', {
  platform: process.platform,
  invoke(channel, ...args) {
    if (!INVOKE.has(channel)) return Promise.reject(new Error(`blocked channel ${channel}`));
    return ipcRenderer.invoke(channel, ...args);
  },
  send(channel, ...args) {
    if (SEND.has(channel)) ipcRenderer.send(channel, ...args);
  },
  on(channel, callback) {
    if (!RECEIVE.has(channel)) return () => {};
    const listener = (_event, ...args) => callback(...args);
    ipcRenderer.on(channel, listener);
    return () => ipcRenderer.removeListener(channel, listener);
  },
  inputPort: {
    /** Returns false when the port is not there yet (use 'input:events'). */
    send(msg) {
      if (!inputPort) return false;
      inputPort.postMessage(msg);
      return true;
    },
    on(callback) {
      inputListeners.add(callback);
      return () => inputListeners.delete(callback);
    },
  },
  pathForFile(file) {
    try {
      return webUtils.getPathForFile(file);
    } catch {
      return '';
    }
  },
});

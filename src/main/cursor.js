// Host side (main process): polls the OS cursor shape and produces the
// {type:'cursor'} messages the host renderer forwards on the "control" channel.
//
//   {type:'cursor', shape:'text'}                         CSS keyword
//   {type:'cursor', shape:'none'}                         hidden (viewer shows a dot)
//   {type:'cursor', shape:'custom', id, hot:[x,y], scale, png?}
//       unnamed cursor bitmap (Linux: Chromium/Electron apps, animated
//       cursors). `png` (data URL) is sent once per id and per viewer; later
//       messages only carry the id.

import crypto from 'node:crypto';

const MAX_CUSTOM = 128; // Chromium ignores bigger CSS cursor images

async function createProbe(log) {
  try {
    if (process.platform === 'win32') return (await import('./input/cursor-win32.js')).createWin32CursorProbe();
    // On Wayland, XFixes only sees XWayland windows: better report nothing.
    if (process.platform === 'linux' && process.env.XDG_SESSION_TYPE !== 'wayland') {
      return (await import('./input/cursor-x11.js')).createX11CursorProbe();
    }
  } catch (err) {
    log?.warn?.(`[cursor] shape tracking unavailable: ${err.message}`);
  }
  return null;
}

function isBlank(bgra) {
  for (let i = 3; i < bgra.length; i += 4) if (bgra[i] !== 0) return false;
  return true;
}

/**
 * @param {object} o
 * @param {(bgra: Buffer, width: number, height: number) => string} o.encodePng  data URL encoder
 *        (main process: (b, width, height) => nativeImage.createFromBitmap(b, { width, height }).toDataURL())
 * @param {() => number} [o.scale]  host display scale factor (for HiDPI custom cursors)
 */
export async function createCursorTracker({ log, encodePng, scale = () => 1, intervalMs = 50, probe = null } = {}) {
  probe ??= await createProbe(log);
  if (!probe) return null;
  let timer = null;
  let lastKey = null;
  let sentImages = new Set();
  let send = null;

  function message(cur) {
    if (cur.shape) return { key: cur.shape, msg: { type: 'cursor', shape: cur.shape } };
    if (!cur.bgra || !encodePng || cur.width > MAX_CUSTOM || cur.height > MAX_CUSTOM) {
      return { key: 'default', msg: { type: 'cursor', shape: 'default' } }; // Windows app cursor, or too big
    }
    if (isBlank(cur.bgra)) return { key: 'none', msg: { type: 'cursor', shape: 'none' } };
    const id = crypto.createHash('sha1')
      .update(`${cur.width}x${cur.height}@${cur.xhot},${cur.yhot};`)
      .update(cur.bgra)
      .digest('base64url')
      .slice(0, 16);
    const msg = { type: 'cursor', shape: 'custom', id, hot: [cur.xhot, cur.yhot], scale: scale() };
    if (!sentImages.has(id)) {
      msg.png = encodePng(cur.bgra, cur.width, cur.height);
      sentImages.add(id);
    }
    return { key: `custom:${id}`, msg };
  }

  let lastRaw = null;

  function poll() {
    let cur;
    try {
      cur = probe.read({ image: 'unknown' });
    } catch {
      return;
    }
    if (!cur) return; // secure desktop / UAC: keep the previous shape
    // Fast path: same serial (X11) / handle (Windows) as last poll = no change.
    const raw = `${cur.serial ?? cur.handle}|${cur.shape}|${cur.hidden ?? ''}`;
    if (raw === lastRaw) return;
    lastRaw = raw;
    const { key, msg } = message(cur);
    if (key === lastKey) return;
    lastKey = key;
    send?.(msg);
  }

  return {
    /** Starts polling; `onMessage` receives each {type:'cursor'} change. */
    start(onMessage) {
      send = onMessage;
      this.resync();
      clearInterval(timer);
      timer = setInterval(poll, intervalMs);
      timer.unref?.();
      poll();
    },
    /** Forces the current shape (and its bitmap) to be sent again, e.g. after a viewer (re)connects. */
    resync() {
      lastKey = null;
      lastRaw = null;
      sentImages = new Set();
    },
    stop() {
      clearInterval(timer);
      timer = null;
      send = null;
    },
    close() {
      this.stop();
      probe.close?.();
    },
  };
}

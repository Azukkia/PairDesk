// Entry point of the "capture helper" utility process (forked by
// src/main/capture/native-capture.js): captures the shared display without
// the mouse cursor and sends the pictures straight to the host window through
// a MessagePort (32-bit BGRX/BGRA rows).
//
// Port protocol:
//   helper → window  {type:'frame', width, height, stride, format, data}
//                    {type:'error', message}  (capture stopped)
//   window → helper  {type:'ack'}             (frame handed to the encoder)
//                    {type:'fps', fps}        (new frame rate)
//                    {type:'stop'}

const parent = process.parentPort;
const log = (level, msg) => parent.postMessage({ type: 'log', level, msg: String(msg) });

process.on('uncaughtException', (err) => log('error', `[capture-helper] ${err.stack || err.message}`));
process.on('unhandledRejection', (err) => log('error', `[capture-helper] ${err?.stack || err}`));

// Two pictures on their way at most: when the window (encoder) is behind,
// pictures are skipped rather than queued, so the delay never builds up.
const MAX_IN_FLIGHT = 2;

async function openBackend(rect, options) {
  if (process.platform === 'win32') return (await import('../capture/capture-win32.js')).openWin32Capture(rect, options);
  if (process.platform === 'linux') return (await import('../capture/capture-x11.js')).openX11Capture(rect, options);
  throw new Error(`no native capture on ${process.platform}`);
}

let current = null; // { id, cap, port, timer, fps, inFlight, sentAt }

function stop(cur = current) {
  if (!cur || cur.stopped) return;
  cur.stopped = true;
  clearTimeout(cur.timer);
  try {
    cur.cap?.close();
  } catch (err) {
    log('warn', `[capture] close: ${err.message}`);
  }
  try {
    cur.port?.close();
  } catch {
    /* already closed */
  }
  if (current === cur) current = null;
}

function fail(cur, err) {
  log('warn', `[capture] stopped: ${err.message}`);
  try {
    cur.port.postMessage({ type: 'error', message: err.message });
  } catch {
    /* window gone */
  }
  stop(cur);
}

function tick(cur) {
  if (cur.stopped) return;
  const start = performance.now();
  // A lost acknowledgement must not stop the picture for good.
  if (cur.inFlight >= MAX_IN_FLIGHT && start - cur.sentAt > 1000) cur.inFlight = 0;
  if (cur.inFlight < MAX_IN_FLIGHT) {
    let frame;
    try {
      frame = cur.cap.grab();
    } catch (err) {
      fail(cur, err);
      return;
    }
    if (frame) {
      cur.port.postMessage({
        type: 'frame', width: cur.cap.width, height: cur.cap.height, stride: cur.cap.stride, format: cur.cap.format || 'BGRX', data: frame,
      });
      cur.inFlight++;
      cur.sentAt = performance.now();
      cur.frames++;
    }
  }
  const interval = 1000 / cur.fps;
  // Next picture one interval after this one started (no drift).
  cur.timer = setTimeout(() => tick(cur), Math.max(1, interval - (performance.now() - start)));
}

async function start({ id, rect, fps, dll }, port) {
  stop();
  const cur = { id, port, fps: Math.max(1, Math.min(60, Number(fps) || 30)), inFlight: 0, sentAt: 0, frames: 0, stopped: false };
  current = cur;
  port.on('message', (e) => {
    const msg = e.data;
    if (msg?.type === 'ack') cur.inFlight = Math.max(0, cur.inFlight - 1);
    else if (msg?.type === 'fps') cur.fps = Math.max(1, Math.min(60, Number(msg.fps) || cur.fps));
    else if (msg?.type === 'stop') stop(cur);
  });
  port.on('close', () => stop(cur));
  port.start();
  try {
    cur.cap = await openBackend(rect, { fps: cur.fps, dll });
  } catch (err) {
    parent.postMessage({ type: 'started', id, ok: false, error: err.message });
    stop(cur);
    return;
  }
  if (cur.stopped) {
    cur.cap.close();
    return;
  }
  log('info', `[capture] ${rect.width}x${rect.height} at (${rect.x}, ${rect.y}), ${cur.fps} fps → ${cur.cap.width}x${cur.cap.height}`);
  parent.postMessage({ type: 'started', id, ok: true, width: cur.cap.width, height: cur.cap.height });
  tick(cur);
}

parent.on('message', (e) => {
  const msg = e.data;
  switch (msg?.type) {
    case 'start':
      if (e.ports[0]) start(msg, e.ports[0]);
      break;
    case 'stop':
      stop();
      break;
    case 'stats':
      parent.postMessage({ type: 'stats', frames: current?.frames ?? 0, active: Boolean(current && !current.stopped) });
      break;
    default:
      break;
  }
});
parent.postMessage({ type: 'ready' });

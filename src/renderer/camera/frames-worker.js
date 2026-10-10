// Worker of the camera window: scales the phone's frames to the webcam
// size (whole picture or fill) and sends them, as RGBA, straight to the
// camera helper process through its MessagePort. Off the window's thread,
// and with a single copy of each frame.
//
// From the window:
//   {type:'port', port, format:{width, height}}   the line to the camera helper
//   {type:'frames', readable}                     the track's VideoFrame stream
//   {type:'bitmap', bitmap}                       one picture (fallback without a stream)
//   {type:'fill', fill}                           crop to fill instead of fitting
// To the window: {type:'ack', connected} (an application shows the webcam).

let port = null;
let format = null;
let fill = false;
let canvas = null;
let ctx = null;
// Up to two frames in flight (one handled by the helper, one on its way):
// when the helper is behind, frames are skipped rather than queued.
const MAX_IN_FLIGHT = 2;
let inFlight = 0;
let sentAt = 0;
let reader = null;

function push(source, sw, sh) {
  if (!port || !format || !sw || !sh) return;
  // A lost acknowledgement must not stop the webcam for good.
  if (inFlight >= MAX_IN_FLIGHT && performance.now() - sentAt < 1000) return;
  if (inFlight >= MAX_IN_FLIGHT) inFlight = 0;
  if (!canvas || canvas.width !== format.width || canvas.height !== format.height) {
    canvas = new OffscreenCanvas(format.width, format.height);
    ctx = canvas.getContext('2d', { willReadFrequently: true, alpha: false });
  }
  const scale = fill ? Math.max(format.width / sw, format.height / sh) : Math.min(format.width / sw, format.height / sh);
  const dw = sw * scale;
  const dh = sh * scale;
  ctx.fillStyle = '#000';
  ctx.fillRect(0, 0, format.width, format.height);
  ctx.drawImage(source, (format.width - dw) / 2, (format.height - dh) / 2, dw, dh);
  const data = ctx.getImageData(0, 0, format.width, format.height).data.buffer;
  // Copied, not transferred: a transferred buffer does not reach the
  // utility process (Electron's MessagePortMain).
  port.postMessage({ width: format.width, height: format.height, data });
  inFlight++;
  sentAt = performance.now();
}

async function read(readable) {
  const own = readable.getReader();
  reader?.cancel().catch(() => {});
  reader = own;
  for (;;) {
    const { value: frame, done } = await own.read().catch(() => ({ done: true }));
    if (done) break;
    if (reader !== own) {
      frame.close();
      break;
    }
    try {
      push(frame, frame.displayWidth, frame.displayHeight);
    } catch (err) {
      console.warn(`[camera] frame: ${err.message}`);
    } finally {
      frame.close();
    }
  }
}

self.onmessage = (e) => {
  const msg = e.data;
  switch (msg?.type) {
    case 'port':
      try {
        port?.close();
      } catch {
        /* ignore */
      }
      port = msg.port;
      format = msg.format;
      inFlight = 0;
      port.onmessage = (m) => {
        if (m.data?.type !== 'ack') return;
        inFlight = Math.max(0, inFlight - 1);
        self.postMessage({ type: 'ack', connected: Boolean(m.data.connected) });
      };
      port.start();
      break;
    case 'frames':
      read(msg.readable);
      break;
    case 'bitmap':
      try {
        push(msg.bitmap, msg.bitmap.width, msg.bitmap.height);
      } finally {
        msg.bitmap.close();
      }
      break;
    case 'fill':
      fill = Boolean(msg.fill);
      break;
    default:
      break;
  }
};

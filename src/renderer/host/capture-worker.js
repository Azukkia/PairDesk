// Worker of the host window: turns the pictures of the capture helper
// (src/main/helper/capture-helper.js) into video frames, off the window's
// thread, which relays the remote mouse and keyboard and must never wait
// behind a picture (copying a 4K picture takes tens of milliseconds).
//
// From the window:
//   {type:'start', port, writable}  the helper's port and the track's writable stream
//   {type:'fps', fps}               new frame rate (passed on to the helper)
//   {type:'stop'}
// To the window: {type:'started'} at the first picture, {type:'error', message}.

// Only changes are sent. After one, the last picture is given again to the
// encoder a few times so that it can refine it (screen content is first sent
// at a lower quality), then once a second.
const REFINE_DELAYS = [80, 100, 150, 200, 300, 400];
const IDLE_DELAY = 1000;

const now = () => Math.round(performance.now() * 1000); // µs

let port = null;
let writer = null;
let last = null;
let repeats = 0;
let timer = null;
let started = false;
let stopped = false;

// The generator closes each frame once it has been handed on.
const write = (frame) => writer.write(frame).catch(() => frame.close());

function scheduleRepeat() {
  clearTimeout(timer);
  const delay = repeats < REFINE_DELAYS.length ? REFINE_DELAYS[repeats] : IDLE_DELAY;
  timer = setTimeout(() => {
    if (stopped || !last) return;
    repeats++;
    write(new VideoFrame(last, { timestamp: now() }));
    scheduleRepeat();
  }, delay);
}

function stop() {
  if (stopped) return;
  stopped = true;
  clearTimeout(timer);
  last?.close();
  last = null;
  try {
    port?.postMessage({ type: 'stop' });
    port?.close();
  } catch {
    /* already closed */
  }
  writer?.close().catch(() => {});
}

function onFrame(msg) {
  let frame;
  try {
    frame = new VideoFrame(msg.data, {
      format: msg.format === 'BGRA' ? 'BGRA' : 'BGRX',
      codedWidth: msg.width,
      codedHeight: msg.height,
      timestamp: now(),
      layout: [{ offset: 0, stride: msg.stride || msg.width * 4 }],
    });
  } catch (err) {
    console.warn('[capture] bad picture', err.message);
    return;
  } finally {
    // The picture was copied: the helper may send the next one.
    port.postMessage({ type: 'ack' });
  }
  last?.close();
  last = frame.clone();
  repeats = 0;
  write(frame);
  scheduleRepeat();
  if (!started) {
    started = true;
    self.postMessage({ type: 'started' });
  }
}

self.onmessage = (e) => {
  const msg = e.data;
  switch (msg?.type) {
    case 'start':
      port = msg.port;
      writer = msg.writable.getWriter();
      port.onmessage = (m) => {
        if (stopped) return;
        if (m.data?.type === 'frame') onFrame(m.data);
        else if (m.data?.type === 'error') {
          stop();
          self.postMessage({ type: 'error', message: m.data.message });
        }
      };
      port.start();
      break;
    case 'fps':
      if (!stopped) port?.postMessage({ type: 'fps', fps: msg.fps });
      break;
    case 'stop':
      stop();
      break;
    default:
      break;
  }
};

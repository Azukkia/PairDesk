// Host window side of the screen capture without the mouse cursor
// (src/main/capture/native-capture.js): the capture helper sends raw
// pictures through a MessagePort, turned here into a video track.

const ports = new Map(); // token → port that arrived before it was awaited
const portWaiters = new Map();
window.addEventListener('message', (e) => {
  if (e.source !== window || e.data?.type !== 'pairdesk:capture-port' || !e.ports[0]) return;
  const waiter = portWaiters.get(e.data.token);
  if (waiter) waiter(e.ports[0]);
  else ports.set(e.data.token, e.ports[0]);
});

function waitPort(token, ms) {
  const early = ports.get(token);
  if (early) {
    ports.delete(token);
    return Promise.resolve(early);
  }
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      portWaiters.delete(token);
      reject(new Error('no capture port'));
    }, ms);
    portWaiters.set(token, (port) => {
      clearTimeout(timer);
      portWaiters.delete(token);
      resolve(port);
    });
  });
}

// Only changes are sent. After one, the last picture is given again to the
// encoder a few times so that it can refine it (screen content is first sent
// at a lower quality), then once a second.
const REFINE_DELAYS = [80, 100, 150, 200, 300, 400];
const IDLE_DELAY = 1000;

const now = () => Math.round(performance.now() * 1000); // µs

/**
 * Starts the native capture of `displayId`. Resolves { track, width, height }
 * once the first picture arrived; rejects when the native capture is not
 * available (the caller then uses Chromium's capture). `onFailure` is called
 * if the capture stops later on. Stopping the track stops the capture.
 */
export async function startNativeCapture(api, { displayId, fps, onFailure }) {
  if (typeof MediaStreamTrackGenerator !== 'function' || typeof VideoFrame !== 'function') throw new Error('no MediaStreamTrackGenerator');
  const res = await api.invoke('capture:start', { displayId, fps });
  if (!res?.ok) throw new Error(res?.reason || 'unavailable');
  const port = await waitPort(res.token, 3000);
  const generator = new MediaStreamTrackGenerator({ kind: 'video' });
  const writer = generator.writable.getWriter();
  let last = null; // the last picture, given again for refinement
  let repeats = 0;
  let repeatTimer = null;
  let stopped = false;
  let started = false;
  let resolveFirst;
  let rejectFirst;
  const first = new Promise((resolve, reject) => {
    resolveFirst = resolve;
    rejectFirst = reject;
  });

  // The generator closes each frame once it has been handed on.
  const write = (frame) => writer.write(frame).catch(() => frame.close());

  const scheduleRepeat = () => {
    clearTimeout(repeatTimer);
    const delay = repeats < REFINE_DELAYS.length ? REFINE_DELAYS[repeats] : IDLE_DELAY;
    repeatTimer = setTimeout(() => {
      if (stopped || !last) return;
      repeats++;
      write(new VideoFrame(last, { timestamp: now() }));
      scheduleRepeat();
    }, delay);
  };

  const cleanup = () => {
    if (stopped) return;
    stopped = true;
    clearTimeout(repeatTimer);
    last?.close();
    last = null;
    try {
      port.postMessage({ type: 'stop' });
      port.close();
    } catch {
      /* already closed */
    }
    writer.close().catch(() => {});
  };

  port.onmessage = (e) => {
    const msg = e.data;
    if (stopped) return;
    if (msg?.type === 'frame') {
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
        resolveFirst();
      }
    } else if (msg?.type === 'error') {
      cleanup();
      if (started) onFailure?.(new Error(msg.message));
      else rejectFirst(new Error(msg.message));
    }
  };
  port.start();

  const timer = setTimeout(() => rejectFirst(new Error('no picture')), 4000);
  try {
    await first;
  } catch (err) {
    cleanup();
    generator.stop();
    throw err;
  } finally {
    clearTimeout(timer);
  }

  const track = generator;
  const stopTrack = track.stop.bind(track);
  track.stop = () => {
    cleanup();
    stopTrack();
  };
  track.pairdeskNative = true; // no cursor in this picture
  track.pairdeskSetFps = (value) => {
    if (!stopped) port.postMessage({ type: 'fps', fps: value });
  };
  return { track, width: res.width, height: res.height };
}

/**
 * The computer's sound alone (Windows): Chromium only captures it together
 * with a picture, which is stopped at once.
 */
export async function captureDesktopAudio(sourceId) {
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: { mandatory: { chromeMediaSource: 'desktop' } },
    video: { mandatory: { chromeMediaSource: 'desktop', chromeMediaSourceId: sourceId, maxWidth: 320, maxHeight: 240, maxFrameRate: 1 } },
  });
  for (const tr of stream.getVideoTracks()) tr.stop();
  return stream.getAudioTracks()[0] || null;
}

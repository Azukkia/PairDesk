// Host window side of the screen capture without the mouse cursor
// (src/main/capture/native-capture.js): the capture helper sends raw
// pictures through a MessagePort; a worker (capture-worker.js) turns them
// into the frames of a video track.

const ports = new Map(); // token → port that arrived before it was awaited
const portWaiters = new Map();
const endListeners = new Map(); // token → called when the helper went away
window.addEventListener('message', (e) => {
  if (e.source !== window) return;
  if (e.data?.type === 'pairdesk:capture-ended') {
    endListeners.get(e.data.token)?.();
    return;
  }
  if (e.data?.type !== 'pairdesk:capture-port' || !e.ports[0]) return;
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
  const worker = new Worker(new URL('./capture-worker.js', import.meta.url));
  let state = 'starting'; // 'running', 'stopped'
  let firstDone;
  const first = new Promise((resolve, reject) => {
    firstDone = (err) => (err ? reject(err) : resolve());
  });

  const cleanup = () => {
    if (state === 'stopped') return;
    state = 'stopped';
    endListeners.delete(res.token);
    worker.postMessage({ type: 'stop' });
    // Time to close the port and the stream, then the worker goes.
    setTimeout(() => worker.terminate(), 500);
  };
  const failed = (err) => {
    const wasRunning = state === 'running';
    cleanup();
    if (wasRunning) onFailure?.(err);
    else firstDone(err);
  };
  worker.onmessage = (e) => {
    if (e.data?.type === 'started' && state === 'starting') {
      state = 'running';
      firstDone();
    } else if (e.data?.type === 'error') {
      failed(new Error(e.data.message));
    }
  };
  worker.onerror = (e) => failed(new Error(e.message || 'capture worker failed'));
  // The capture helper process went away (crash): no 'error' comes from it.
  endListeners.set(res.token, () => failed(new Error('capture helper exited')));
  worker.postMessage({ type: 'start', port, writable: generator.writable }, [port, generator.writable]);

  const timer = setTimeout(() => failed(new Error('no picture')), 4000);
  try {
    await first;
  } catch (err) {
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
    if (state === 'running') worker.postMessage({ type: 'fps', fps: value });
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

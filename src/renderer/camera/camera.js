// Camera window: a phone streams its camera to this computer (camera
// session, docs/PROTOCOL.md 6). The picture is shown here and, on Windows,
// handed frame by frame to the "PairDesk Camera" virtual webcam through
// the camera helper process. Frames are read from the track itself, not
// from what is on screen: the webcam keeps working while this window is
// minimized during a video call.

import { api, h, icon, t, setLanguage } from '../common/ui.js';
import { RtcSession } from '../common/rtc.js';
import { formatId } from '../shared/protocol.js';

const app = document.getElementById('app');
const port = window.pairdesk.cameraPort;
const ui = {};
const timers = {};
const st = {
  facing: null,
  fill: readPref('fill') === '1', // crop to fill the webcam picture instead of fitting it
  vcam: null, // { supported, active, error, name }
  inUse: false, // an application shows the webcam
  attached: false,
};
let init = null;
let rtc = null;
let track = null;
let inflight = 0; // when the frame being handled by the camera helper was sent

port.on((msg) => {
  if (msg?.type !== 'ack') return;
  inflight = 0;
  if (st.inUse !== Boolean(msg.connected)) {
    st.inUse = Boolean(msg.connected);
    renderVcam();
  }
});

function readPref(key) {
  try {
    return localStorage.getItem(`camera.${key}`);
  } catch {
    return null;
  }
}

function writePref(key, value) {
  try {
    localStorage.setItem(`camera.${key}`, value);
  } catch {
    /* not persisted */
  }
}

// ───────────────────────────── UI ─────────────────────────────

function build() {
  const name = init.peer.name || formatId(init.peer.id);
  document.title = `${t('camera.title', { name })} — PairDesk`;
  ui.status = h('span', { class: 'badge' }, t('camera.connecting'));
  ui.facing = h('span', { class: 'badge accent', hidden: true });
  ui.switchBtn = h('button', {
    class: 'btn small', id: 'camera-switch', title: t('camera.switch'), disabled: true,
    onclick: () => rtc?.sendControl({ type: 'camera-switch' }),
  }, icon('swap', 'sm'), t('camera.switch'));
  ui.fillBtn = h('button', { class: 'btn small', id: 'camera-fill', onclick: () => setFill(!st.fill) });
  ui.endBtn = h('button', { class: 'btn small danger', id: 'camera-end', onclick: end }, icon('power', 'sm'), t('camera.end'));
  ui.video = h('video', { id: 'camera-video', autoplay: true, muted: true, playsInline: true });
  ui.overlay = h('div', { class: 'overlay' }, icon('camera'), h('span', { class: 'text' }, t('camera.waiting')));
  ui.vcam = h('div', { class: 'vcam', id: 'camera-vcam' });
  app.append(
    h('header', { class: 'cam-top' },
      h('div', { class: 'peer' }, icon('camera'), h('b', {}, name), h('span', { class: 'id' }, formatId(init.peer.id)), ui.status, ui.facing),
      h('div', { class: 'tools' }, ui.switchBtn, ui.fillBtn, ui.endBtn)),
    h('main', { class: 'cam-stage' }, ui.video, ui.overlay),
    ui.vcam,
  );
  setFill(st.fill);
  renderVcam();
}

function setStatus(text, kind = '') {
  ui.status.textContent = text;
  ui.status.className = `badge ${kind}`.trim();
}

function setFill(fill) {
  st.fill = fill;
  writePref('fill', fill ? '1' : '0');
  ui.fillBtn.replaceChildren(icon('fit', 'sm'), fill ? t('camera.fill') : t('camera.fit'));
  ui.fillBtn.title = t('camera.framing');
}

function renderVcam() {
  const v = st.vcam;
  let state;
  let text;
  if (!v || !v.supported) {
    state = 'unsupported';
    text = t('camera.vcamUnsupported');
  } else if (v.error) {
    state = 'error';
    text = t('camera.vcamError', { error: v.error });
  } else if (!v.active) {
    state = 'starting';
    text = t('camera.vcamStarting', { name: v.name });
  } else {
    state = st.inUse ? 'in-use' : 'ready';
    text = st.inUse ? t('camera.vcamInUse', { name: v.name }) : t('camera.vcamReady', { name: v.name });
  }
  const look = { unsupported: ['', 'info'], error: ['error', 'alert'], starting: ['busy', 'info'], ready: ['ok', 'check'], 'in-use': ['ok', 'check'] }[state];
  // The framing only matters for the webcam picture.
  ui.fillBtn.hidden = state === 'unsupported' || state === 'error';
  ui.vcam.className = `vcam ${look[0]}`.trim();
  ui.vcam.dataset.state = state;
  ui.vcam.replaceChildren(icon(look[1], 'sm'), h('span', {}, text));
}

// ───────────────────────────── session ─────────────────────────────

function start() {
  rtc = new RtcSession({ role: 'host', iceServers: init.iceServers, camera: true });
  rtc.addEventListener('track', ({ detail: event }) => {
    if (event.track.kind !== 'video') return;
    track = event.track;
    ui.video.srcObject = new MediaStream([track]);
    ui.video.play().catch(() => {});
    feedWebcam(track);
  });
  rtc.addEventListener('control', ({ detail: msg }) => onControl(msg));
  rtc.addEventListener('control-open', () => {
    ui.switchBtn.disabled = false;
  });
  rtc.addEventListener('state', ({ detail: state }) => onConnectionState(state));
  ui.video.addEventListener('loadeddata', () => {
    ui.overlay.hidden = true;
  });
  rtc.ready();
  timers.connect = setTimeout(() => {
    if (rtc.pc.connectionState !== 'connected') api.send('session:failed', 'ice-timeout');
  }, 60_000);
}

function onConnectionState(state) {
  clearTimeout(timers.restart);
  if (state === 'connected') {
    clearTimeout(timers.connect);
    clearTimeout(timers.fail);
    setStatus(t('camera.live'), 'success');
  } else if (state === 'disconnected') {
    setStatus(t('camera.reconnecting'));
    timers.restart = setTimeout(() => rtc.restartIce(), 4000);
  } else if (state === 'failed') {
    setStatus(t('camera.reconnecting'));
    rtc.restartIce();
    clearTimeout(timers.fail);
    timers.fail = setTimeout(() => {
      if (rtc.pc.connectionState !== 'connected') api.send('session:failed', 'connection-lost');
    }, 30_000);
  }
}

function onControl(msg) {
  switch (msg?.type) {
    case 'camera-info':
      st.facing = msg.facing === 'front' || msg.facing === 'back' ? msg.facing : null;
      ui.facing.hidden = !st.facing;
      ui.facing.dataset.facing = st.facing || '';
      ui.facing.textContent = st.facing ? t(`camera.${st.facing}`) : '';
      // The phone's own view of its front camera is a mirror: same here (the webcam is not mirrored).
      ui.video.classList.toggle('mirror', st.facing === 'front');
      break;
    case 'bye':
      api.invoke('camera:end');
      break;
    default:
      break;
  }
}

function end() {
  rtc?.sendControl({ type: 'bye' });
  setTimeout(() => api.invoke('camera:end'), 50);
}

// ───────────────────────────── virtual webcam ─────────────────────────────

/**
 * Reads the frames of `videoTrack` and sends them, scaled to the webcam
 * size (fit or fill), to the camera helper. One frame in flight at a time:
 * when the helper is busy, frames are skipped rather than queued.
 */
async function feedWebcam(videoTrack) {
  if (!st.attached) {
    st.attached = true;
    st.vcam = await api.invoke('camera:attach');
    renderVcam();
  }
  if (!st.vcam?.active) return;
  let canvas = null;
  let ctx = null;
  const push = (source, sw, sh) => {
    const fmt = port.format();
    if (!fmt || !sw || !sh) return;
    // A lost acknowledgement must not stop the webcam for good.
    if (inflight && performance.now() - inflight < 1000) return;
    if (!canvas || canvas.width !== fmt.width || canvas.height !== fmt.height) {
      canvas = new OffscreenCanvas(fmt.width, fmt.height);
      ctx = canvas.getContext('2d', { willReadFrequently: true, alpha: false });
    }
    const scale = st.fill ? Math.max(fmt.width / sw, fmt.height / sh) : Math.min(fmt.width / sw, fmt.height / sh);
    const dw = sw * scale;
    const dh = sh * scale;
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, fmt.width, fmt.height);
    ctx.drawImage(source, (fmt.width - dw) / 2, (fmt.height - dh) / 2, dw, dh);
    const data = ctx.getImageData(0, 0, fmt.width, fmt.height).data.buffer;
    if (port.sendFrame(fmt.width, fmt.height, data)) inflight = performance.now();
  };

  if (typeof MediaStreamTrackProcessor === 'function') {
    const reader = new MediaStreamTrackProcessor({ track: videoTrack }).readable.getReader();
    for (;;) {
      const { value: frame, done } = await reader.read().catch(() => ({ done: true }));
      if (done) break;
      if (videoTrack !== track) {
        frame.close();
        reader.cancel().catch(() => {});
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
    return;
  }
  // Fallback: sample the video element 30 times a second.
  clearInterval(timers.sample);
  timers.sample = setInterval(() => {
    if (videoTrack.readyState === 'ended' || videoTrack !== track) clearInterval(timers.sample);
    else push(ui.video, ui.video.videoWidth, ui.video.videoHeight);
  }, 33);
}

// ───────────────────────────── boot ─────────────────────────────

init = await api.invoke('session:init');
setLanguage(init.lang);
st.vcam = init.vcam || null;
build();
start();

// Host panel (controlled side): asks for consent when required, captures the
// screen, streams it over WebRTC and relays remote input to the main process.
// It stays visible for the whole session so the local user always knows that
// someone is connected and can end the session in one click.

import { api, h, clear, icon, t, setLanguage, formatDuration, initials } from '../common/ui.js';
import { RtcSession, TransferList, ChatView } from '../common/rtc.js';
import { AdaptiveScaler, CpuWatch, rankCodecs } from '../common/adaptive.js';
import { formatId, QUALITY_PRESETS, versionAtLeast } from '../shared/protocol.js';

const app = document.getElementById('app');
let init = null;
let rtc = null;
let perms = {};
let displays = [];
let current = null;
let videoSender = null;
let videoTransceiver = null;
let audioTrack = null;
let quality = 'balanced';
let captureFps = 30;
let codecFirst = 'vp9';
const scaler = new AdaptiveScaler();
const cpuWatch = new CpuWatch();
let transfers = null;
let chat = null;
let chatOpen = false;
let collapsed = false;
const timers = {};
const ui = {};

const peerLabel = () => init.peer.name || formatId(init.peer.id);

// ───────────────────────────── layout ─────────────────────────────

function resize() {
  const base = app.querySelector('.host-head').offsetHeight + ui.body.offsetHeight + ui.transfers.offsetHeight + 2;
  const height = collapsed ? 44 : chatOpen ? Math.max(430, base + 240) : base;
  api.send('host:resize', height);
}

function build() {
  ui.title = h('span', { class: 'title' });
  ui.timer = h('span', { class: 'timer' });
  ui.live = h('span', { class: 'live', hidden: true });
  ui.collapse = h('button', { class: 'icon-btn', title: '', onclick: () => { collapsed = !collapsed; document.body.classList.toggle('collapsed', collapsed); clear(ui.collapse, icon(collapsed ? 'maximize' : 'minimize', 'sm')); resize(); } }, icon('minimize', 'sm'));
  ui.body = h('div', { class: 'host-body' });
  chat = new ChatView({ onSend: (text) => rtc?.sendControl({ type: 'chat', text }) });
  ui.chat = h('div', { class: 'host-chat', hidden: true }, chat.list, chat.form);
  ui.fileInput = h('input', { type: 'file', multiple: true, hidden: true, onchange: () => { sendFiles([...ui.fileInput.files]); ui.fileInput.value = ''; } });
  ui.transfers = h('div', { class: 'host-transfers' });
  clear(app,
    h('header', { class: 'host-head' }, h('img', { src: '../assets/logo.svg', alt: '' }), ui.live, ui.title, ui.timer, ui.collapse),
    ui.body, ui.transfers, ui.chat, ui.fileInput);
  transfers = new TransferList({ container: ui.transfers, onChange: () => requestAnimationFrame(resize) });
}

function whoBlock(sub) {
  return h('div', { class: 'who' },
    h('span', { class: 'avatar' }, initials(init.peer.name) || icon('user', 'sm')),
    h('div', { class: 'text' }, h('b', { id: 'host-peer' }, sub), h('div', { class: 'sub' }, `ID ${formatId(init.peer.id)}`)));
}

// ───────────────────────────── consent ─────────────────────────────

function showPending() {
  ui.title.textContent = t('host.requestTitle');
  let remaining = 45;
  const countdown = h('div', { class: 'countdown' }, t('host.autoDecline', { s: remaining }));
  clearInterval(timers.countdown);
  timers.countdown = setInterval(() => {
    remaining = Math.max(0, remaining - 1);
    countdown.textContent = t('host.autoDecline', { s: remaining });
  }, 1000);
  clear(ui.body,
    whoBlock(t('host.requestBody', { name: peerLabel(), id: formatId(init.peer.id) })),
    countdown,
    h('div', { class: 'host-actions' },
      h('button', { class: 'btn', id: 'host-decline', onclick: () => api.invoke('host:consent', false) }, t('host.decline')),
      h('button', { class: 'btn primary', id: 'host-accept', onclick: () => accept() }, t('host.accept'))));
  requestAnimationFrame(resize);
}

async function accept() {
  clearInterval(timers.countdown);
  await api.invoke('host:consent', true);
}

// ───────────────────────────── session ─────────────────────────────

function showActive(status) {
  ui.title.textContent = t('host.activeTitle');
  ui.live.hidden = false;
  ui.status = h('div', { class: 'host-status', id: 'host-status' }, status);
  clear(ui.body,
    whoBlock(t(perms.control ? 'host.controlledBy' : 'host.viewedBy', { name: peerLabel() })),
    ui.status,
    ui.error = h('div', { class: 'host-error', hidden: true }),
    h('div', { class: 'host-actions' },
      h('button', { class: 'btn', id: 'host-chat', onclick: () => toggleChat() }, icon('message', 'sm'), t('host.chat')),
      h('button', { class: 'btn', id: 'host-file', onclick: () => ui.fileInput.click() }, icon('upload', 'sm'), t('host.sendFile')),
      h('button', { class: 'btn danger', id: 'host-end', onclick: () => end() }, icon('power', 'sm'), t('host.end'))));
  requestAnimationFrame(resize);
}

function setStatus(text) {
  if (ui.status) ui.status.textContent = text;
}

function toggleChat(open = !chatOpen) {
  chatOpen = open;
  ui.chat.hidden = !open;
  if (open) setTimeout(() => chat.input.focus(), 30);
  resize();
}

async function capture(display, withAudio, fps = captureFps) {
  const video = {
    mandatory: {
      chromeMediaSource: 'desktop',
      chromeMediaSourceId: display.sourceId,
      maxWidth: display.width,
      maxHeight: display.height,
      maxFrameRate: fps,
    },
  };
  if (withAudio) {
    try {
      return await navigator.mediaDevices.getUserMedia({ audio: { mandatory: { chromeMediaSource: 'desktop' } }, video });
    } catch (err) {
      console.warn('audio capture unavailable', err.message);
    }
  }
  return navigator.mediaDevices.getUserMedia({ audio: false, video });
}

// With 'motion' content WebRTC lowers the resolution by itself; with screen
// content ('detail') PairDesk does it (AdaptiveScaler), else slow links freeze.
const appScaling = () => QUALITY_PRESETS[quality].contentHint !== 'motion';

function currentScale() {
  const preset = QUALITY_PRESETS[quality];
  const large = current.width * current.height > 2560 * 1600;
  return preset.scale[large ? 1 : 0] * (appScaling() ? scaler.factor : 1);
}

async function applyQuality(mode) {
  const previous = quality;
  quality = QUALITY_PRESETS[mode] ? mode : 'balanced';
  if (!videoSender?.track) return;
  const preset = QUALITY_PRESETS[quality];
  if (quality !== previous) {
    scaler.reset(performance.now());
    if (preset.maxFramerate !== captureFps) {
      captureFps = preset.maxFramerate;
      await recapture(); // the capture rate is fixed when the track starts
    }
  }
  videoSender.track.contentHint = preset.contentHint;
  const params = videoSender.getParameters();
  if (!params.encodings || !params.encodings.length) params.encodings = [{}];
  Object.assign(params.encodings[0], {
    maxBitrate: preset.maxBitrate,
    maxFramerate: preset.maxFramerate,
    scaleResolutionDownBy: currentScale(),
  });
  params.degradationPreference = preset.degradation;
  try {
    await videoSender.setParameters(params);
  } catch (err) {
    console.warn('setParameters failed', err.message);
  }
}

function applyCodecs() {
  try {
    const caps = RTCRtpSender.getCapabilities('video')?.codecs;
    if (caps && videoTransceiver?.setCodecPreferences) videoTransceiver.setCodecPreferences(rankCodecs(caps, codecFirst));
  } catch (err) {
    console.warn('setCodecPreferences failed', err.message);
  }
}

// Once a second: adapt the resolution to the uplink, fall back to VP8 on hosts
// too slow for VP9, and tell the viewer where the delay comes from.
async function monitor() {
  if (!rtc || rtc.closed || rtc.pc.connectionState !== 'connected') return;
  const s = await rtc.senderStats();
  if (appScaling() && scaler.update({ now: performance.now(), bandwidth: s.bandwidth, pacerMs: s.pacerMs }) != null) {
    console.info(`[host] adaptive scale ${scaler.factor} (bandwidth ${Math.round((s.bandwidth || 0) / 1000)} kbps, queue ${Math.round(s.pacerMs || 0)} ms)`);
    applyQuality(quality);
  }
  if (codecFirst === 'vp9' && /vp9/i.test(s.codec || '') && cpuWatch.update({ limitation: s.limitation, fps: s.fps })) {
    console.info('[host] VP9 is too slow on this computer: switching to VP8');
    codecFirst = 'vp8';
    applyCodecs();
    rtc.renegotiate();
  }
  rtc.sendControl({
    type: 'host-stats',
    encodeMs: s.encodeMs,
    pacerMs: s.pacerMs,
    fps: s.fps,
    codec: s.codec,
    encoder: s.encoder,
    limitation: s.limitation,
    bandwidth: s.bandwidth,
    scale: currentScale(),
  });
}

function sendInfo() {
  rtc?.sendControl({
    type: 'info',
    displays: displays.map((d) => ({ id: d.displayId, width: d.width, height: d.height, primary: d.primary })),
    current: current?.displayId,
    perms,
  });
}

// Captures run one at a time, each for the display wanted when it starts: a
// quality change made during a display switch must not bring back the old
// display (the viewer would see one screen while controlling the other).
let captureChain = Promise.resolve();
let captureTarget = null; // display the latest switch is heading to

function recapture() {
  const run = captureChain.then(async () => {
    const stream = await capture(captureTarget || current, false);
    const track = stream.getVideoTracks()[0];
    track.contentHint = QUALITY_PRESETS[quality].contentHint;
    const old = videoSender.track;
    await videoSender.replaceTrack(track);
    old?.stop();
  });
  captureChain = run.catch(() => {});
  return run;
}

async function switchDisplay(displayId) {
  const target = displays.find((d) => d.displayId === displayId);
  if (!target || target === (captureTarget || current) || !videoSender) return;
  captureTarget = target;
  try {
    await recapture();
    if (captureTarget !== target) return; // a newer switch finishes the job
    current = target;
    captureTarget = null;
    api.send('host:set-display', current.displayId);
    await applyQuality(quality);
    rtc.sendControl({ type: 'display-changed', current: current.displayId });
  } catch (err) {
    if (captureTarget === target) captureTarget = null;
    console.error('display switch failed', err);
  }
}

let hostingStarted = false;
async function startHosting() {
  if (hostingStarted) return;
  hostingStarted = true;
  clearInterval(timers.countdown);
  showActive(t('host.connecting'));
  displays = init.displays || [];
  current = displays.find((d) => d.primary) || displays[0];
  if (!current) {
    showCaptureError('no display');
    return;
  }
  api.send('host:set-display', current.displayId);

  // Capture first so that the first offer already contains the screen track
  // (a single negotiation round).
  let stream;
  try {
    console.info(`[host] capturing ${current.sourceId} (${current.width}x${current.height})`);
    stream = await capture(current, perms.audio);
    console.info(`[host] capture started: ${stream.getTracks().map((tr) => tr.kind).join(', ')}`);
  } catch (err) {
    showCaptureError(err.message || err.name);
    api.send('session:failed', `capture: ${err.name}`);
    return;
  }

  rtc = new RtcSession({ role: 'host', iceServers: init.iceServers });
  rtc.acceptFile = async ({ name, size }) => {
    if (!perms.files) return { ok: false };
    const res = await api.invoke('files:begin', { name, size });
    if (!res.ok) return res;
    return { ...res, view: transfers.add(res.name, size, 'in') };
  };
  rtc.addEventListener('input', ({ detail }) => {
    if (perms.control) api.send('input:events', detail);
  });
  rtc.addEventListener('control-open', () => {
    sendInfo();
    api.send('host:cursor-resync');
  });
  rtc.addEventListener('control', ({ detail: msg }) => onControl(msg));
  rtc.addEventListener('state', ({ detail: state }) => onConnectionState(state));

  // Audio gets its own stream: in a shared stream the viewer would delay the
  // picture to keep lip sync with the audio buffer (measured +60 ms). Viewers
  // older than 1.1 can only display a single stream.
  const separateStreams = versionAtLeast(init.peer.version, '1.1.0');
  for (const track of stream.getTracks()) {
    const sender = rtc.pc.addTrack(track, separateStreams ? new MediaStream([track]) : stream);
    if (track.kind === 'video') videoSender = sender;
    else audioTrack = track;
  }
  videoTransceiver = rtc.pc.getTransceivers().find((tr) => tr.sender === videoSender) || null;
  applyCodecs();
  scaler.reset(performance.now());
  await applyQuality(quality);
  rtc.ready();
  timers.monitor = setInterval(monitor, 1000);
  timers.connect = setTimeout(() => {
    if (rtc.pc.connectionState !== 'connected') api.send('session:failed', 'ice-timeout');
  }, 60_000);
}

function showCaptureError(message) {
  if (!ui.error) return;
  ui.error.hidden = false;
  ui.error.textContent = t('host.captureError', { error: message });
  resize();
}

let startedAt = null;
function onConnectionState(state) {
  clearTimeout(timers.restart);
  if (state === 'connected') {
    clearTimeout(timers.connect);
    clearTimeout(timers.fail);
    if (!startedAt) {
      startedAt = Date.now();
      timers.clock = setInterval(() => { ui.timer.textContent = formatDuration(Date.now() - startedAt); }, 1000);
      scaler.reset(performance.now());
    }
    applyQuality(quality);
    rtc.stats().then((s) => setStatus(s.relayed ? t('viewer.relayed') : t('viewer.direct')));
  } else if (state === 'disconnected') {
    setStatus(t('viewer.reconnecting'));
    timers.restart = setTimeout(() => rtc.restartIce(), 4000);
  } else if (state === 'failed') {
    setStatus(t('viewer.reconnecting'));
    rtc.restartIce();
    clearTimeout(timers.fail);
    timers.fail = setTimeout(() => {
      if (rtc.pc.connectionState !== 'connected') api.send('session:failed', 'connection-lost');
    }, 30_000);
  }
}

function onControl(msg) {
  switch (msg.type) {
    case 'hello':
      if (msg.quality) applyQuality(msg.quality);
      break;
    case 'quality':
      applyQuality(msg.mode);
      break;
    case 'select-display':
      switchDisplay(String(msg.displayId));
      break;
    case 'chat':
      chat.add(String(msg.text || '').slice(0, 2000), false, peerLabel());
      if (!chatOpen) toggleChat(true);
      break;
    case 'clipboard':
      if (perms.clipboard && typeof msg.text === 'string') api.send('clipboard:remote', msg.text);
      break;
    case 'bye':
      api.invoke('host:end');
      break;
    default:
      break;
  }
}

function sendFiles(files) {
  if (!rtc || rtc.pc.connectionState !== 'connected') return;
  for (const file of files) rtc.sendFile(file, transfers.add(file.name, file.size, 'out'));
}

function end() {
  rtc?.sendControl({ type: 'bye' });
  setTimeout(() => api.invoke('host:end'), 50);
}

// ───────────────────────────── boot ─────────────────────────────

api.on('session:state', ({ state, perms: p }) => {
  if (state === 'active') {
    perms = p || perms;
    startHosting();
  }
});
api.on('clipboard:local', (text) => {
  if (perms.clipboard && rtc?.pc.connectionState === 'connected') rtc.sendControl({ type: 'clipboard', text });
});
// Shape of the local mouse cursor, drawn by the viewer with zero delay.
api.on('host:cursor', (msg) => {
  if (perms.control) rtc?.sendControl(msg);
});

init = await api.invoke('session:init');
setLanguage(init.lang);
perms = init.perms || {};
build();
ui.collapse.title = t('common.hide');
if (init.state === 'pending') showPending();
else startHosting();

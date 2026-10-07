// Viewer window (controller side): shows the partner's screen and forwards
// mouse and keyboard input.

import { api, h, clear, icon, t, setLanguage, toast, showMenu } from '../common/ui.js';
import { RtcSession, TransferList, ChatView } from '../common/rtc.js';
import { KEYMAP, KEY_COMBOS } from '../shared/keymap.js';
import { formatId } from '../shared/protocol.js';

const app = document.getElementById('app');
let init = null;
let rtc = null;
const ui = {};
const st = {
  connected: false,
  hadVideo: false,
  ended: null,
  displays: [],
  current: null,
  quality: 'balanced',
  scale: 'fit',
  clipboard: true,
  muted: false,
  hasAudio: false,
  fullscreen: false,
  chatOpen: false,
  unread: 0,
};
const timers = {};
const pressedKeys = new Set();
const pressedButtons = new Set();
let transfers = null;
let chat = null;

const canControl = () => st.connected && !st.ended && Boolean(init?.caps?.control);

// ───────────────────────────── UI ─────────────────────────────

function toolButton(name, title, onClick, id) {
  const btn = h('button', { class: 'icon-btn', title, 'aria-label': title, id, onclick: (e) => onClick(e.currentTarget) }, icon(name));
  return btn;
}

function buildUI() {
  ui.dot = h('span', { class: 'dot connecting' });
  ui.name = h('b');
  ui.id = h('span', { class: 'id' });
  ui.stats = h('span', { class: 'stats' });
  ui.viewOnly = h('span', { class: 'badge warning', title: t('viewer.viewOnlyHelp'), hidden: true }, icon('eye', 'sm'), t('viewer.viewOnly'));

  ui.monitorBtn = h('button', { class: 'tool-label', id: 'monitor-button', hidden: true, onclick: (e) => monitorMenu(e.currentTarget) }, icon('monitor', 'sm'), h('span'), icon('chevronDown', 'sm'));
  ui.scaleBtn = toolButton('fit', t('viewer.original'), () => setScale(st.scale === 'fit' ? 'original' : 'fit'), 'scale-button');
  ui.qualityBtn = toolButton('gauge', t('viewer.quality'), (b) => qualityMenu(b), 'quality-button');
  ui.keysBtn = toolButton('keyboard', t('viewer.actions'), (b) => keysMenu(b), 'keys-button');
  ui.clipBtn = toolButton('clipboard', t('viewer.clipboard'), () => setClipboard(!st.clipboard), 'clipboard-button');
  ui.fileBtn = toolButton('upload', t('viewer.sendFile'), () => ui.fileInput.click(), 'file-button');
  ui.chatBtn = toolButton('message', t('viewer.chat'), () => toggleChat(), 'chat-button');
  ui.soundBtn = toolButton('volume', t('viewer.mute'), () => setMuted(!st.muted), 'sound-button');
  ui.fsBtn = toolButton('maximize', t('viewer.fullscreen'), () => toggleFullscreen(), 'fullscreen-button');
  ui.endBtn = h('button', { class: 'btn danger small', id: 'disconnect-button', onclick: () => disconnect() }, icon('power', 'sm'), t('viewer.disconnect'));
  ui.fileInput = h('input', { type: 'file', multiple: true, hidden: true, onchange: () => { sendFiles([...ui.fileInput.files]); ui.fileInput.value = ''; } });

  ui.topbar = h('header', { class: 'topbar' },
    h('div', { class: 'peer' }, ui.dot, ui.name, ui.id, ui.viewOnly, ui.stats),
    h('div', { class: 'tools' }, ui.monitorBtn, ui.scaleBtn, ui.qualityBtn, ui.keysBtn, h('span', { class: 'sep' }),
      ui.clipBtn, ui.fileBtn, ui.chatBtn, ui.soundBtn, h('span', { class: 'sep' }), ui.fsBtn, ui.endBtn, ui.fileInput));

  ui.video = h('video', { id: 'remote-screen', autoplay: true, playsInline: true, muted: true });
  ui.stage = h('main', { class: 'stage fit', tabindex: '0', id: 'stage' }, ui.video);
  ui.overlay = h('div', { class: 'overlay' });
  ui.stage.append(ui.overlay);

  chat = new ChatView({ onSend: (text) => rtc?.sendControl({ type: 'chat', text }) });
  ui.chat = h('aside', { class: 'chat-panel', hidden: true },
    h('header', null, t('chat.title'), h('button', { class: 'icon-btn', onclick: () => toggleChat(false) }, icon('x', 'sm'))),
    chat.list, chat.form);
  ui.drop = h('div', { class: 'drop-hint', hidden: true }, t('viewer.dropHint'));
  ui.hotzone = h('div', { class: 'hotzone' });

  clear(app, ui.topbar, ui.stage, ui.chat);
  document.body.append(ui.drop, ui.hotzone);
  transfers = new TransferList();
  attachInput();
  attachDragDrop();
  attachToolbarAutoHide();
}

function refreshToolbar() {
  const caps = init.caps || {};
  ui.name.textContent = init.peer.name || formatId(init.peer.id);
  ui.id.textContent = `ID ${formatId(init.peer.id)}`;
  const live = !st.ended;
  ui.viewOnly.hidden = Boolean(caps.control) || !live;
  ui.scaleBtn.hidden = !live;
  ui.qualityBtn.hidden = !live;
  ui.keysBtn.hidden = !caps.control || !live;
  ui.clipBtn.hidden = !caps.clipboard || !live;
  ui.clipBtn.classList.toggle('active', st.clipboard);
  ui.fileBtn.hidden = !caps.files || !live;
  ui.soundBtn.hidden = !st.hasAudio || !live;
  clear(ui.soundBtn, icon(st.muted ? 'volumeX' : 'volume'));
  ui.soundBtn.title = st.muted ? t('viewer.unmute') : t('viewer.mute');
  clear(ui.scaleBtn, icon(st.scale === 'fit' ? 'fit' : 'maximize'));
  ui.scaleBtn.title = st.scale === 'fit' ? t('viewer.original') : t('viewer.fit');
  clear(ui.fsBtn, icon(st.fullscreen ? 'minimize' : 'maximize'));
  ui.fsBtn.title = st.fullscreen ? t('viewer.exitFullscreen') : t('viewer.fullscreen');
  ui.monitorBtn.hidden = st.displays.length < 2 || !live;
  const idx = st.displays.findIndex((d) => d.id === st.current);
  ui.monitorBtn.children[1].textContent = t('viewer.monitorN', { n: idx >= 0 ? idx + 1 : 1 });
  ui.chatBtn.querySelector('.count-badge')?.remove();
  if (st.unread) ui.chatBtn.append(h('span', { class: 'count-badge' }, String(st.unread)));
  ui.chatBtn.classList.toggle('active', st.chatOpen);
  ui.stage.classList.toggle('control', canControl());
  clear(ui.endBtn, icon(st.ended ? 'x' : 'power', 'sm'), st.ended ? t('common.close') : t('viewer.disconnect'));
  ui.dot.className = `dot ${st.ended ? 'offline' : st.connected ? 'online' : 'connecting'}`;
}

function setOverlay(kind, extra = {}) {
  if (!kind) {
    ui.overlay.hidden = true;
    return;
  }
  ui.overlay.hidden = false;
  const busy = ['connecting', 'negotiating', 'waitingVideo', 'reconnecting'].includes(kind);
  const text = {
    connecting: t('viewer.connecting', { name: init.peer.name || formatId(init.peer.id) }),
    negotiating: t('viewer.negotiating'),
    waitingVideo: t('viewer.waitingVideo'),
    reconnecting: t('viewer.reconnecting'),
    failed: t('viewer.failed'),
    ended: extra.reason === 'peer' ? t('viewer.endedByPeer') : extra.reason === 'failed' ? t('viewer.failed') : t('viewer.ended'),
  }[kind];
  const actions = [];
  if (kind === 'ended' || kind === 'failed') {
    actions.push(h('button', { class: 'btn', onclick: () => api.invoke('viewer:end') }, t('common.close')));
    actions.push(h('button', { class: 'btn primary', id: 'reconnect-button', onclick: () => reconnect() }, icon('refresh', 'sm'), t('viewer.reconnect')));
  } else {
    actions.push(h('button', { class: 'btn danger', onclick: () => disconnect() }, t('viewer.disconnect')));
  }
  clear(ui.overlay, h('div', { class: 'box', id: 'viewer-overlay', dataset: { kind } },
    busy ? h('span', { class: 'spinner lg' }) : icon(kind === 'ended' ? 'power' : 'alert', 'bad'),
    h('div', null, text),
    extra.error ? h('div', { class: 'error-text' }, extra.error) : null,
    h('div', { class: 'actions' }, actions)));
}

// ───────────────────────────── session ─────────────────────────────

function startSession() {
  st.connected = false;
  st.hadVideo = false;
  st.ended = null;
  st.hasAudio = false;
  rtc = new RtcSession({ role: 'viewer', iceServers: init.iceServers });
  rtc.acceptFile = async ({ name, size }) => {
    const res = await api.invoke('files:begin', { name, size });
    if (!res.ok) return res;
    return { ...res, view: transfers.add(res.name, size, 'in') };
  };
  rtc.addEventListener('track', ({ detail: event }) => {
    const stream = event.streams[0] || new MediaStream([event.track]);
    if (ui.video.srcObject !== stream) ui.video.srcObject = stream;
    if (event.track.kind === 'audio') {
      st.hasAudio = true;
      ui.video.muted = st.muted;
    }
    ui.video.play().catch(() => {});
    refreshToolbar();
  });
  rtc.addEventListener('state', ({ detail: state }) => onConnectionState(state));
  rtc.addEventListener('control-open', () => {
    rtc.sendControl({ type: 'hello', quality: st.quality, clipboard: st.clipboard });
  });
  rtc.addEventListener('control-close', () => {
    if (!st.ended) onConnectionState('disconnected');
  });
  rtc.addEventListener('control', ({ detail: msg }) => onControl(msg));
  setOverlay('negotiating');
  clearTimeout(timers.connect);
  timers.connect = setTimeout(() => {
    if (!st.connected && !st.ended) fail('ice-timeout');
  }, 45_000);
  rtc.ready();
  refreshToolbar();
}

function onConnectionState(state) {
  if (st.ended) return;
  clearTimeout(timers.lost);
  if (state === 'connected') {
    st.connected = true;
    clearTimeout(timers.connect);
    clearTimeout(timers.fail);
    setOverlay(st.hadVideo ? null : 'waitingVideo');
    startStats();
  } else if (state === 'disconnected' || state === 'failed') {
    timers.lost = setTimeout(() => {
      if (st.ended) return;
      st.connected = false;
      releaseAll();
      setOverlay('reconnecting');
      refreshToolbar();
    }, state === 'failed' ? 0 : 1500);
    clearTimeout(timers.fail);
    timers.fail = setTimeout(() => {
      if (!st.connected && !st.ended) fail('connection-lost');
    }, 30_000);
  }
  refreshToolbar();
}

function fail(reason) {
  api.send('session:failed', reason);
  showEnded('failed');
}

function showEnded(reason) {
  if (st.ended) return;
  st.ended = reason;
  st.connected = false;
  releaseAll();
  clearTimeout(timers.connect);
  clearTimeout(timers.fail);
  clearInterval(timers.stats);
  rtc?.close();
  ui.stats.textContent = '';
  if (st.fullscreen) toggleFullscreen();
  setOverlay('ended', { reason });
  refreshToolbar();
}

async function reconnect() {
  setOverlay('connecting');
  const res = await api.invoke('viewer:reconnect');
  if (!res.ok) {
    setOverlay('ended', { reason: st.ended, error: t(`error.${res.error}`, { seconds: res.retryIn ?? '?' }) });
    return;
  }
  init = await api.invoke('session:init');
  startSession();
}

function disconnect() {
  rtc?.sendControl({ type: 'bye' });
  releaseAll();
  setTimeout(() => api.invoke('viewer:end'), 50);
}

function onControl(msg) {
  switch (msg.type) {
    case 'info':
      st.displays = Array.isArray(msg.displays) ? msg.displays : [];
      st.current = msg.current;
      if (msg.perms) init.caps = { ...init.caps, ...msg.perms };
      refreshToolbar();
      break;
    case 'display-changed':
      st.current = msg.current;
      refreshToolbar();
      break;
    case 'chat':
      chat.add(String(msg.text || '').slice(0, 2000), false, init.peer.name || formatId(init.peer.id));
      if (!st.chatOpen) {
        st.unread++;
        toast(`${init.peer.name || formatId(init.peer.id)} : ${String(msg.text).slice(0, 80)}`, { type: 'info', action: { label: t('viewer.chat'), run: () => toggleChat(true) } });
        refreshToolbar();
      }
      break;
    case 'clipboard':
      if (st.clipboard && typeof msg.text === 'string') api.send('clipboard:remote', msg.text);
      break;
    case 'bye':
      showEnded('peer');
      break;
    default:
      break;
  }
}

function startStats() {
  clearInterval(timers.stats);
  timers.stats = setInterval(async () => {
    if (!rtc || st.ended) return;
    const s = await rtc.stats();
    const parts = [];
    if (s.relayed != null) parts.push(s.relayed ? t('viewer.relayed') : t('viewer.direct'));
    if (s.rtt != null) parts.push(`${s.rtt} ms`);
    if (s.fps != null) parts.push(`${Math.round(s.fps)} i/s`);
    if (s.bitrate != null) parts.push(`${(s.bitrate / 1e6).toFixed(1)} Mb/s`);
    if (s.width) parts.push(`${s.width}×${s.height}`);
    ui.stats.textContent = parts.join(' · ');
  }, 1000);
}

// ───────────────────────────── toolbar actions ─────────────────────────────

function monitorMenu(anchor) {
  showMenu(anchor, [{ title: t('viewer.monitor') }, ...st.displays.map((d, i) => ({
    label: `${t('viewer.monitorN', { n: i + 1 })}${d.primary ? ` (${t('viewer.primary')})` : ''} — ${d.width}×${d.height}`,
    checked: d.id === st.current,
    onClick: () => rtc?.sendControl({ type: 'select-display', displayId: d.id }),
  }))]);
}

function qualityMenu(anchor) {
  showMenu(anchor, [{ title: t('viewer.quality') }, ...['speed', 'balanced', 'quality'].map((mode) => ({
    label: t(`quality.${mode}`),
    checked: st.quality === mode,
    onClick: () => {
      st.quality = mode;
      rtc?.sendControl({ type: 'quality', mode });
      api.invoke('viewer:set-prefs', { quality: mode });
    },
  }))]);
}

function keysMenu(anchor) {
  const combos = ['ctrlAltDel', 'ctrlShiftEsc', 'altTab', 'altF4', 'win', 'winR', 'winD', 'winE', 'winL', 'printScreen'];
  showMenu(anchor, [{ title: t('viewer.actions') }, ...combos.map((name) => ({
    label: t(`keys.${name}`),
    onClick: () => sendCombo(KEY_COMBOS[name]),
  }))]);
}

function sendCombo(codes) {
  if (!canControl()) return;
  const events = [...codes.map((c) => ['k', c, 1]), ...[...codes].reverse().map((c) => ['k', c, 0])];
  flushInput(events);
  ui.stage.focus();
}

function setScale(scale) {
  st.scale = scale;
  ui.stage.classList.toggle('fit', scale === 'fit');
  ui.stage.classList.toggle('original', scale === 'original');
  applyVideoSize();
  api.invoke('viewer:set-prefs', { scale });
  refreshToolbar();
}

function applyVideoSize() {
  const v = ui.video;
  if (st.scale === 'original' && v.videoWidth) {
    v.style.width = `${v.videoWidth / devicePixelRatio}px`;
    v.style.height = `${v.videoHeight / devicePixelRatio}px`;
  } else {
    v.style.width = '';
    v.style.height = '';
  }
}

function setClipboard(enabled) {
  st.clipboard = enabled;
  api.invoke('viewer:set-clipboard', enabled);
  refreshToolbar();
}

function setMuted(muted) {
  st.muted = muted;
  ui.video.muted = muted;
  refreshToolbar();
}

async function toggleFullscreen() {
  st.fullscreen = await api.invoke('viewer:fullscreen', !st.fullscreen);
  document.body.classList.toggle('fullscreen', st.fullscreen);
  document.body.classList.remove('toolbar-hidden');
  if (st.fullscreen) scheduleToolbarHide();
  refreshToolbar();
}

function toggleChat(open = !st.chatOpen) {
  st.chatOpen = open;
  ui.chat.hidden = !open;
  if (open) {
    st.unread = 0;
    setTimeout(() => chat.input.focus(), 30);
  } else {
    ui.stage.focus();
  }
  refreshToolbar();
}

function sendFiles(files) {
  if (!rtc || st.ended || !st.connected) return;
  if (!init.caps.files) {
    toast(t('viewer.filesDisabled'), { type: 'error' });
    return;
  }
  for (const file of files) rtc.sendFile(file, transfers.add(file.name, file.size, 'out'));
}

// ───────────────────────────── input capture ─────────────────────────────

let inputQueue = [];
let pendingMove = null;
let moveScheduled = false;

function flushInput(extra = []) {
  if (pendingMove) {
    inputQueue.push(pendingMove);
    pendingMove = null;
  }
  inputQueue.push(...extra);
  if (inputQueue.length) rtc?.sendInput(inputQueue);
  inputQueue = [];
}

function queueMove(x, y) {
  pendingMove = ['m', x, y];
  if (!moveScheduled) {
    // Coalesce mouse moves (~120 Hz). A timer is used instead of
    // requestAnimationFrame, which stops when the window is occluded.
    moveScheduled = true;
    setTimeout(() => {
      moveScheduled = false;
      flushInput();
    }, 8);
  }
}

const round4 = (v) => Math.round(v * 10000) / 10000;

/** Maps a mouse position to normalized remote coordinates (null if outside). */
function mapPoint(e, clamp = false) {
  const v = ui.video;
  if (!v.videoWidth) return null;
  const r = v.getBoundingClientRect();
  let dw = r.width;
  let dh = r.height;
  let ox = r.left;
  let oy = r.top;
  if (st.scale === 'fit') {
    const s = Math.min(r.width / v.videoWidth, r.height / v.videoHeight);
    dw = v.videoWidth * s;
    dh = v.videoHeight * s;
    ox = r.left + (r.width - dw) / 2;
    oy = r.top + (r.height - dh) / 2;
  }
  let x = (e.clientX - ox) / dw;
  let y = (e.clientY - oy) / dh;
  if (x < 0 || x > 1 || y < 0 || y > 1) {
    if (!clamp) return null;
    x = Math.min(1, Math.max(0, x));
    y = Math.min(1, Math.max(0, y));
  }
  return [round4(x), round4(y)];
}

function releaseAll() {
  if (pressedKeys.size || pressedButtons.size) flushInput([['r']]);
  pressedKeys.clear();
  pressedButtons.clear();
}

function isTypingTarget(target) {
  return target instanceof HTMLElement && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable);
}

function attachInput() {
  const wheelFactor = window.pairdesk.platform === 'linux' ? 2.25 : 1.2;

  ui.stage.addEventListener('mousedown', (e) => {
    if (!canControl() || e.target.closest('.overlay')) return;
    e.preventDefault();
    if (isTypingTarget(document.activeElement)) document.activeElement.blur();
    ui.stage.focus({ preventScroll: true });
    const p = mapPoint(e);
    if (!p) return;
    pressedButtons.add(e.button);
    flushInput([['d', e.button, p[0], p[1]]]);
  });
  window.addEventListener('mouseup', (e) => {
    if (!pressedButtons.has(e.button)) return;
    e.preventDefault();
    pressedButtons.delete(e.button);
    const p = mapPoint(e, true);
    flushInput([p ? ['u', e.button, p[0], p[1]] : ['u', e.button]]);
  });
  window.addEventListener('mousemove', (e) => {
    if (!canControl()) return;
    if (!pressedButtons.size && !ui.stage.contains(e.target)) return;
    if (e.target.closest?.('.overlay')) return;
    const p = mapPoint(e, pressedButtons.size > 0);
    if (p) queueMove(p[0], p[1]);
  });
  ui.stage.addEventListener('wheel', (e) => {
    if (!canControl()) return;
    e.preventDefault();
    const unit = e.deltaMode === 1 ? 40 : e.deltaMode === 2 ? 800 : wheelFactor;
    const dx = Math.round(e.deltaX * unit);
    const dy = Math.round(e.deltaY * unit);
    if (dx || dy) flushInput([['w', dx, dy]]);
  }, { passive: false });
  ui.stage.addEventListener('contextmenu', (e) => e.preventDefault());
  // Prevent "back"/"forward" mouse buttons from doing anything locally.
  window.addEventListener('auxclick', (e) => e.preventDefault());

  const onKey = (down) => (e) => {
    if (!canControl() || isTypingTarget(e.target)) return;
    if (!KEYMAP[e.code]) return;
    e.preventDefault();
    e.stopPropagation();
    if (down) pressedKeys.add(e.code);
    else if (!pressedKeys.delete(e.code)) return;
    flushInput([['k', e.code, down ? 1 : 0]]);
  };
  window.addEventListener('keydown', onKey(true), true);
  window.addEventListener('keyup', onKey(false), true);
  window.addEventListener('blur', releaseAll);
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) releaseAll();
  });

  ui.video.addEventListener('resize', () => {
    applyVideoSize();
  });
  ui.video.addEventListener('loadeddata', () => {
    if (!st.hadVideo) {
      st.hadVideo = true;
      if (st.connected) setOverlay(null);
      ui.stage.focus({ preventScroll: true });
    }
    applyVideoSize();
  });
}

function attachDragDrop() {
  let depth = 0;
  window.addEventListener('dragenter', (e) => {
    e.preventDefault();
    if (++depth === 1 && init?.caps?.files && st.connected) ui.drop.hidden = false;
  });
  window.addEventListener('dragleave', () => {
    if (--depth <= 0) {
      depth = 0;
      ui.drop.hidden = true;
    }
  });
  window.addEventListener('dragover', (e) => e.preventDefault());
  window.addEventListener('drop', (e) => {
    e.preventDefault();
    depth = 0;
    ui.drop.hidden = true;
    const files = [...(e.dataTransfer?.files || [])];
    if (files.length) sendFiles(files);
  });
}

function scheduleToolbarHide() {
  clearTimeout(timers.toolbar);
  timers.toolbar = setTimeout(() => {
    if (st.fullscreen && !ui.topbar.matches(':hover') && !document.querySelector('.menu')) document.body.classList.add('toolbar-hidden');
  }, 2200);
}

function attachToolbarAutoHide() {
  const reveal = () => {
    if (!st.fullscreen) return;
    document.body.classList.remove('toolbar-hidden');
    scheduleToolbarHide();
  };
  ui.hotzone.addEventListener('mouseenter', reveal);
  ui.topbar.addEventListener('mouseenter', reveal);
  ui.topbar.addEventListener('mouseleave', scheduleToolbarHide);
}

// ───────────────────────────── boot ─────────────────────────────

api.on('session:ended', ({ reason }) => showEnded(reason));
api.on('clipboard:local', (text) => {
  if (st.clipboard && st.connected && init?.caps?.clipboard) rtc?.sendControl({ type: 'clipboard', text });
});

init = await api.invoke('session:init');
setLanguage(init.lang);
st.quality = init.prefs.quality;
st.scale = init.prefs.scale;
st.clipboard = init.prefs.clipboard;
buildUI();
setScale(st.scale);
if (init.ended) showEnded(init.ended);
else startSession();

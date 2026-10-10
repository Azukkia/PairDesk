// Main window: identity (ID / password), connection to a partner, recent
// partners, settings, about & updates.

import { api, h, clear, icon, t, setLanguage, toast, showMenu, relativeTime, initials } from '../common/ui.js';
import { formatId, normalizeId, isValidId, MIN_PERMANENT_PASSWORD_LENGTH } from '../shared/protocol.js';

const root = document.getElementById('app');
let state = null;
let page = 'home';
let lang = null;
const els = {};
let pageView = null;

// ───────────────────────────── layout ─────────────────────────────

function build() {
  const nav = h('nav', { class: 'nav' });
  const items = [['home', 'home', 'nav.home'], ['recents', 'clock', 'nav.recents'], ['settings', 'sliders', 'nav.settings'], ['about', 'info', 'nav.about']];
  els.navButtons = {};
  for (const [id, ic, label] of items) {
    const btn = h('button', { onclick: () => navigate(id), dataset: { page: id } }, icon(ic), h('span', null, t(label)));
    els.navButtons[id] = btn;
    nav.append(btn);
  }
  els.banners = h('div', { class: 'stack' });
  els.page = h('div', { class: 'stack', style: { gap: '18px' } });
  els.statusDot = h('span', { class: 'dot' });
  els.statusText = h('span');
  els.statusDetail = h('span', { class: 'detail' });

  clear(root, h('div', { class: 'shell' },
    h('aside', { class: 'sidebar' },
      h('div', { class: 'brand' }, h('img', { src: '../assets/logo.svg', alt: '' }), h('div', null, h('b', null, 'PairDesk'), h('small', null, t('app.tagline')))),
      nav,
      h('div', { class: 'sidebar-foot' },
        h('div', { class: 'secure' }, icon('shield', 'sm'), h('span', null, 'E2E · CPace · DTLS')),
        h('div', null, `v${state.version}`))),
    h('main', { class: 'content' }, els.banners, els.page),
    h('footer', { class: 'statusbar' }, els.statusDot, els.statusText, els.statusDetail)));
  renderPage();
  updateChrome();
}

function navigate(id) {
  page = id;
  renderPage();
  updateChrome();
}

function renderPage() {
  for (const [id, btn] of Object.entries(els.navButtons)) btn.classList.toggle('active', id === page);
  const views = { home: HomePage, recents: RecentsPage, settings: SettingsPage, about: AboutPage };
  pageView = views[page]();
  clear(els.page, pageView.el);
  pageView.update?.();
}

function updateChrome() {
  if (!state) return;
  // Status bar
  const n = state.network;
  els.statusDot.className = `dot ${n.status}`;
  let text = t(n.status === 'online' ? 'status.online' : n.status === 'error' ? 'status.error' : n.status === 'offline' ? 'status.offline' : 'status.connecting');
  if (n.idTaken) text = t('status.idTaken');
  els.statusText.textContent = text;
  els.statusDetail.textContent = n.kind === 'server' ? t('status.viaServer', { url: n.url }) : t('status.viaRelays', { n: n.relays ?? 0 });
  // Sidebar badge when an update is waiting
  const updateReady = ['available', 'downloading', 'downloaded'].includes(state.update.status);
  els.navButtons.about.querySelector('.nav-badge')?.remove();
  if (updateReady) els.navButtons.about.append(h('span', { class: 'nav-badge' }));
  renderBanners();
}

// ───────────────────────────── banners ─────────────────────────────

function renderBanners() {
  const list = [];
  const incoming = state.sessions.incoming;
  if (incoming) {
    const key = incoming.state === 'pending' ? 'home.pendingIncoming' : incoming.control ? 'home.activeIncoming' : 'home.activeViewing';
    list.push(h('div', { class: 'banner warning' }, icon('monitor', 'lg'),
      h('div', { class: 'text' }, h('b', null, t(key, { name: incoming.peerName })), h('span', { class: 'small muted' }, `ID ${formatId(incoming.peerId)}`)),
      h('button', { class: 'btn danger', onclick: () => api.invoke('session:end-incoming') }, icon('power', 'sm'), t('home.endSession'))));
  }
  const banner = updateBanner(true);
  if (banner) list.push(banner);
  clear(els.banners, list);
}

function updateBanner(compact) {
  const u = state.update;
  const actions = [];
  let text = null;
  let progress = null;
  if (u.status === 'available') {
    text = t('update.available', { version: u.version });
    actions.push(h('button', { class: 'btn solid', onclick: () => api.invoke('update:download') }, icon('download', 'sm'), t('update.download')));
  } else if (u.status === 'downloading') {
    text = t('update.downloading', { progress: u.progress });
    progress = h('div', { class: 'progress' }, h('div', { style: { width: `${u.progress}%` } }));
  } else if (u.status === 'downloaded') {
    text = t('update.downloaded', { version: u.version });
    actions.push(h('button', { class: 'btn solid', onclick: () => api.invoke('update:install') }, icon('refresh', 'sm'), t('update.install')));
  } else {
    return null;
  }
  if (compact && page === 'about') return null;
  return h('div', { class: 'banner accent' }, icon('update', 'lg'),
    h('div', { class: 'text' }, h('b', null, text), progress,
      u.notes && u.status === 'available' ? h('a', { style: { color: '#fff', fontSize: '12.5px' }, onclick: () => navigate('about') }, t('update.notes')) : null),
    actions);
}

// ───────────────────────────── home ─────────────────────────────

/** QR code (rows of '0'/'1') as SVG squares. */
function qrSvg(rows, size = 116) {
  const NS = 'http://www.w3.org/2000/svg';
  const n = rows.length + 4; // quiet zone
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', `0 0 ${n} ${n}`);
  svg.setAttribute('width', String(size));
  svg.setAttribute('height', String(size));
  svg.setAttribute('class', 'qr');
  svg.setAttribute('shape-rendering', 'crispEdges');
  const bg = document.createElementNS(NS, 'rect');
  bg.setAttribute('width', String(n));
  bg.setAttribute('height', String(n));
  bg.setAttribute('fill', '#fff');
  const path = document.createElementNS(NS, 'path');
  let d = '';
  rows.forEach((row, y) => {
    for (let x = 0; x < row.length; x++) if (row[x] === '1') d += `M${x + 2} ${y + 2}h1v1h-1z`;
  });
  path.setAttribute('d', d);
  path.setAttribute('fill', '#0e1322');
  svg.append(bg, path);
  return svg;
}

/** The Android app: control this computer from the phone, show the phone here, use it as a webcam. */
function PhoneCard() {
  const qrBox = h('div', { class: 'qr-box' });
  let url = '';
  const card = h('section', { class: 'card phone-card', id: 'phone-card', hidden: true },
    qrBox,
    h('div', { class: 'phone-text' },
      h('h3', null, icon('phone', 'sm'), t('home.phone.title')),
      h('p', { class: 'desc' }, t('home.phone.desc')),
      h('p', { class: 'hint' }, t('home.phone.hint')),
      h('div', { class: 'row' },
        h('button', { class: 'btn small', onclick: () => url && copy(url) }, icon('copy', 'sm'), t('home.phone.copy')),
        h('button', { class: 'btn small ghost', onclick: () => url && api.invoke('app:open-external', url) }, icon('download', 'sm'), t('home.phone.download')))));
  // Shown once a release carries the APK.
  api.invoke('app:android').then((res) => {
    url = res.url;
    clear(qrBox, qrSvg(res.qr));
    card.hidden = !res.available;
  }).catch(() => {});
  return card;
}

function HomePage() {
  const idValue = h('span', { class: 'value', id: 'my-id' });
  const pwValue = h('span', { class: 'value password', id: 'my-password' });
  const unattended = h('div', { class: 'unattended' });
  const disabledNote = h('div', { class: 'banner warning', hidden: true }, icon('alert'), h('span', { class: 'text small' }, t('home.incomingDisabled')));
  const inputNote = h('div', { class: 'banner warning', hidden: true }, icon('alert'), h('span', { class: 'text small' }));

  const incoming = h('section', { class: 'card panel' },
    h('div', { class: 'panel-head' }, h('div', { class: 'bubble' }, icon('shield', 'lg')), h('h2', null, t('home.incoming.title'))),
    h('p', { class: 'desc' }, t('home.incoming.desc')),
    h('div', { class: 'credential' },
      h('label', { class: 'label' }, t('home.yourId')),
      h('div', { class: 'value-row' }, idValue,
        h('button', { class: 'icon-btn', title: t('home.copyId'), onclick: () => copy(state.id) }, icon('copy')))),
    h('div', { class: 'credential' },
      h('label', { class: 'label' }, t('home.password')),
      h('div', { class: 'value-row' }, pwValue,
        h('button', { class: 'icon-btn', id: 'regen-password', title: t('home.newPassword'), onclick: () => api.invoke('app:regenerate-password') }, icon('refresh')),
        h('button', { class: 'icon-btn', title: t('home.copyPassword'), onclick: () => state.password && copy(state.password) }, icon('copy')))),
    unattended, disabledNote, inputNote);

  const idInput = h('input', {
    class: 'input partner-input', id: 'partner-id', inputmode: 'numeric', autocomplete: 'off', spellcheck: 'false',
    placeholder: '000 000 000', maxlength: 11,
    oninput: () => {
      const digits = normalizeId(idInput.value).slice(0, 9);
      const pos = idInput.selectionStart;
      const before = idInput.value.length;
      idInput.value = formatId(digits);
      const delta = idInput.value.length - before;
      idInput.setSelectionRange(pos + delta, pos + delta);
      errorText.textContent = '';
    },
  });
  const errorText = h('div', { class: 'error-text' });
  const recentBox = h('div', { class: 'recent-mini' });
  const outgoing = h('section', { class: 'card panel' },
    h('div', { class: 'panel-head' }, h('div', { class: 'bubble' }, icon('monitor', 'lg')), h('h2', null, t('home.outgoing.title'))),
    h('p', { class: 'desc' }, t('home.outgoing.desc')),
    h('form', {
      class: 'stack',
      onsubmit: (e) => {
        e.preventDefault();
        const id = normalizeId(idInput.value);
        if (!isValidId(id)) {
          errorText.textContent = t('home.invalidId');
          idInput.focus();
          return;
        }
        if (id === state.id) {
          errorText.textContent = t('error.self');
          return;
        }
        connectTo(id);
      },
    },
    h('label', { class: 'label', for: 'partner-id' }, t('home.partnerId')),
    idInput, errorText,
    h('button', { class: 'btn primary big connect-btn', type: 'submit', id: 'connect-button' }, t('home.connect'), icon('arrowRight'))),
    recentBox);

  const how = h('section', { class: 'card how', hidden: true },
    h('h3', null, t('home.howTitle')),
    h('div', { class: 'how-steps' }, [1, 2, 3].map((n) => h('div', { class: 'how-step' }, h('span', { class: 'num' }, String(n)), h('span', null, t(`home.step${n}`))))));
  const el = h('div', { class: 'stack', style: { gap: '18px' } }, h('div', { class: 'home-grid' }, incoming, outgoing), PhoneCard(), how);
  setTimeout(() => idInput.focus(), 50);

  return {
    el,
    setPartner(id) {
      idInput.value = formatId(id);
    },
    update() {
      idValue.textContent = formatId(state.id);
      const s = state.settings;
      if (state.password) {
        pwValue.textContent = state.password;
        pwValue.className = 'value password';
      } else {
        pwValue.textContent = s.useRandomPassword ? '······' : t('home.noRandomPassword');
        pwValue.className = s.useRandomPassword ? 'value password' : 'value placeholder';
      }
      unattended.className = `unattended${s.hasPermanentPassword ? '' : ' off'}`;
      clear(unattended, icon(s.hasPermanentPassword ? 'check' : 'key', 'sm'),
        s.hasPermanentPassword ? t('home.unattendedOn') : h('a', { onclick: () => navigate('settings') }, t('home.unattendedOff')));
      disabledNote.hidden = s.allowIncoming;
      inputNote.hidden = state.input.available;
      if (!state.input.available) inputNote.querySelector('.text').textContent = t('home.inputUnavailable', { reason: state.input.reason || '' });

      how.hidden = state.recents.length > 0;
      const recents = state.recents.slice(0, 4);
      clear(recentBox, recents.length ? [h('h3', null, t('home.recents')), recents.map(recentChip)] : null);
    },
  };
}

function recentChip(r) {
  return h('button', { class: 'recent-chip', onclick: () => connectTo(r.id) },
    h('span', { class: 'avatar' }, initials(r.name) || icon('monitor', 'sm')),
    h('span', { class: 'who' }, h('b', null, r.name || formatId(r.id)), h('span', null, formatId(r.id))),
    r.hasPassword ? icon('key', 'sm') : null,
    icon('arrowRight', 'sm'));
}

async function copy(text) {
  await api.invoke('app:copy', text);
  toast(t('common.copied'));
}

// ───────────────────────────── connect dialog ─────────────────────────────

let activeDialog = null;

function connectTo(id) {
  activeDialog?.close();
  activeDialog = new ConnectDialog(id);
}

class ConnectDialog {
  constructor(id) {
    this.id = id;
    this.cancelled = false;
    this.body = h('div');
    this.el = h('div', { class: 'modal-backdrop' },
      h('div', { class: 'modal connect-modal', role: 'dialog' },
        h('h3', null, t('home.connect')),
        h('div', { class: 'peer' }, h('span', { class: 'avatar' }, icon('monitor', 'sm')), h('b', null, formatId(id))),
        this.body));
    this.el.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') this.cancel();
    });
    document.body.append(this.el);
    this.offStatus = api.on('connect:status', ({ id: target, status }) => {
      if (target === this.id && this.statusEl) this.statusEl.textContent = t(`connect.${status === 'waiting-approval' ? 'waitingApproval' : status}`);
    });
    this.probe();
  }

  close() {
    this.offStatus?.();
    this.el.remove();
    if (activeDialog === this) activeDialog = null;
  }

  cancel() {
    this.cancelled = true;
    api.invoke('connect:cancel');
    this.close();
  }

  showProgress(text) {
    this.statusEl = h('span', null, text);
    clear(this.body,
      h('div', { class: 'status-line' }, h('span', { class: 'spinner' }), this.statusEl),
      h('div', { class: 'actions' }, h('button', { class: 'btn', onclick: () => this.cancel() }, t('common.cancel'))));
  }

  showError(code, retryIn) {
    const message = t(`error.${code}`, { seconds: retryIn ?? '?' });
    const known = message !== `error.${code}`;
    const retry = ['offline', 'network', 'timeout', 'busy', 'locked'].includes(code);
    clear(this.body,
      h('div', { class: 'error-box', id: 'connect-error' }, icon('alert'), h('span', null, known ? message : t('error.unknown', { code }))),
      h('div', { class: 'actions' },
        h('button', { class: 'btn', onclick: () => this.close() }, t('common.close')),
        retry ? h('button', { class: 'btn primary', onclick: () => this.probe() }, t('common.retry')) : null));
  }

  showPassword(errorCode) {
    const input = h('input', { class: 'input', type: 'password', id: 'connect-password', autocomplete: 'off', spellcheck: 'false' });
    const toggle = h('button', {
      class: 'icon-btn', type: 'button', title: t('common.show'),
      onclick: () => {
        input.type = input.type === 'password' ? 'text' : 'password';
        clear(toggle, icon(input.type === 'password' ? 'eye' : 'eyeOff'));
      },
    }, icon('eye'));
    const remember = h('input', { type: 'checkbox', id: 'connect-remember' });
    const error = h('div', { class: 'error-text' }, errorCode ? t(`error.${errorCode}`) : '');
    clear(this.body,
      h('form', {
        class: 'stack',
        onsubmit: (e) => {
          e.preventDefault();
          if (!input.value.trim()) return;
          this.attempt(input.value, remember.checked);
        },
      },
      h('label', { class: 'label', for: 'connect-password' }, t('connect.passwordPrompt', { id: formatId(this.id) })),
      h('div', { class: 'password-row' }, input, toggle),
      h('label', { class: 'checkbox small' }, remember, t('connect.remember')),
      error,
      h('div', { class: 'actions', style: { marginTop: '8px' } },
        h('button', { class: 'btn', type: 'button', onclick: () => this.cancel() }, t('common.cancel')),
        h('button', { class: 'btn primary', type: 'submit', id: 'connect-submit' }, t('home.connect')))));
    setTimeout(() => input.focus(), 30);
  }

  async probe() {
    this.showProgress(t('connect.searching', { id: formatId(this.id) }));
    const res = await api.invoke('connect:probe', this.id);
    if (this.cancelled) return;
    if (!res.ok) return this.showError(res.error);
    if (res.hasSavedPassword) this.attempt(null, true);
    else this.showPassword();
  }

  async attempt(password, remember) {
    this.showProgress(t('connect.authenticating'));
    const res = await api.invoke('connect:start', { id: this.id, password, remember });
    if (this.cancelled) return;
    if (res.ok) {
      this.close();
      return;
    }
    if (res.error === 'auth' || res.error === 'need-password') this.showPassword(res.error === 'auth' ? 'auth' : null);
    else this.showError(res.error, res.retryIn);
  }
}

// ───────────────────────────── recents ─────────────────────────────

function RecentsPage() {
  const list = h('div', { class: 'recents-list' });
  const el = h('div', { class: 'stack', style: { gap: '16px' } }, h('h1', { class: 'page-title' }, t('recents.title')), list);
  return {
    el,
    update() {
      if (!state.recents.length) {
        clear(list, h('div', { class: 'card empty-state' }, icon('clock'), t('recents.empty')));
        return;
      }
      clear(list, state.recents.map((r) => {
        const more = h('button', { class: 'icon-btn', title: '…' }, icon('more'));
        more.addEventListener('click', () => showMenu(more, [
          r.hasPassword ? { label: t('recents.forgetPassword'), icon: 'key', onClick: () => api.invoke('recents:forget-password', r.id) } : null,
          { label: t('recents.remove'), icon: 'trash', onClick: () => api.invoke('recents:remove', r.id) },
        ].filter(Boolean)));
        return h('div', { class: 'card recent-row' },
          h('span', { class: 'avatar lg' }, initials(r.name) || icon('monitor')),
          h('div', { class: 'who' },
            h('b', null, r.name || formatId(r.id)),
            h('div', { class: 'meta' },
              h('span', null, `ID ${formatId(r.id)}`),
              h('span', null, t('recents.lastSeen', { date: relativeTime(r.lastAt, lang) })),
              r.hasPassword ? h('span', { class: 'badge success' }, icon('key', 'sm'), t('recents.savedPassword')) : null)),
          h('button', { class: 'btn primary', onclick: () => connectTo(r.id) }, t('recents.connect')),
          more);
      }));
    },
  };
}

// ───────────────────────────── settings ─────────────────────────────

const update = (patch) => api.invoke('settings:update', patch);

function toggle(key, { disabled = false, onChange } = {}) {
  const input = h('input', { type: 'checkbox', id: `set-${key}`, checked: Boolean(state.settings[key]), disabled });
  input.addEventListener('change', () => (onChange ? onChange(input.checked) : update({ [key]: input.checked })));
  return h('label', { class: 'switch' }, input, h('span'));
}

function settingRow(label, help, control) {
  return h('div', { class: 'setting' }, h('div', { class: 'text' }, h('div', null, label), help ? h('div', { class: 'help' }, help) : null), h('div', { class: 'control' }, control));
}

function select(key, options) {
  const sel = h('select', { class: 'input', id: `set-${key}` }, options.map(([value, label]) => h('option', { value: String(value), selected: String(state.settings[key]) === String(value) }, label)));
  sel.addEventListener('change', () => update({ [key]: typeof options[0][0] === 'number' ? Number(sel.value) : sel.value }));
  return sel;
}

function SettingsPage() {
  const s = state.settings;
  const nameInput = h('input', { class: 'input', id: 'set-displayName', value: s.displayName, placeholder: state.hostname, maxlength: 64 });
  const permStatus = h('span');
  const permButtons = h('div', { class: 'row' });

  const general = h('section', { class: 'card settings-section' },
    h('h2', null, icon('sliders'), t('settings.general')),
    h('div', { class: 'setting block' },
      h('div', { class: 'text' }, h('div', null, t('settings.displayName')), h('div', { class: 'help' }, t('settings.displayNameHelp'))),
      h('form', { class: 'inline-form', onsubmit: async (e) => { e.preventDefault(); await update({ displayName: nameInput.value.trim() }); toast(t('settings.saved')); } },
        nameInput, h('button', { class: 'btn', type: 'submit' }, t('common.save')))),
    settingRow(t('settings.language'), null, select('language', [['auto', t('language.auto')], ['fr', t('language.fr')], ['en', t('language.en')]])),
    settingRow(t('settings.theme'), null, select('theme', [['system', t('theme.system')], ['light', t('theme.light')], ['dark', t('theme.dark')]])),
    settingRow(t('settings.launchAtStartup'), null, toggle('launchAtStartup')),
    settingRow(t('settings.closeToTray'), null, toggle('closeToTray')));

  const security = h('section', { class: 'card settings-section' },
    h('h2', null, icon('lock'), t('settings.security')),
    settingRow(t('settings.allowIncoming'), null, toggle('allowIncoming')),
    settingRow(t('settings.useRandomPassword'), null, toggle('useRandomPassword')),
    settingRow(t('settings.passwordLength'), null, select('passwordLength', [6, 8, 10, 12].map((n) => [n, t('settings.chars', { n })]))),
    settingRow(t('settings.regenerate'), null, toggle('regeneratePasswordAfterSession')),
    h('div', { class: 'setting' },
      h('div', { class: 'text' }, h('div', null, t('settings.permanent')), h('div', { class: 'help' }, t('settings.permanentHelp')), h('div', { class: 'help', style: { marginTop: '6px' } }, permStatus)),
      h('div', { class: 'control' }, permButtons)),
    settingRow(t('settings.confirmIncoming'), null, toggle('confirmIncoming')),
    settingRow(t('settings.allowControl'), t('settings.allowControlHelp'), toggle('allowControl')),
    settingRow(t('settings.allowFileTransfer'), null, toggle('allowFileTransfer')),
    settingRow(t('settings.allowClipboard'), null, toggle('allowClipboard')),
    state.platform === 'win32' ? settingRow(t('settings.shareAudio'), null, toggle('shareAudio')) : null);

  // Network
  const serverInput = h('input', { class: 'input', id: 'set-serverUrl', value: s.serverUrl, placeholder: 'wss://pairdesk.example.com/ws', spellcheck: 'false' });
  const serverError = h('div', { class: 'error-text' });
  const serverForm = h('form', {
    class: 'stack',
    onsubmit: async (e) => {
      e.preventDefault();
      const url = serverInput.value.trim();
      if (!/^wss?:\/\/\S+$/i.test(url)) {
        serverError.textContent = t('settings.invalidUrl');
        return;
      }
      serverError.textContent = '';
      await update({ serverUrl: url, networkMode: 'server' });
      toast(t('settings.saved'));
    },
  }, h('label', { class: 'label', for: 'set-serverUrl' }, t('settings.serverUrl')),
  h('div', { class: 'inline-form' }, serverInput, h('button', { class: 'btn primary', type: 'submit' }, t('common.apply'))), serverError);

  const modeCard = (mode, title, help) => {
    const radio = h('input', { type: 'radio', name: 'network-mode', checked: s.networkMode === mode });
    const card = h('label', { class: `radio-card${s.networkMode === mode ? ' selected' : ''}` }, radio,
      h('div', null, h('div', { style: { fontWeight: 600 } }, title), h('div', { class: 'help' }, help)));
    radio.addEventListener('change', () => {
      for (const c of card.parentElement.querySelectorAll('.radio-card')) c.classList.toggle('selected', c === card);
      serverForm.hidden = mode !== 'server';
      if (mode === 'public') update({ networkMode: 'public' });
      else if (s.serverUrl) update({ networkMode: 'server' });
      else serverInput.focus();
    });
    return card;
  };
  serverForm.hidden = s.networkMode !== 'server';

  const ice = s.customIce || {};
  const turnUrls = h('input', { class: 'input', value: ice.urls || '', placeholder: 'turn:turn.example.com:3478', spellcheck: 'false' });
  const turnUser = h('input', { class: 'input', value: ice.username || '', placeholder: t('settings.turnUser') });
  const turnPass = h('input', { class: 'input', type: 'password', value: ice.credential || '', placeholder: t('settings.turnPass') });

  const network = h('section', { class: 'card settings-section' },
    h('h2', null, icon('globe'), t('settings.network')),
    h('div', { class: 'setting block' },
      h('div', null, t('settings.networkMode')),
      h('div', { class: 'stack' },
        modeCard('public', t('settings.networkPublic'), t('settings.networkPublicHelp')),
        modeCard('server', t('settings.networkServer'), t('settings.networkServerHelp'))),
      serverForm),
    h('div', { class: 'setting block' },
      h('div', { class: 'text' }, h('div', null, t('settings.turn')), h('div', { class: 'help' }, t('settings.turnHelp'))),
      h('form', {
        class: 'stack',
        onsubmit: async (e) => {
          e.preventDefault();
          await update({ customIce: { urls: turnUrls.value.trim(), username: turnUser.value.trim(), credential: turnPass.value } });
          toast(t('settings.saved'));
        },
      }, h('div', { class: 'turn-grid' }, turnUrls, turnUser, turnPass),
      h('div', { class: 'row', style: { justifyContent: 'flex-end' } }, h('button', { class: 'btn', type: 'submit' }, t('common.apply'))))));

  const updates = h('section', { class: 'card settings-section' },
    h('h2', null, icon('update'), t('settings.updates')),
    settingRow(t('settings.autoCheckUpdates'), null, toggle('autoCheckUpdates')));

  const el = h('div', { class: 'stack', style: { gap: '16px' } }, h('h1', { class: 'page-title' }, t('settings.title')), general, security, network, updates);
  return {
    el,
    update() {
      const cur = state.settings;
      permStatus.textContent = cur.hasPermanentPassword ? t('settings.permanentIsSet') : t('settings.permanentNotSet');
      clear(permButtons,
        h('button', { class: 'btn small', id: 'set-permanent', onclick: () => permanentPasswordDialog() }, cur.hasPermanentPassword ? t('settings.permanentChange') : t('settings.permanentSet')),
        cur.hasPermanentPassword ? h('button', { class: 'btn small danger', onclick: () => api.invoke('settings:set-permanent-password', null) }, t('settings.permanentRemove')) : null);
      for (const [key, value] of Object.entries(cur)) {
        const input = el.querySelector(`#set-${key}`);
        if (input?.type === 'checkbox') input.checked = Boolean(value);
      }
    },
  };
}

function permanentPasswordDialog() {
  const a = h('input', { class: 'input', type: 'password', id: 'perm-1', autocomplete: 'new-password' });
  const b = h('input', { class: 'input', type: 'password', id: 'perm-2', autocomplete: 'new-password' });
  const error = h('div', { class: 'error-text' });
  const close = () => backdrop.remove();
  const backdrop = h('div', { class: 'modal-backdrop' },
    h('form', {
      class: 'modal stack',
      onsubmit: async (e) => {
        e.preventDefault();
        if (a.value.trim().length < MIN_PERMANENT_PASSWORD_LENGTH) {
          error.textContent = t('settings.permanentTooShort');
          return;
        }
        if (a.value !== b.value) {
          error.textContent = t('settings.permanentMismatch');
          return;
        }
        const res = await api.invoke('settings:set-permanent-password', a.value);
        if (!res.ok) {
          error.textContent = t('settings.permanentTooShort');
          return;
        }
        close();
        toast(t('settings.saved'));
      },
    },
    h('h3', null, t('settings.permanent')),
    h('p', { class: 'muted small' }, t('settings.permanentHelp')),
    h('label', { class: 'label', for: 'perm-1' }, t('settings.permanentNew')), a,
    h('label', { class: 'label', for: 'perm-2' }, t('settings.permanentConfirm')), b,
    error,
    h('div', { class: 'actions' }, h('button', { class: 'btn', type: 'button', onclick: close }, t('common.cancel')), h('button', { class: 'btn primary', type: 'submit', id: 'perm-save' }, t('common.save')))));
  backdrop.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') close();
  });
  document.body.append(backdrop);
  setTimeout(() => a.focus(), 30);
}

// ───────────────────────────── about ─────────────────────────────

function AboutPage() {
  const box = h('div');
  const el = h('div', { class: 'card about-card' },
    h('div', { class: 'about-head' }, h('img', { src: '../assets/logo.svg', alt: '' }),
      h('div', null, h('h2', null, 'PairDesk'), h('div', { class: 'muted' }, t('about.version', { version: state.version })))),
    h('p', { class: 'muted' }, t('about.desc')),
    box,
    h('div', { class: 'links' },
      h('button', { class: 'btn', onclick: () => api.invoke('app:open-external', 'https://github.com/Azukkia/PairDesk') }, icon('external', 'sm'), t('about.website')),
      h('button', { class: 'btn', onclick: () => api.invoke('app:open-logs') }, icon('folder', 'sm'), t('about.logs'))));
  return {
    el,
    update() {
      const u = state.update;
      let text = '';
      let action = null;
      let busy = false;
      switch (u.status) {
        case 'unsupported': text = t('update.unsupported'); break;
        case 'checking': text = t('update.checking'); busy = true; break;
        case 'up-to-date': text = t('update.upToDate'); break;
        case 'available':
          text = t('update.available', { version: u.version });
          action = h('button', { class: 'btn primary', onclick: () => api.invoke('update:download') }, icon('download', 'sm'), t('update.download'));
          break;
        case 'downloading': text = t('update.downloading', { progress: u.progress }); busy = true; break;
        case 'downloaded':
          text = t('update.downloaded', { version: u.version });
          action = h('button', { class: 'btn primary', onclick: () => api.invoke('update:install') }, icon('refresh', 'sm'), t('update.install'));
          break;
        case 'error': text = t('update.error', { error: u.error || '' }); break;
        default: text = t('about.version', { version: state.version });
      }
      if (!action && !busy && u.status !== 'unsupported') {
        action = h('button', { class: 'btn', id: 'check-updates', onclick: () => api.invoke('update:check') }, icon('refresh', 'sm'), t('about.checkUpdates'));
      }
      clear(box, h('div', { class: 'stack' },
        h('div', { class: 'update-box' }, busy ? h('span', { class: 'spinner' }) : icon(u.status === 'error' ? 'alert' : 'update', 'lg'),
          h('div', { class: 'text' }, text,
            u.status === 'downloading' ? h('div', { class: 'progress', style: { marginTop: '8px' } }, h('div', { style: { width: `${u.progress}%` } })) : null),
          action),
        u.notes && ['available', 'downloading', 'downloaded'].includes(u.status)
          ? h('div', null, h('label', { class: 'label' }, t('update.notes')), h('div', { class: 'notes' }, u.notes)) : null));
    },
  };
}

// ───────────────────────────── boot ─────────────────────────────

function applyState(next) {
  const langChanged = next.lang !== lang;
  state = next;
  if (langChanged) {
    lang = next.lang;
    setLanguage(lang);
    build();
    return;
  }
  updateChrome();
  pageView?.update?.();
}

api.on('app:state', applyState);
api.on('app:navigate', (target) => navigate(target));
api.on('app:deeplink', (id) => {
  navigate('home');
  pageView.setPartner?.(id);
  connectTo(id);
});

applyState(await api.invoke('app:state'));

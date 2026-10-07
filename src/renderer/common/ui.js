// Small DOM helpers shared by the renderers (no framework).

import { createTranslator } from '../shared/i18n.js';

export const api = window.pairdesk;

let translate = createTranslator('fr');
export const t = (key, vars) => translate(key, vars);
export function setLanguage(lang) {
  translate = createTranslator(lang);
  document.documentElement.lang = lang;
}

export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value == null || value === false) continue;
    if (key === 'class') el.className = value;
    else if (key === 'style' && typeof value === 'object') Object.assign(el.style, value);
    else if (key === 'dataset') Object.assign(el.dataset, value);
    else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key in el && typeof value !== 'string') el[key] = value;
    else el.setAttribute(key, value === true ? '' : value);
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

export function clear(el, ...children) {
  el.replaceChildren();
  append(el, children);
  return el;
}

// Stroke icons (24×24).
const ICONS = {
  home: 'M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z',
  clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2',
  sliders: 'M4 7h9M17 7h3M4 17h3M11 17h9M15 9a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM9 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4z',
  info: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 16v-5M12 8h.01',
  copy: 'M9 9h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2zM5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1',
  refresh: 'M20 11A8 8 0 1 0 17.7 16.7M20 4v7h-7',
  eye: 'M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
  eyeOff: 'M3 3l18 18M10.6 5.1A10.9 10.9 0 0 1 12 5c6.4 0 10 7 10 7a17.6 17.6 0 0 1-3.2 4.1M6.6 6.6A17.5 17.5 0 0 0 2 12s3.6 7 10 7a10 10 0 0 0 5.4-1.6M9.9 9.9a3 3 0 0 0 4.2 4.2',
  monitor: 'M5 4h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM8 21h8M12 17v4',
  arrowRight: 'M5 12h14M13 6l6 6-6 6',
  x: 'M6 6l12 12M18 6 6 18',
  power: 'M12 3v9M6.3 6.3a8 8 0 1 0 11.4 0',
  message: 'M21 12a8 8 0 0 1-11.6 7.1L4 21l1.9-5.4A8 8 0 1 1 21 12z',
  upload: 'M12 16V4M6 10l6-6 6 6M4 20h16',
  download: 'M12 4v12M6 10l6 6 6-6M4 20h16',
  clipboard: 'M9 4h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM16 6h2a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h2',
  keyboard: 'M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10',
  maximize: 'M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5',
  minimize: 'M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5',
  fit: 'M4 8V4h4M16 4h4v4M20 16v4h-4M8 20H4v-4M9 9h6v6H9z',
  gauge: 'M12 14l4-4M3.5 18a10 10 0 1 1 17 0',
  shield: 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM9 12l2 2 4-4',
  check: 'M5 12l5 5 9-10',
  user: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0',
  send: 'M22 2 11 13M22 2l-7 20-4-9-9-4z',
  trash: 'M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3',
  key: 'M8 19a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM11 12l9-9M17 6l3 3M15 8l2 2',
  pin: 'M12 17v5M5 17h14M7 17l1-7-2-3h12l-2 3 1 7',
  volume: 'M11 5 6 9H2v6h4l5 4zM15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14',
  volumeX: 'M11 5 6 9H2v6h4l5 4zM22 9l-6 6M16 9l6 6',
  external: 'M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5',
  folder: 'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
  chevronDown: 'M6 9l6 6 6-6',
  lock: 'M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2zM8 11V7a4 4 0 0 1 8 0v4',
  globe: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18',
  update: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 16V8M8 12l4-4 4 4',
  alert: 'M12 3 2 20h20zM12 10v4M12 17h.01',
  pointer: 'M4 3l7 17 2-7 7-2z',
  more: 'M12 6h.01M12 12h.01M12 18h.01',
};

export function icon(name, cls = '') {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('class', `icon ${cls}`.trim());
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', ICONS[name] || ICONS.info);
  svg.append(path);
  return svg;
}

let toastHost = null;
export function toast(message, { type = 'success', timeout = 2600, action } = {}) {
  if (!toastHost) {
    toastHost = h('div', { class: 'toasts' });
    document.body.append(toastHost);
  }
  const el = h('div', { class: `toast ${type}` },
    icon(type === 'error' ? 'alert' : type === 'info' ? 'info' : 'check'),
    h('span', { class: 'grow' }, message),
    action ? h('button', { class: 'btn small', onclick: () => { action.run(); el.remove(); } }, action.label) : null);
  toastHost.append(el);
  setTimeout(() => el.remove(), timeout);
  return el;
}

let openMenu = null;
/** Shows a popover menu below `anchor`. items: {label, icon, checked, onClick} | 'sep' | {title} */
export function showMenu(anchor, items) {
  closeMenu();
  const menu = h('div', { class: 'menu', role: 'menu' }, items.map((item) => {
    if (item === 'sep') return h('div', { class: 'sep' });
    if (item.title) return h('div', { class: 'menu-title' }, item.title);
    return h('button', {
      class: item.checked ? 'checked' : '',
      role: 'menuitem',
      onclick: (e) => {
        e.stopPropagation();
        closeMenu();
        item.onClick();
      },
    }, item.icon ? icon(item.icon, 'sm') : null, item.label);
  }));
  document.body.append(menu);
  const r = anchor.getBoundingClientRect();
  const mw = menu.offsetWidth;
  const left = Math.max(8, Math.min(r.left + r.width / 2 - mw / 2, window.innerWidth - mw - 8));
  menu.style.left = `${left}px`;
  menu.style.top = `${r.bottom + 6}px`;
  openMenu = menu;
  setTimeout(() => document.addEventListener('mousedown', onOutside, true));
  return menu;
}

function onOutside(e) {
  if (openMenu && !openMenu.contains(e.target)) closeMenu();
}

export function closeMenu() {
  document.removeEventListener('mousedown', onOutside, true);
  openMenu?.remove();
  openMenu = null;
}

export function formatBytes(n) {
  if (n < 1024) return `${n} o`;
  const units = ['Ko', 'Mo', 'Go', 'To'];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(v < 10 ? 1 : 0)} ${units[i]}`;
}

export function formatDuration(ms) {
  const s = Math.floor(ms / 1000);
  const hh = Math.floor(s / 3600);
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, '0');
  const ss = String(s % 60).padStart(2, '0');
  return hh ? `${hh}:${mm}:${ss}` : `${mm}:${ss}`;
}

export function relativeTime(ts, lang) {
  const rtf = new Intl.RelativeTimeFormat(lang, { numeric: 'auto' });
  const diff = (ts - Date.now()) / 1000;
  const steps = [[60, 'second'], [3600, 'minute'], [86400, 'hour'], [604800, 'day'], [2629800, 'week'], [31557600, 'month']];
  let prev = 1;
  for (const [limit, unit] of steps) {
    if (Math.abs(diff) < limit) return rtf.format(Math.round(diff / prev), unit);
    prev = limit;
  }
  return rtf.format(Math.round(diff / 31557600), 'year');
}

export function initials(name) {
  const parts = String(name || '').trim().split(/[\s._-]+/).filter(Boolean);
  if (!parts.length) return '';
  return (parts[0][0] + (parts[1]?.[0] || '')).toUpperCase();
}

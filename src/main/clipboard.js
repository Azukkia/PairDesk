// Clipboard synchronisation helper: text, rich text (HTML/RTF), images and
// copied files. Electron has no clipboard change event, so the clipboard is
// polled while at least one session uses it (on Windows the cheap clipboard
// sequence number avoids reading it every time).
//
// Electron 44's clipboard is asynchronous and modelled on the W3C API:
// clipboard.read() → [ClipboardItem], item.types, item.getType(type) → Blob,
// clipboard.write([new ClipboardItem({ type: data })]). OS formats are
// reached with "electron application/osclipboard;format=<name>".

import { EventEmitter } from 'node:events';
import crypto from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';

// Text, HTML and RTF travel in one data-channel message (SCTP limit 256 KiB,
// and big messages delay the mouse and keyboard behind them).
export const MAX_TEXT_BYTES = 200 * 1024;
const MAX_RICH_BYTES = 240 * 1024;
// Images travel like a file, right away.
export const MAX_IMAGE_BYTES = 16 * 1024 * 1024;

const bytes = (s) => (s ? Buffer.byteLength(s, 'utf8') : 0);
const fits = (text) => bytes(text) <= MAX_TEXT_BYTES;
export const osFormat = (name) => `electron application/osclipboard;format="${name}"`;

/** The text, HTML and RTF parts that fit in one message (text first). */
export function richParts({ text = '', html = '', rtf = '' }) {
  const out = {};
  let used = 0;
  for (const [key, value] of [['text', text], ['html', html], ['rtf', rtf]]) {
    if (!value || typeof value !== 'string') continue;
    const size = bytes(value);
    if (size > MAX_TEXT_BYTES || used + size > MAX_RICH_BYTES) continue;
    out[key] = value;
    used += size;
  }
  return out;
}

/** file:// URIs of a text/uri-list (or GNOME's "copy\n…" list) → paths. */
export function parseUriList(text) {
  return String(text || '')
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l.startsWith('file://'))
    .map((l) => {
      try {
        return fileURLToPath(l);
      } catch {
        return null;
      }
    })
    .filter(Boolean);
}

export const uriList = (paths) => paths.map((p) => pathToFileURL(p).href);

export class ClipboardSync extends EventEmitter {
  /**
   * @param {object} o
   * @param {object} o.clipboard       Electron clipboard (asynchronous API)
   * @param {Function} o.ClipboardItem Electron ClipboardItem
   * @param {object} [o.files]         Windows: { read(): string[]|null, write(paths): boolean } (clipboard-native.js)
   */
  constructor({ clipboard, ClipboardItem, files = null, getSequence = null, intervalMs = 700, log }) {
    super();
    Object.assign(this, { clipboard, ClipboardItem, files, getSequence, intervalMs, log });
    this.users = 0;
    this.timer = null;
    this.lastKey = null;
    this.lastSeq = null;
    this.polling = false;
    this.imageCache = null; // { key, png }: the same image is not fetched again every poll
  }

  acquire() {
    if (this.users++ === 0) {
      this.lastSeq = this.getSequence?.() ?? null;
      this.read().then((c) => {
        this.lastKey = this.#key(c);
      });
      this.timer = setInterval(() => this.#poll(), this.intervalMs);
    }
  }

  release() {
    if (this.users > 0 && --this.users === 0) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  /** Current content: { files } or { text, html, rtf, png } (missing parts are empty). */
  async read() {
    try {
      const files = this.files?.read();
      if (files?.length) return { files };
    } catch (err) {
      this.log?.warn(`[clipboard] files: ${err.message}`);
    }
    const out = { text: '', html: '', rtf: '', png: null };
    try {
      const [item] = await this.clipboard.read();
      if (!item) return out;
      const types = item.types || [];
      const text = async (type) => (types.includes(type) ? (await item.getType(type)).text() : '');
      // Files copied in a Linux file manager.
      for (const type of ['text/uri-list', osFormat('x-special/gnome-copied-files'), 'x-special/gnome-copied-files']) {
        if (!types.includes(type)) continue;
        const paths = parseUriList(await text(type));
        if (paths.length) return { files: paths };
      }
      out.text = await text('text/plain');
      out.html = await text('text/html');
      out.rtf = await text('text/rtf');
      if (types.includes('image/png')) {
        // Without a sequence number (X11) the image would be fetched again
        // every poll: only when something else changed.
        const key = `${types.join(',')}|${this.getSequence ? this.getSequence() : out.text}`;
        if (this.imageCache?.key !== key) {
          const blob = await item.getType('image/png');
          this.imageCache = { key, png: Buffer.from(await blob.arrayBuffer()) };
        }
        out.png = this.imageCache.png;
      }
    } catch (err) {
      this.log?.warn(`[clipboard] read failed: ${err.message}`);
    }
    return out;
  }

  /** Same content → same key. */
  #key(c) {
    const h = crypto.createHash('sha1');
    if (c.files) h.update(`files:${c.files.join('\n')}`);
    else {
      h.update(`t:${c.text}\0h:${c.html}\0r:${c.rtf}\0`);
      if (c.png) h.update(c.png);
    }
    return h.digest('hex');
  }

  async #poll() {
    if (this.polling) return;
    if (this.getSequence) {
      const seq = this.getSequence();
      if (seq === this.lastSeq) return;
      this.lastSeq = seq;
    }
    this.polling = true;
    try {
      const content = await this.read();
      const key = this.#key(content);
      if (key === this.lastKey) return;
      this.lastKey = key;
      if (!ClipboardSync.isEmpty(content)) this.emit('change', content);
    } finally {
      this.polling = false;
    }
  }

  static isEmpty(c) {
    return !c || (!c.files?.length && !c.text && !c.html && !c.rtf && !c.png);
  }

  /** Writes content received from a partner, without echoing it back. */
  async write({ text = '', html = '', rtf = '', png = null, files = null } = {}) {
    try {
      if (files?.length) {
        if (this.files) {
          if (!this.files.write(files)) return false;
        } else {
          // X11: the standard list, and GNOME's own format (Nautilus pastes from it).
          const uris = uriList(files);
          await this.clipboard.write([new this.ClipboardItem({
            [osFormat('text/uri-list')]: uris.join('\r\n'),
            [osFormat('x-special/gnome-copied-files')]: `copy\n${uris.join('\n')}`,
          })]);
        }
      } else {
        const data = {};
        if (text) data['text/plain'] = text;
        if (html) data['text/html'] = html;
        if (rtf) data['text/rtf'] = rtf;
        if (png) data['image/png'] = new Blob([png], { type: 'image/png' });
        if (!Object.keys(data).length) return false;
        await this.clipboard.write([new this.ClipboardItem(data)]);
      }
    } catch (err) {
      this.log?.warn(`[clipboard] write failed: ${err.message}`);
      return false;
    }
    this.lastSeq = this.getSequence?.() ?? null;
    this.lastKey = this.#key(await this.read());
    return true;
  }

  /** Text received from a partner (PairDesk 1.0/1.1 send only text). */
  setText(text) {
    if (typeof text !== 'string' || !text || !fits(text)) return Promise.resolve(false);
    return this.write({ text });
  }
}

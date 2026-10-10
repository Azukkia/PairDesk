// Clipboard content exchanged with a partner (main process side).
//
// Text, HTML and RTF go in the clipboard message itself; an image follows
// right away as a file transfer. Copied files are only announced (names and
// sizes): their content is transferred when the other side needs it (Ctrl+V
// in the viewer, or the viewer losing the focus to paste locally), then
// written to the clipboard as real files (Windows CF_HDROP / X11 uri-list)
// from a staging folder.

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { randomBytes } from 'node:crypto';
import { richParts, MAX_IMAGE_BYTES } from './clipboard.js';
import { sanitizeFileName } from './files.js';

const MAX_ENTRIES = 5000;
const MAX_ANNOUNCED = 200;

/** Lists the files under `paths` (folders recursively) with their relative names. */
export async function expandPaths(paths, { maxEntries = MAX_ENTRIES } = {}) {
  const entries = [];
  let total = 0;
  const walk = async (abs, rel) => {
    if (entries.length >= maxEntries) return;
    let st;
    try {
      st = await fs.promises.stat(abs);
    } catch {
      return;
    }
    if (st.isDirectory()) {
      let names = [];
      try {
        names = await fs.promises.readdir(abs);
      } catch {
        return;
      }
      if (!names.length) entries.push({ abs: null, rel: `${rel}/`, size: 0, dir: true }); // keep empty folders
      for (const name of names.sort()) await walk(path.join(abs, name), `${rel}/${name}`);
    } else if (st.isFile()) {
      entries.push({ abs, rel, size: st.size });
      total += st.size;
    }
  };
  for (const p of paths) await walk(p, path.basename(p));
  return { entries, total, truncated: entries.length >= maxEntries };
}

/** Safe relative path under a folder: every segment sanitized, no "..". */
export function safeRelative(rel) {
  const parts = String(rel || '').split(/[\\/]+/).filter((s) => s && s !== '.' && s !== '..');
  if (!parts.length || parts.length > 32) return null;
  return parts.map(sanitizeFileName).join(path.sep);
}

export class ClipboardTransfers {
  constructor({ clipboard, log, stagingRoot = path.join(os.tmpdir(), 'PairDesk', 'clipboard') }) {
    Object.assign(this, { clipboard, log, stagingRoot });
    this.outbox = null; // our clipboard as last announced: { cid, paths, png, list? }
    this.inbound = new Map(); // ctx → { cid, text, html, rtf, image, files, dir, expected, received, tops }
    this.tokens = new Map(); // file token → { ctx, cid, kind, top }
    this.queue = Promise.resolve();
  }

  cleanup(maxAgeMs = 24 * 3600_000) {
    try {
      for (const name of fs.readdirSync(this.stagingRoot)) {
        const dir = path.join(this.stagingRoot, name);
        if (Date.now() - fs.statSync(dir).mtimeMs > maxAgeMs) fs.rmSync(dir, { recursive: true, force: true });
      }
    } catch {
      /* nothing yet */
    }
  }

  /**
   * Turns local clipboard content into the payload sent to session windows:
   * { cid, text?, html?, rtf?, png?, files?: [{name, size, dir}], total }.
   */
  async announce(content) {
    const cid = randomBytes(6).toString('hex');
    if (content.files?.length) {
      const top = [];
      let total = 0;
      for (const p of content.files.slice(0, MAX_ANNOUNCED)) {
        try {
          const st = await fs.promises.stat(p);
          top.push({ name: path.basename(p), size: st.isDirectory() ? null : st.size, dir: st.isDirectory() });
          if (!st.isDirectory()) total += st.size;
        } catch {
          /* vanished */
        }
      }
      if (!top.length) return null;
      // Folder sizes are only known once listed: do it now for the total.
      if (top.some((t) => t.dir)) total = (await expandPaths(content.files)).total;
      this.outbox = { cid, paths: content.files, list: null };
      return { cid, files: top, total };
    }
    const png = content.png && content.png.length <= MAX_IMAGE_BYTES ? content.png : null;
    const parts = richParts(content);
    if (!png && !Object.keys(parts).length) return null;
    this.outbox = { cid, paths: null, png };
    return { cid, ...parts, png };
  }

  /** Files of announcement `cid`, for the session window to send: [{id, rel, size, dir}]. */
  async list(cid) {
    const box = this.outbox;
    if (!box || box.cid !== cid || !box.paths) return null;
    if (!box.list) {
      const { entries } = await expandPaths(box.paths);
      box.list = entries.map((e, id) => ({ ...e, id }));
    }
    return box.list.map(({ id, rel, size, dir }) => ({ id, rel, size, dir: Boolean(dir) }));
  }

  /** A chunk of one of those files. */
  async read(cid, id, start, end) {
    const entry = this.outbox?.cid === cid ? this.outbox.list?.[id] : null;
    if (!entry?.abs || !Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || end < start) {
      throw new Error('unknown clipboard file');
    }
    const len = Math.min(end, entry.size) - start;
    if (len <= 0) return new Uint8Array(0);
    const fh = await fs.promises.open(entry.abs, 'r');
    try {
      const buf = Buffer.alloc(len);
      const { bytesRead } = await fh.read(buf, 0, len, start);
      return new Uint8Array(buf.buffer, buf.byteOffset, bytesRead);
    } finally {
      await fh.close();
    }
  }

  /**
   * Clipboard content announced by the partner of `ctx`. Text-only content
   * is written now; an image is written when it arrives; files when they
   * are fetched. Returns what the window should know: { files, total }.
   */
  remote(ctx, payload) {
    if (typeof payload === 'string') {
      this.#write(() => this.clipboard.setText(payload)); // PairDesk 1.0/1.1
      return null;
    }
    if (!payload || typeof payload !== 'object' || typeof payload.cid !== 'string' || payload.cid.length > 32) return null;
    this.#forget(ctx);
    const parts = richParts(payload);
    const entry = { cid: payload.cid, ...parts, image: Boolean(payload.image), files: null, dir: null, expected: 0, received: 0, tops: new Set() };
    if (Array.isArray(payload.files) && payload.files.length) {
      entry.files = payload.files.slice(0, MAX_ANNOUNCED).map((f) => ({ name: String(f?.name || '').slice(0, 255), size: Number(f?.size) || 0, dir: Boolean(f?.dir) }));
      entry.total = Number(payload.total) || 0;
    } else if (Object.keys(parts).length) {
      this.#write(() => this.clipboard.write(parts)); // the image (if any) completes it later
    }
    this.inbound.set(ctx, entry);
    return entry.files ? { cid: entry.cid, files: entry.files, total: entry.total } : null;
  }

  /** Clipboard writes run one after the other, in the order they were asked. */
  #write(fn) {
    this.queue = this.queue.then(fn).catch((err) => {
      this.log?.warn(`[clipboard] write: ${err.message}`);
      return false;
    });
    return this.queue;
  }

  #forget(ctx) {
    const old = this.inbound.get(ctx);
    if (!old) return;
    this.inbound.delete(ctx);
    for (const [token, t] of this.tokens) if (t.ctx === ctx) this.tokens.delete(token);
  }

  endSession(ctx) {
    this.#forget(ctx);
  }

  /**
   * Incoming clipboard file offer (`clip` = {cid, kind, rel, n}). Returns the
   * folder and name to receive it in, or null to refuse it.
   */
  accept(ctx, clip, name) {
    const entry = this.inbound.get(ctx);
    if (!entry || !clip || clip.cid !== entry.cid) return null;
    if (!entry.dir) {
      entry.dir = path.join(this.stagingRoot, `${Date.now().toString(36)}-${randomBytes(4).toString('hex')}`);
    }
    if (clip.kind === 'image') {
      if (!entry.image) return null;
      fs.mkdirSync(entry.dir, { recursive: true });
      return { directory: entry.dir, name: 'clipboard.png', kind: 'image' };
    }
    if (clip.kind !== 'file' || !entry.files) return null;
    const rel = safeRelative(clip.rel || name);
    if (!rel) return null;
    if (!entry.expected) entry.expected = Math.min(Number(clip.n) || 1, MAX_ENTRIES);
    const full = path.join(entry.dir, rel);
    fs.mkdirSync(path.dirname(full), { recursive: true });
    return { directory: path.dirname(full), name: path.basename(full), kind: 'file', top: path.join(entry.dir, rel.split(path.sep)[0]) };
  }

  track(token, ctx, cid, accepted) {
    this.tokens.set(token, { ctx, cid, kind: accepted.kind, top: accepted.top });
  }

  /**
   * A clipboard file finished (`file`) or failed (null). Returns
   * { ready: cid } once the clipboard has been written with all the files.
   */
  async fileDone(token, file) {
    const t = this.tokens.get(token);
    if (!t) return null;
    this.tokens.delete(token);
    const entry = this.inbound.get(t.ctx);
    if (!entry || entry.cid !== t.cid) return null;
    if (t.kind === 'image') {
      if (!file) return null;
      let png;
      try {
        png = fs.readFileSync(file);
        fs.rmSync(file, { force: true });
      } catch (err) {
        this.log?.warn(`[clipboard] image: ${err.message}`);
        return null;
      }
      await this.#write(() => this.clipboard.write({ text: entry.text, html: entry.html, rtf: entry.rtf, png }));
      return null;
    }
    entry.received++;
    if (file) entry.tops.add(t.top);
    if (entry.received < entry.expected) return null;
    const tops = [...entry.tops].filter((p) => fs.existsSync(p));
    if (!tops.length) return null;
    if (!(await this.#write(() => this.clipboard.write({ files: tops })))) return null;
    this.log?.info(`[clipboard] ${entry.received} file(s) from the partner are in the clipboard`);
    return { ready: entry.cid };
  }

  /**
   * The partner is about to send the files of `cid`: `n` files, plus empty
   * folders (no transfer carries them). With nothing to transfer, the
   * clipboard is written right away and { ready: cid } returned.
   */
  async expect(ctx, cid, n, folders = []) {
    const entry = this.inbound.get(ctx);
    if (!entry || entry.cid !== cid || !entry.files) return null;
    entry.dir ??= path.join(this.stagingRoot, `${Date.now().toString(36)}-${randomBytes(4).toString('hex')}`);
    entry.expected = Math.max(0, Math.min(Number(n) || 0, MAX_ENTRIES));
    entry.received = 0;
    for (const r of (Array.isArray(folders) ? folders : []).slice(0, MAX_ENTRIES)) {
      const rel = safeRelative(r);
      if (!rel) continue;
      fs.mkdirSync(path.join(entry.dir, rel), { recursive: true });
      entry.tops.add(path.join(entry.dir, rel.split(path.sep)[0]));
    }
    if (entry.expected > 0) return null;
    const tops = [...entry.tops];
    return tops.length && (await this.#write(() => this.clipboard.write({ files: tops }))) ? { ready: cid } : null;
  }
}

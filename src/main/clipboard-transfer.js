// Clipboard content exchanged with a partner (main process side).
//
// Text, HTML and RTF go in the clipboard message itself; an image follows
// right away as a file transfer. Copied files are only announced (names and
// sizes): their content is transferred when the other side needs it (Ctrl+V
// in the viewer, or the viewer losing the focus to paste locally), then
// written to the clipboard as real files (Windows CF_HDROP / X11 uri-list)
// from a staging folder. A staging folder is deleted a while after its files
// left the clipboard (a paste may still be copying them).

import fs from 'node:fs';
import path from 'node:path';
import { randomBytes } from 'node:crypto';
import { richParts, MAX_IMAGE_BYTES } from './clipboard.js';
import { sanitizeFileName, privateTempRoot } from './files.js';

const MAX_ENTRIES = 5000;
const MAX_ANNOUNCED = 200;
const MAX_DEPTH = 32; // path segments (see safeRelative)
// Empty folders are listed in the clipboard-files message (data channel
// messages: 256 KiB at most).
const MAX_FOLDERS_BYTES = 200 * 1024;
// Copied files announced lately can still be asked for: every session start
// announces the clipboard again, other sessions keep the cid they got.
const RECENT_ANNOUNCEMENTS = 8;
const UNUSED_GRACE_MS = 30 * 60_000;

/** Lists the files under `paths` (folders recursively) with their relative names. */
export async function expandPaths(paths, { maxEntries = MAX_ENTRIES } = {}) {
  const entries = [];
  let total = 0;
  let truncated = false;
  const walk = async (abs, rel, depth) => {
    if (entries.length >= maxEntries || depth > MAX_DEPTH) {
      truncated = true;
      return;
    }
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
      for (const name of names.sort()) await walk(path.join(abs, name), `${rel}/${name}`, depth + 1);
    } else if (st.isFile()) {
      entries.push({ abs, rel, size: st.size });
      total += st.size;
    }
  };
  for (const p of paths) await walk(p, path.basename(p), 1);
  return { entries, total, truncated };
}

/** Safe relative path under a folder: every segment sanitized, no "..". */
export function safeRelative(rel) {
  const parts = String(rel || '').split(/[\\/]+/).filter((s) => s && s !== '.' && s !== '..');
  if (!parts.length || parts.length > MAX_DEPTH) return null;
  return parts.map(sanitizeFileName).join(path.sep);
}

export class ClipboardTransfers {
  constructor({ clipboard, log, stagingRoot = path.join(privateTempRoot(), 'clipboard') }) {
    Object.assign(this, { clipboard, log, stagingRoot });
    this.announced = new Map(); // cid → our copied files as announced: { cid, paths, list? } (oldest first)
    this.inbound = new Map(); // ctx → { cid, text, html, rtf, image, files, dir, expected, received, tops, generation }
    this.tokens = new Map(); // file token → { ctx, cid, kind, top }
    this.queue = Promise.resolve();
    // Bumped by every clipboard change (local copy, content from a partner):
    // remote content arriving after a newer one is not written.
    this.generation = 0;
    this.current = null; // staging folder whose files we put in the clipboard
    this.unused = new Map(); // staging folder → since when nothing needs it
  }

  cleanup(maxAgeMs = 24 * 3600_000) {
    const inUse = new Set([this.current, ...[...this.inbound.values()].map((e) => e.dir)]);
    try {
      for (const name of fs.readdirSync(this.stagingRoot)) {
        const dir = path.join(this.stagingRoot, name);
        if (inUse.has(dir)) continue;
        if (Date.now() - fs.statSync(dir).mtimeMs > maxAgeMs) fs.rmSync(dir, { recursive: true, force: true });
      }
    } catch {
      /* nothing yet */
    }
  }

  /** Deletes the staging folders nothing needs anymore (run periodically). */
  sweep(now = Date.now()) {
    for (const [dir, since] of this.unused) {
      if (now - since < UNUSED_GRACE_MS) continue;
      this.unused.delete(dir);
      try {
        fs.rmSync(dir, { recursive: true, force: true });
      } catch (err) {
        this.log?.warn(`[clipboard] cleanup: ${err.message}`);
      }
    }
    this.cleanup();
  }

  /** The clipboard now holds the files of staging folder `dir` (null: other content). */
  #holding(dir) {
    if (this.current && this.current !== dir) this.unused.set(this.current, Date.now());
    this.current = dir;
    if (dir) this.unused.delete(dir);
  }

  /** No session needs staging folder `dir` anymore (its files may still be in the clipboard). */
  #release(dir) {
    if (dir && dir !== this.current && !this.unused.has(dir)) this.unused.set(dir, Date.now());
  }

  /** The clipboard changed on this computer (copy). */
  localChanged() {
    this.generation++;
    this.#holding(null);
  }

  /**
   * Turns local clipboard content into the payload sent to session windows:
   * { cid, text?, html?, rtf?, png?, files?: [{name, size, dir}], total, notice? }.
   * `notice` ('too-many-files', 'image-too-big', 'text-too-long'): part of
   * the content cannot be sent; a payload may carry only that.
   */
  async announce(content) {
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
      let truncated = false;
      if (top.some((t) => t.dir)) ({ total, truncated } = await expandPaths(content.files));
      if (truncated) this.log?.warn(`[clipboard] more than ${MAX_ENTRIES} files copied, or folders nested too deeply: only part of them can be sent`);
      const cid = randomBytes(6).toString('hex');
      this.announced.set(cid, { cid, paths: content.files, list: null });
      while (this.announced.size > RECENT_ANNOUNCEMENTS) this.announced.delete(this.announced.keys().next().value);
      return { cid, files: top, total, ...(truncated ? { notice: 'too-many-files' } : {}) };
    }
    let notice = null;
    let png = content.png || null;
    if (png && png.length > MAX_IMAGE_BYTES) {
      this.log?.warn(`[clipboard] copied image too large to be sent (${png.length} bytes)`);
      png = null;
      notice = 'image-too-big';
    }
    const parts = richParts(content);
    const left = ['text', 'html', 'rtf'].filter((key) => content[key] && !parts[key]);
    if (left.length) this.log?.warn(`[clipboard] copied ${left.join(', ')} too large to be sent`);
    if (content.text && !parts.text) notice ??= 'text-too-long';
    if (!png && !Object.keys(parts).length) return notice ? { notice } : null;
    return { cid: randomBytes(6).toString('hex'), ...parts, png, ...(notice ? { notice } : {}) };
  }

  /** Files of announcement `cid`, for the session window to send: [{id, rel, size, dir}]. */
  async list(cid) {
    const box = this.announced.get(cid);
    if (!box) return null;
    // Listed once: sessions sending it at the same time read the same ids.
    box.list ??= this.#listing(box.paths);
    return (await box.list).map(({ id, rel, size, dir }) => ({ id, rel, size, dir: Boolean(dir) }));
  }

  async #listing(paths) {
    const { entries, truncated } = await expandPaths(paths);
    if (truncated) this.log?.warn(`[clipboard] only the first ${entries.length} copied files are sent`);
    let bytes = 0;
    const kept = entries.filter((e) => !e.dir || (bytes += Buffer.byteLength(JSON.stringify(e.rel)) + 1) <= MAX_FOLDERS_BYTES);
    if (kept.length < entries.length) this.log?.warn(`[clipboard] ${entries.length - kept.length} empty folders left out`);
    return kept.map((e, id) => ({ ...e, id }));
  }

  /** A chunk of one of those files. */
  async read(cid, id, start, end) {
    const list = await this.announced.get(cid)?.list;
    const entry = list && Number.isInteger(id) ? list[id] : null;
    if (!entry?.abs || !Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || end < start) {
      throw new Error('unknown clipboard file');
    }
    const len = Math.min(end, entry.size) - start;
    if (len <= 0) return new Uint8Array(0);
    const fh = await fs.promises.open(entry.abs, 'r');
    try {
      const buf = Buffer.alloc(len);
      for (let got = 0; got < len;) {
        const { bytesRead } = await fh.read(buf, got, len - got, start + got);
        // Shorter than when it was listed: fail rather than send something else.
        if (!bytesRead) throw new Error(`${path.basename(entry.abs)} changed since it was copied`);
        got += bytesRead;
      }
      return new Uint8Array(buf.buffer, buf.byteOffset, len);
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
      this.generation++;
      this.#holding(null);
      this.#write(() => this.clipboard.setText(payload)); // PairDesk 1.0/1.1
      return null;
    }
    if (!payload || typeof payload !== 'object' || typeof payload.cid !== 'string' || payload.cid.length > 32) return null;
    this.#forget(ctx);
    const parts = richParts(payload);
    const entry = {
      cid: payload.cid, ...parts, image: Boolean(payload.image), files: null, dir: null, expected: 0, received: 0, tops: new Set(),
      generation: ++this.generation,
    };
    if (Array.isArray(payload.files) && payload.files.length) {
      entry.files = payload.files.slice(0, MAX_ANNOUNCED).map((f) => ({ name: String(f?.name || '').slice(0, 255), size: Number(f?.size) || 0, dir: Boolean(f?.dir) }));
      entry.total = Number(payload.total) || 0;
    } else if (Object.keys(parts).length) {
      this.#holding(null);
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

  /** Something newer was copied here, or came from a partner, since `entry` was announced. */
  #outdated(entry) {
    if (entry.generation === this.generation) return false;
    this.log?.info('[clipboard] content from the partner arrived after a newer copy: not written');
    return true;
  }

  #forget(ctx) {
    const old = this.inbound.get(ctx);
    if (!old) return;
    this.inbound.delete(ctx);
    for (const [token, t] of this.tokens) if (t.ctx === ctx) this.tokens.delete(token);
    this.#release(old.dir);
  }

  endSession(ctx) {
    this.#forget(ctx);
  }

  #newDir() {
    return path.join(this.stagingRoot, `${Date.now().toString(36)}-${randomBytes(4).toString('hex')}`);
  }

  /**
   * Incoming clipboard file offer (`clip` = {cid, kind, rel, n}). Returns the
   * folder and name to receive it in, or null to refuse it.
   */
  accept(ctx, clip, name, size = 0) {
    const entry = this.inbound.get(ctx);
    if (!entry || !clip || clip.cid !== entry.cid) return null;
    entry.dir ??= this.#newDir();
    if (clip.kind === 'image') {
      // One image per announcement, of a sensible size.
      if (!entry.image || !(Number(size) <= MAX_IMAGE_BYTES)) return null;
      entry.image = false;
      fs.mkdirSync(entry.dir, { recursive: true });
      return { directory: entry.dir, name: 'clipboard.png', kind: 'image' };
    }
    if (clip.kind !== 'file' || !entry.files) return null;
    const rel = safeRelative(clip.rel || name);
    if (!rel) return null;
    if (!entry.expected) entry.expected = Math.min(Number(clip.n) || 1, MAX_ENTRIES);
    const full = path.join(entry.dir, rel);
    fs.mkdirSync(path.dirname(full), { recursive: true });
    // A file at the top is known by its final name (a name already taken,
    // e.g. "readme" after "README" on Windows, gets " (1)"): see fileDone.
    const top = rel.includes(path.sep) ? path.join(entry.dir, rel.split(path.sep)[0]) : null;
    return { directory: path.dirname(full), name: path.basename(full), kind: 'file', top };
  }

  track(token, ctx, cid, accepted) {
    this.tokens.set(token, { ctx, cid, kind: accepted.kind, top: accepted.top });
  }

  /**
   * A clipboard file finished (`file`: its final path) or failed (null).
   * Returns { ready: cid } once the clipboard has been written with all the files.
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
      if (this.#outdated(entry)) return null;
      this.#holding(null);
      await this.#write(() => this.clipboard.write({ text: entry.text, html: entry.html, rtf: entry.rtf, png }));
      return null;
    }
    entry.received++;
    if (file) entry.tops.add(t.top || file);
    if (entry.received < entry.expected) return null;
    return this.#writeFiles(entry);
  }

  async #writeFiles(entry) {
    const tops = [...entry.tops].filter((p) => fs.existsSync(p));
    if (!tops.length || this.#outdated(entry)) return null;
    this.#holding(entry.dir);
    if (!(await this.#write(() => this.clipboard.write({ files: tops })))) return null;
    this.log?.info(`[clipboard] ${tops.length} item(s) from the partner are in the clipboard`);
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
    entry.dir ??= this.#newDir();
    entry.expected = Math.max(0, Math.min(Number(n) || 0, MAX_ENTRIES));
    entry.received = 0;
    for (const r of (Array.isArray(folders) ? folders : []).slice(0, MAX_ENTRIES)) {
      const rel = safeRelative(r);
      if (!rel) continue;
      try {
        fs.mkdirSync(path.join(entry.dir, rel), { recursive: true });
      } catch (err) {
        this.log?.warn(`[clipboard] folder ${r}: ${err.message}`); // e.g. a file of that name (case-insensitive)
        continue;
      }
      entry.tops.add(path.join(entry.dir, rel.split(path.sep)[0]));
    }
    if (entry.expected > 0) return null;
    return this.#writeFiles(entry);
  }
}

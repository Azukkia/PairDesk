// Files dropped by the controller onto the remote screen land where they were
// dropped (desktop, Explorer folder, application window...), like a local
// drag and drop.
//
// The viewer sends the drop position with the files. Once every file of the
// drop has arrived (in a staging folder), PairDesk replays a real drag and
// drop on this computer: a tiny "source" window appears near the drop point,
// the input helper presses the mouse on it and drags; at "dragstart" the
// main process starts a native drag of the files (webContents.startDrag),
// which the helper carries to the drop point and releases. The target
// receives the files exactly as if somebody had dropped them on site, and
// places them itself (icon position on the desktop, current folder...).
//
// If the drag cannot start, the files are moved to Downloads/PairDesk.

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { randomBytes } from 'node:crypto';

const MAX_FILES = 100;
const EXPIRES_MS = 15 * 60_000;
const clamp01 = (v) => Math.min(1, Math.max(0, v));

/** Validates the `drop` field of a file offer: {id, x, y, n}. */
export function parseDrop(drop) {
  if (!drop || typeof drop !== 'object') return null;
  const { id, x, y, n } = drop;
  if (typeof id !== 'string' || !/^[A-Za-z0-9_-]{4,32}$/.test(id)) return null;
  if (!Number.isFinite(x) || !Number.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1) return null;
  if (!Number.isInteger(n) || n < 1 || n > MAX_FILES) return null;
  return { id, x, y, n };
}

/** Where the drag starts: a little to the side of the drop point, inside the display. */
export function dragPlan(rect, x, y, scale = 1) {
  const to = { x: Math.round(rect.x + clamp01(x) * (rect.width - 1)), y: Math.round(rect.y + clamp01(y) * (rect.height - 1)) };
  const offset = Math.round(48 * scale);
  const dir = to.x - rect.x > offset + 16 ? -1 : 1;
  return { from: { x: to.x + dir * offset, y: to.y }, to };
}

export class DropManager {
  /**
   * @param {object} o
   * @param {() => object|null} o.target  { sid, rect, scale, toDip(point) } of the incoming
   *        session when files may be dropped (remote control allowed), else null
   * @param {object} o.windows   Windows (createDropSource)
   * @param {object} o.input     InputService (drag, dragStarted)
   * @param {() => string} o.fallbackDir  Downloads/PairDesk
   */
  constructor({ target, windows, input, fallbackDir, log, stagingRoot = path.join(os.tmpdir(), 'PairDesk', 'drop') }) {
    Object.assign(this, { target, windows, input, fallbackDir, log, stagingRoot });
    this.drops = new Map(); // id → { sid, n, x, y, dir, paths, expires }
    this.tokens = new Map(); // file token → drop id
    this.current = null; // drag in progress: { win, files }
    this.queue = Promise.resolve();
  }

  /** Removes staging folders left by previous runs (Explorer may still copy right after a drop). */
  cleanup(maxAgeMs = 24 * 3600_000) {
    try {
      for (const name of fs.readdirSync(this.stagingRoot)) {
        const dir = path.join(this.stagingRoot, name);
        if (Date.now() - fs.statSync(dir).mtimeMs > maxAgeMs) fs.rmSync(dir, { recursive: true, force: true });
      }
    } catch {
      /* nothing to clean */
    }
  }

  /**
   * A file offer with a drop position. Returns the folder to receive it in,
   * or null when it should go to Downloads as usual.
   */
  accept(sid, rawDrop) {
    const drop = parseDrop(rawDrop);
    const target = this.target();
    if (!drop || !target || target.sid !== sid) return null;
    const now = Date.now();
    for (const [id, d] of this.drops) if (d.expires < now) this.drops.delete(id);
    let entry = this.drops.get(drop.id);
    if (entry && entry.sid !== sid) return null;
    if (!entry) {
      const dir = path.join(this.stagingRoot, `${Date.now().toString(36)}-${randomBytes(4).toString('hex')}`);
      entry = { sid, n: drop.n, x: drop.x, y: drop.y, dir, paths: [], announced: 0, expires: now + EXPIRES_MS };
      this.drops.set(drop.id, entry);
    }
    if (entry.announced >= entry.n) return null;
    entry.announced++;
    fs.mkdirSync(entry.dir, { recursive: true });
    return { id: drop.id, directory: entry.dir };
  }

  track(token, dropId) {
    this.tokens.set(token, dropId);
  }

  /** A file finished (path) or failed (null). Starts the drop when the last one arrives. */
  fileDone(token, filePath) {
    const id = this.tokens.get(token);
    if (!id) return;
    this.tokens.delete(token);
    const entry = this.drops.get(id);
    if (!entry) return;
    entry.n -= filePath ? 0 : 1; // a failed file is not waited for
    if (filePath) entry.paths.push(filePath);
    if (entry.paths.length < entry.n || !entry.paths.length) return;
    this.drops.delete(id);
    this.queue = this.queue.then(() => this.#perform(entry)).catch((err) => this.log.warn(`[drop] ${err.message}`));
  }

  async #perform(entry) {
    const target = this.target();
    let ok = false;
    if (target && target.sid === entry.sid) {
      try {
        ok = await this.#drag(entry, target);
      } catch (err) {
        this.log.warn(`[drop] drag failed: ${err.message}`);
      }
    }
    if (!ok) this.#fallback(entry.paths);
  }

  async #drag(entry, target) {
    const plan = dragPlan(target.rect, entry.x, entry.y, target.scale);
    const dip = target.toDip(plan.from);
    const win = this.windows.createDropSource(dip);
    this.current = { win, files: entry.paths, started: false };
    try {
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('source window timeout')), 5000);
        win.webContents.once('did-finish-load', () => {
          clearTimeout(timer);
          resolve();
        });
      });
      win.showInactive();
      await new Promise((r) => setTimeout(r, 150));
      this.log.info(`[drop] dropping ${entry.paths.length} file(s) at ${plan.to.x},${plan.to.y}`);
      const result = await this.input.drag(plan);
      return Boolean(result?.ok && this.current?.started);
    } finally {
      const done = this.current;
      this.current = null;
      // The target may still read the files through the source (X11 selections).
      setTimeout(() => {
        if (!done.win.isDestroyed()) done.win.destroy();
      }, 3000);
    }
  }

  /** "dragstart" in the source window: hand the files to the OS drag. */
  onDragStart(webContents, icon) {
    const cur = this.current;
    if (!cur || cur.started || cur.win.isDestroyed() || webContents !== cur.win.webContents) return;
    cur.started = true;
    // The helper carries the drag to the target from now on. On Windows,
    // startDrag returns only once the files are dropped.
    this.input.dragStarted();
    webContents.startDrag({ file: cur.files[0], files: cur.files, icon });
  }

  #fallback(paths) {
    const dir = this.fallbackDir();
    fs.mkdirSync(dir, { recursive: true });
    for (const file of paths) {
      try {
        const ext = path.extname(file);
        const base = path.basename(file, ext);
        let dest = path.join(dir, path.basename(file));
        for (let i = 1; fs.existsSync(dest); i++) dest = path.join(dir, `${base} (${i})${ext}`);
        try {
          fs.renameSync(file, dest);
        } catch (err) {
          if (err.code !== 'EXDEV') throw err; // Downloads on another drive
          fs.copyFileSync(file, dest);
          fs.rmSync(file, { force: true });
        }
      } catch (err) {
        this.log.warn(`[drop] could not move ${path.basename(file)}: ${err.message}`);
      }
    }
    this.log.info(`[drop] ${paths.length} file(s) saved in ${dir} instead`);
  }
}

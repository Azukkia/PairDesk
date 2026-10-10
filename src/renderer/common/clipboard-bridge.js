// Clipboard exchange with the partner (renderer side, viewer and host).
//
//   {type:'clipboard', cid, text?, html?, rtf?, image?:{size}, files?:[{name,size,dir}], total?}
//       our clipboard changed. 1.0/1.1 peers only read `text`. An image
//       follows right away as a file transfer (clip.kind 'image').
//   {type:'clipboard-fetch', cid}   "send me the files you announced"
//   {type:'clipboard-files', cid, n, folders}   n files follow (clip.kind 'file')
//   {type:'clipboard-ready', cid}   the files are in my clipboard now
//
// Files are transferred on demand: the viewer pushes its copied files when
// the user presses Ctrl+V in it (the keystroke waits for them) or brings the
// window to the front, and fetches the partner's files when the user leaves
// the window (to paste locally).

import { api } from './ui.js';
import { versionAtLeast } from '../shared/protocol.js';

const quiet = { update() {} };

export class ClipboardBridge {
  /**
   * @param {object} o
   * @param {() => object|null} o.rtc        current RtcSession
   * @param {() => string} o.peerVersion
   * @param {() => boolean} o.enabled        clipboard sync allowed and on
   * @param {() => boolean} [o.files]        copied files may be exchanged (file transfers allowed)
   * @param {(name, size, dir) => object} [o.view]  transfer view for clipboard files
   */
  constructor({ rtc, peerVersion, enabled, files = () => true, view = () => quiet }) {
    Object.assign(this, { rtc, peerVersion, enabled, filesAllowed: files, view });
    this.outbox = null; // our last announced clipboard {cid, files, total}
    this.inbox = null; // the partner's last clipboard with files {cid, files, total, fetched}
    this.pushes = new Map(); // cid → Promise of the partner's "ready"
    this.failed = new Set(); // cids whose files could not be sent (not retried automatically)
    this.delivered = new Set(); // cids whose files are in the partner's clipboard
    this.waiters = new Map(); // cid → resolve
    this.offReady = api.on('clipboard:ready', (cid) => this.rtc()?.sendControl({ type: 'clipboard-ready', cid }));
  }

  get rich() {
    return versionAtLeast(this.peerVersion(), '1.2.0');
  }

  /** Our clipboard changed (payload from the main process, see clipboard-transfer.js). */
  local(payload) {
    const rtc = this.rtc();
    if (!payload || !rtc || !this.enabled()) return;
    if (!this.rich) {
      if (payload.text) rtc.sendControl({ type: 'clipboard', text: payload.text });
      return;
    }
    const { cid, text, html, rtf, png, total } = payload;
    const files = payload.files && this.filesAllowed() ? payload.files : null;
    if (payload.files && !files) return; // copied files, but no file transfers in this session
    this.outbox = files ? { cid, files, total } : null;
    rtc.sendControl({
      type: 'clipboard', cid, text, html, rtf, ...(png ? { image: { size: png.byteLength } } : {}), ...(files ? { files, total } : {}),
    });
    if (png) {
      const blob = new Blob([png], { type: 'image/png' });
      const file = { name: 'clipboard.png', size: blob.size, slice: (a, b) => blob.slice(a, b) };
      rtc.sendFile(file, quiet, { clip: { cid, kind: 'image', n: 1 } });
    }
  }

  /** Clipboard and clipboard-* control messages from the partner. Returns true when handled. */
  onControl(msg) {
    switch (msg?.type) {
      case 'clipboard':
        if (!this.enabled()) return true;
        if (typeof msg.cid === 'string' && Array.isArray(msg.files) && msg.files.length) {
          if (!this.filesAllowed()) return true;
          this.inbox = { cid: msg.cid, files: msg.files, total: Number(msg.total) || 0, fetched: false };
        } else if (typeof msg.cid === 'string') {
          this.inbox = null;
        }
        api.send('clipboard:remote', typeof msg.cid === 'string' ? msg : String(msg.text ?? ''));
        return true;
      case 'clipboard-fetch':
        if (this.enabled() && msg.cid === this.outbox?.cid) this.push(msg.cid);
        return true;
      case 'clipboard-files':
        if (this.enabled() && msg.cid === this.inbox?.cid) {
          api.invoke('clipboard:expect', { cid: msg.cid, n: msg.n, folders: msg.folders }).then((res) => {
            if (res?.ready) this.rtc()?.sendControl({ type: 'clipboard-ready', cid: msg.cid });
          });
        }
        return true;
      case 'clipboard-ready':
        this.waiters.get(msg.cid)?.();
        return true;
      default:
        return false;
    }
  }

  /** Our copied files, not sent yet. */
  get pendingOut() {
    const box = this.outbox;
    return box && !this.pushes.has(box.cid) && !this.failed.has(box.cid) ? box : null;
  }

  /** Our copied files, not in the partner's clipboard yet (maybe on their way): a paste waits for them. */
  get undelivered() {
    const box = this.outbox;
    return box && !this.delivered.has(box.cid) && !this.failed.has(box.cid) ? box : null;
  }

  /**
   * Sends our copied files (announcement `cid`); resolves once they are in
   * the partner's clipboard (or false on failure).
   */
  push(cid = this.outbox?.cid) {
    if (!cid) return Promise.resolve(false);
    if (this.pushes.has(cid)) return this.pushes.get(cid);
    const p = (async () => {
      const rtc = this.rtc();
      const list = await api.invoke('clipboard:list', cid);
      if (!rtc || !list) return false;
      const files = list.filter((e) => !e.dir);
      const folders = list.filter((e) => e.dir).map((e) => e.rel);
      const ready = new Promise((resolve) => {
        this.waiters.set(cid, () => resolve(true));
      });
      rtc.sendControl({ type: 'clipboard-files', cid, n: files.length, folders });
      // A few at a time: each transfer has its own data channel.
      const queue = files.slice();
      let sent = true;
      const worker = async () => {
        while (queue.length && sent) {
          const entry = queue.shift();
          const file = {
            name: entry.rel.split('/').pop(),
            size: entry.size,
            slice: (start, end) => ({ arrayBuffer: () => api.invoke('clipboard:read', cid, entry.id, start, end).then((u8) => u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.byteLength)) }),
          };
          const view = this.view(entry.rel, entry.size, 'out');
          if (!(await rtc.sendFile(file, view, { clip: { cid, kind: 'file', rel: entry.rel, n: files.length } }))) sent = false;
        }
      };
      await Promise.all([worker(), worker(), worker()]);
      // All delivered: the partner writes its clipboard, then says so.
      const ok = sent && (await Promise.race([ready, new Promise((r) => setTimeout(() => r(false), 60_000))]));
      this.waiters.delete(cid);
      if (ok) this.delivered.add(cid);
      else {
        this.pushes.delete(cid);
        this.failed.add(cid);
      }
      return ok;
    })();
    this.pushes.set(cid, p);
    return p;
  }

  /** Asks the partner for the files it announced (once). */
  fetch() {
    const box = this.inbox;
    if (!box || box.fetched) return false;
    box.fetched = true;
    this.rtc()?.sendControl({ type: 'clipboard-fetch', cid: box.cid });
    return true;
  }

  /** Session start: our current clipboard. */
  async sendCurrent() {
    if (!this.enabled()) return;
    this.local(await api.invoke('clipboard:current'));
  }

  dispose() {
    this.offReady?.();
  }
}

// WebRTC session shared by the viewer (controller) and the host panel.
//
// The host is always the offerer: it owns the screen track and creates the
// "control" (JSON messages) and "input" (mouse/keyboard events) channels.
// Files travel on dedicated "file:<id>" channels with back-pressure.

import { api, h, icon, t, formatBytes } from './ui.js';

const CHUNK_SIZE = 64 * 1024;
const HIGH_WATER = 4 * 1024 * 1024;
const IPC_BATCH = 512 * 1024;

const randomId = () => Array.from(crypto.getRandomValues(new Uint8Array(8)), (b) => b.toString(16).padStart(2, '0')).join('');

export class RtcSession extends EventTarget {
  constructor({ role, iceServers }) {
    super();
    this.role = role;
    this.pc = new RTCPeerConnection({ iceServers, bundlePolicy: 'max-bundle', iceCandidatePoolSize: 2 });
    this.control = null;
    this.input = null;
    this.pendingCandidates = [];
    this.fileWaiters = new Map();
    this.incomingFiles = new Map();
    this.closed = false;
    this.makingOffer = false;
    this.acceptFile = async () => ({ ok: false });

    const pc = this.pc;
    pc.onicecandidate = ({ candidate }) => {
      if (candidate) api.send('session:signal', { candidate: candidate.toJSON() });
    };
    pc.onconnectionstatechange = () => this.#emit('state', pc.connectionState);
    pc.ontrack = (event) => this.#emit('track', event);
    pc.ondatachannel = ({ channel }) => this.#adoptChannel(channel);

    if (role === 'host') {
      this.#adoptChannel(pc.createDataChannel('control', { ordered: true }));
      this.#adoptChannel(pc.createDataChannel('input', { ordered: true }));
      pc.onnegotiationneeded = () => this.#offer();
    }
    this.offSignal = api.on('session:signal', (data) => this.#onSignal(data));
  }

  #emit(type, detail) {
    this.dispatchEvent(new CustomEvent(type, { detail }));
  }

  /** Tells the main process we are ready to receive queued signaling. */
  ready() {
    api.send('session:ready');
  }

  async #offer(options) {
    if (this.closed) return;
    try {
      this.makingOffer = true;
      const offer = await this.pc.createOffer(options);
      await this.pc.setLocalDescription(offer);
      api.send('session:signal', { description: this.pc.localDescription.toJSON() });
    } catch (err) {
      console.error('offer failed', err);
    } finally {
      this.makingOffer = false;
    }
  }

  restartIce() {
    if (this.role === 'host' && !this.closed) this.#offer({ iceRestart: true });
  }

  async #onSignal(data) {
    if (this.closed || !data) return;
    const pc = this.pc;
    try {
      if (data.description) {
        await pc.setRemoteDescription(data.description);
        if (data.description.type === 'offer') {
          await pc.setLocalDescription(await pc.createAnswer());
          api.send('session:signal', { description: pc.localDescription.toJSON() });
        }
        for (const c of this.pendingCandidates.splice(0)) await pc.addIceCandidate(c).catch(() => {});
      } else if (data.candidate) {
        if (!pc.remoteDescription) this.pendingCandidates.push(data.candidate);
        else await pc.addIceCandidate(data.candidate).catch(() => {});
      }
    } catch (err) {
      console.error('signaling error', err);
    }
  }

  #adoptChannel(channel) {
    if (channel.label === 'control') {
      this.control = channel;
      channel.onopen = () => this.#emit('control-open');
      channel.onclose = () => this.#emit('control-close');
      channel.onmessage = (e) => {
        let msg;
        try {
          msg = JSON.parse(e.data);
        } catch {
          return;
        }
        this.#onControl(msg);
      };
    } else if (channel.label === 'input') {
      this.input = channel;
      channel.onmessage = (e) => {
        try {
          this.#emit('input', JSON.parse(e.data));
        } catch {
          /* ignore malformed input */
        }
      };
    } else if (channel.label.startsWith('file:')) {
      this.#receiveFileChannel(channel);
    } else {
      channel.close();
    }
  }

  sendControl(msg) {
    if (this.control?.readyState === 'open') this.control.send(JSON.stringify(msg));
  }

  sendInput(events) {
    if (this.input?.readyState === 'open' && events.length) this.input.send(JSON.stringify(events));
  }

  #onControl(msg) {
    switch (msg?.type) {
      case 'file-offer':
        this.#onFileOffer(msg);
        return;
      case 'file-accept':
      case 'file-reject':
      case 'file-done':
        this.fileWaiters.get(msg.fid)?.(msg);
        return;
      case 'file-cancel': {
        const rx = this.incomingFiles.get(msg.fid);
        if (rx) this.#abortIncoming(rx);
        return;
      }
      default:
        this.#emit('control', msg);
    }
  }

  // ───────────── sending files ─────────────

  #waitFor(fid, types, timeoutMs) {
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.fileWaiters.delete(fid);
        resolve({ type: 'timeout' });
      }, timeoutMs);
      this.fileWaiters.set(fid, (msg) => {
        if (!types.includes(msg.type)) return;
        clearTimeout(timer);
        this.fileWaiters.delete(fid);
        resolve(msg);
      });
    });
  }

  async sendFile(file, view) {
    const fid = randomId();
    view.update({ status: 'pending' });
    const answer = this.#waitFor(fid, ['file-accept', 'file-reject'], 60_000);
    this.sendControl({ type: 'file-offer', fid, name: file.name, size: file.size });
    const reply = await answer;
    if (reply.type !== 'file-accept') {
      view.update({ status: reply.type === 'file-reject' ? 'rejected' : 'failed' });
      return false;
    }
    const done = this.#waitFor(fid, ['file-done'], 10 * 60_000);
    if (file.size > 0) {
      const dc = this.pc.createDataChannel(`file:${fid}`, { ordered: true });
      dc.binaryType = 'arraybuffer';
      dc.bufferedAmountLowThreshold = HIGH_WATER / 2;
      await new Promise((resolve, reject) => {
        dc.onopen = resolve;
        dc.onerror = reject;
        dc.onclose = reject;
      }).catch(() => null);
      if (dc.readyState !== 'open') {
        view.update({ status: 'failed' });
        return false;
      }
      let offset = 0;
      try {
        while (offset < file.size) {
          if (dc.readyState !== 'open') throw new Error('channel closed');
          if (dc.bufferedAmount > HIGH_WATER) {
            await new Promise((resolve) => {
              dc.onbufferedamountlow = () => {
                dc.onbufferedamountlow = null;
                resolve();
              };
            });
          }
          const buf = await file.slice(offset, offset + CHUNK_SIZE).arrayBuffer();
          dc.send(buf);
          offset += buf.byteLength;
          view.update({ progress: offset / file.size });
        }
      } catch {
        this.sendControl({ type: 'file-cancel', fid });
        view.update({ status: 'failed' });
        return false;
      }
    }
    const result = await done;
    const ok = result.type === 'file-done' && result.ok;
    view.update({ status: ok ? 'sent' : 'failed', progress: 1 });
    return ok;
  }

  // ───────────── receiving files ─────────────

  async #onFileOffer(msg) {
    const size = Number(msg.size);
    const name = String(msg.name || 'file').slice(0, 255);
    if (!msg.fid || !Number.isSafeInteger(size) || size < 0) return;
    const res = await this.acceptFile({ name, size });
    if (!res.ok) {
      this.sendControl({ type: 'file-reject', fid: msg.fid });
      return;
    }
    const rx = { fid: msg.fid, token: res.token, name: res.name, size, received: 0, buffer: [], buffered: 0, view: res.view };
    this.incomingFiles.set(msg.fid, rx);
    this.sendControl({ type: 'file-accept', fid: msg.fid });
    if (size === 0) this.#finishIncoming(rx);
  }

  #receiveFileChannel(channel) {
    const fid = channel.label.slice(5);
    const rx = this.incomingFiles.get(fid);
    if (!rx) {
      channel.close();
      return;
    }
    channel.binaryType = 'arraybuffer';
    channel.onmessage = (e) => {
      if (!(e.data instanceof ArrayBuffer)) return;
      const chunk = new Uint8Array(e.data);
      rx.received += chunk.length;
      rx.buffer.push(chunk);
      rx.buffered += chunk.length;
      if (rx.buffered >= IPC_BATCH || rx.received >= rx.size) this.#flushIncoming(rx);
      rx.view?.update({ progress: rx.size ? rx.received / rx.size : 1 });
      if (rx.received >= rx.size) {
        channel.onclose = null;
        this.#finishIncoming(rx);
      }
    };
    channel.onclose = () => {
      if (this.incomingFiles.get(fid) === rx) this.#abortIncoming(rx);
    };
  }

  #flushIncoming(rx) {
    if (!rx.buffered) return;
    const merged = new Uint8Array(rx.buffered);
    let off = 0;
    for (const part of rx.buffer) {
      merged.set(part, off);
      off += part.length;
    }
    rx.buffer = [];
    rx.buffered = 0;
    api.send('files:chunk', rx.token, merged);
  }

  async #finishIncoming(rx) {
    this.incomingFiles.delete(rx.fid);
    this.#flushIncoming(rx);
    const res = await api.invoke('files:end', rx.token);
    this.sendControl({ type: 'file-done', fid: rx.fid, ok: res.ok });
    rx.view?.update({ status: res.ok ? 'received' : 'failed', progress: 1, path: res.path });
  }

  #abortIncoming(rx) {
    this.incomingFiles.delete(rx.fid);
    api.send('files:abort', rx.token);
    rx.view?.update({ status: 'failed' });
  }

  async stats() {
    const out = { rtt: null, fps: null, bitrate: null, width: null, height: null, relayed: null };
    if (this.closed) return out;
    const report = await this.pc.getStats();
    let pair = null;
    const byId = new Map();
    report.forEach((s) => byId.set(s.id, s));
    report.forEach((s) => {
      if (s.type === 'transport' && s.selectedCandidatePairId) pair = byId.get(s.selectedCandidatePairId);
      if (s.type === 'inbound-rtp' && s.kind === 'video') {
        out.fps = s.framesPerSecond ?? null;
        out.width = s.frameWidth ?? null;
        out.height = s.frameHeight ?? null;
        const now = s.timestamp;
        if (this.lastBytes && now > this.lastBytes.ts) out.bitrate = ((s.bytesReceived - this.lastBytes.bytes) * 8 * 1000) / (now - this.lastBytes.ts);
        this.lastBytes = { bytes: s.bytesReceived, ts: now };
      }
    });
    if (pair) {
      out.rtt = pair.currentRoundTripTime != null ? Math.round(pair.currentRoundTripTime * 1000) : null;
      const local = byId.get(pair.localCandidateId);
      const remote = byId.get(pair.remoteCandidateId);
      out.relayed = local?.candidateType === 'relay' || remote?.candidateType === 'relay';
    }
    return out;
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    this.offSignal?.();
    for (const rx of this.incomingFiles.values()) this.#abortIncoming(rx);
    try {
      this.pc.close();
    } catch {
      /* ignore */
    }
  }
}

/** Progress widget for file transfers (bottom-left stack). */
export class TransferList {
  constructor({ container = null, onChange = () => {} } = {}) {
    this.el = container || h('div', { class: 'transfers' });
    if (!container) document.body.append(this.el);
    this.onChange = onChange;
  }

  add(name, size, direction) {
    const label = h('span', { class: 'name grow' });
    const sizeEl = h('span', { class: 'faint small' }, formatBytes(size));
    const bar = h('div', { style: { width: '0%' } });
    const actions = h('span');
    const item = h('div', { class: 'transfer' },
      h('div', { class: 'row' }, icon(direction === 'out' ? 'upload' : 'download', 'sm'), label, sizeEl, actions),
      h('div', { class: 'progress' }, bar));
    this.el.append(item);
    this.onChange();
    const key = direction === 'out' ? 'files.sending' : 'files.receiving';
    label.textContent = t(key, { name });
    label.title = name;
    const remove = (delay) => setTimeout(() => {
      item.remove();
      this.onChange();
    }, delay);
    return {
      update({ progress, status, path } = {}) {
        if (progress != null) bar.style.width = `${Math.round(progress * 100)}%`;
        if (status === 'sent' || status === 'received') {
          label.textContent = t(status === 'sent' ? 'files.sent' : 'files.received', { name });
          bar.style.background = 'var(--success)';
          if (path) {
            actions.replaceChildren(h('button', { class: 'btn small ghost', onclick: () => api.invoke('files:show', path) }, t('files.open')));
          }
          remove(path ? 12_000 : 5000);
        } else if (status === 'failed' || status === 'rejected') {
          label.textContent = t(status === 'rejected' ? 'files.rejected' : 'files.failed', { name });
          bar.style.background = 'var(--danger)';
          remove(6000);
        }
      },
    };
  }
}

/** Chat panel content shared by the viewer and the host panel. */
export class ChatView {
  constructor({ onSend }) {
    this.list = h('div', { class: 'chat-messages' }, h('div', { class: 'chat-empty' }, t('chat.empty')));
    this.input = h('input', { class: 'input', placeholder: t('chat.placeholder'), maxlength: 2000 });
    this.form = h('form', {
      class: 'chat-form',
      onsubmit: (e) => {
        e.preventDefault();
        const text = this.input.value.trim();
        if (!text) return;
        onSend(text);
        this.add(text, true);
        this.input.value = '';
      },
    }, this.input, h('button', { class: 'btn primary', type: 'submit', title: t('chat.send') }, icon('send', 'sm')));
  }

  add(text, mine, author = '') {
    this.list.querySelector('.chat-empty')?.remove();
    const time = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    this.list.append(h('div', { class: `chat-msg${mine ? ' mine' : ''}` },
      h('span', { class: 'meta' }, `${mine ? t('chat.you') : author} · ${time}`), text));
    this.list.scrollTop = this.list.scrollHeight;
  }
}

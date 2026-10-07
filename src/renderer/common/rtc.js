// WebRTC session shared by the viewer (controller) and the host panel.
//
// The host is always the offerer: it owns the screen track and creates the
// "control" (JSON messages), "input" (clicks, keys: ordered and reliable) and
// "pointer" (mouse moves: unordered, never retransmitted, latest wins)
// channels. Files travel on dedicated "file:<id>" channels, paced so that
// they never delay the mouse and keyboard.

import { api, h, icon, t, formatBytes } from './ui.js';
import { FilePacer } from './file-pacer.js';

// Small chunks and a small send buffer: input messages share the connection
// and wait behind whatever is already queued.
const CHUNK_SIZE = 16 * 1024;
const HIGH_WATER = 128 * 1024;
const LOW_WATER = 32 * 1024;
const IPC_BATCH = 512 * 1024;
const PING_INTERVAL_MS = 250;

const randomId = () => Array.from(crypto.getRandomValues(new Uint8Array(8)), (b) => b.toString(16).padStart(2, '0')).join('');

export class RtcSession extends EventTarget {
  constructor({ role, iceServers }) {
    super();
    this.role = role;
    this.pc = new RTCPeerConnection({ iceServers, bundlePolicy: 'max-bundle', iceCandidatePoolSize: 2 });
    this.control = null;
    this.input = null;
    this.pointer = null;
    this.pendingCandidates = [];
    this.pacer = new FilePacer();
    this.sendingFiles = 0;
    this.peerPongs = false; // the partner answers pings (1.1+): files can be paced
    this.pingTimer = null;
    this.fileWaiters = new Map();
    this.incomingFiles = new Map();
    this.closed = false;
    this.makingOffer = false;
    this.acceptFile = async () => ({ ok: false });

    const pc = this.pc;
    pc.onicecandidate = ({ candidate }) => {
      if (!candidate) {
        console.info('[rtc] local ICE gathering complete');
        return;
      }
      console.info(`[rtc] local candidate ${candidate.type || '?'} ${candidate.protocol || ''}`);
      api.send('session:signal', { candidate: candidate.toJSON() });
    };
    pc.oniceconnectionstatechange = () => console.info(`[rtc] ice ${pc.iceConnectionState}`);
    pc.onconnectionstatechange = () => {
      console.info(`[rtc] connection ${pc.connectionState}`);
      this.#emit('state', pc.connectionState);
    };
    pc.ontrack = (event) => this.#emit('track', event);
    pc.ondatachannel = ({ channel }) => this.#adoptChannel(channel);

    if (role === 'host') {
      this.#adoptChannel(pc.createDataChannel('control', { ordered: true }));
      this.#adoptChannel(pc.createDataChannel('input', { ordered: true }));
      this.#adoptChannel(pc.createDataChannel('pointer', { ordered: false, maxRetransmits: 0 }));
      pc.onnegotiationneeded = () => this.#offer();
    }
    // Signaling messages are applied strictly one after the other.
    this.signalChain = Promise.resolve();
    this.offSignal = api.on('session:signal', (data) => {
      this.signalChain = this.signalChain.then(() => this.#onSignal(data));
    });
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
      console.info(`[rtc] sending offer${options?.iceRestart ? ' (ICE restart)' : ''}`);
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

  /** New offer with the current transceiver settings (e.g. another codec). */
  renegotiate() {
    if (this.role === 'host' && !this.closed) this.#offer();
  }

  async #onSignal(data) {
    if (this.closed || !data) return;
    const pc = this.pc;
    try {
      if (data.description) {
        console.info(`[rtc] received ${data.description.type}`);
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
    } else if (channel.label === 'input' || channel.label === 'pointer') {
      if (channel.label === 'input') this.input = channel;
      else this.pointer = channel;
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

  static #send(channel, data) {
    if (channel?.readyState !== 'open') return false;
    try {
      channel.send(data);
      return true;
    } catch (err) {
      // e.g. a message above the SCTP size limit, or a channel closing.
      console.warn(`[rtc] send on ${channel.label} failed: ${err.message}`);
      return false;
    }
  }

  sendControl(msg) {
    return RtcSession.#send(this.control, JSON.stringify(msg));
  }

  /** Clicks, keys, wheel: ordered and reliable. */
  sendInput(events) {
    if (events.length) RtcSession.#send(this.input, JSON.stringify(events));
  }

  /** Mouse moves: latest wins (falls back to the reliable channel on hosts older than 1.1). */
  sendPointer(events) {
    if (!events.length) return;
    if (!RtcSession.#send(this.pointer, JSON.stringify(events))) this.sendInput(events);
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
      case 'ping':
        this.sendControl({ type: 'pong', t: msg.t });
        return;
      case 'pong':
        if (Number.isFinite(msg.t)) {
          this.peerPongs = true;
          this.pacer.onRtt(performance.now() - msg.t);
        }
        return;
      default:
        this.#emit('control', msg);
    }
  }

  // ───────────── sending files ─────────────

  /** Resolves with the first `types` message for `fid`; the timeout can start later (startTimeout). */
  #waitFor(fid, types, timeoutMs = null) {
    let timer = null;
    let finish;
    const promise = new Promise((resolve) => {
      finish = (msg) => {
        clearTimeout(timer);
        if (this.fileWaiters.get(fid) === waiter) this.fileWaiters.delete(fid);
        resolve(msg);
      };
    });
    const waiter = (msg) => {
      if (types.includes(msg.type)) finish(msg);
    };
    this.fileWaiters.set(fid, waiter);
    promise.startTimeout = (ms) => {
      clearTimeout(timer);
      timer = setTimeout(() => finish({ type: 'timeout' }), ms);
    };
    if (timeoutMs != null) promise.startTimeout(timeoutMs);
    return promise;
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
    // The completion timeout only starts once everything is sent: a slow
    // link is not a failure.
    const done = this.#waitFor(fid, ['file-done']);
    if (file.size > 0) {
      const dc = this.pc.createDataChannel(`file:${fid}`, { ordered: true });
      dc.binaryType = 'arraybuffer';
      dc.bufferedAmountLowThreshold = LOW_WATER;
      await new Promise((resolve, reject) => {
        dc.onopen = resolve;
        dc.onerror = reject;
        dc.onclose = reject;
      }).catch(() => null);
      if (dc.readyState !== 'open') {
        done.startTimeout(0);
        view.update({ status: 'failed' });
        return false;
      }
      let offset = 0;
      this.#startPings();
      try {
        while (offset < file.size) {
          if (dc.readyState !== 'open') throw new Error('channel closed');
          if (dc.bufferedAmount > HIGH_WATER) {
            await new Promise((resolve) => {
              const timer = setTimeout(resolve, 1000); // in case the event is missed
              dc.onbufferedamountlow = () => {
                clearTimeout(timer);
                dc.onbufferedamountlow = null;
                resolve();
              };
            });
            continue;
          }
          const buf = await file.slice(offset, offset + CHUNK_SIZE).arrayBuffer();
          // Peers older than 1.1 never answer pings: no pacing for them
          // (plain buffer-based flow control, as before).
          if (this.peerPongs) await this.pacer.take(buf.byteLength);
          if (dc.readyState !== 'open') throw new Error('channel closed');
          dc.send(buf);
          offset += buf.byteLength;
          view.update({ progress: offset / file.size });
        }
      } catch {
        done.startTimeout(0);
        this.sendControl({ type: 'file-cancel', fid });
        view.update({ status: 'failed' });
        return false;
      } finally {
        this.#stopPings();
      }
    }
    done.startTimeout(10 * 60_000);
    const result = await done;
    const ok = result.type === 'file-done' && result.ok;
    view.update({ status: ok ? 'sent' : 'failed', progress: 1 });
    return ok;
  }

  // Round-trip probes while files are being sent: they drive the file pacer.
  #startPings() {
    if (this.sendingFiles++ > 0) return;
    this.pingTimer = setInterval(() => this.sendControl({ type: 'ping', t: performance.now() }), PING_INTERVAL_MS);
  }

  #stopPings() {
    if (--this.sendingFiles > 0) return;
    this.sendingFiles = 0;
    clearInterval(this.pingTimer);
    this.pingTimer = null;
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

  async #report() {
    const report = await this.pc.getStats();
    const byId = new Map();
    report.forEach((s) => byId.set(s.id, s));
    let pair = null;
    report.forEach((s) => {
      if (s.type === 'transport' && s.selectedCandidatePairId) pair = byId.get(s.selectedCandidatePairId);
    });
    return { report, byId, pair };
  }

  static #rate(prev, cur, num, den, factor = 1) {
    if (!prev || !cur) return null;
    const dd = (cur[den] ?? 0) - (prev[den] ?? 0);
    if (dd <= 0) return null;
    return (((cur[num] ?? 0) - (prev[num] ?? 0)) / dd) * factor;
  }

  /** Receiver (viewer) side: what the controller sees, and where the delay comes from. */
  async stats() {
    const out = {
      rtt: null, fps: null, bitrate: null, width: null, height: null, relayed: null,
      jitterBufferMs: null, decodeMs: null, freezes: null, codec: null, decoder: null,
    };
    if (this.closed) return out;
    const { report, byId, pair } = await this.#report();
    report.forEach((s) => {
      if (s.type === 'inbound-rtp' && s.kind === 'video') {
        const prev = this.lastInbound;
        out.fps = s.framesPerSecond ?? null;
        out.width = s.frameWidth ?? null;
        out.height = s.frameHeight ?? null;
        if (prev && s.timestamp > prev.timestamp) out.bitrate = ((s.bytesReceived - prev.bytesReceived) * 8 * 1000) / (s.timestamp - prev.timestamp);
        out.jitterBufferMs = RtcSession.#rate(prev, s, 'jitterBufferDelay', 'jitterBufferEmittedCount', 1000);
        out.decodeMs = RtcSession.#rate(prev, s, 'totalDecodeTime', 'framesDecoded', 1000);
        out.freezes = s.freezeCount ?? null;
        out.decoder = s.decoderImplementation ?? null;
        out.codec = byId.get(s.codecId)?.mimeType?.replace('video/', '') ?? null;
        this.lastInbound = s;
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

  /** Sender (host) side: encoder and send-queue health, used for adaptation. */
  async senderStats() {
    const out = {
      fps: null, encodeMs: null, pacerMs: null, limitation: null, bandwidth: null, rtt: null,
      codec: null, encoder: null, width: null, height: null, targetBitrate: null,
    };
    if (this.closed) return out;
    const { report, byId, pair } = await this.#report();
    report.forEach((s) => {
      if (s.type === 'outbound-rtp' && s.kind === 'video') {
        const prev = this.lastOutbound;
        out.fps = s.framesPerSecond ?? null;
        out.width = s.frameWidth ?? null;
        out.height = s.frameHeight ?? null;
        out.encodeMs = RtcSession.#rate(prev, s, 'totalEncodeTime', 'framesEncoded', 1000);
        out.pacerMs = RtcSession.#rate(prev, s, 'totalPacketSendDelay', 'packetsSent', 1000);
        out.limitation = s.qualityLimitationReason ?? null;
        out.encoder = s.encoderImplementation ?? null;
        out.targetBitrate = s.targetBitrate ?? null;
        out.codec = byId.get(s.codecId)?.mimeType?.replace('video/', '') ?? null;
        this.lastOutbound = s;
      }
    });
    if (pair) {
      out.bandwidth = pair.availableOutgoingBitrate ?? null;
      out.rtt = pair.currentRoundTripTime != null ? Math.round(pair.currentRoundTripTime * 1000) : null;
    }
    return out;
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.pingTimer);
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

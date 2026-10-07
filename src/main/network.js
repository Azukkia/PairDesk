// Owns the signaling transport (public relays or PairDesk server), the
// Signaling engine and the host passwords.

import { EventEmitter } from 'node:events';
import { MqttTransport } from './signaling/transport-mqtt.js';
import { WsTransport } from './signaling/transport-ws.js';
import { Signaling, AuthLimiter } from './signaling/signaling.js';
import { derivePrs } from './crypto/prs.js';
import { generatePassword } from '../shared/protocol.js';

function parseIceUrls(text) {
  return String(text || '').split(/[\s,;]+/).map((s) => s.trim()).filter((s) => /^(stun|turns?):/i.test(s));
}

export class Network extends EventEmitter {
  constructor({ settings, config, log, canAccept }) {
    super();
    this.settings = settings;
    this.config = config;
    this.log = log;
    this.myId = settings.get('deviceId');
    this.transport = null;
    this.serverIce = [];
    this.temp = { password: '', prs: null };
    this.limiter = new AuthLimiter();
    this.signaling = null;
    this.canAccept = canAccept;
  }

  start() {
    this.#createTransport();
    this.signaling = new Signaling({
      transport: this.transport,
      myId: this.myId,
      getCredentials: () => this.credentials(),
      canAccept: () => this.canAccept(),
      limiter: this.limiter,
      log: this.log,
    });
    this.regeneratePassword();
  }

  stop() {
    this.transport?.stop();
  }

  describeTransport() {
    const mode = this.settings.get('networkMode');
    if (this.config.forcedServerUrl) return { kind: 'server', url: this.config.forcedServerUrl };
    if (mode === 'server' && this.settings.get('serverUrl')) return { kind: 'server', url: this.settings.get('serverUrl') };
    if (this.config.defaultServerUrl) return { kind: 'server', url: this.config.defaultServerUrl };
    return { kind: 'public', brokers: this.config.publicBrokers };
  }

  #createTransport() {
    const desc = this.describeTransport();
    const transport = desc.kind === 'server'
      ? new WsTransport({ url: desc.url, log: this.log })
      : new MqttTransport({ brokers: desc.brokers, log: this.log });
    transport.on('state', () => this.emit('state', this.state));
    transport.on('ice-servers', (servers) => {
      this.serverIce = servers;
    });
    transport.on('id-taken', () => this.emit('id-taken'));
    this.transport = transport;
    this.transportDesc = desc;
    this.serverIce = [];
    transport.start(this.myId, this.settings.get('deviceKey'));
    this.log.info(`[network] using ${desc.kind === 'server' ? `server ${desc.url}` : 'public relays'}`);
  }

  /** Re-creates the transport after a network settings change. */
  restart() {
    const old = this.transport;
    this.#createTransport();
    // Established sessions are kept: only the transport changes.
    this.signaling.setTransport(this.transport);
    old?.removeAllListeners();
    old?.stop();
    this.emit('state', this.state);
  }

  get state() {
    const t = this.transport;
    return {
      status: t?.state || 'offline',
      kind: this.transportDesc?.kind || 'public',
      url: this.transportDesc?.url || '',
      relays: t?.connectedBrokers?.length ?? null,
      error: t?.lastError || null,
    };
  }

  get iceServers() {
    const servers = [...this.config.iceServers];
    const custom = this.settings.get('customIce') || {};
    const urls = parseIceUrls(custom.urls);
    if (urls.length) {
      const entry = { urls };
      if (custom.username) entry.username = custom.username;
      if (custom.credential) entry.credential = custom.credential;
      servers.push(entry);
    }
    return [...servers, ...this.serverIce];
  }

  // ───────────── host passwords ─────────────

  async regeneratePassword() {
    if (!this.settings.get('useRandomPassword')) {
      this.temp = { password: '', prs: null };
      this.emit('password', '');
      return '';
    }
    const password = generatePassword(this.settings.get('passwordLength') || 6);
    this.temp = { password, prs: null };
    this.emit('password', password);
    const prs = await derivePrs(password, this.myId);
    if (this.temp.password === password) this.temp.prs = prs;
    return password;
  }

  async setPermanentPassword(password) {
    if (!password) {
      this.settings.set('permanentPrs', null);
      return;
    }
    const prs = await derivePrs(password, this.myId);
    this.settings.set('permanentPrs', this.settings.seal(prs));
  }

  credentials() {
    const creds = [];
    if (this.temp.prs) creds.push({ kind: 'temp', prs: this.temp.prs });
    const perm = this.settings.unseal(this.settings.get('permanentPrs'));
    if (perm) creds.push({ kind: 'perm', prs: perm });
    return creds;
  }
}

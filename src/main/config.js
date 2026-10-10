// Build-time configuration. Values can be customised without touching the code
// by editing pairdesk.config.json at the root of the repository (it is shipped
// inside the installer), and overridden with environment variables for tests.

import fs from 'node:fs';
import path from 'node:path';

const DEFAULTS = {
  // Public MQTT brokers used as zero-configuration relays for signaling.
  // Only end-to-end encrypted/PAKE messages go through them.
  publicBrokers: [
    'wss://broker.emqx.io:8084/mqtt',
    'wss://broker.hivemq.com:8884/mqtt',
    'wss://test.mosquitto.org:8081/mqtt',
    'wss://mqtt.eclipseprojects.io:443/mqtt',
  ],
  // When set, new installations use this PairDesk server instead of the
  // public relays (users can still change it in the settings).
  defaultServerUrl: '',
  // STUN/TURN servers always offered to WebRTC.
  iceServers: [
    { urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] },
    { urls: ['stun:stun.cloudflare.com:3478'] },
  ],
  // Where to look for updates and the project page.
  homepage: 'https://github.com/Azukkia/PairDesk',
  releasesUrl: 'https://github.com/Azukkia/PairDesk/releases',
  // The Android app of the latest release (fixed name: a permanent link).
  androidApkUrl: 'https://github.com/Azukkia/PairDesk/releases/latest/download/PairDesk.apk',
};

export function loadConfig(appPath) {
  let fileConfig = {};
  try {
    fileConfig = JSON.parse(fs.readFileSync(path.join(appPath, 'pairdesk.config.json'), 'utf8'));
  } catch {
    /* optional file */
  }
  const config = { ...DEFAULTS, ...fileConfig };
  if (process.env.PAIRDESK_MQTT_BROKERS) config.publicBrokers = process.env.PAIRDESK_MQTT_BROKERS.split(',').filter(Boolean);
  if (process.env.PAIRDESK_SERVER_URL) config.forcedServerUrl = process.env.PAIRDESK_SERVER_URL;
  if (process.env.PAIRDESK_ICE_SERVERS) {
    try {
      config.iceServers = JSON.parse(process.env.PAIRDESK_ICE_SERVERS);
    } catch {
      /* ignore */
    }
  }
  return config;
}

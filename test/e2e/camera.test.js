// End-to-end test of a camera session: a phone streams its camera to a
// PairDesk computer (docs/PROTOCOL.md 6). The test plays the phone: its
// signaling runs here (the desktop signaling code, controller side, with
// intro.kind = 'camera') and its WebRTC side in a page of the computer's
// app, which sends a red test pattern instead of a camera.
//
// On Windows (PairDesk Camera DLL built: npm run build:camera) the picture
// must also come out of the "PairDesk Camera" virtual webcam.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { Signaling } from '../../src/main/signaling/signaling.js';
import { MqttTransport } from '../../src/main/signaling/transport-mqtt.js';
import { WsTransport } from '../../src/main/signaling/transport-ws.js';
import { derivePrs } from '../../src/main/crypto/prs.js';
import { generateDeviceId } from '../../src/shared/protocol.js';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { artifacts, startSignaling, launch, waitForWindow, poll, sleep, dumpDiagnostics } from './helpers.js';

const quiet = { info() {}, warn: (m) => process.stderr.write(`[phone] ${m}\n`), error: (m) => process.stderr.write(`[phone] ${m}\n`) };

/** The phone's WebRTC side: answers the computer's offer with a test pattern. */
const PHONE_PAGE = `<!doctype html><body style="margin:0;background:#000"><script>
window.__out = []; window.__in = [];
const canvas = document.createElement('canvas'); canvas.width = 640; canvas.height = 480;
const g = canvas.getContext('2d'); let n = 0;
setInterval(() => {
  g.fillStyle = '#ff2000'; g.fillRect(0, 0, 640, 480);
  g.fillStyle = '#ffffff'; g.fillRect((n++ * 8) % 600, 20, 40, 40);
}, 33);
const stream = canvas.captureStream(30);
const pc = new RTCPeerConnection();
pc.onicecandidate = (e) => { if (e.candidate) window.__out.push({ candidate: e.candidate.toJSON() }); };
pc.ondatachannel = ({ channel }) => {
  if (channel.label !== 'control') return;
  const info = () => channel.send(JSON.stringify({ type: 'camera-info', width: 640, height: 480, facing: 'back' }));
  if (channel.readyState === 'open') info(); else channel.onopen = info;
  channel.onmessage = (m) => {
    const msg = JSON.parse(m.data);
    window.__in.push(msg);
    if (msg.type === 'camera-hello') info();
  };
};
window.__signal = async (data) => {
  if (data.description) {
    await pc.setRemoteDescription(data.description);
    if (data.description.type === 'offer') {
      const tr = pc.getTransceivers().find((t) => t.receiver.track.kind === 'video');
      tr.direction = 'sendonly';
      await tr.sender.replaceTrack(stream.getVideoTracks()[0]);
      await pc.setLocalDescription(await pc.createAnswer());
      window.__out.push({ description: pc.localDescription.toJSON() });
    }
  } else if (data.candidate) {
    await pc.addIceCandidate(data.candidate).catch(() => {});
  }
};
</script></body>`;

async function startPhone(env) {
  const transport = env.PAIRDESK_SERVER_URL
    ? new WsTransport({ url: env.PAIRDESK_SERVER_URL, log: quiet })
    : new MqttTransport({ brokers: env.PAIRDESK_MQTT_BROKERS.split(','), log: quiet });
  const myId = generateDeviceId();
  transport.start(myId, randomBytes(32).toString('base64url'));
  await poll(() => transport.state === 'online', { message: 'phone signaling online' });
  const signaling = new Signaling({ transport, myId, getCredentials: () => [], log: quiet });
  return { transport, signaling, myId };
}

test('camera session: a phone streams its camera to the computer', { timeout: 180_000 }, async (t) => {
  const net = await startSignaling();
  // Without the Windows webcam DLL, a fake one still checks the frames' path.
  const pc = await launch('pc', { ...net.env, ...(process.platform === 'win32' ? {} : { PAIRDESK_FAKE_VCAM: '1' }) });
  pc.name = 'pc';
  const phone = await startPhone(net.env);
  t.after(async () => {
    phone.signaling.removeAllListeners();
    phone.transport.stop();
    await pc.app.close().catch(() => {});
    await net.close();
  });
  try {
    await scenario(pc, phone);
  } catch (err) {
    await dumpDiagnostics([pc]);
    throw err;
  }
});

async function scenario(pc, phone) {
  await poll(async () => (await pc.main.$('.dot.online')) !== null, { message: 'computer online' });
  const hostId = (await pc.main.textContent('#my-id')).replace(/\s/g, '');
  const password = (await pc.main.textContent('#my-password')).trim();

  // The phone's WebRTC page, in the computer's app (separate session).
  await pc.app.evaluate(({ BrowserWindow }, html) => {
    const win = new BrowserWindow({ width: 320, height: 240, show: false, webPreferences: { partition: 'e2e-phone', backgroundThrottling: false } });
    win.loadURL(`data:text/html,${encodeURIComponent(html)}`);
    globalThis.__phone = win;
  }, PHONE_PAGE);
  const phoneRun = (code) => pc.app.evaluate((_e, js) => globalThis.__phone.webContents.executeJavaScript(js), code);
  await poll(() => phoneRun('typeof window.__signal === "function"'), { message: 'phone page loaded' });

  // Connect with intro.kind = 'camera'.
  const prs = await derivePrs(password, hostId);
  const session = await phone.signaling.connect(hostId, prs, {
    intro: { name: 'Téléphone de test', platform: 'android', appVersion: '1.2.0', kind: 'camera' },
  });
  assert.equal(session.info.kind, 'camera', 'the computer accepts a camera session');
  assert.equal(session.info.caps.control, false);

  // Signaling relay between the phone's page and the computer.
  let closed = null;
  phone.signaling.on('message', (sid, msg) => {
    if (sid === session.sid && msg?.type === 'signal') phoneRun(`window.__signal(${JSON.stringify(msg.data)})`).catch(() => {});
  });
  phone.signaling.on('closed', (sid, reason) => {
    if (sid === session.sid) closed = reason || 'closed';
  });
  let relaying = true;
  const relay = (async () => {
    while (relaying) {
      const out = await phoneRun('window.__out.splice(0)').catch(() => []);
      for (const data of out) await phone.signaling.send(session.sid, { type: 'signal', data }).catch(() => {});
      await sleep(80);
    }
  })();

  try {
    // The camera window shows the phone's picture.
    const win = await waitForWindow(pc.app, '/camera/');
    win.on('pageerror', (e) => process.stderr.write(`[camera pageerror] ${e.message}\n`));
    await poll(() => win.evaluate(() => {
      const v = document.querySelector('#camera-video');
      return v && v.videoWidth === 640 && v.currentTime > 0;
    }), { timeout: 45_000, message: 'phone camera shown in the camera window' });
    await poll(() => win.evaluate(() => document.querySelector('[data-facing]')?.dataset.facing === 'back'), { message: 'camera-info shown' });
    await win.screenshot({ path: path.join(artifacts, 'camera-window.png') }).catch(() => {});

    // "Switch camera" reaches the phone.
    await win.click('#camera-switch');
    await poll(async () => (await phoneRun('window.__in')).some((m) => m.type === 'camera-switch'), { message: 'camera-switch received by the phone' });

    // The virtual webcam.
    const vcamState = () => win.evaluate(() => document.querySelector('#camera-vcam')?.dataset.state);
    const vcam = await poll(async () => {
      const state = await vcamState();
      return state && state !== 'starting' ? { state, text: await win.textContent('#camera-vcam') } : null;
    }, { timeout: 30_000, message: 'virtual webcam status' });
    process.stderr.write(`virtual webcam: ${vcam.state}: ${vcam.text}\n`);
    assert.ok(['ready', 'in-use'].includes(vcam.state), `virtual webcam ready: ${vcam.text}`);
    // The phone's frames reach the camera helper (red in the middle).
    let stats = null;
    await poll(async () => {
      stats = await pc.main.evaluate(() => window.pairdesk.invoke('e2e:vcam'));
      const c = stats?.center;
      return stats?.frames > 5 && c && c[0] > 180 && c[1] < 90 && c[2] < 90;
    }, { timeout: 20_000, message: 'phone frames handed to the webcam' }).catch((err) => {
      throw new Error(`${err.message}: ${JSON.stringify(stats)}`);
    });
    const t0 = stats.frames;
    const shown = () => win.evaluate(() => document.querySelector('#camera-video').getVideoPlaybackQuality().totalVideoFrames);
    const q0 = await shown();
    await sleep(3000);
    const received = ((await shown()) - q0) / 3;
    const fps = ((await pc.main.evaluate(() => window.pairdesk.invoke('e2e:vcam'))).frames - t0) / 3;
    process.stderr.write(`webcam: ${JSON.stringify(stats)}, ${fps.toFixed(1)} frames/s (${received.toFixed(1)} received)\n`);
    assert.ok(fps >= 12, `webcam frame rate ${fps}`);
    if (process.platform === 'win32') {
      await checkVirtualWebcam(pc);
      await poll(async () => (await vcamState()) === 'in-use', { message: 'webcam reported in use' });
    }

    // The computer ends the session: the phone is told.
    await win.click('#camera-end');
    await poll(() => closed !== null, { message: 'phone told the session ended' });
    await poll(() => !pc.app.windows().some((w) => w.url().includes('/camera/')), { message: 'camera window closed' });
  } finally {
    relaying = false;
    await relay;
  }
}

/** Opens "PairDesk Camera" like any application would, and checks the picture is the phone's (red). */
async function checkVirtualWebcam(pc) {
  // A secure context is needed for navigator.mediaDevices: a file, not a data: URL.
  const page = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'pd-webcam-')), 'webcam.html');
  fs.writeFileSync(page, '<!doctype html><title>webcam</title><body></body>');
  const result = await pc.app.evaluate(async ({ BrowserWindow, session }, file) => {
    const ses = session.fromPartition('e2e-webcam-user');
    ses.setPermissionRequestHandler((_wc, _perm, cb) => cb(true));
    ses.setPermissionCheckHandler(() => true);
    const win = new BrowserWindow({ width: 320, height: 240, show: false, webPreferences: { partition: 'e2e-webcam-user', backgroundThrottling: false } });
    await win.loadFile(file);
    return win.webContents.executeJavaScript(`(async () => {
      const devices = await navigator.mediaDevices.enumerateDevices();
      const cams = devices.filter((d) => d.kind === 'videoinput').map((d) => d.label);
      const dev = devices.find((d) => d.kind === 'videoinput' && d.label.includes('PairDesk Camera'));
      if (!dev) return { cams };
      const stream = await navigator.mediaDevices.getUserMedia({ video: { deviceId: { exact: dev.deviceId } } });
      const video = document.createElement('video');
      video.muted = true; video.srcObject = stream; await video.play();
      const deadline = Date.now() + 15000;
      let px = null;
      while (Date.now() < deadline) {
        await new Promise((r) => setTimeout(r, 300));
        if (!video.videoWidth) continue;
        const c = document.createElement('canvas'); c.width = video.videoWidth; c.height = video.videoHeight;
        const g = c.getContext('2d'); g.drawImage(video, 0, 0);
        px = Array.from(g.getImageData(c.width >> 1, (c.height * 3) >> 2, 1, 1).data);
        if (px[0] > 180 && px[1] < 90 && px[2] < 90) break;
      }
      const size = [video.videoWidth, video.videoHeight];
      stream.getTracks().forEach((tr) => tr.stop());
      return { cams, px, size };
    })()`).finally(() => win.destroy());
  }, page);
  process.stderr.write(`webcams: ${JSON.stringify(result)}\n`);
  assert.ok(result.px, `"PairDesk Camera" listed among the webcams (${JSON.stringify(result.cams)})`);
  assert.deepEqual(result.size, [1280, 720]);
  assert.ok(result.px[0] > 180 && result.px[1] < 90 && result.px[2] < 90, `the webcam shows the phone's picture: ${result.px}`);
}

// Remote input of the incoming session, run in the "input helper" utility
// process (src/main/helper/input-helper.js).
//
// Why a separate process: on Windows, grabbing a window's title bar, opening
// the tray menu or a native dialog runs a modal loop on the thread that owns
// the window — the main process. Its JavaScript then stops until the loop
// ends, and if it also had to inject the mouse it could never inject the
// button release that ends the loop: the session froze until somebody moved
// the physical mouse of the controlled computer. Here, the host window sends
// input straight to this process through a MessagePort, and nothing on that
// path waits for the main process.

import { PointerSequencer } from '../input/pointer-seq.js';

const clamp01 = (v) => (Number.isFinite(v) ? Math.min(1, Math.max(0, v)) : 0);
const clampWheel = (v) => (Number.isFinite(v) ? Math.max(-2400, Math.min(2400, v)) : 0);

/**
 * @param {object} o
 * @param {object} o.injector      platform injector (src/main/input/*.js)
 * @param {Function} [o.createTracker]  async () => cursor tracker | null
 * @param {object} [o.guard]       { check(): null | 'elevated' | 'secure-desktop' }
 * @param {Function} o.toMain      posts a message to the main process
 */
export function createInputCore({ injector, createTracker = null, guard = null, toMain, log, guardIntervalMs = 1000 }) {
  let session = null; // { id, control, rect, scale, seq }
  let port = null;
  let tracker = null;
  let trackerPromise = null;
  let guardTimer = null;
  let blocked = null;
  let dragging = null; // drop replay in progress: remote input waits


  const toRenderer = (msg) => {
    try {
      port?.postMessage(msg);
    } catch {
      /* port closed */
    }
  };

  function startTracker() {
    if (!createTracker) return;
    trackerPromise ??= Promise.resolve(createTracker()).catch((err) => {
      log('warn', `[cursor] ${err.message}`);
      return null;
    });
    trackerPromise.then((t) => {
      tracker = t;
      if (t && session?.control) t.start((msg) => toRenderer(msg));
    });
  }

  function stopTracker() {
    tracker?.stop();
  }

  function setBlocked(value) {
    if (value === blocked) return;
    blocked = value;
    log('info', `[input] ${value ? `blocked by Windows (${value})` : 'no longer blocked'}`);
    toRenderer({ type: 'input-state', blocked: value });
    toMain({ type: 'state', blocked: value });
  }

  function startGuard() {
    if (!guard || guardTimer) return;
    const tick = () => {
      try {
        setBlocked(guard.check());
      } catch (err) {
        log('warn', `[input] guard: ${err.message}`);
      }
    };
    tick();
    guardTimer = setInterval(tick, guardIntervalMs);
  }

  function stopGuard() {
    clearInterval(guardTimer);
    guardTimer = null;
    setBlocked(null);
  }

  function inject(events) {
    if (!session || !Array.isArray(events)) return;
    const list = events.slice(0, 200);
    if (!session.control) {
      // Not injected, but numbered clicks must still count, else moves would
      // wait for them once control is allowed again.
      for (const ev of list) if (Array.isArray(ev)) session.seq.accept(ev);
      return;
    }
    const rect = session.rect;
    if (!rect) return;
    const move = (nx, ny) => injector.moveTo(rect.x + clamp01(nx) * (rect.width - 1), rect.y + clamp01(ny) * (rect.height - 1));
    for (const ev of list) {
      if (!Array.isArray(ev) || !session.seq.accept(ev)) continue;
      switch (ev[0]) {
        case 'm':
          move(ev[1], ev[2]);
          break;
        case 'd':
        case 'u':
          if (ev.length >= 4) move(ev[2], ev[3]);
          if (Number.isInteger(ev[1])) injector.button(ev[1], ev[0] === 'd');
          break;
        case 'w':
          injector.wheel(clampWheel(ev[1]), clampWheel(ev[2]));
          break;
        case 'k':
          if (typeof ev[1] === 'string' && ev[1].length < 32) injector.key(ev[1], Boolean(ev[2]));
          break;
        case 't':
          if (typeof ev[1] === 'string' && ev[1]) injector.typeText?.(ev[1].slice(0, 256));
          break;
        case 'r':
          injector.releaseAll();
          break;
        default:
          break;
      }
    }
  }

  function endSession() {
    if (!session) return;
    try {
      injector.releaseAll();
    } catch (err) {
      log('warn', `[input] release failed: ${err.message}`);
    }
    session = null;
    stopTracker();
    stopGuard();
    try {
      port?.close();
    } catch {
      /* ignore */
    }
    port = null;
  }

  function wake() {
    try {
      injector.wake?.(Boolean(session?.control));
    } catch (err) {
      log('warn', `[input] wake failed: ${err.message}`);
    }
  }

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  /**
   * Replays a drag and drop (see src/main/drops.js): press on the source
   * window at `from`, move past the drag threshold, wait for the main process
   * to start the native drag, then carry it to `to` and release.
   */
  async function drag({ from, to }) {
    if (!session?.control || dragging) return { ok: false, reason: dragging ? 'busy' : 'control' };
    let started;
    dragging = { started: new Promise((r) => { started = r; }), resolve: () => started(true) };
    try {
      injector.releaseAll();
      injector.moveTo(from.x, from.y);
      await sleep(80);
      injector.button(0, true);
      const dir = to.x >= from.x ? 1 : -1;
      for (let i = 1; i <= 8; i++) {
        injector.moveTo(from.x + dir * i * 3, from.y);
        await sleep(16);
      }
      const ok = await Promise.race([dragging.started, sleep(2000).then(() => false)]);
      if (ok) {
        // Glide to the target so it gets drag-enter/over events, then drop.
        const sx = from.x + dir * 24;
        for (let i = 1; i <= 15; i++) {
          injector.moveTo(Math.round(sx + ((to.x - sx) * i) / 15), Math.round(from.y + ((to.y - from.y) * i) / 15));
          await sleep(20);
        }
        await sleep(200);
        injector.moveTo(to.x, to.y);
        await sleep(120);
      }
      injector.button(0, false);
      await sleep(100);
      return { ok };
    } finally {
      dragging = null;
    }
  }

  function onRendererMessage(msg) {
    if (dragging) return; // the drop replay owns the mouse for a moment
    if (Array.isArray(msg)) inject(msg);
    else if (msg?.type === 'cursor-resync') tracker?.resync();
    else if (msg?.type === 'wake' && session) wake();
  }

  return {
    /** Messages from the main process (`ports` = transferred MessagePorts). */
    handleMain(msg, ports = []) {
      switch (msg?.type) {
        case 'session': {
          if (!msg.id) {
            endSession();
            return;
          }
          if (session?.id !== msg.id) {
            endSession();
            session = { id: msg.id, seq: new PointerSequencer() };
          }
          const wasControl = session.control;
          Object.assign(session, { control: Boolean(msg.control), rect: msg.rect || session.rect, scale: msg.scale || 1 });
          if (session.control && !wasControl) {
            wake();
            startTracker();
            startGuard();
          } else if (!session.control && wasControl) {
            injector.releaseAll();
            stopTracker();
            stopGuard();
          }
          return;
        }
        case 'port': {
          if (!session || msg.id !== session.id || !ports[0]) {
            ports[0]?.close();
            return;
          }
          try {
            port?.close();
          } catch {
            /* ignore */
          }
          port = ports[0];
          port.on?.('message', (e) => onRendererMessage(e.data));
          port.start?.();
          // A new host window (or a reload): it needs the cursor and state again.
          tracker?.resync();
          toRenderer({ type: 'input-state', blocked });
          return;
        }
        case 'input':
          if (session && msg.id === session.id && !dragging) inject(msg.events);
          return;
        case 'drag':
          drag(msg).then(
            (result) => toMain({ type: 'reply', rid: msg.rid, value: result }),
            (err) => toMain({ type: 'reply', rid: msg.rid, value: { ok: false, reason: err.message } }),
          );
          return;
        case 'drag-started':
          dragging?.resolve();
          return;
        case 'release':
          injector.releaseAll();
          return;
        case 'query': {
          let value = null;
          if (msg.what === 'cursor-pos') value = injector.cursorPos();
          else if (msg.what === 'cursor-debug') value = tracker?.inspect() ?? null;
          toMain({ type: 'reply', rid: msg.rid, value });
          return;
        }
        default:
          break;
      }
    },
    /** For tests: what the renderer would send through the port. */
    handleRenderer: onRendererMessage,
    get session() {
      return session;
    },
    get scale() {
      return session?.scale || 1;
    },
    dispose() {
      endSession();
      tracker?.close();
    },
  };
}

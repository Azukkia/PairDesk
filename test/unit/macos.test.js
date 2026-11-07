// macOS support, tested on any system with fake native bindings: input
// injection (Quartz events), cursor shape (NSCursor), copied files
// (NSPasteboard), Ctrl/Cmd shortcuts, privacy permissions, manual updates.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createDarwinInjector, FLAGS } from '../../src/main/input/darwin.js';
import { createDarwinCursorProbe } from '../../src/main/input/cursor-darwin.js';
import { createCursorTracker } from '../../src/main/cursor.js';
import { createInputCore } from '../../src/main/helper/input-core.js';
import { macPasteboard } from '../../src/main/clipboard-native.js';
import { createPermissions, PRIVACY_PANES } from '../../src/main/permissions.js';
import { Updater, compareVersions, macDownload } from '../../src/main/updater.js';
import { KEYMAP, MAC_KEYCODES, KEY_COMBOS } from '../../src/shared/keymap.js';
import { swapsCtrlCmd, swapCtrlCmd, macTapKey, MAC_KEY_COMBOS, heldNonModifiers } from '../../src/shared/shortcuts.js';

const log = { info() {}, warn() {}, error() {} };

/** Records the Quartz calls; events are plain objects. */
function fakeQuartz({ location = { x: 10, y: 20 } } = {}) {
  const posted = [];
  const released = [];
  let created = 0;
  const event = (props) => ({ id: ++created, fields: {}, flags: 0, ...props });
  return {
    posted,
    released,
    get created() {
      return created;
    },
    CGEventCreate: () => event({ kind: 'null', location }),
    CGEventGetLocation: (e) => ({ ...e.location }),
    CGEventCreateMouseEvent: (_src, type, position, button) => event({ kind: 'mouse', type, position: { ...position }, button }),
    CGEventCreateKeyboardEvent: (_src, key, down) => event({ kind: 'key', key, down }),
    CGEventCreateScrollWheelEvent2: (_src, units, count, w1, w2, w3) => event({ kind: 'scroll', units, count, wheels: [w1, w2, w3] }),
    CGEventSetIntegerValueField: (e, field, value) => { e.fields[field] = value; },
    CGEventSetFlags: (e, flags) => { e.flags = flags; },
    CGEventKeyboardSetUnicodeString: (e, length, chars) => { e.text = String.fromCharCode(...chars.slice(0, length)); },
    CGEventPost: (tap, e) => posted.push({ tap, ...e }),
    CFRelease: (e) => released.push(e),
    CFStringCreateWithCString: (_a, text) => ({ cfString: text }),
    IOPMAssertionDeclareUserActivity: (name, type, id) => {
      posted.push({ kind: 'activity', name, type });
      id[0] = 42;
      return 0;
    },
  };
}

test('macOS injector: moves, drags and multiple clicks', () => {
  const q = fakeQuartz();
  let now = 1000;
  const inj = createDarwinInjector(log, { quartz: q, layout: 'ANSI', now: () => now });
  assert.equal(inj.platform, 'darwin');
  inj.moveTo(100.5, 200.25); // global points, sub-point precision kept
  assert.deepEqual(q.posted.at(-1).position, { x: 100.5, y: 200.25 });
  assert.equal(q.posted.at(-1).type, 5); // kCGEventMouseMoved
  assert.equal(q.posted.at(-1).tap, 0); // kCGHIDEventTap
  assert.equal(q.posted.at(-1).fields[4], 91); // delta from the real cursor position (10, 20)

  // Double click: same place, quick succession → click state 1 then 2.
  inj.button(0, true);
  inj.button(0, false);
  now += 200;
  inj.button(0, true);
  inj.button(0, false);
  now += 200;
  inj.button(0, true);
  inj.button(0, false);
  const clicks = q.posted.filter((e) => e.kind === 'mouse' && e.type !== 5);
  assert.deepEqual(clicks.map((e) => [e.type, e.fields[1]]), [[1, 1], [2, 1], [1, 2], [2, 2], [1, 3], [2, 3]]);
  // Too late, or elsewhere: a new single click.
  now += 900;
  inj.button(0, true);
  inj.button(0, false);
  assert.equal(q.posted.at(-1).fields[1], 1);
  now += 100;
  inj.moveTo(300, 200);
  inj.button(0, true);
  assert.equal(q.posted.at(-1).fields[1], 1);

  // Moving with the left button held is a drag; the release ends it.
  inj.moveTo(310, 210);
  assert.equal(q.posted.at(-1).type, 6); // kCGEventLeftMouseDragged
  inj.button(0, false);
  inj.moveTo(320, 220);
  assert.equal(q.posted.at(-1).type, 5);

  // Right and middle buttons, back/forward as "other" buttons 3 and 4.
  inj.button(2, true);
  assert.deepEqual([q.posted.at(-1).type, q.posted.at(-1).button], [3, 1]);
  inj.moveTo(321, 221);
  assert.equal(q.posted.at(-1).type, 7); // right dragged
  inj.button(2, false);
  inj.button(1, true);
  assert.deepEqual([q.posted.at(-1).type, q.posted.at(-1).button], [25, 2]);
  inj.moveTo(322, 222);
  assert.deepEqual([q.posted.at(-1).type, q.posted.at(-1).button], [27, 2]);
  inj.button(1, false);
  inj.button(3, true);
  inj.button(3, false);
  assert.deepEqual([q.posted.at(-1).type, q.posted.at(-1).button], [26, 3]);
  inj.button(9, true); // unknown button: ignored

  // Every created event is released.
  assert.equal(q.released.length, q.created);
});

test('macOS injector: wheel in pixels, browser direction reversed', () => {
  const q = fakeQuartz();
  const inj = createDarwinInjector(log, { quartz: q, layout: 'ANSI' });
  inj.wheel(0, 120); // down
  inj.wheel(-30, 0); // left
  inj.wheel(0, 0);
  const scrolls = q.posted.filter((e) => e.kind === 'scroll');
  assert.deepEqual(scrolls.map((e) => [e.units, e.count, ...e.wheels]), [[0, 2, -120, 0, 0], [0, 2, 0, 30, 0]]);
});

test('macOS injector: keys by physical position, modifier flags, Caps Lock, autorepeat', () => {
  const q = fakeQuartz();
  const inj = createDarwinInjector(log, { quartz: q, layout: 'ANSI' });
  const keys = () => q.posted.filter((e) => e.kind === 'key');
  inj.key('MetaLeft', true);
  inj.key('KeyC', true);
  inj.key('KeyC', true); // held: autorepeat
  inj.key('KeyC', false);
  inj.key('MetaLeft', false);
  assert.deepEqual(keys().map((e) => [e.key, e.down, e.flags, e.fields[8] ?? 0]), [
    [0x37, true, FLAGS.command | 0x8, 0],
    [0x08, true, FLAGS.command | 0x8, 0],
    [0x08, true, FLAGS.command | 0x8, 1],
    [0x08, false, FLAGS.command | 0x8, 0],
    [0x37, false, 0, 0],
  ]);
  q.posted.length = 0;
  // AZERTY or QWERTY: the viewer sends positions, the Mac's layout picks the character.
  inj.key('KeyQ', true);
  inj.key('KeyQ', false);
  inj.key('ShiftRight', true);
  inj.key('AltRight', true);
  inj.key('Digit5', true);
  assert.equal(keys()[0].key, 0x0c);
  assert.equal(keys().at(-1).flags, FLAGS.shift | 0x4 | FLAGS.option | 0x40);
  inj.releaseAll();
  assert.deepEqual(keys().slice(-3).map((e) => [e.key, e.down]), [[0x3c, false], [0x3d, false], [0x17, false]]);
  assert.equal(keys().at(-1).flags, 0);
  q.posted.length = 0;
  // Arrows carry the numeric pad and fn flags, F keys fn, keypad keys numeric pad.
  inj.key('ArrowUp', true);
  inj.key('F5', true);
  inj.key('Numpad7', true);
  assert.deepEqual(keys().map((e) => e.flags), [FLAGS.numericPad | FLAGS.fn, FLAGS.fn, FLAGS.numericPad]);
  q.posted.length = 0;
  // Caps Lock is not injected (macOS would not toggle it): its flag goes on the keys.
  inj.key('CapsLock', true);
  inj.key('CapsLock', false);
  inj.key('KeyA', true);
  assert.deepEqual(keys().map((e) => [e.key, e.flags]), [[0x00, FLAGS.capsLock]]);
  inj.key('CapsLock', true);
  inj.key('CapsLock', false);
  inj.key('KeyA', false);
  assert.equal(keys().at(-1).flags, 0);
  // Keys a Mac does not have are ignored.
  const before = q.posted.length;
  inj.key('BrowserBack', true);
  inj.key('NotAKey', true);
  assert.equal(q.posted.length, before);
});

test('macOS injector: ISO keyboards swap the keys left of 1 and left of Z', () => {
  const ansi = fakeQuartz();
  createDarwinInjector(log, { quartz: ansi, layout: 'ANSI' }).key('Backquote', true);
  const iso = fakeQuartz();
  const inj = createDarwinInjector(log, { quartz: iso, layout: 'ISO' });
  inj.key('Backquote', true);
  inj.key('IntlBackslash', true);
  assert.equal(ansi.posted[0].key, 0x32);
  assert.deepEqual(iso.posted.map((e) => e.key), [0x0a, 0x32]);
});

test('macOS injector: text, wake, cursor position and failed event creation', () => {
  const q = fakeQuartz({ location: { x: 1440.5, y: 12 } });
  const warnings = [];
  const inj = createDarwinInjector({ ...log, warn: (m) => warnings.push(m) }, { quartz: q, layout: 'ANSI' });
  inj.key('MetaLeft', true);
  inj.typeText('é😀\nb\r\n');
  const typed = q.posted.filter((e) => e.kind === 'key' && e.key !== 0x37);
  assert.deepEqual(typed.map((e) => [e.key, e.down, e.text ?? null, e.flags]), [
    [0, true, 'é', 0], [0, false, 'é', 0],
    [0, true, '😀', 0], [0, false, '😀', 0],
    [0x24, true, null, 0], [0x24, false, null, 0],
    [0, true, 'b', 0], [0, false, 'b', 0],
    [0x24, true, null, 0], [0x24, false, null, 0],
  ]);
  inj.wake();
  inj.wake();
  const activity = q.posted.filter((e) => e.kind === 'activity');
  assert.equal(activity.length, 2);
  assert.deepEqual(activity[0].name, { cfString: 'PairDesk remote session' });
  assert.deepEqual(inj.cursorPos(), { x: 1440.5, y: 12 });

  q.CGEventCreateMouseEvent = () => null;
  inj.moveTo(1, 1);
  inj.button(0, true);
  assert.equal(warnings.length, 2);
  assert.equal(q.released.length, q.created);
});

test('macOS key codes: every Mac key once, standard positions', () => {
  const entries = Object.entries(MAC_KEYCODES).filter(([code]) => !code.startsWith('OS'));
  const codes = entries.map(([, vk]) => vk);
  assert.equal(new Set(codes).size, codes.length);
  for (const [code] of entries) assert.ok(KEYMAP[code], `${code} is a known key`);
  for (const code of Object.keys(KEYMAP).filter((c) => /^(Key|Digit|F\d$|F1[0-2]$|Arrow|Shift|Control|Alt|Meta)/.test(c))) {
    assert.ok(code in MAC_KEYCODES, `${code} has a Mac key code`);
  }
  assert.equal(MAC_KEYCODES.KeyA, 0x00);
  assert.equal(MAC_KEYCODES.KeyZ, 0x06);
  assert.equal(MAC_KEYCODES.Enter, 0x24);
  assert.equal(MAC_KEYCODES.Backspace, 0x33);
  assert.equal(MAC_KEYCODES.Delete, 0x75);
  assert.equal(MAC_KEYCODES.MetaLeft, 0x37);
  assert.equal(MAC_KEYCODES.ArrowUp, 0x7e);
});

test('Ctrl/Cmd shortcuts: the Mac side swaps them with a PC', () => {
  assert.equal(swapsCtrlCmd('darwin', 'win32'), true);
  assert.equal(swapsCtrlCmd('darwin', 'linux'), true);
  assert.equal(swapsCtrlCmd('darwin', 'android'), true);
  assert.equal(swapsCtrlCmd('darwin', 'darwin'), false);
  assert.equal(swapsCtrlCmd('darwin', ''), false, 'unknown peer: unchanged');
  assert.equal(swapsCtrlCmd('win32', 'darwin'), false, 'a PC never swaps (the Mac does)');
  assert.equal(swapsCtrlCmd('linux', 'win32'), false);
  assert.deepEqual(['ControlLeft', 'ControlRight', 'MetaLeft', 'MetaRight', 'OSLeft', 'KeyC', 'AltLeft'].map(swapCtrlCmd),
    ['MetaLeft', 'MetaRight', 'ControlLeft', 'ControlRight', 'ControlLeft', 'KeyC', 'AltLeft']);
  // A Mac viewer: keys pressed with Cmd and Caps Lock go as a whole press.
  assert.equal(macTapKey('KeyC', true, true), true);
  assert.equal(macTapKey('KeyC', true, false), false);
  assert.equal(macTapKey('KeyC', false, true), false);
  assert.equal(macTapKey('ShiftLeft', true, true), false);
  assert.equal(macTapKey('CapsLock', false, false), true);
  assert.deepEqual(heldNonModifiers(new Set(['ControlLeft', 'KeyA', 'ShiftLeft', 'Tab'])), ['KeyA', 'Tab']);
  for (const combo of [...Object.values(MAC_KEY_COMBOS), ...Object.values(KEY_COMBOS)]) {
    for (const code of combo) assert.ok(KEYMAP[code], code);
  }
  for (const combo of Object.values(MAC_KEY_COMBOS)) for (const code of combo) assert.ok(code in MAC_KEYCODES, code);
});

test('input core: a Mac host controlled from a PC swaps Ctrl and Cmd', () => {
  const calls = [];
  const injector = {
    platform: 'darwin', moveTo() {}, button() {}, wheel() {}, releaseAll() {}, cursorPos: () => ({ x: 0, y: 0 }),
    key: (code, down) => calls.push([code, down]),
  };
  const core = createInputCore({ injector, toMain() {}, log() {} });
  core.handleMain({ type: 'session', id: 's1', control: true, rect: { x: 0, y: 0, width: 100, height: 100 } });
  core.handleMain({ type: 'keys', id: 'other', swap: true });
  core.handleRenderer([['k', 'ControlLeft', 1], ['k', 'KeyC', 1]]);
  core.handleMain({ type: 'keys', id: 's1', swap: true });
  core.handleRenderer([['k', 'ControlLeft', 1], ['k', 'KeyV', 1], ['k', 'MetaLeft', 0]]);
  assert.deepEqual(calls, [['ControlLeft', true], ['KeyC', true], ['MetaLeft', true], ['KeyV', true], ['ControlLeft', false]]);
  // A new session starts unswapped until told.
  core.handleMain({ type: 'session', id: 's2', control: true, rect: { x: 0, y: 0, width: 100, height: 100 } });
  core.handleRenderer([['k', 'ControlLeft', 1]]);
  assert.deepEqual(calls.at(-1), ['ControlLeft', true]);
  core.dispose();
});

/** NSCursor through a fake Objective-C runtime. */
function fakeCursorSystem() {
  const sys = {
    image: { pixel: 0x80, width: 64, height: 64, points: 32, hot: { x: 4, y: 3 } },
    pools: 0,
    contexts: [],
  };
  const objc = {
    cls: (name) => ({ cls: name }),
    withPool(fn) {
      sys.pools++;
      try {
        return fn();
      } finally {
        sys.pools--;
      }
    },
    send(ret, target, selector) {
      if (selector === 'currentSystemCursor') return { cursor: true };
      if (selector === 'image') return { nsimage: true };
      if (selector === 'size') return { width: sys.image.points, height: sys.image.points };
      if (selector === 'hotSpot') return { ...sys.image.hot };
      if (selector === 'CGImageForProposedRect:context:hints:') return { cgimage: sys.image };
      throw new Error(`unexpected ${selector}`);
    },
  };
  const quartz = {
    CGColorSpaceCreateDeviceRGB: () => ({ space: true }),
    CGImageGetWidth: (img) => img.cgimage.width,
    CGImageGetHeight: (img) => img.cgimage.height,
    CGBitmapContextCreate: (data, width, height, bits, row, space, info) => {
      const ctx = { width, height, bits, row, info, data: null };
      sys.contexts.push(ctx);
      return ctx;
    },
    CGContextDrawImage: (ctx, rect, img) => {
      assert.deepEqual(rect, { x: 0, y: 0, width: ctx.width, height: ctx.height });
      ctx.data = Buffer.alloc(ctx.width * ctx.height * 4, img.cgimage.pixel);
    },
    CGBitmapContextGetData: (ctx) => ctx.data,
    CGEventCreate: () => ({ event: true }),
    CGEventGetLocation: () => ({ x: 500.5, y: 300 }),
    CFRelease: (o) => { o.released = true; },
  };
  return { sys, objc, quartz, decode: (ptr, n) => new Uint8Array(ptr.buffer, ptr.byteOffset, n) };
}

test('macOS cursor probe: NSCursor picture at the screen resolution, change detection', () => {
  const { sys, objc, quartz, decode } = fakeCursorSystem();
  const probe = createDarwinCursorProbe({ objc, quartz, decode });
  const a = probe.read({ image: 'unknown' });
  assert.equal(a.shape, null);
  assert.deepEqual([a.width, a.height, a.xhot, a.yhot, a.scale, a.x, a.y], [64, 64, 8, 6, 2, 500.5, 300]);
  assert.equal(a.bgra.length, 64 * 64 * 4);
  assert.equal(sys.contexts[0].info, 2 | (2 << 12), 'premultiplied BGRA');
  assert.ok(sys.contexts.every((c) => c.released), 'bitmap contexts released');
  assert.equal(sys.pools, 0, 'autorelease pool popped');
  assert.equal(probe.read({ image: 'unknown' }).serial, a.serial, 'same picture, same serial');
  assert.equal(probe.read({ image: 'never' }).bgra, undefined);
  sys.image = { ...sys.image, pixel: 0x10 };
  assert.notEqual(probe.read({ image: 'unknown' }).serial, a.serial);
  sys.image = { ...sys.image, width: 600, height: 600 };
  assert.equal(probe.read({ image: 'unknown' }).shape, 'default', 'huge accessibility cursors: plain arrow');
  probe.close();
  assert.equal(probe.read(), null);
});

test('cursor tracker: a Mac custom cursor carries its own scale', async () => {
  const { objc, quartz, decode } = fakeCursorSystem();
  const probe = createDarwinCursorProbe({ objc, quartz, decode });
  const tracker = await createCursorTracker({ log, encodePng: () => 'data:image/png;base64,AAAA', scale: () => 1, probe, intervalMs: 1000 });
  const sent = [];
  tracker.start((msg) => sent.push(msg));
  tracker.stop();
  assert.equal(sent.length, 1);
  assert.equal(sent[0].shape, 'custom');
  assert.equal(sent[0].scale, 2);
  assert.deepEqual(sent[0].hot, [8, 6]);
  assert.equal(sent[0].png, 'data:image/png;base64,AAAA');
});

test('macOS pasteboard: copied files as file URLs', () => {
  const calls = [];
  let items = [
    { 'public.file-url': 'file:///Users/me/Documents/a%20b.txt' },
    { 'public.utf8-plain-text': 'not a file' },
    { 'public.file-url': 'file:///.file/id=6571367.2773272' },
  ];
  let changeCount = 7;
  const references = { 'file:///.file/id=6571367.2773272': '/Users/me/Dossier' };
  const str = (text) => ({ str: text });
  const objc = {
    cls: (name) => ({ cls: name }),
    withPool: (fn) => fn(),
    string: str,
    text: (s) => s?.str ?? '',
    send(ret, target, selector, types, arg) {
      calls.push(selector);
      switch (selector) {
        case 'generalPasteboard': return { pasteboard: true };
        case 'changeCount': return BigInt(changeCount);
        case 'pasteboardItems': return { items };
        case 'count': return BigInt(target.items.length);
        case 'objectAtIndex:': return target.items[arg];
        case 'stringForType:': return target[arg.str] ? str(target[arg.str]) : null;
        case 'URLWithString:': return { href: arg.str };
        case 'filePathURL': return { path: references[target.href] ?? decodeURIComponent(new URL(target.href).pathname) };
        case 'path': return str(target.path);
        case 'array': return { list: [] };
        case 'fileURLWithPath:': return { file: arg.str };
        case 'addObject:': target.list.push(arg); return undefined;
        case 'clearContents': items = []; return BigInt(++changeCount);
        case 'writeObjects:':
          items = arg.list.map((u) => ({ 'public.file-url': `file://${encodeURI(u.file)}` }));
          changeCount++;
          return 1;
        default: throw new Error(selector);
      }
    },
  };
  const board = macPasteboard(objc);
  assert.equal(board.changeCount(), 7);
  assert.deepEqual(board.read(), ['/Users/me/Documents/a b.txt', '/Users/me/Dossier']);
  assert.equal(board.write(['/tmp/é t.txt', '/tmp/dossier']), true);
  assert.deepEqual(board.read(), ['/tmp/é t.txt', '/tmp/dossier']);
  assert.equal(board.changeCount(), 9);
  assert.ok(calls.indexOf('clearContents') < calls.indexOf('writeObjects:'));
});

test('permissions: nothing to ask outside macOS', async () => {
  const p = createPermissions({ platform: 'win32' });
  assert.equal(p.status(), null);
  assert.equal(p.canCapture(), true);
  assert.equal(p.canControl(), true);
  assert.equal(await p.open('screen'), false);
});

test('permissions: macOS Screen Recording and Accessibility', async () => {
  let screen = 'denied';
  let trusted = false;
  const prompts = [];
  const opened = [];
  const systemPreferences = {
    getMediaAccessStatus: (kind) => {
      assert.equal(kind, 'screen');
      return screen;
    },
    isTrustedAccessibilityClient: (prompt) => {
      if (prompt) prompts.push('accessibility');
      return trusted;
    },
  };
  const desktopCapturer = { getSources: async () => { prompts.push('screen'); return []; } };
  const p = createPermissions({ platform: 'darwin', systemPreferences, desktopCapturer, openExternal: async (url) => opened.push(url), log });
  assert.deepEqual(p.status(), { screen: 'denied', accessibility: false, restart: false });
  assert.equal(p.canCapture(), false);
  assert.equal(p.canControl(), false);
  assert.equal(await p.open('screen'), true);
  assert.equal(await p.open('accessibility'), true);
  assert.equal(await p.open('camera'), false);
  assert.deepEqual(prompts, ['screen', 'accessibility']);
  assert.deepEqual(opened, [PRIVACY_PANES.screen, PRIVACY_PANES.accessibility]);
  assert.match(PRIVACY_PANES.screen, /^x-apple\.systempreferences:com\.apple\.preference\.security\?Privacy_ScreenCapture$/);
  // Granted while running: Accessibility applies at once, Screen Recording after a restart.
  screen = 'granted';
  trusted = true;
  assert.deepEqual(p.status(), { screen: 'granted', accessibility: true, restart: true });
  assert.equal(p.canCapture(), false);
  assert.equal(p.canControl(), true);
  const again = createPermissions({ platform: 'darwin', systemPreferences, desktopCapturer, openExternal: async () => {}, log });
  assert.equal(again.canCapture(), true);
  assert.equal(again.status().restart, false);
});

test('updates on macOS: the latest release is offered for download, never installed', async () => {
  assert.ok(compareVersions('1.10.0', '1.9.3') > 0);
  assert.ok(compareVersions('v1.2.0', '1.2.0') === 0);
  assert.ok(compareVersions('1.2.0-beta.1', '1.2.1') < 0);
  const release = {
    tag_name: 'v1.3.0',
    body: '<p>Nouveau : <b>macOS</b></p>',
    html_url: 'https://github.com/Azukkia/PairDesk/releases/tag/v1.3.0',
    assets: [
      { name: 'PairDesk-1.3.0-mac.dmg', browser_download_url: 'https://github.com/Azukkia/PairDesk/releases/download/v1.3.0/PairDesk-1.3.0-mac.dmg' },
      { name: 'PairDesk-mac.dmg', browser_download_url: 'https://github.com/Azukkia/PairDesk/releases/download/v1.3.0/PairDesk-mac.dmg' },
    ],
  };
  assert.equal(macDownload(release), 'https://github.com/Azukkia/PairDesk/releases/download/v1.3.0/PairDesk-mac.dmg');
  assert.equal(macDownload({ html_url: 'https://evil.example/x' }), 'https://github.com/Azukkia/PairDesk/releases/latest');

  let answer = { ok: true, status: 200, json: async () => release };
  const fetched = [];
  const opened = [];
  const updater = new Updater({
    log,
    isPackaged: true,
    currentVersion: '1.2.0',
    platform: 'darwin',
    fetch: async (url) => {
      fetched.push(url);
      if (answer instanceof Error) throw answer;
      return answer;
    },
    openExternal: async (url) => opened.push(url),
  });
  const available = [];
  updater.on('available', (v) => available.push(v));
  assert.equal(updater.state.manual, true);
  await updater.check();
  assert.equal(updater.state.status, 'available');
  assert.equal(updater.state.version, '1.3.0');
  assert.equal(updater.state.notes, 'Nouveau : macOS');
  assert.deepEqual(available, ['1.3.0']);
  assert.match(fetched[0], /^https:\/\/api\.github\.com\/repos\/Azukkia\/PairDesk\/releases\/latest$/);
  await updater.check(); // still the same version: no new notification
  assert.deepEqual(available, ['1.3.0']);
  await updater.download();
  assert.deepEqual(opened, ['https://github.com/Azukkia/PairDesk/releases/download/v1.3.0/PairDesk-mac.dmg']);
  assert.equal(updater.install(), false);
  // A failed background check keeps the offer; a manual one reports the error.
  answer = new Error('offline');
  await updater.check();
  assert.equal(updater.state.status, 'available');
  await updater.check({ manual: true });
  assert.equal(updater.state.status, 'error');
  answer = { ok: true, status: 200, json: async () => ({ ...release, tag_name: 'v1.2.0' }) };
  await updater.check({ manual: true });
  assert.equal(updater.state.status, 'up-to-date');
});

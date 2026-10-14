// Linux (X11) input injection through the XTest extension, called with koffi.
// Note: on Wayland sessions only XWayland applications receive these events.

import koffi from 'koffi';
import { KEYMAP } from '../../shared/keymap.js';

// Browser button index → X11 button number.
const X_BUTTONS = [1, 2, 3, 8, 9];

export function createX11Injector(log) {
  const x11 = koffi.load('libX11.so.6');
  const xtst = koffi.load('libXtst.so.6');
  const XOpenDisplay = x11.func('void *XOpenDisplay(const char *name)');
  const XFlush = x11.func('int XFlush(void *dpy)');
  const XDefaultRootWindow = x11.func('unsigned long XDefaultRootWindow(void *dpy)');
  const XQueryPointer = x11.func('int XQueryPointer(void *dpy, unsigned long w, _Out_ unsigned long *root, _Out_ unsigned long *child, _Out_ int *rx, _Out_ int *ry, _Out_ int *wx, _Out_ int *wy, _Out_ unsigned int *mask)');
  const XTestFakeMotionEvent = xtst.func('int XTestFakeMotionEvent(void *dpy, int screen, int x, int y, unsigned long delay)');
  const XTestFakeButtonEvent = xtst.func('int XTestFakeButtonEvent(void *dpy, unsigned int button, int is_press, unsigned long delay)');
  const XTestFakeKeyEvent = xtst.func('int XTestFakeKeyEvent(void *dpy, unsigned int keycode, int is_press, unsigned long delay)');
  const XKeysymToKeycode = x11.func('unsigned char XKeysymToKeycode(void *dpy, unsigned long keysym)');
  const XkbKeycodeToKeysym = x11.func('unsigned long XkbKeycodeToKeysym(void *dpy, unsigned char keycode, int group, int level)');
  const XDisplayKeycodes = x11.func('int XDisplayKeycodes(void *dpy, _Out_ int *min, _Out_ int *max)');
  const XGetKeyboardMapping = x11.func('void *XGetKeyboardMapping(void *dpy, unsigned char first, int count, _Out_ int *perCode)');
  const XChangeKeyboardMapping = x11.func('int XChangeKeyboardMapping(void *dpy, int first, int perCode, unsigned long *keysyms, int count)');
  const XSync = x11.func('int XSync(void *dpy, int discard)');
  const XFree = x11.func('int XFree(void *data)');
  const XForceScreenSaver = x11.func('int XForceScreenSaver(void *dpy, int mode)');

  const dpy = XOpenDisplay(null);
  if (!dpy) throw new Error('Cannot open the X display');
  if (process.env.XDG_SESSION_TYPE === 'wayland') {
    log?.warn('[input] Wayland session: remote input only reaches XWayland applications');
  }

  const pressedKeys = new Set();
  const pressedButtons = new Set();
  const wheelAcc = { x: 0, y: 0 };

  // A keycode without any keysym, temporarily mapped to characters the
  // keyboard layout cannot type (like xdotool does).
  let spareKeycode;
  function spare() {
    if (spareKeycode !== undefined) return spareKeycode;
    spareKeycode = 0;
    try {
      const min = [0];
      const max = [0];
      XDisplayKeycodes(dpy, min, max);
      const count = max[0] - min[0] + 1;
      const per = [0];
      const ptr = XGetKeyboardMapping(dpy, min[0], count, per);
      if (ptr) {
        const syms = koffi.decode(ptr, 'unsigned long', count * per[0]);
        XFree(ptr);
        for (let kc = max[0]; kc >= min[0]; kc--) {
          const base = (kc - min[0]) * per[0];
          if (syms.slice(base, base + per[0]).every((s) => !s)) {
            spareKeycode = kc;
            break;
          }
        }
      }
    } catch (err) {
      log?.warn(`[input] no spare keycode: ${err.message}`);
    }
    return spareKeycode;
  }
  const pause = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
  const tap = (keycode) => {
    XTestFakeKeyEvent(dpy, keycode, 1, 0);
    XTestFakeKeyEvent(dpy, keycode, 0, 0);
  };

  function typeChar(ch) {
    const cp = ch.codePointAt(0);
    const keysym = ch === '\n' ? 0xff0d : ch === '\t' ? 0xff09 : cp >= 0x20 && cp < 0x100 ? cp : 0x01000000 + cp;
    const kc = XKeysymToKeycode(dpy, keysym);
    if (kc) {
      if (XkbKeycodeToKeysym(dpy, kc, 0, 0) === keysym) {
        tap(kc);
        return;
      }
      if (XkbKeycodeToKeysym(dpy, kc, 0, 1) === keysym) {
        const shift = XKeysymToKeycode(dpy, 0xffe1); // Shift_L
        XTestFakeKeyEvent(dpy, shift, 1, 0);
        tap(kc);
        XTestFakeKeyEvent(dpy, shift, 0, 0);
        return;
      }
    }
    const sk = spare();
    if (!sk) return;
    XChangeKeyboardMapping(dpy, sk, 1, [keysym], 1);
    XSync(dpy, 0);
    pause(10); // let clients process MappingNotify
    tap(sk);
    XSync(dpy, 0);
    pause(10);
    XChangeKeyboardMapping(dpy, sk, 1, [0], 1);
    XSync(dpy, 0);
  }

  function click(button, times) {
    for (let i = 0; i < times; i++) {
      XTestFakeButtonEvent(dpy, button, 1, 0);
      XTestFakeButtonEvent(dpy, button, 0, 0);
    }
  }

  return {
    available: true,
    platform: 'linux',

    moveTo(x, y) {
      XTestFakeMotionEvent(dpy, -1, Math.round(x), Math.round(y), 0);
      XFlush(dpy);
    },

    button(index, down) {
      const button = X_BUTTONS[index];
      if (!button) return;
      XTestFakeButtonEvent(dpy, button, down ? 1 : 0, 0);
      XFlush(dpy);
      if (down) pressedButtons.add(index);
      else pressedButtons.delete(index);
    },

    wheel(dx, dy) {
      // X11 only knows discrete wheel clicks: accumulate 120ths of a notch.
      wheelAcc.x += dx;
      wheelAcc.y += dy;
      const ny = Math.trunc(wheelAcc.y / 120);
      const nx = Math.trunc(wheelAcc.x / 120);
      wheelAcc.y -= ny * 120;
      wheelAcc.x -= nx * 120;
      if (ny > 0) click(5, ny);
      if (ny < 0) click(4, -ny);
      if (nx > 0) click(7, nx);
      if (nx < 0) click(6, -nx);
      if (nx || ny) XFlush(dpy);
    },

    key(code, down) {
      const entry = KEYMAP[code];
      if (!entry?.evdev) return;
      XTestFakeKeyEvent(dpy, entry.evdev + 8, down ? 1 : 0, 0);
      XFlush(dpy);
      if (down) pressedKeys.add(code);
      else pressedKeys.delete(code);
    },

    /** Ends the screen saver / turns the screen back on. */
    wake() {
      XForceScreenSaver(dpy, 0); // ScreenSaverReset
      XFlush(dpy);
    },

    /** Types text whatever the keyboard layout (phones' soft keyboards). */
    typeText(text) {
      for (const ch of String(text).replace(/\r\n?/g, '\n')) typeChar(ch);
      XFlush(dpy);
    },

    releaseAll() {
      for (const code of [...pressedKeys]) this.key(code, false);
      for (const index of [...pressedButtons]) this.button(index, false);
      wheelAcc.x = 0;
      wheelAcc.y = 0;
    },

    cursorPos() {
      const out = { root: [0], child: [0], rx: [0], ry: [0], wx: [0], wy: [0], mask: [0] };
      XQueryPointer(dpy, XDefaultRootWindow(dpy), out.root, out.child, out.rx, out.ry, out.wx, out.wy, out.mask);
      return { x: out.rx[0], y: out.ry[0] };
    },
  };
}

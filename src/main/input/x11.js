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

  const dpy = XOpenDisplay(null);
  if (!dpy) throw new Error('Cannot open the X display');
  if (process.env.XDG_SESSION_TYPE === 'wayland') {
    log?.warn('[input] Wayland session: remote input only reaches XWayland applications');
  }

  const pressedKeys = new Set();
  const pressedButtons = new Set();
  const wheelAcc = { x: 0, y: 0 };

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

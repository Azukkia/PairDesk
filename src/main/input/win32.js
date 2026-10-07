// Windows input injection through SendInput (user32.dll), called with koffi.

import koffi from 'koffi';
import { KEYMAP, WIN_VK_FALLBACK } from '../../shared/keymap.js';

const INPUT_MOUSE = 0;
const INPUT_KEYBOARD = 1;

const MOUSEEVENTF_MOVE = 0x0001;
const MOUSEEVENTF_LEFTDOWN = 0x0002;
const MOUSEEVENTF_LEFTUP = 0x0004;
const MOUSEEVENTF_RIGHTDOWN = 0x0008;
const MOUSEEVENTF_RIGHTUP = 0x0010;
const MOUSEEVENTF_MIDDLEDOWN = 0x0020;
const MOUSEEVENTF_MIDDLEUP = 0x0040;
const MOUSEEVENTF_XDOWN = 0x0080;
const MOUSEEVENTF_XUP = 0x0100;
const MOUSEEVENTF_WHEEL = 0x0800;
const MOUSEEVENTF_HWHEEL = 0x1000;
const MOUSEEVENTF_VIRTUALDESK = 0x4000;
const MOUSEEVENTF_ABSOLUTE = 0x8000;

const KEYEVENTF_EXTENDEDKEY = 0x0001;
const KEYEVENTF_KEYUP = 0x0002;
const KEYEVENTF_SCANCODE = 0x0008;

const SM_XVIRTUALSCREEN = 76;
const SM_YVIRTUALSCREEN = 77;
const SM_CXVIRTUALSCREEN = 78;
const SM_CYVIRTUALSCREEN = 79;

// Tags our synthetic events (visible to hooks via dwExtraInfo).
const EXTRA_INFO = 0x50444b31;

const BUTTONS = [
  [MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP, 0],
  [MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP, 0],
  [MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP, 0],
  [MOUSEEVENTF_XDOWN, MOUSEEVENTF_XUP, 1], // XBUTTON1 = back
  [MOUSEEVENTF_XDOWN, MOUSEEVENTF_XUP, 2], // XBUTTON2 = forward
];

export function createWin32Injector(log) {
  const user32 = koffi.load('user32.dll');

  const MOUSEINPUT = koffi.struct('PD_MOUSEINPUT', {
    dx: 'long', dy: 'long', mouseData: 'uint32_t', dwFlags: 'uint32_t', time: 'uint32_t', dwExtraInfo: 'uintptr_t',
  });
  const KEYBDINPUT = koffi.struct('PD_KEYBDINPUT', {
    wVk: 'uint16_t', wScan: 'uint16_t', dwFlags: 'uint32_t', time: 'uint32_t', dwExtraInfo: 'uintptr_t',
  });
  const HARDWAREINPUT = koffi.struct('PD_HARDWAREINPUT', { uMsg: 'uint32_t', wParamL: 'uint16_t', wParamH: 'uint16_t' });
  const INPUT = koffi.struct('PD_INPUT', {
    type: 'uint32_t',
    u: koffi.union('PD_INPUT_UNION', { mi: MOUSEINPUT, ki: KEYBDINPUT, hi: HARDWAREINPUT }),
  });
  const POINT = koffi.struct('PD_POINT', { x: 'long', y: 'long' });

  const SendInput = user32.func('unsigned int __stdcall SendInput(unsigned int cInputs, PD_INPUT *pInputs, int cbSize)');
  const GetSystemMetrics = user32.func('int __stdcall GetSystemMetrics(int nIndex)');
  const GetCursorPos = user32.func('int __stdcall GetCursorPos(_Out_ PD_POINT *lpPoint)');
  const GetClipboardSequenceNumber = user32.func('uint32_t __stdcall GetClipboardSequenceNumber()');
  const INPUT_SIZE = koffi.sizeof(INPUT);

  const pressedKeys = new Set();
  const pressedButtons = new Set();
  let failures = 0;

  function send(inputs) {
    const sent = SendInput(inputs.length, inputs, INPUT_SIZE);
    if (sent !== inputs.length && failures++ < 5) {
      // Typically UIPI: the foreground window belongs to an elevated process.
      log?.warn(`[input] SendInput injected ${sent}/${inputs.length} events (blocked by an elevated window or the secure desktop?)`);
    }
  }

  const mouse = (dwFlags, extra = {}) => ({
    type: INPUT_MOUSE,
    u: { mi: { dx: 0, dy: 0, mouseData: 0, dwFlags, time: 0, dwExtraInfo: EXTRA_INFO, ...extra } },
  });

  function keyInput(code, down) {
    const entry = KEYMAP[code];
    if (!entry) return null;
    if (entry.win == null) {
      const vk = WIN_VK_FALLBACK[code];
      if (!vk) return null;
      return { type: INPUT_KEYBOARD, u: { ki: { wVk: vk, wScan: 0, dwFlags: down ? 0 : KEYEVENTF_KEYUP, time: 0, dwExtraInfo: EXTRA_INFO } } };
    }
    let flags = KEYEVENTF_SCANCODE;
    if (entry.win & 0xe000) flags |= KEYEVENTF_EXTENDEDKEY;
    if (!down) flags |= KEYEVENTF_KEYUP;
    return { type: INPUT_KEYBOARD, u: { ki: { wVk: 0, wScan: entry.win & 0xff, dwFlags: flags, time: 0, dwExtraInfo: EXTRA_INFO } } };
  }

  return {
    available: true,
    platform: 'win32',

    moveTo(x, y) {
      const vx = GetSystemMetrics(SM_XVIRTUALSCREEN);
      const vy = GetSystemMetrics(SM_YVIRTUALSCREEN);
      const vw = Math.max(2, GetSystemMetrics(SM_CXVIRTUALSCREEN));
      const vh = Math.max(2, GetSystemMetrics(SM_CYVIRTUALSCREEN));
      const dx = Math.round(((x - vx) * 65535) / (vw - 1));
      const dy = Math.round(((y - vy) * 65535) / (vh - 1));
      send([mouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, { dx, dy })]);
    },

    button(index, down) {
      const def = BUTTONS[index];
      if (!def) return;
      const [downFlag, upFlag, xButton] = def;
      send([mouse(down ? downFlag : upFlag, { mouseData: xButton })]);
      if (down) pressedButtons.add(index);
      else pressedButtons.delete(index);
    },

    wheel(dx, dy) {
      const inputs = [];
      // Browser convention: positive deltaY scrolls down; Windows: positive = up.
      if (dy) inputs.push(mouse(MOUSEEVENTF_WHEEL, { mouseData: Math.round(-dy) >>> 0 }));
      if (dx) inputs.push(mouse(MOUSEEVENTF_HWHEEL, { mouseData: Math.round(dx) >>> 0 }));
      if (inputs.length) send(inputs);
    },

    key(code, down) {
      const input = keyInput(code, down);
      if (!input) return;
      send([input]);
      if (down) pressedKeys.add(code);
      else pressedKeys.delete(code);
    },

    releaseAll() {
      for (const code of [...pressedKeys]) this.key(code, false);
      for (const index of [...pressedButtons]) this.button(index, false);
    },

    cursorPos() {
      const pt = {};
      GetCursorPos(pt);
      return { x: pt.x, y: pt.y };
    },

    clipboardSequence() {
      return GetClipboardSequenceNumber();
    },
  };
}

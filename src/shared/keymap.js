// Physical key mapping, indexed by KeyboardEvent.code.
//   win: PS/2 set 1 scan code as used by SendInput (0xE0xx = extended key)
//   evdev: Linux input event code (X11 keycode = evdev + 8)
// Keys are sent by physical position, like TeamViewer does by default: the
// keyboard layout of the remote computer decides which character is typed.

const K = (win, evdev) => ({ win, evdev });

export const KEYMAP = {
  Escape: K(0x01, 1),
  Digit1: K(0x02, 2), Digit2: K(0x03, 3), Digit3: K(0x04, 4), Digit4: K(0x05, 5), Digit5: K(0x06, 6),
  Digit6: K(0x07, 7), Digit7: K(0x08, 8), Digit8: K(0x09, 9), Digit9: K(0x0a, 10), Digit0: K(0x0b, 11),
  Minus: K(0x0c, 12), Equal: K(0x0d, 13), Backspace: K(0x0e, 14), Tab: K(0x0f, 15),
  KeyQ: K(0x10, 16), KeyW: K(0x11, 17), KeyE: K(0x12, 18), KeyR: K(0x13, 19), KeyT: K(0x14, 20),
  KeyY: K(0x15, 21), KeyU: K(0x16, 22), KeyI: K(0x17, 23), KeyO: K(0x18, 24), KeyP: K(0x19, 25),
  BracketLeft: K(0x1a, 26), BracketRight: K(0x1b, 27), Enter: K(0x1c, 28), ControlLeft: K(0x1d, 29),
  KeyA: K(0x1e, 30), KeyS: K(0x1f, 31), KeyD: K(0x20, 32), KeyF: K(0x21, 33), KeyG: K(0x22, 34),
  KeyH: K(0x23, 35), KeyJ: K(0x24, 36), KeyK: K(0x25, 37), KeyL: K(0x26, 38),
  Semicolon: K(0x27, 39), Quote: K(0x28, 40), Backquote: K(0x29, 41), ShiftLeft: K(0x2a, 42), Backslash: K(0x2b, 43),
  KeyZ: K(0x2c, 44), KeyX: K(0x2d, 45), KeyC: K(0x2e, 46), KeyV: K(0x2f, 47), KeyB: K(0x30, 48),
  KeyN: K(0x31, 49), KeyM: K(0x32, 50), Comma: K(0x33, 51), Period: K(0x34, 52), Slash: K(0x35, 53),
  ShiftRight: K(0x36, 54), NumpadMultiply: K(0x37, 55), AltLeft: K(0x38, 56), Space: K(0x39, 57), CapsLock: K(0x3a, 58),
  F1: K(0x3b, 59), F2: K(0x3c, 60), F3: K(0x3d, 61), F4: K(0x3e, 62), F5: K(0x3f, 63),
  F6: K(0x40, 64), F7: K(0x41, 65), F8: K(0x42, 66), F9: K(0x43, 67), F10: K(0x44, 68),
  NumLock: K(0xe045, 69), ScrollLock: K(0x46, 70),
  Numpad7: K(0x47, 71), Numpad8: K(0x48, 72), Numpad9: K(0x49, 73), NumpadSubtract: K(0x4a, 74),
  Numpad4: K(0x4b, 75), Numpad5: K(0x4c, 76), Numpad6: K(0x4d, 77), NumpadAdd: K(0x4e, 78),
  Numpad1: K(0x4f, 79), Numpad2: K(0x50, 80), Numpad3: K(0x51, 81), Numpad0: K(0x52, 82), NumpadDecimal: K(0x53, 83),
  IntlBackslash: K(0x56, 86), F11: K(0x57, 87), F12: K(0x58, 88),
  NumpadEqual: K(0x59, 117),
  F13: K(0x64, 183), F14: K(0x65, 184), F15: K(0x66, 185), F16: K(0x67, 186), F17: K(0x68, 187),
  F18: K(0x69, 188), F19: K(0x6a, 189), F20: K(0x6b, 190), F21: K(0x6c, 191), F22: K(0x6d, 192),
  F23: K(0x6e, 193), F24: K(0x76, 194),
  KanaMode: K(0x70, 93), Lang2: K(0x71, 123), Lang1: K(0x72, 122), IntlRo: K(0x73, 89),
  Convert: K(0x79, 92), NonConvert: K(0x7b, 94), IntlYen: K(0x7d, 124), NumpadComma: K(0x7e, 121),
  // Extended keys
  MediaTrackPrevious: K(0xe010, 165), MediaTrackNext: K(0xe019, 163),
  NumpadEnter: K(0xe01c, 96), ControlRight: K(0xe01d, 97),
  AudioVolumeMute: K(0xe020, 113), LaunchApp2: K(0xe021, 140), MediaPlayPause: K(0xe022, 164), MediaStop: K(0xe024, 166),
  AudioVolumeDown: K(0xe02e, 114), AudioVolumeUp: K(0xe030, 115), BrowserHome: K(0xe032, 172),
  NumpadDivide: K(0xe035, 98), PrintScreen: K(0xe037, 99), AltRight: K(0xe038, 100),
  Home: K(0xe047, 102), ArrowUp: K(0xe048, 103), PageUp: K(0xe049, 104),
  ArrowLeft: K(0xe04b, 105), ArrowRight: K(0xe04d, 106),
  End: K(0xe04f, 107), ArrowDown: K(0xe050, 108), PageDown: K(0xe051, 109),
  Insert: K(0xe052, 110), Delete: K(0xe053, 111),
  MetaLeft: K(0xe05b, 125), MetaRight: K(0xe05c, 126), ContextMenu: K(0xe05d, 127),
  BrowserSearch: K(0xe065, 217), BrowserFavorites: K(0xe066, 156), BrowserRefresh: K(0xe067, 173),
  BrowserStop: K(0xe068, 128), BrowserForward: K(0xe069, 159), BrowserBack: K(0xe06a, 158),
  LaunchApp1: K(0xe06b, 157), LaunchMail: K(0xe06c, 155),
  // Pause has no usable scan code (E1 1D 45): injected through its virtual key.
  Pause: K(null, 119),
};

// Older browsers report the Windows/Command keys as OSLeft/OSRight.
KEYMAP.OSLeft = KEYMAP.MetaLeft;
KEYMAP.OSRight = KEYMAP.MetaRight;

export const WIN_VK_FALLBACK = { Pause: 0x13 };

// macOS virtual key codes (kVK_* of HIToolbox Events.h), by physical position
// like the table above: the same codes Chromium maps KeyboardEvent.code from
// on a Mac. Keys a Mac keyboard does not have are missing (not injected).
export const MAC_KEYCODES = {
  KeyA: 0x00, KeyS: 0x01, KeyD: 0x02, KeyF: 0x03, KeyH: 0x04, KeyG: 0x05, KeyZ: 0x06, KeyX: 0x07,
  KeyC: 0x08, KeyV: 0x09, IntlBackslash: 0x0a, KeyB: 0x0b, KeyQ: 0x0c, KeyW: 0x0d, KeyE: 0x0e, KeyR: 0x0f,
  KeyY: 0x10, KeyT: 0x11, Digit1: 0x12, Digit2: 0x13, Digit3: 0x14, Digit4: 0x15, Digit6: 0x16, Digit5: 0x17,
  Equal: 0x18, Digit9: 0x19, Digit7: 0x1a, Minus: 0x1b, Digit8: 0x1c, Digit0: 0x1d, BracketRight: 0x1e, KeyO: 0x1f,
  KeyU: 0x20, BracketLeft: 0x21, KeyI: 0x22, KeyP: 0x23, Enter: 0x24, KeyL: 0x25, KeyJ: 0x26, Quote: 0x27,
  KeyK: 0x28, Semicolon: 0x29, Backslash: 0x2a, Comma: 0x2b, Slash: 0x2c, KeyN: 0x2d, KeyM: 0x2e, Period: 0x2f,
  Tab: 0x30, Space: 0x31, Backquote: 0x32, Backspace: 0x33, Escape: 0x35,
  MetaRight: 0x36, MetaLeft: 0x37, ShiftLeft: 0x38, CapsLock: 0x39, AltLeft: 0x3a, ControlLeft: 0x3b,
  ShiftRight: 0x3c, AltRight: 0x3d, ControlRight: 0x3e,
  F17: 0x40, NumpadDecimal: 0x41, NumpadMultiply: 0x43, NumpadAdd: 0x45, NumLock: 0x47,
  AudioVolumeUp: 0x48, AudioVolumeDown: 0x49, AudioVolumeMute: 0x4a,
  NumpadDivide: 0x4b, NumpadEnter: 0x4c, NumpadSubtract: 0x4e, F18: 0x4f, F19: 0x50, NumpadEqual: 0x51,
  Numpad0: 0x52, Numpad1: 0x53, Numpad2: 0x54, Numpad3: 0x55, Numpad4: 0x56, Numpad5: 0x57, Numpad6: 0x58,
  Numpad7: 0x59, F20: 0x5a, Numpad8: 0x5b, Numpad9: 0x5c, IntlYen: 0x5d, IntlRo: 0x5e, NumpadComma: 0x5f,
  F5: 0x60, F6: 0x61, F7: 0x62, F3: 0x63, F8: 0x64, F9: 0x65, Lang2: 0x66, F11: 0x67, Lang1: 0x68,
  F13: 0x69, F16: 0x6a, F14: 0x6b, F10: 0x6d, ContextMenu: 0x6e, F12: 0x6f, F15: 0x71,
  // Insert sits where old Mac keyboards had Help.
  Insert: 0x72, Home: 0x73, PageUp: 0x74, Delete: 0x75, F4: 0x76, End: 0x77, F2: 0x78, PageDown: 0x79, F1: 0x7a,
  ArrowLeft: 0x7b, ArrowRight: 0x7c, ArrowDown: 0x7d, ArrowUp: 0x7e,
};
MAC_KEYCODES.OSLeft = MAC_KEYCODES.MetaLeft;
MAC_KEYCODES.OSRight = MAC_KEYCODES.MetaRight;

export const MODIFIER_CODES = new Set([
  'ShiftLeft', 'ShiftRight', 'ControlLeft', 'ControlRight', 'AltLeft', 'AltRight', 'MetaLeft', 'MetaRight',
]);

// Key combinations offered in the viewer's "Actions" menu (they are usually
// intercepted by the local operating system and can't be typed directly).
export const KEY_COMBOS = {
  ctrlAltDel: ['ControlLeft', 'AltLeft', 'Delete'],
  ctrlShiftEsc: ['ControlLeft', 'ShiftLeft', 'Escape'],
  altTab: ['AltLeft', 'Tab'],
  altF4: ['AltLeft', 'F4'],
  win: ['MetaLeft'],
  winR: ['MetaLeft', 'KeyR'],
  winD: ['MetaLeft', 'KeyD'],
  winE: ['MetaLeft', 'KeyE'],
  winL: ['MetaLeft', 'KeyL'],
  printScreen: ['PrintScreen'],
};

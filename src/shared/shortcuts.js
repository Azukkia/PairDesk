// Keyboard shortcuts between a Mac and a PC. The shortcut key is Command on a
// Mac (Cmd+C, Cmd+V…) and Ctrl on Windows and Linux: when one side is a Mac
// and the other is not, Ctrl and Cmd (the Windows key on a PC) change places,
// so that Ctrl+C typed on a PC copies on the Mac and Cmd+C typed on a Mac
// copies on the PC. The Mac side does it — a Mac host for the keys it
// receives, a Mac viewer for the keys it types — so PCs of any version and
// the Android app need nothing.
// Shared by the viewer and the input helper: no Node/DOM specific APIs.

import { MODIFIER_CODES } from './keymap.js';

const SWAP = {
  ControlLeft: 'MetaLeft',
  ControlRight: 'MetaRight',
  MetaLeft: 'ControlLeft',
  MetaRight: 'ControlRight',
  OSLeft: 'ControlLeft',
  OSRight: 'ControlRight',
};

/** Whether this side (platform `own`) swaps Ctrl and Cmd in a session with a `peer` computer. */
export function swapsCtrlCmd(own, peer) {
  return own === 'darwin' && typeof peer === 'string' && peer !== '' && peer !== 'darwin';
}

/** The key Ctrl/Cmd become once swapped (other keys are unchanged). */
export const swapCtrlCmd = (code) => SWAP[code] || code;

/**
 * Mac viewer: macOS reports no release for a key let go while Command is
 * held, and a single event per Caps Lock toggle (down when it turns on, up
 * when it turns off). Such keys are sent as a full press and release.
 */
export function macTapKey(code, down, metaKey) {
  return code === 'CapsLock' || (down && Boolean(metaKey) && !MODIFIER_CODES.has(code));
}

// Key combinations of the viewer's "Actions" menu for a Mac, in Mac terms
// (MetaLeft = Command, AltLeft = Option).
export const MAC_KEY_COMBOS = {
  cmdTab: ['MetaLeft', 'Tab'],
  spotlight: ['MetaLeft', 'Space'],
  missionControl: ['ControlLeft', 'ArrowUp'],
  forceQuit: ['MetaLeft', 'AltLeft', 'Escape'],
  cmdQ: ['MetaLeft', 'KeyQ'],
  lockScreen: ['ControlLeft', 'MetaLeft', 'KeyQ'],
  screenshot: ['MetaLeft', 'ShiftLeft', 'Digit5'],
};

/** Mac viewer, Command released: the keys still held, whose release macOS will not report. */
export const heldNonModifiers = (codes) => [...codes].filter((code) => !MODIFIER_CODES.has(code));

// macOS input injection through Quartz Event Services (CoreGraphics), called
// with koffi. macOS silently drops these events unless PairDesk has the
// Accessibility permission (System Settings → Privacy & Security →
// Accessibility), which the main process checks before allowing control
// (src/main/permissions.js).
//
// Coordinates are global display points, the unit of Quartz events: on macOS
// the display rect of the session is in points (physicalRect in sessions.js),
// so the position is right whatever the Retina scale factor.

import { MAC_KEYCODES } from '../../shared/keymap.js';
import { loadQuartz, keyboardLayoutType } from './quartz.js';

const HID_EVENT_TAP = 0; // kCGHIDEventTap
const EV = {
  leftDown: 1, leftUp: 2, rightDown: 3, rightUp: 4, moved: 5, leftDragged: 6, rightDragged: 7,
  otherDown: 25, otherUp: 26, otherDragged: 27,
};
const FIELD_CLICK_STATE = 1; // kCGMouseEventClickState
const FIELD_DELTA_X = 4; // kCGMouseEventDeltaX
const FIELD_DELTA_Y = 5; // kCGMouseEventDeltaY
const FIELD_AUTOREPEAT = 8; // kCGKeyboardEventAutorepeat
const SCROLL_PIXELS = 0; // kCGScrollEventUnitPixel
const CF_UTF8 = 0x08000100; // kCFStringEncodingUTF8
const USER_ACTIVE_LOCAL = 0; // kIOPMUserActiveLocal

// Browser button index → [down, up, dragged event types, CGMouseButton].
const BUTTONS = [
  [EV.leftDown, EV.leftUp, EV.leftDragged, 0],
  [EV.otherDown, EV.otherUp, EV.otherDragged, 2], // middle
  [EV.rightDown, EV.rightUp, EV.rightDragged, 1],
  [EV.otherDown, EV.otherUp, EV.otherDragged, 3], // back
  [EV.otherDown, EV.otherUp, EV.otherDragged, 4], // forward
];

// Event flags (CGEventFlags): device-independent masks, plus the left/right
// device bits real keyboards set (NX_DEVICE*KEYMASK).
export const FLAGS = {
  capsLock: 0x10000, shift: 0x20000, control: 0x40000, option: 0x80000, command: 0x100000, numericPad: 0x200000, fn: 0x800000,
};
const MODIFIER_FLAGS = {
  0x38: FLAGS.shift | 0x2, // left shift
  0x3c: FLAGS.shift | 0x4, // right shift
  0x3b: FLAGS.control | 0x1, // left control
  0x3e: FLAGS.control | 0x2000, // right control
  0x3a: FLAGS.option | 0x20, // left option
  0x3d: FLAGS.option | 0x40, // right option
  0x37: FLAGS.command | 0x8, // left command
  0x36: FLAGS.command | 0x10, // right command
};
const CAPS_LOCK = 0x39;
const RETURN = 0x24;
const TAB = 0x30;
// Extra flags a Mac keyboard sets: arrows (numeric pad + fn), keypad keys
// (numeric pad), function and navigation keys (fn).
const ARROWS = new Set([0x7b, 0x7c, 0x7d, 0x7e]);
const KEYPAD = new Set([0x41, 0x43, 0x45, 0x47, 0x4b, 0x4c, 0x4e, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5b, 0x5c]);
const FN_KEYS = new Set([
  0x7a, 0x78, 0x63, 0x76, 0x60, 0x61, 0x62, 0x64, 0x65, 0x6d, 0x67, 0x6f, 0x69, 0x6b, 0x71, 0x6a, 0x40, 0x4f, 0x50, 0x5a, // F1-F20
  0x72, 0x73, 0x74, 0x75, 0x77, 0x79, // help/insert, home, page up, forward delete, end, page down
]);

// Two presses of a button closer than this, in time and distance (points),
// make a double (triple…) click: macOS apps read the count from the event.
const MULTI_CLICK_MS = 500;
const MULTI_CLICK_DISTANCE = 5;

/**
 * `quartz` (the functions of quartz.js), `layout` and `now` can be injected
 * for tests.
 */
export function createDarwinInjector(log, { quartz = loadQuartz(), layout = keyboardLayoutType(), now = () => Date.now() } = {}) {
  const q = quartz;
  // ISO keyboards: the key left of "1" is kVK_ISO_Section and the key left of
  // "Z" kVK_ANSI_Grave, the other way round on ANSI keyboards.
  const keycodes = layout === 'ISO' ? { ...MAC_KEYCODES, Backquote: 0x0a, IntlBackslash: 0x32 } : MAC_KEYCODES;
  const pressedKeys = new Set();
  const pressedButtons = new Set();
  let capsLock = false;
  let pos = null; // last position sent, in points
  let lastPress = null; // { index, at, x, y, count }
  let activityName = null;
  const activityId = [0];
  let failures = 0;

  // Modifier keys held (and Caps Lock): set on every event, else apps would
  // see Cmd+C as a plain "c" and a Shift+click as a click.
  function flags() {
    let f = capsLock ? FLAGS.capsLock : 0;
    for (const code of pressedKeys) f |= MODIFIER_FLAGS[keycodes[code]] || 0;
    return f;
  }

  function post(event, what) {
    if (!event) {
      if (failures++ < 5) log?.warn(`[input] could not create a ${what} event`);
      return;
    }
    try {
      q.CGEventPost(HID_EVENT_TAP, event);
    } finally {
      q.CFRelease(event);
    }
  }

  function cursorPos() {
    const event = q.CGEventCreate(null);
    if (!event) return pos ? { ...pos } : { x: 0, y: 0 };
    try {
      const p = q.CGEventGetLocation(event);
      return { x: p.x, y: p.y };
    } finally {
      q.CFRelease(event);
    }
  }

  function keyEvent(vk, down, f) {
    const event = q.CGEventCreateKeyboardEvent(null, vk, down);
    if (event) q.CGEventSetFlags(event, f);
    return event;
  }

  function tapKey(vk) {
    post(keyEvent(vk, true, 0), 'keyboard');
    post(keyEvent(vk, false, 0), 'keyboard');
  }

  return {
    available: true,
    platform: 'darwin',

    moveTo(x, y) {
      const prev = pos || cursorPos();
      pos = { x, y };
      // While a button is held the move is a drag (left first, like macOS).
      const held = pressedButtons.has(0) ? 0 : pressedButtons.has(2) ? 2 : pressedButtons.values().next().value;
      const def = BUTTONS[held];
      const event = q.CGEventCreateMouseEvent(null, def ? def[2] : EV.moved, pos, def ? def[3] : 0);
      if (event) {
        q.CGEventSetIntegerValueField(event, FIELD_DELTA_X, Math.round(x - prev.x));
        q.CGEventSetIntegerValueField(event, FIELD_DELTA_Y, Math.round(y - prev.y));
        q.CGEventSetFlags(event, flags());
      }
      post(event, 'mouse');
    },

    button(index, down) {
      const def = BUTTONS[index];
      if (!def) return;
      pos ??= cursorPos();
      if (down) {
        const t = now();
        const again = lastPress && lastPress.index === index && t - lastPress.at <= MULTI_CLICK_MS
          && Math.abs(pos.x - lastPress.x) <= MULTI_CLICK_DISTANCE && Math.abs(pos.y - lastPress.y) <= MULTI_CLICK_DISTANCE;
        lastPress = { index, at: t, x: pos.x, y: pos.y, count: again ? lastPress.count + 1 : 1 };
        pressedButtons.add(index);
      } else {
        pressedButtons.delete(index);
      }
      const event = q.CGEventCreateMouseEvent(null, down ? def[0] : def[1], pos, def[3]);
      if (event) {
        // The release carries the count of its press (double click = 2 on both).
        q.CGEventSetIntegerValueField(event, FIELD_CLICK_STATE, lastPress?.index === index ? lastPress.count : 1);
        q.CGEventSetFlags(event, flags());
      }
      post(event, 'mouse');
    },

    wheel(dx, dy) {
      // Browser convention: positive = down/right. Quartz: positive = up/left.
      const vertical = -Math.round(dy) || 0;
      const horizontal = -Math.round(dx) || 0;
      if (!vertical && !horizontal) return;
      const event = q.CGEventCreateScrollWheelEvent2(null, SCROLL_PIXELS, 2, vertical, horizontal, 0);
      if (event) q.CGEventSetFlags(event, flags()); // Ctrl/Cmd+wheel zooms in many apps
      post(event, 'scroll');
    },

    key(code, down) {
      const vk = keycodes[code];
      if (vk == null) return;
      if (vk === CAPS_LOCK) {
        // Injected events do not toggle Caps Lock: its flag goes on the next keys instead.
        if (down && !pressedKeys.has(code)) capsLock = !capsLock;
        if (down) pressedKeys.add(code);
        else pressedKeys.delete(code);
        return;
      }
      const repeat = down && pressedKeys.has(code);
      if (down) pressedKeys.add(code);
      else pressedKeys.delete(code);
      let f = flags();
      if (ARROWS.has(vk)) f |= FLAGS.numericPad | FLAGS.fn;
      else if (KEYPAD.has(vk)) f |= FLAGS.numericPad;
      else if (FN_KEYS.has(vk)) f |= FLAGS.fn;
      const event = keyEvent(vk, down, f);
      if (event && repeat) q.CGEventSetIntegerValueField(event, FIELD_AUTOREPEAT, 1);
      post(event, 'keyboard');
    },

    /** Types text whatever the keyboard layout (phones' soft keyboards). */
    typeText(text) {
      for (const ch of String(text).replace(/\r\n?/g, '\n')) {
        if (ch === '\n' || ch === '\t') {
          tapKey(ch === '\n' ? RETURN : TAB);
          continue;
        }
        // One character per event (two UTF-16 units outside the BMP), without
        // modifiers: a held Cmd must not turn it into a shortcut.
        const units = Uint16Array.from({ length: ch.length }, (_, i) => ch.charCodeAt(i));
        for (const down of [true, false]) {
          const event = keyEvent(0, down, 0);
          if (event) q.CGEventKeyboardSetUnicodeString(event, units.length, units);
          post(event, 'keyboard');
        }
      }
    },

    /** Turns the display back on and postpones display sleep. */
    wake() {
      activityName ??= q.CFStringCreateWithCString(null, 'PairDesk remote session', CF_UTF8);
      // The same assertion id is reused: each call extends it.
      q.IOPMAssertionDeclareUserActivity(activityName, USER_ACTIVE_LOCAL, activityId);
    },

    releaseAll() {
      for (const code of [...pressedKeys]) this.key(code, false);
      for (const index of [...pressedButtons]) this.button(index, false);
    },

    cursorPos,
  };
}

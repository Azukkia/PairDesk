// Current cursor shape on macOS: [NSCursor currentSystemCursor] (AppKit,
// through the Objective-C runtime) is the cursor shown on screen, whatever
// application set it. macOS gives no names to cursors, so its picture is sent
// like the unnamed cursors of X11 (a custom cursor bitmap).

import crypto from 'node:crypto';
import koffi from 'koffi';
import { loadObjc } from '../objc.js';
import { loadQuartz } from './quartz.js';

const BGRA_PREMULTIPLIED = 2 | (2 << 12); // kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little
const MAX_SIDE = 256; // pixels: bigger cursors (accessibility sizes) are reported as the default arrow

/**
 * Returns a probe: probe.read({ image }) → { serial, shape, width, height,
 * xhot, yhot, scale, x, y, bgra? } | null. `shape` is null (a bitmap) or
 * 'default' (too big); `scale` is pixels per point of the bitmap (2 on
 * Retina displays); `x`, `y` is the cursor position in global points (the
 * unit of the session rect on macOS). `bgra` (premultiplied) is left out
 * when image === 'never'. `objc`, `quartz` and `decode` can be injected for tests.
 */
export function createDarwinCursorProbe({ objc = loadObjc(), quartz = loadQuartz(), decode = (ptr, n) => koffi.decode(ptr, 'uint8_t', n) } = {}) {
  const q = quartz;
  const NSCursor = objc.cls('NSCursor');
  let space = q.CGColorSpaceCreateDeviceRGB();

  function pixels(image, width, height) {
    const ctx = q.CGBitmapContextCreate(null, width, height, 8, width * 4, space, BGRA_PREMULTIPLIED);
    if (!ctx) return null;
    try {
      q.CGContextDrawImage(ctx, { x: 0, y: 0, width, height }, image);
      const data = q.CGBitmapContextGetData(ctx);
      return data ? Buffer.from(decode(data, width * height * 4)) : null;
    } finally {
      q.CFRelease(ctx);
    }
  }

  function location() {
    const event = q.CGEventCreate(null);
    if (!event) return { x: undefined, y: undefined };
    try {
      return q.CGEventGetLocation(event);
    } finally {
      q.CFRelease(event);
    }
  }

  return {
    read({ image = 'never' } = {}) {
      if (!space) return null;
      return objc.withPool(() => {
        const cursor = objc.send('void *', NSCursor, 'currentSystemCursor');
        const img = cursor && objc.send('void *', cursor, 'image');
        if (!img) return null;
        const size = objc.send('PD_NSSize', img, 'size'); // points
        const hot = objc.send('PD_NSPoint', cursor, 'hotSpot'); // points, from the top left corner
        // The best representation for the screen (2x on Retina), owned by the NSImage.
        const cg = objc.send('void *', img, 'CGImageForProposedRect:context:hints:', ['void *', 'void *', 'void *'], null, null, null);
        if (!cg) return null;
        const width = Number(q.CGImageGetWidth(cg));
        const height = Number(q.CGImageGetHeight(cg));
        const { x, y } = location();
        if (!width || !height) return null;
        if (width > MAX_SIDE || height > MAX_SIDE) return { serial: `big:${width}x${height}`, shape: 'default', width, height, x, y };
        const bgra = pixels(cg, width, height);
        if (!bgra) return null;
        const scale = size.width > 0 ? width / size.width : 1;
        const xhot = Math.max(0, Math.min(width - 1, Math.round(hot.x * scale)));
        const yhot = Math.max(0, Math.min(height - 1, Math.round(hot.y * scale)));
        // macOS builds a new NSCursor at each call: the picture tells whether it changed.
        const serial = crypto.createHash('sha1').update(`${width}x${height}@${xhot},${yhot};`).update(bgra).digest('base64url').slice(0, 16);
        const out = { serial, shape: null, width, height, xhot, yhot, scale, x, y };
        if (image !== 'never') out.bgra = bgra;
        return out;
      });
    },
    close() {
      if (space) q.CFRelease(space);
      space = null;
    },
  };
}

// Current cursor shape on X11 through XFixes (libXfixes), called with koffi.
// Runs in the host main process. Works for every X client because XFixes
// reports the cursor currently displayed by the server.

import koffi from 'koffi';
import { cssFromX11Name } from '../../shared/cursor-shapes.js';

let types = null;
function declareTypes() {
  // /usr/include/X11/extensions/Xfixes.h (XFIXES_MAJOR >= 2), LP64 offsets
  // checked with offsetof(): x 0, y 2, width 4, height 6, xhot 8, yhot 10,
  // cursor_serial 16, pixels 24, atom 32, name 40; sizeof = 48.
  types ??= {
    XFixesCursorImage: koffi.struct('PD_XFixesCursorImage', {
      x: 'short',
      y: 'short',
      width: 'unsigned short',
      height: 'unsigned short',
      xhot: 'unsigned short',
      yhot: 'unsigned short',
      cursor_serial: 'unsigned long',
      pixels: 'void *', // unsigned long * : one ARGB (premultiplied) pixel per *long*
      atom: 'unsigned long', // Atom
      name: 'const char *',
    }),
  };
  return types;
}

/**
 * Opens a private X connection and returns a probe:
 *   probe.read({ image }) → { serial, name, shape, width, height, xhot, yhot, bgra? } | null
 * `shape` is a CSS cursor keyword, or null when the cursor has no known name.
 * Chromium/Electron apps and animated cursors never name their cursors (only
 * libXcursor clients such as GTK, Qt, Xlib do), so for those the caller needs
 * the bitmap: `bgra` (Buffer, premultiplied, width*height*4) is filled when
 * image === 'always', or when image === 'unknown' and the name is not known.
 */
export function createX11CursorProbe() {
  const { XFixesCursorImage } = declareTypes();
  const x11 = koffi.load('libX11.so.6');
  const xfixes = koffi.load('libXfixes.so.3');
  const XOpenDisplay = x11.func('void *XOpenDisplay(const char *name)');
  const XCloseDisplay = x11.func('int XCloseDisplay(void *dpy)');
  const XFree = x11.func('int XFree(void *data)');
  const XFixesQueryExtension = xfixes.func('int XFixesQueryExtension(void *dpy, _Out_ int *event_base, _Out_ int *error_base)');
  const XFixesQueryVersion = xfixes.func('int XFixesQueryVersion(void *dpy, _Inout_ int *major, _Inout_ int *minor)');
  // Returns XFixesCursorImage* (one Xlib allocation, image + name included): XFree() it.
  const XFixesGetCursorImage = xfixes.func('void *XFixesGetCursorImage(void *dpy)');

  let dpy = XOpenDisplay(null);
  if (!dpy) throw new Error('Cannot open the X display');
  const ev = [0];
  const err = [0];
  if (!XFixesQueryExtension(dpy, ev, err)) {
    XCloseDisplay(dpy);
    throw new Error('XFixes extension missing');
  }
  const major = [6];
  const minor = [0];
  XFixesQueryVersion(dpy, major, minor);
  if (major[0] < 2) {
    XCloseDisplay(dpy);
    throw new Error(`XFixes ${major[0]}.${minor[0]} has no cursor names`);
  }

  return {
    xfixesVersion: `${major[0]}.${minor[0]}`,
    read({ image = 'never' } = {}) {
      if (!dpy) return null;
      const ptr = XFixesGetCursorImage(dpy);
      if (!ptr) return null;
      try {
        const img = koffi.decode(ptr, XFixesCursorImage);
        const name = img.name || '';
        const out = {
          serial: Number(img.cursor_serial),
          name,
          shape: cssFromX11Name(name),
          width: img.width,
          height: img.height,
          xhot: img.xhot,
          yhot: img.yhot,
        };
        const wantImage = image === 'always' || (image === 'unknown' && !out.shape);
        if (wantImage && img.width && img.height && img.pixels) {
          const n = img.width * img.height;
          // koffi.view() cannot be used in Electron (no external ArrayBuffers in
          // the V8 sandbox): decode() copies. Each pixel is an unsigned long
          // (8 bytes on LP64) whose low 32 bits hold premultiplied ARGB.
          const words = koffi.decode(img.pixels, 'uint32_t', n * 2);
          const bgra = Buffer.alloc(n * 4);
          for (let i = 0; i < n; i++) bgra.writeUInt32LE(words[i * 2] >>> 0, i * 4); // LE: B, G, R, A
          out.bgra = bgra;
        }
        return out;
      } finally {
        XFree(ptr);
      }
    },
    close() {
      if (dpy) XCloseDisplay(dpy);
      dpy = null;
    },
  };
}

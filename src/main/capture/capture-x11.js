// Screen capture on Linux (X11) with the MIT-SHM extension, called with
// koffi from the capture helper process. The X server copies the screen into
// a shared memory segment: no cursor in the picture (Chromium's capture adds
// it, see native-capture.js).

import koffi from 'koffi';

const ZPixmap = 2;
const IPC_PRIVATE = 0;
const IPC_CREAT = 0o1000;
const IPC_RMID = 0;
const ALL_PLANES = 0xffffffffffffffffn;

let types = null;
function declareTypes() {
  types ??= {
    // typedef struct { ShmSeg shmseg; int shmid; char *shmaddr; Bool readOnly; } XShmSegmentInfo;
    ShmInfo: koffi.struct('PD_XShmSegmentInfo', {
      shmseg: 'unsigned long',
      shmid: 'int',
      shmaddr: 'void *',
      readOnly: 'int',
    }),
    // The first fields of XImage (the rest is not used here).
    ImageHead: koffi.struct('PD_XImageHead', {
      width: 'int',
      height: 'int',
      xoffset: 'int',
      format: 'int',
      data: 'void *',
      byte_order: 'int',
      bitmap_unit: 'int',
      bitmap_bit_order: 'int',
      bitmap_pad: 'int',
      depth: 'int',
      bytes_per_line: 'int',
      bits_per_pixel: 'int',
    }),
  };
  return types;
}

/**
 * Opens a capture of `rect` (physical pixels of the X screen). Returns
 * { width, height, grab() → Buffer | null, close() }: grab() returns the
 * picture as 32-bit BGRX rows of width * 4 bytes (a view of the shared
 * segment, valid until the next grab), or null if nothing changed since the
 * previous one.
 */
export function openX11Capture(rect) {
  if (process.env.XDG_SESSION_TYPE === 'wayland') throw new Error('Wayland session');
  const { ShmInfo, ImageHead } = declareTypes();
  const x11 = koffi.load('libX11.so.6');
  const xext = koffi.load('libXext.so.6');
  const libc = koffi.load('libc.so.6');
  const XOpenDisplay = x11.func('void *XOpenDisplay(const char *name)');
  const XCloseDisplay = x11.func('int XCloseDisplay(void *dpy)');
  const XDefaultScreen = x11.func('int XDefaultScreen(void *dpy)');
  const XRootWindow = x11.func('unsigned long XRootWindow(void *dpy, int screen)');
  const XDefaultVisual = x11.func('void *XDefaultVisual(void *dpy, int screen)');
  const XDefaultDepth = x11.func('int XDefaultDepth(void *dpy, int screen)');
  const XDestroyImage = x11.func('int XDestroyImage(void *image)');
  const XSync = x11.func('int XSync(void *dpy, int discard)');
  const XShmQueryExtension = xext.func('int XShmQueryExtension(void *dpy)');
  // The segment info is kept by Xlib (image->obdata): it lives in memory
  // allocated here, not in a temporary copy made for one call.
  const XShmCreateImage = xext.func('void *XShmCreateImage(void *dpy, void *visual, unsigned int depth, int format, void *data, void *info, unsigned int width, unsigned int height)');
  const XShmAttach = xext.func('int XShmAttach(void *dpy, void *info)');
  const XShmDetach = xext.func('int XShmDetach(void *dpy, void *info)');
  const XShmGetImage = xext.func('int XShmGetImage(void *dpy, unsigned long drawable, void *image, int x, int y, unsigned long planes)');
  const shmget = libc.func('int shmget(int key, size_t size, int flags)');
  const shmat = libc.func('void *shmat(int id, void *addr, int flags)');
  const shmdt = libc.func('int shmdt(void *addr)');
  const shmctl = libc.func('int shmctl(int id, int cmd, void *buf)');
  // Copies out of the segment (Electron does not allow a JavaScript view of
  // memory it does not own).
  const memcpy = libc.func('void *memcpy(_Out_ uint8_t *dst, void *src, size_t n)');

  const dpy = XOpenDisplay(null);
  if (!dpy) throw new Error('cannot open the X display');
  let image = null;
  let attached = false;
  let addr = null;
  const info = koffi.alloc(ShmInfo, 1);
  const setInfo = (fields) => koffi.encode(info, ShmInfo, { ...koffi.decode(info, ShmInfo), ...fields });
  setInfo({ shmseg: 0, shmid: -1, shmaddr: null, readOnly: 0 });
  let closed = false;
  const close = () => {
    if (closed) return;
    closed = true;
    if (attached) XShmDetach(dpy, info);
    if (image) {
      // XDestroyImage frees `data` too: detach it first (it is the shared segment).
      koffi.encode(image, 16, 'void *', null);
      XDestroyImage(image);
    }
    if (addr) shmdt(addr);
    XCloseDisplay(dpy);
    koffi.free(info);
    image = null;
    addr = null;
    attached = false;
  };
  try {
    if (!XShmQueryExtension(dpy)) throw new Error('no MIT-SHM extension');
    const scr = XDefaultScreen(dpy);
    const depth = XDefaultDepth(dpy, scr);
    if (depth !== 24 && depth !== 32) throw new Error(`unsupported depth ${depth}`);
    const root = XRootWindow(dpy, scr);
    const width = rect.width;
    const height = rect.height;
    image = XShmCreateImage(dpy, XDefaultVisual(dpy, scr), depth, ZPixmap, null, info, width, height);
    if (!image) throw new Error('XShmCreateImage failed');
    const head = koffi.decode(image, ImageHead);
    if (head.bits_per_pixel !== 32) throw new Error(`unsupported ${head.bits_per_pixel} bits per pixel`);
    const size = head.bytes_per_line * height;
    const shmid = shmget(IPC_PRIVATE, size, IPC_CREAT | 0o600);
    if (shmid < 0) throw new Error('shmget failed');
    addr = shmat(shmid, null, 0);
    // The segment goes away once both sides detached (also if this process dies).
    shmctl(shmid, IPC_RMID, null);
    if (!addr || koffi.address(addr) === 0xffffffffffffffffn) {
      addr = null;
      throw new Error('shmat failed');
    }
    setInfo({ shmid, shmaddr: addr });
    koffi.encode(image, 16, 'void *', addr); // image->data
    if (!XShmAttach(dpy, info)) throw new Error('XShmAttach failed');
    attached = true;
    XSync(dpy, 0);
    const stride = head.bytes_per_line;
    // Two buffers: the new picture and the previous one, to skip unchanged screens.
    let picture = Buffer.alloc(size);
    let previous = Buffer.alloc(size);
    let first = true;
    return {
      width,
      height,
      stride,
      grab() {
        if (!XShmGetImage(dpy, root, image, rect.x, rect.y, ALL_PLANES)) throw new Error('XShmGetImage failed');
        memcpy(picture, addr, size);
        // Unchanged screen: nothing to send (the encoder keeps the last picture).
        if (!first && picture.equals(previous)) return null;
        first = false;
        [picture, previous] = [previous, picture];
        return previous;
      },
      close,
    };
  } catch (err) {
    close();
    throw err;
  }
}

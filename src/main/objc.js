// Objective-C runtime through koffi (macOS): just enough to call the few
// AppKit methods PairDesk needs and that have no C equivalent (NSCursor for
// the cursor shape, NSPasteboard for copied files). Loaded once per process.

import koffi from 'koffi';

let runtime = null;

export function loadObjc() {
  if (runtime) return runtime;
  const lib = koffi.load('/usr/lib/libobjc.A.dylib');
  // Already loaded by Electron; kept referenced for its classes.
  const appKit = koffi.load('/System/Library/Frameworks/AppKit.framework/AppKit');
  koffi.struct('PD_NSPoint', { x: 'double', y: 'double' });
  koffi.struct('PD_NSSize', { width: 'double', height: 'double' });
  const getClass = lib.func('void *objc_getClass(const char *name)');
  const registerSelector = lib.func('void *sel_registerName(const char *name)');
  const poolPush = lib.func('void *objc_autoreleasePoolPush()');
  const poolPop = lib.func('void objc_autoreleasePoolPop(void *pool)');

  const classes = new Map();
  const selectors = new Map();
  // objc_msgSend must be called with the exact C prototype of each method
  // (on Apple silicon above all): one declaration per signature.
  const senders = new Map();
  const sender = (ret, types) => {
    const key = `${ret}(${types.join(',')})`;
    let fn = senders.get(key);
    if (!fn) {
      fn = lib.func('objc_msgSend', ret, ['void *', 'void *', ...types]);
      senders.set(key, fn);
    }
    return fn;
  };

  runtime = {
    appKit,
    cls(name) {
      let c = classes.get(name);
      if (!c) {
        c = getClass(name);
        if (!c) throw new Error(`Objective-C class ${name} not found`);
        classes.set(name, c);
      }
      return c;
    },
    /**
     * [target selector…]: `ret` is the koffi return type, `types` the
     * argument types, e.g. send('void *', pasteboard, 'stringForType:', ['void *'], type).
     */
    send(ret, target, selector, types = [], ...args) {
      let s = selectors.get(selector);
      if (!s) {
        s = registerSelector(selector);
        selectors.set(selector, s);
      }
      return sender(ret, types)(target, s, ...args);
    },
    /** Runs `fn` in an autorelease pool (objects returned by methods are freed after). */
    withPool(fn) {
      const pool = poolPush();
      try {
        return fn();
      } finally {
        poolPop(pool);
      }
    },
    /** JS string → autoreleased NSString. */
    string(text) {
      return this.send('void *', this.cls('NSString'), 'stringWithUTF8String:', ['const char *'], String(text));
    },
    /** NSString → JS string ('' for nil). */
    text(nsString) {
      return nsString ? this.send('const char *', nsString, 'UTF8String') || '' : '';
    },
  };
  return runtime;
}

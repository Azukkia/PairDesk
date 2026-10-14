// Picks the input injector for the current platform. When injection is not
// possible the session still works in "view only" mode.

function unavailable(reason) {
  return {
    available: false,
    reason,
    platform: process.platform,
    moveTo() {},
    button() {},
    wheel() {},
    key() {},
    typeText() {},
    wake() {},
    releaseAll() {},
    cursorPos() {
      return { x: 0, y: 0 };
    },
  };
}

export async function createInjector(log) {
  try {
    if (process.platform === 'win32') return (await import('./win32.js')).createWin32Injector(log);
    if (process.platform === 'linux') return (await import('./x11.js')).createX11Injector(log);
    return unavailable('unsupported-platform');
  } catch (err) {
    log?.error(`[input] remote control unavailable: ${err.message}`);
    return unavailable(err.message);
  }
}

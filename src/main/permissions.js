// macOS privacy permissions a controlled computer needs: Screen Recording
// (without it macOS only lets PairDesk capture the desktop background) and
// Accessibility (without it macOS drops the injected mouse and keyboard
// events). Other systems ask for nothing: everything is allowed there.

export const PRIVACY_PANES = {
  screen: 'x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture',
  accessibility: 'x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility',
};

/**
 * @param {object} o
 * @param {object} o.systemPreferences  Electron systemPreferences
 * @param {object} o.desktopCapturer    Electron desktopCapturer
 * @param {(url: string) => Promise} o.openExternal
 */
export function createPermissions({ platform = process.platform, systemPreferences, desktopCapturer, openExternal, log }) {
  if (platform !== 'darwin') {
    return { status: () => null, canCapture: () => true, canControl: () => true, open: async () => false };
  }
  const screen = () => {
    try {
      return systemPreferences.getMediaAccessStatus('screen');
    } catch (err) {
      log?.warn(`[permissions] screen: ${err.message}`);
      return 'unknown';
    }
  };
  const accessibility = () => {
    try {
      return Boolean(systemPreferences.isTrustedAccessibilityClient(false));
    } catch (err) {
      log?.warn(`[permissions] accessibility: ${err.message}`);
      return false;
    }
  };
  // Screen Recording granted while PairDesk runs only applies once it restarts.
  const screenAtLaunch = screen();
  log?.info(`[permissions] screen recording: ${screenAtLaunch}, accessibility: ${accessibility()}`);

  return {
    /**
     * For the main window: { screen: 'granted' | 'denied' | 'not-determined' |
     * 'restricted' | 'unknown', accessibility: boolean, restart: boolean }.
     */
    status() {
      const now = screen();
      return { screen: now, accessibility: accessibility(), restart: now === 'granted' && screenAtLaunch !== 'granted' };
    },
    /** The screen can be shared (else the picture would only show the desktop background). */
    canCapture: () => screenAtLaunch === 'granted' && screen() === 'granted',
    /** Injected input reaches the applications. */
    canControl: accessibility,
    /** Opens the System Settings pane of `kind` ('screen' or 'accessibility'). */
    async open(kind) {
      if (!PRIVACY_PANES[kind]) return false;
      try {
        if (kind === 'accessibility') {
          // Adds PairDesk to the list (and shows macOS' own prompt the first time).
          systemPreferences.isTrustedAccessibilityClient(true);
        } else if (screen() !== 'granted') {
          // A capture request adds PairDesk to the Screen Recording list.
          await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: 1, height: 1 } });
        }
      } catch (err) {
        log?.warn(`[permissions] ${kind}: ${err.message}`);
      }
      await openExternal(PRIVACY_PANES[kind]);
      return true;
    },
  };
}

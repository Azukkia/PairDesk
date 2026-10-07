// Viewer side (renderer): the controller's own OS cursor is drawn over the
// remote video (zero latency) and takes the shape the host reports.
import { CSS_CURSORS, MAX_CURSOR_IMAGES } from '../shared/cursor-shapes.js';

// Shown when the remote cursor is hidden (or the host has no mouse, which
// Windows reports as "hidden"): the controller must never lose its pointer.
export const HIDDEN_CURSOR =
  "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12'%3E%3Ccircle cx='6' cy='6' r='3.2' fill='%23fff' stroke='%23000' stroke-width='1.4'/%3E%3C/svg%3E\") 6 6, crosshair";

const PNG_DATA_URL = /^data:image\/png;base64,[A-Za-z0-9+/]+={0,2}$/;
const clampInt = (v, min, max) => Math.min(max, Math.max(min, Math.round(Number(v) || 0)));

export function createRemoteCursor(el) {
  const images = new Map(); // custom id → CSS cursor value, least recently used first
  // null until the host reports a shape: hosts older than 1.1 never do, and
  // the stylesheet's dot cursor is then kept.
  let css = null;
  let active = false;

  const apply = () => {
    // Inline style wins over the stylesheet rule for .stage.control video.
    el.style.cursor = active && css ? css : '';
  };

  return {
    /** true while the viewer controls the remote (pointer over the video sends input). */
    setActive(on) {
      active = Boolean(on);
      apply();
    },
    /** Handles a {type:'cursor'} control message from the host. */
    handle(msg) {
      if (msg.shape === 'custom') {
        const id = typeof msg.id === 'string' && msg.id.length <= 64 ? msg.id : null;
        if (!id) return;
        // Same cache rule as the host (src/main/cursor.js): a hit becomes the most recent entry.
        const cached = images.get(id);
        if (cached) {
          images.delete(id);
          images.set(id, cached);
        } else if (typeof msg.png === 'string' && msg.png.length <= 96 * 1024 && PNG_DATA_URL.test(msg.png)) {
          const scale = Math.min(4, Math.max(1, Number(msg.scale) || 1));
          const hot = Array.isArray(msg.hot) ? msg.hot : [0, 0];
          const hx = clampInt(hot[0] / scale, 0, 127);
          const hy = clampInt(hot[1] / scale, 0, 127);
          const img = scale === 1 ? `url("${msg.png}")` : `image-set(url("${msg.png}") ${scale}x)`;
          images.set(id, `${img} ${hx} ${hy}, default`);
          if (images.size > MAX_CURSOR_IMAGES) images.delete(images.keys().next().value);
        }
        css = images.get(id) ?? 'default';
      } else if (msg.shape === 'none') {
        css = HIDDEN_CURSOR;
      } else {
        css = CSS_CURSORS.has(msg.shape) ? msg.shape : 'default';
      }
      apply();
    },
    /** Forget cached bitmaps (new session / reconnection: the host resends them). */
    reset() {
      images.clear();
      css = null;
      apply();
    },
  };
}

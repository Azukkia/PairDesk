// Remote cursor shapes: OS cursor identifiers → CSS cursor keywords.
// Shared by the host main process (detection) and the viewer (validation).
// Must stay free of Node/DOM specific APIs (like src/shared/protocol.js).

/** CSS keywords the viewer accepts in a {type:'cursor'} message. */
export const CSS_CURSORS = new Set([
  'default', 'none', 'text', 'vertical-text', 'pointer', 'wait', 'progress', 'crosshair', 'help',
  'move', 'all-scroll', 'not-allowed', 'no-drop', 'grab', 'grabbing', 'copy', 'alias', 'cell',
  'context-menu', 'zoom-in', 'zoom-out', 'col-resize', 'row-resize',
  'ns-resize', 'ew-resize', 'nwse-resize', 'nesw-resize',
  'n-resize', 's-resize', 'e-resize', 'w-resize', 'ne-resize', 'nw-resize', 'se-resize', 'sw-resize',
]);

// Windows: system cursor ids (MAKEINTRESOURCE values of IDC_*) → CSS.
export const WIN_IDC_TO_CSS = {
  32512: 'default', //    IDC_ARROW
  32513: 'text', //       IDC_IBEAM
  32514: 'wait', //       IDC_WAIT
  32515: 'crosshair', //  IDC_CROSS
  32516: 'default', //    IDC_UPARROW (no CSS equivalent)
  32642: 'nwse-resize', // IDC_SIZENWSE
  32643: 'nesw-resize', // IDC_SIZENESW
  32644: 'ew-resize', //  IDC_SIZEWE
  32645: 'ns-resize', //  IDC_SIZENS
  32646: 'move', //       IDC_SIZEALL
  32648: 'not-allowed', // IDC_NO
  32649: 'pointer', //    IDC_HAND
  32650: 'progress', //   IDC_APPSTARTING
  32651: 'help', //       IDC_HELP
  32671: 'pointer', //    IDC_PIN (Windows 10 1809+)
  32672: 'pointer', //    IDC_PERSON (Windows 10 1809+)
};

// X11: cursor names (Xcursor theme names, legacy core-font names and the
// CSS-style names GTK/Qt/Chromium use) → CSS. XFixes reports the name the
// client gave with XFixesSetCursorName (libXcursor does it automatically).
export const X11_NAME_TO_CSS = {
  // arrows
  left_ptr: 'default', default: 'default', arrow: 'default', top_left_arrow: 'default', left_arrow: 'default',
  right_ptr: 'default', center_ptr: 'default', X_cursor: 'default', x_cursor: 'default',
  // text
  xterm: 'text', text: 'text', ibeam: 'text', 'vertical-text': 'vertical-text',
  // links
  hand: 'pointer', hand1: 'pointer', hand2: 'pointer', pointer: 'pointer', pointing_hand: 'pointer',
  // busy
  watch: 'wait', wait: 'wait',
  left_ptr_watch: 'progress', progress: 'progress', 'half-busy': 'progress',
  // precision / help
  crosshair: 'crosshair', cross: 'crosshair', tcross: 'crosshair', cross_reverse: 'crosshair', diamond_cross: 'crosshair',
  plus: 'cell', cell: 'cell',
  question_arrow: 'help', help: 'help', whats_this: 'help', left_ptr_help: 'help', 'dnd-ask': 'help',
  // move / forbidden
  fleur: 'move', move: 'move', size_all: 'move', 'all-scroll': 'all-scroll',
  'not-allowed': 'not-allowed', crossed_circle: 'not-allowed', forbidden: 'not-allowed', circle: 'not-allowed',
  'no-drop': 'no-drop', 'dnd-no-drop': 'no-drop', 'dnd-none': 'grabbing',
  // grab / drag and drop
  grab: 'grab', openhand: 'grab', grabbing: 'grabbing', closedhand: 'grabbing', dnd_move: 'grabbing', 'dnd-move': 'grabbing',
  copy: 'copy', 'dnd-copy': 'copy',
  alias: 'alias', link: 'alias', 'dnd-link': 'alias',
  'context-menu': 'context-menu', 'zoom-in': 'zoom-in', 'zoom-out': 'zoom-out',
  // resize (two-way)
  sb_v_double_arrow: 'ns-resize', v_double_arrow: 'ns-resize', 'ns-resize': 'ns-resize', size_ver: 'ns-resize',
  sb_h_double_arrow: 'ew-resize', h_double_arrow: 'ew-resize', 'ew-resize': 'ew-resize', size_hor: 'ew-resize',
  fd_double_arrow: 'nwse-resize', size_fdiag: 'nwse-resize', 'nwse-resize': 'nwse-resize',
  bd_double_arrow: 'nesw-resize', size_bdiag: 'nesw-resize', 'nesw-resize': 'nesw-resize',
  'col-resize': 'col-resize', split_h: 'col-resize',
  'row-resize': 'row-resize', split_v: 'row-resize',
  // resize (one edge / corner)
  top_side: 'n-resize', 'n-resize': 'n-resize', bottom_side: 's-resize', 's-resize': 's-resize',
  left_side: 'w-resize', 'w-resize': 'w-resize', right_side: 'e-resize', 'e-resize': 'e-resize',
  top_left_corner: 'nw-resize', 'nw-resize': 'nw-resize', top_right_corner: 'ne-resize', 'ne-resize': 'ne-resize',
  bottom_left_corner: 'sw-resize', 'sw-resize': 'sw-resize', bottom_right_corner: 'se-resize', 'se-resize': 'se-resize',
};

/** Maps an X11 cursor name to a CSS keyword, or null when unknown. */
export function cssFromX11Name(name) {
  if (!name) return null;
  return X11_NAME_TO_CSS[name] ?? X11_NAME_TO_CSS[name.toLowerCase()] ?? null;
}

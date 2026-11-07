// macOS specifics of the main process. Windows and Linux have no application
// menu; a Mac needs one: its Edit menu is what makes Cmd+C/V/X/Z/A work in
// text fields, and Cmd+Q, Cmd+W, Cmd+H come from it too. In a viewer window
// that controls the partner, the page sees those keys first and sends them
// to the remote computer instead.

import { Menu } from 'electron';

/** Builds the menu bar (again when the language changes). */
export function setMacMenu(t, { onAbout, onSettings, onQuit }) {
  const sep = { type: 'separator' };
  Menu.setApplicationMenu(Menu.buildFromTemplate([
    {
      label: 'PairDesk',
      submenu: [
        { label: t('menu.about'), click: onAbout },
        sep,
        { label: t('menu.settings'), accelerator: 'Cmd+,', click: onSettings },
        sep,
        { label: t('menu.hide'), role: 'hide' },
        { label: t('menu.hideOthers'), role: 'hideOthers' },
        { label: t('menu.showAll'), role: 'unhide' },
        sep,
        // Like the menu bar icon's Quit: confirmed while somebody is connected.
        // (Logging out or restarting the Mac quits without asking.)
        { label: t('menu.quit'), accelerator: 'Cmd+Q', click: onQuit },
      ],
    },
    {
      label: t('menu.edit'),
      submenu: [
        { label: t('menu.undo'), role: 'undo' },
        { label: t('menu.redo'), role: 'redo' },
        sep,
        { label: t('menu.cut'), role: 'cut' },
        { label: t('menu.copy'), role: 'copy' },
        { label: t('menu.paste'), role: 'paste' },
        { label: t('menu.selectAll'), role: 'selectAll' },
      ],
    },
    {
      label: t('menu.window'),
      submenu: [
        { label: t('menu.minimize'), role: 'minimize' },
        { label: t('menu.zoom'), role: 'zoom' },
        { label: t('menu.close'), role: 'close' },
        sep,
        { label: t('menu.front'), role: 'front' },
      ],
    },
  ]));
}


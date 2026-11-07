// Notification area icon (menu bar icon on macOS): keeps PairDesk reachable
// when the window is closed.

import path from 'node:path';
import { Tray, Menu, nativeImage, clipboard } from 'electron';
import { formatId } from '../shared/protocol.js';

export function createTray({ appPath, t, id, onOpen, onCheckUpdates, onQuit }) {
  // macOS: a black "template" image (and its @2x version) that the menu bar
  // tints for light and dark modes.
  const file = { win32: 'tray.ico', darwin: 'trayTemplate.png' }[process.platform] || 'tray.png';
  let image = nativeImage.createFromPath(path.join(appPath, 'assets', file));
  if (image.isEmpty()) image = nativeImage.createFromPath(path.join(appPath, 'assets', 'icon.png')).resize({ width: 16, height: 16 });
  else if (process.platform === 'darwin') image.setTemplateImage(true);
  const tray = new Tray(image);

  const refresh = (translate = t) => {
    tray.setToolTip(translate('tray.tooltip', { id: formatId(id) }));
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: translate('tray.open'), click: onOpen },
      { type: 'separator' },
      { label: translate('tray.id', { id: formatId(id) }), enabled: false },
      { label: translate('tray.copyId'), click: () => clipboard.writeText(id).catch(() => {}) },
      { type: 'separator' },
      { label: translate('tray.checkUpdates'), click: onCheckUpdates },
      { label: translate('tray.quit'), click: onQuit },
    ]));
  };
  refresh();
  // On macOS a click opens the menu, like every menu bar icon.
  if (process.platform !== 'darwin') {
    tray.on('click', onOpen);
    tray.on('double-click', onOpen);
  }
  return { tray, refresh, destroy: () => tray.destroy() };
}

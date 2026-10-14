// Tiny window used to replay, on the controlled computer, a file drop made
// by the controller: the input helper presses the mouse here and drags; at
// "dragstart" the main process starts a native drag of the received files,
// which the helper then carries to the point where the controller dropped
// them (desktop, Explorer folder, application...).
document.getElementById('source').addEventListener('dragstart', (event) => {
  event.preventDefault();
  window.pairdesk.send('drop:start');
});

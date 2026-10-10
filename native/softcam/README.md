# PairDesk Camera (virtual webcam for Windows)

When a phone streams its camera to a computer (PairDesk camera session),
Windows applications (Teams, Zoom, Discord, OBS, browsers…) see it as a
webcam named **PairDesk Camera**.

This is [softcam](https://github.com/tshino/softcam) by tshino (MIT, see
`LICENSE`), a DirectShow source filter, vendored from commit
`8f545e2a3faa2c0d6e6e05378f945ee2a7adfc1d`. The Base Classes library in
`src/baseclasses` comes from Microsoft's Windows classic samples (MIT).

PairDesk changes:

* filter name `PairDesk Camera`, its own class ID
  `{DC55A64C-A76A-40C0-8494-4D5E79100E45}` and its own shared memory / mutex
  names, so it never clashes with another softcam-based camera;
* built with CMake and the static C runtime (`CMakeLists.txt`).

How PairDesk uses it (`src/main/camera/`): the DLL ships in the app's
`resources/camera` folder. It is registered for the current user only
(HKCU, no administrator rights) by calling its `DllRegisterServer` with
`HKEY_CLASSES_ROOT` redirected to `HKCU\Software\Classes`. During a camera
session a helper process calls `scCreateCamera` and `scSendFrame` with the
phone's frames (top-down BGR24). The uninstaller removes the registration.

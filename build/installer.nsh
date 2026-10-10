; Extra NSIS steps (included automatically by electron-builder).

!macro customUnInstall
  ; Remove the "start with Windows" entry, except during an update
  ; (updates run the previous uninstaller with the --updated flag).
  ${ifNot} ${isUpdated}
    DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "com.azukkia.pairdesk"
    ; The "PairDesk Camera" webcam registered for this user (src/main/camera/registration.js).
    DeleteRegKey HKCU "Software\Classes\CLSID\{860BB310-5D01-11D0-BD3B-00A0C911CE86}\Instance\{DC55A64C-A76A-40C0-8494-4D5E79100E45}"
    DeleteRegKey HKCU "Software\Classes\CLSID\{DC55A64C-A76A-40C0-8494-4D5E79100E45}"
    RMDir /r "$LOCALAPPDATA\PairDesk\camera"
  ${endIf}
!macroend

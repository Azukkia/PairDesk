; Extra NSIS steps (included automatically by electron-builder).

!macro customUnInstall
  ; Remove the "start with Windows" entry, except during an update
  ; (updates run the previous uninstaller with the --updated flag).
  ${ifNot} ${isUpdated}
    DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "com.azukkia.pairdesk"
  ${endIf}
!macroend

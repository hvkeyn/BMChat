; BMChat Windows Setup — standalone NSIS installer.
;
; electron-builder's own NSIS target cannot build the uninstaller on this
; machine (its BUILD_UNINSTALLER pass exits with code 2), so the installer is
; assembled here from the already packaged `win-unpacked` directory.
;
; Compile:
;   makensis.exe /DVERSION=2.50.53 bmchat-setup.nsi
;
; The installer upgrades an existing per-machine install in place
; (%ProgramFiles%\BMChat). The user profile (%LOCALAPPDATA%\BMChat,
; %APPDATA%\BMChat) is never touched, so chats and keys survive.

Unicode true

!ifndef VERSION
  !define VERSION "2.50.53"
!endif
!ifndef PAYLOAD
  !define PAYLOAD "E:\PPROJECTS\BMChat\clients\desktop\packages\target-electron\dist\win-unpacked"
!endif
!ifndef OUTFILE
  !define OUTFILE "E:\PPROJECTS\BMChat\clients\desktop\packages\target-electron\dist\BMChat-${VERSION}-Setup.x64.exe"
!endif

!define APPNAME "BMChat"
!define COMPANY "Bromore"
!define APPEXE "BMChat.exe"
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\BMChat"

Name "${APPNAME} ${VERSION}"
OutFile "${OUTFILE}"
RequestExecutionLevel user
InstallDir "$LOCALAPPDATA\Programs\${APPNAME}"
InstallDirRegKey HKCU "${UNINST_KEY}" "InstallLocation"

; LZMA must NOT be solid: makensis 3.0.4.1 turns this payload into an
; installer that fails to start (exit code 2, nothing installed) when it is
; packed as one solid LZMA block with a 64 MB dictionary. Plain LZMA works.
SetCompressor lzma
SetDatablockOptimize on
CRCCheck on
XPStyle on

!include "MUI2.nsh"

!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "$INSTDIR\${APPEXE}"
!define MUI_FINISHPAGE_RUN_TEXT "Запустить ${APPNAME}"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "Russian"
!insertmacro MUI_LANGUAGE "English"

Section "BMChat" SEC_MAIN
  SetShellVarContext current
  SetOutPath "$INSTDIR"

  ; Replace a running copy: Windows refuses to overwrite a locked exe.
  nsExec::Exec 'taskkill /IM ${APPEXE} /F'
  Sleep 1500

  File /r "${PAYLOAD}\*.*"

  WriteUninstaller "$INSTDIR\Uninstall.exe"

  CreateDirectory "$SMPROGRAMS\${APPNAME}"
  CreateShortCut "$SMPROGRAMS\${APPNAME}\${APPNAME}.lnk" "$INSTDIR\${APPEXE}"
  CreateShortCut "$SMPROGRAMS\${APPNAME}\Удалить ${APPNAME}.lnk" "$INSTDIR\Uninstall.exe"
  CreateShortCut "$DESKTOP\${APPNAME}.lnk" "$INSTDIR\${APPEXE}"

  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "${APPNAME}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "Publisher" "${COMPANY}"
  WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\${APPEXE}"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1
SectionEnd

Section "Uninstall"
  SetShellVarContext current

  nsExec::Exec 'taskkill /IM ${APPEXE} /F'
  Sleep 1500

  RMDir /r "$INSTDIR"

  Delete "$SMPROGRAMS\${APPNAME}\${APPNAME}.lnk"
  Delete "$SMPROGRAMS\${APPNAME}\Удалить ${APPNAME}.lnk"
  RMDir "$SMPROGRAMS\${APPNAME}"
  Delete "$DESKTOP\${APPNAME}.lnk"

  DeleteRegKey HKCU "${UNINST_KEY}"
  DeleteRegKey HKCU "Software\${APPNAME}"
SectionEnd

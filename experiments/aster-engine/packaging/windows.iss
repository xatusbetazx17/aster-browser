; The application identity and profile location must remain stable across updates.
#ifndef AsterVersion
  #error AsterVersion is required
#endif
#ifndef AsterNativeVersion
  #error AsterNativeVersion is required
#endif

[Setup]
AppId=io.aster.browser.EnginePreview
AppName=Aster Preview
AppVersion={#AsterVersion}
VersionInfoVersion={#AsterNativeVersion}
AppPublisher=Aster Browser
AppPublisherURL=https://github.com/xatusbetazx17/aster-browser
AppUpdatesURL=https://github.com/xatusbetazx17/aster-browser/releases/tag/codex-preview
DefaultDirName={localappdata}\Programs\Aster Preview
DefaultGroupName=Aster Preview
PrivilegesRequired=lowest
ArchitecturesAllowed=x64os
ArchitecturesInstallIn64BitMode=x64os
MinVersion=10.0
UsePreviousAppDir=yes
DisableProgramGroupPage=yes
LicenseFile=..\..\..\LICENSE
OutputDir=..\build
OutputBaseFilename=aster-windows-x64-setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
SetupLogging=yes
UninstallDisplayIcon={app}\application\AsterEnginePreview.exe

[Files]
Source: "..\build\native\AsterEnginePreview\*"; DestDir: "{app}\application"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Aster Preview"; Filename: "{app}\application\AsterEnginePreview.exe"
Name: "{autodesktop}\Aster Preview"; Filename: "{app}\application\AsterEnginePreview.exe"; Tasks: desktopicon

[Tasks]
Name: desktopicon; Description: "Create a desktop shortcut"; Flags: unchecked

[Run]
Filename: "{app}\application\AsterEnginePreview.exe"; Description: "Open Aster Preview"; Flags: nowait postinstall skipifsilent

; User bookmarks, notes and sessions live in Java's per-user preferences. There
; are deliberately no registry/profile deletion rules in this installer.

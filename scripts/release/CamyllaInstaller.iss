#ifndef MyAppVersion
  #error MyAppVersion must be supplied to ISCC.
#endif
#ifndef MySourceDir
  #error MySourceDir must be supplied to ISCC.
#endif
#ifndef MyOutputDir
  #error MyOutputDir must be supplied to ISCC.
#endif

[Setup]
AppId={{6D54700A-9767-4FBD-A329-706B44F0D72C}
AppName=Camylla
AppVersion={#MyAppVersion}
AppPublisher=Camylla
DefaultDirName={autopf}\Camylla
DefaultGroupName=Camylla
DisableProgramGroupPage=yes
OutputDir={#MyOutputDir}
OutputBaseFilename=Camylla-{#MyAppVersion}-windows-x64-setup-unsigned
UninstallDisplayIcon={app}\Camylla.Desktop.exe
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
Compression=lzma2
SolidCompression=yes
WizardStyle=modern

[Files]
Source: "{#MySourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Camylla"; Filename: "{app}\Camylla.Desktop.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\Camylla"; Filename: "{app}\Camylla.Desktop.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Additional shortcuts:"; Flags: unchecked

[Run]
Filename: "{app}\Camylla.Desktop.exe"; Description: "Launch Camylla"; WorkingDir: "{app}"; Flags: nowait postinstall skipifsilent

[Code]
procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
begin
  if CurStep = ssPostInstall then
  begin
    WizardForm.StatusLabel.Caption := 'Registering the Camylla virtual camera...';
    if not Exec(ExpandConstant('{app}\camylla_virtual_camera_ctl.exe'),
      'register --scope current-user', ExpandConstant('{app}'), SW_HIDE,
      ewWaitUntilTerminated, ResultCode) then
      RaiseException('Camylla could not register its virtual camera: ' + SysErrorMessage(ResultCode));
    if ResultCode <> 0 then
      RaiseException('Camylla could not register its virtual camera. Setup was cancelled before completion.');
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  ResultCode: Integer;
begin
  if CurUninstallStep = usUninstall then
  begin
    if not Exec(ExpandConstant('{app}\camylla_virtual_camera_ctl.exe'),
      'unregister --scope current-user', ExpandConstant('{app}'), SW_HIDE,
      ewWaitUntilTerminated, ResultCode) then
      RaiseException('Camylla could not remove its virtual camera. The application files were kept intact.');
    if ResultCode <> 0 then
      RaiseException('Camylla could not remove its virtual camera. The application files were kept intact.');
  end;
end;

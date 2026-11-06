; Bộ cài Tivi Desktop Widget (Inno Setup 6)
; Chạy bằng build.ps1 hoặc ISCC.exe TiviWidget.iss sau khi đã publish vào ..\publish

#define MyAppName "Tivi Desktop Widget"
#define MyAppVersion "0.12.0"
#define MyAppExe "TiviWidget.exe"

[Setup]
AppId={{8C3F2B1E-6A4D-4E7B-9F10-2D5A7C9E3B18}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=Tivi
DefaultDirName={autopf}\TiviWidget
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir=Output
OutputBaseFilename=TiviWidget-Setup-{#MyAppVersion}
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest
UninstallDisplayName={#MyAppName}
UninstallDisplayIcon={app}\{#MyAppExe}
CloseApplications=yes

[Tasks]
Name: "autostart"; Description: "Tự khởi động cùng Windows"; GroupDescription: "Tùy chọn:"; Flags: checkedonce

[Files]
Source: "..\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExe}"
Name: "{group}\Gỡ cài đặt {#MyAppName}"; Filename: "{uninstallexe}"

[Registry]
; Tự khởi động cùng Windows cho người dùng hiện tại (không cần quyền admin)
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "TiviWidget"; ValueData: """{app}\{#MyAppExe}"" --autostart"; Flags: uninsdeletevalue; Tasks: autostart

[Run]
Filename: "{app}\{#MyAppExe}"; Description: "Chạy {#MyAppName} ngay"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Dừng widget trước khi gỡ
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM {#MyAppExe}"; Flags: runhidden; RunOnceId: "StopTiviWidget"

[Code]
const
  WEBVIEW2_CLIENT = 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}';

function WebView2Installed(): Boolean;
var
  pv: String;
begin
  Result := RegQueryStringValue(HKLM, 'SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', pv)
         or RegQueryStringValue(HKLM, WEBVIEW2_CLIENT, 'pv', pv)
         or RegQueryStringValue(HKCU, WEBVIEW2_CLIENT, 'pv', pv);
end;

function InitializeSetup(): Boolean;
begin
  Result := True;
  if not WebView2Installed() then
    MsgBox('Chưa thấy Microsoft Edge WebView2 Runtime trên máy. Widget cần runtime này để hiển thị YouTube và kênh TV.' + #13#10 +
           'Tải tại: https://developer.microsoft.com/microsoft-edge/webview2/' + #13#10 +
           'Bạn vẫn có thể tiếp tục cài đặt.', mbInformation, MB_OK);
end;

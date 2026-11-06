# Chuẩn bị trước khi build và kiểm thử (Windows 10 Pro)

## 1. Phần mềm cần cài

| Thứ | Vì sao | Cài ở đâu | Kiểm tra |
|---|---|---|---|
| .NET 8 SDK (8.0.100 trở lên) | Build, test, publish C# | https://dotnet.microsoft.com/download/dotnet/8.0 | `dotnet --list-sdks` có `8.0.` |
| Microsoft Edge WebView2 Runtime | Chạy WebView2 (YouTube, kênh TV, điều khiển) | Thường có sẵn khi có Edge. Nếu thiếu: https://developer.microsoft.com/microsoft-edge/webview2/ | Xem mục 3 |
| Inno Setup 6 | Tạo bộ cài `TiviWidget-Setup-x.exe` (không bắt buộc để chạy app) | https://jrsoftware.org/isinfo.php hoặc `choco install innosetup` | `Test-Path "C:\Program Files (x86)\Inno Setup 6\ISCC.exe"` |
| Gói nhận dạng giọng nói tiếng Việt | Ctrl+M và lệnh giọng nói | Settings > Time & language > Speech > Speech language: thêm Tiếng Việt | Xem mục 3 |
| Git (tuỳ chọn) | Quản lý mã nguồn | https://git-scm.com | `git --version` |
| JDK 17 + Android SDK (chỉ để build APK tại máy) | Build APK Android | Khuyến nghị dùng CI (`ci/android-tv-build.yml`) | `java -version` |

Không cần Visual Studio. NuGet (WebView2, NAudio, System.Speech, xunit) được `dotnet restore` tự tải.

## 2. Kiểm tra nhanh (PowerShell)

```powershell
dotnet --list-sdks
Get-ChildItem "HKLM:\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}" -ErrorAction SilentlyContinue | Out-Null; "WebView2 key: " + (Test-Path "HKLM:\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}")
Add-Type -AssemblyName System.Speech
[System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers() | ForEach-Object { $_.Culture.Name }
```

Kết quả mong đợi:
- Có dòng `8.0.x`.
- `WebView2 key: True`.
- Danh sách culture có `vi-VN`. Nếu không có, phải cài gói tiếng Việt cho giọng nói.

## 3. Cấu trúc thư mục cần có

```
windows_tivi/
  global.json
  TiviWidget/                 mã nguồn app (csproj, *.cs, *.xaml, Assets/, Remote/, Voice/, Web/, Tv/)
  TiviWidget.Tests/           project xUnit
  installer/                  TiviWidget.iss, build.ps1
  ci/                         workflow mẫu (đặt vào repo mới)
```

Trong `TiviWidget/Assets/` phải có: `channels.json`, `blocklist.txt`, `web/truyentep.html`, `web/vendor/firebase-compat.js`.

## 4. Lệnh build, test, đóng gói

Chạy trong thư mục `windows_tivi`:

```powershell
dotnet restore TiviWidget/TiviWidget.csproj
dotnet build TiviWidget/TiviWidget.csproj -c Release
dotnet test TiviWidget.Tests/TiviWidget.Tests.csproj
.\installer\build.ps1
```

- `dotnet build`: phải không có lỗi. Cảnh báo có thể chấp nhận, nhưng ghi lại.
- `dotnet test`: tất cả test phải pass. Xem `TEST_PLAN.md` mục A.
- `build.ps1`: publish ra `windows_tivi/publish/TiviWidget.exe`. Nếu có Inno Setup thì tạo `installer/Output/TiviWidget-Setup-0.12.0.exe`.

## 5. Chạy app lần đầu

- Chạy `publish\TiviWidget.exe` (không cần quyền admin).
- Widget xuất hiện trên nền desktop. Thoát bằng Ctrl+Alt+Q khi cửa sổ widget có focus, hoặc Task Manager.
- Khởi động cùng Windows được bật bởi bộ cài. Muốn tắt: xoá `TiviWidget` trong `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`.

## 6. Repo và token

- Không đẩy code vào repo `a3`. Tạo repo mới cho bản Windows và bản Android nếu cần CI.
- Token GitHub đã dán trong cuộc trò chuyện. Hãy thu hồi và tạo token mới: GitHub > Settings > Developer settings > Personal access tokens.

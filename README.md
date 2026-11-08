# cha — Tivi (Windows widget) + DK TV Gộp + Tivi TV (Android)

- `android_dktv/` — app điều khiển từ xa Android tổng hợp (gộp DK_TV và dktv của a3). Xem `android_dktv/README.md`.
- `android_tivi/` — app "tivi" cài trên TV (nhận tệp từ điện thoại, nút Tệp cài APK). Xem `android_tivi/README.md`.
- `windows_tivi/` — widget Windows 0.12.0 (WPF + WebView2, C# .NET 8).
  - `TiviWidget/` — mã nguồn app.
  - `TiviWidget.Tests/` — kiểm thử xUnit (`dotnet test`).
  - `verify_logic/` — kiểm tra nhanh không cần NuGet (`dotnet run`).
  - `installer/` — `build.ps1` + `TiviWidget.iss` tạo bộ cài.
  - `SETUP_PREREQUISITES.md`, `TEST_PLAN.md`, `VERIFY_LOG.txt`.
- `.github/workflows/windows-build.yml` — CI: build, test và tạo bộ cài cho Windows.
- `.github/workflows/android-dktv-build.yml` — CI: build APK app điều khiển DK TV Gộp.
- `.github/workflows/android-tivi-build.yml` — CI: build APK app tivi cài trên TV.

Chưa được build và chạy trên Windows thật. Xem `windows_tivi/TEST_PLAN.md`.

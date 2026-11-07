# cha — Tivi (Windows widget)

- `windows_tivi/` — widget Windows 0.12.0 (WPF + WebView2, C# .NET 8).
  - `TiviWidget/` — mã nguồn app.
  - `TiviWidget.Tests/` — kiểm thử xUnit (`dotnet test`).
  - `verify_logic/` — kiểm tra nhanh không cần NuGet (`dotnet run`).
  - `installer/` — `build.ps1` + `TiviWidget.iss` tạo bộ cài.
  - `SETUP_PREREQUISITES.md`, `TEST_PLAN.md`, `VERIFY_LOG.txt`.
- `.github/workflows/windows-build.yml` — CI: build, test và tạo bộ cài cho Windows.

Chưa được build và chạy trên Windows thật. Xem `windows_tivi/TEST_PLAN.md`.

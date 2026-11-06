# Đóng gói Tivi Desktop Widget: publish -> (nếu có Inno Setup 6) tạo TiviWidget-Setup-x.exe
param([string]$Configuration = "Release")
$ErrorActionPreference = "Stop"
$root    = Split-Path -Parent $MyInvocation.MyCommand.Path
$proj    = Join-Path $root "..\TiviWidget\TiviWidget.csproj"
$publish = Join-Path $root "..\publish"

Write-Host "1/2 Publish -> $publish"
dotnet publish $proj -c $Configuration -r win-x64 --self-contained true -p:PublishSingleFile=true -o $publish
if ($LASTEXITCODE -ne 0) { throw "dotnet publish lỗi" }

$iscc = @("C:\Program Files (x86)\Inno Setup 6\ISCC.exe", "C:\Program Files\Inno Setup 6\ISCC.exe") |
        Where-Object { Test-Path $_ } | Select-Object -First 1
if ($iscc) {
    Write-Host "2/2 Đóng gói bộ cài bằng Inno Setup"
    & $iscc (Join-Path $root "TiviWidget.iss")
    Write-Host "Xong: $root\Output\"
} else {
    Write-Host "2/2 Bỏ qua bộ cài: chưa có Inno Setup 6 (https://jrsoftware.org/isinfo.php)."
    Write-Host "    Thư mục publish đã sẵn sàng: $publish\TiviWidget.exe"
}

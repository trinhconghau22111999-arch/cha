Tivi Desktop Widget — Bước 3: khung project Windows (WPF + WebView2)

Đã có:
- Cửa sổ không viền, không hiện trên taskbar, nền đen
- WebView2 mở YouTube (trang chủ tiếng Việt, cùng URL như bản Android)
- Thanh dưới trắng: nút TV (placeholder), nút YouTube, đồng hồ + thứ + ngày
- Link target=_blank mở ngay trong khung

Chưa có (các bước sau):
- Bước 4: gắn cửa sổ vào lớp nền desktop (WorkerW), phủ toàn màn hình chính
- Bước 5: hành vi khi có app khác trên màn hình chính
- Bước 6: danh sách kênh TV, âm lịch, phím/chuột
- Bước 7: tự khởi động cùng Windows, đồng bộ điện thoại

Build (Windows 10/11 có .NET 8 SDK + Edge WebView2 Runtime):
  dotnet restore
  dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
Kết quả: bin\Release\net8.0-windows10.0.19041.0\win-x64\publish\TiviWidget.exe

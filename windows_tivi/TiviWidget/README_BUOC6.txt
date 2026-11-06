Tivi Desktop Widget — Bước 6: kênh TV, âm lịch, phím và chuột

Đã port:
- Chế độ TV: trang "Chọn Kênh" theo đài (3 cột), đài 1 kênh mở luôn, đài nhiều kênh có danh sách
  (Assets/channels.json, cùng dữ liệu với bản Android)
- Phát kênh bằng WebView2 + đếm ngược 5-4-3-2-1 che lúc tải (giống Android)
- Chặn quảng cáo ở tầng mạng (AdBlocker.cs, theo blocklist.txt + AD_HOSTS của Android)
- TV_FULL_JS: video phủ toàn khung, tự phát
- Âm lịch: LunarCalendar.cs (port thuật toán Hồ Ngọc Đức), hiển thị "ÂL d/m [nhuận]" trên thanh dưới

Điều khiển:
- Chuột: bấm đài/kênh, nút ← để quay lại khi đang xem
- Phím: ◀▶▲▼ chọn ô (viền trắng), Enter mở, Esc hoặc Backspace quay lại,
  N hoặc PageDown sang kênh kế tiếp trong cùng đài

Chưa port (để bước sau):
- Tìm kênh bằng giọng nói (playByName)
- Chế độ YouTube: vẫn là WebView2 đơn giản, chưa có chặn quảng cáo YouTube và ô chọn video kiểu remote
- Điều khiển từ điện thoại (TransferHost, RemoteHub, SyncClient)

Cần kiểm thử trên Windows:
- Ô chọn bằng phím có nhận keyboard không, vì cửa sổ nằm trong WorkerW (có thể cần bấm chuột vào widget trước)
- Khi đang xem kênh, phím có thể bị WebView2 nuốt; nút ← và chuột vẫn hoạt động

Build: dotnet restore && dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true

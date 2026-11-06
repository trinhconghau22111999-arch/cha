Tivi Desktop Widget — Bước 9: giọng nói và YouTube đầy đủ (đối chiếu với bản Android)

GIỌNG NÓI (port remoteVoice của MainActivity.kt)
- Câu ghép có từ mở + từ khoá: "mở YouTube mở bài hát X" -> sang YouTube, tìm "X".
  "mở TV kênh HTV9" -> sang TV, phát HTV9. Từ đệm (mở, bài, hát, kênh, đài...) được bỏ qua.
- Lệnh mở đơn: "mở TV" -> trang chọn đài. "mở YouTube" -> trang chủ.
- Không có lệnh mở: đang ở TV thì tìm kênh theo tên; đang ở YouTube thì tìm kiếm.

YOUTUBE
- Điều hướng ▲▼◀▶ OK bằng YT_NAV_JS nguyên văn. Khi vào trang kết quả tìm kiếm, video đầu tiên
  được đánh dấu sẵn; bấm Enter (OK) để mở. Đây cũng là hành vi của bản Android (không tự mở).
- ← (Esc/Backspace): lùi theo danh sách trang đã xem, giống back() của Android.
  Lịch sử ghi theo từng video (URL cùng video được coi là một trang). Chuyển trang kiểu SPA được
  ghi qua sự kiện HistoryChanged của WebView2.

GIỚI HẠN CẦN KIỂM THỬ
- Phím: khi WebView2 đang có focus, phím có thể được trang nhận trước cửa sổ.
- Chưa biên dịch trên Windows.
- Nhận dạng giọng nói phụ thuộc gói tiếng Việt đã cài trên Windows; kết quả có thể khác Google.
- Chưa có đồng bộ với điện thoại (Bước 8).

Build: dotnet restore && dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true

Tivi Desktop Widget — Bước 7: tự khởi động, bộ cài, xử lý khi Explorer restart

TỰ KHỞI ĐỘNG
- Bộ cài ghi HKCU\Software\Microsoft\Windows\CurrentVersion\Run = "TiviWidget.exe" --autostart
  (tuỳ chọn, mặc định bật). Không cần quyền admin.
- Khi chạy --autostart, app chờ 3 giây để desktop sẵn sàng.

XỬ LÝ KHI EXPLORER KHỞI ĐỘNG LẠI
- Watchdog kiểm tra mỗi 10 giây. Nếu cửa sổ nền bị huỷ, app tự mở bản mới với --restarted.
- Bản mới chờ tối đa 15 giây để bản cũ thoát (Mutex), rồi gắn lại vào desktop mới.
- Chỉ một bản chạy tại một thời điểm.

THOÁT
- Ctrl+Alt+Q khi cửa sổ widget đang có focus.
- Hoặc Task Manager (kết thúc TiviWidget.exe). Gỡ cài đặt cũng tự dừng widget.

BỘ CÀI
- installer\build.ps1: publish vào ..\publish, rồi tạo installer\Output\TiviWidget-Setup-0.8.0.exe
  nếu đã cài Inno Setup 6 (ISCC.exe). Không có thì chỉ publish.
- Bộ cài kiểm tra WebView2 Runtime và cảnh báo nếu thiếu.

GIỚI HẠN CẦN KIỂM THỬ
- Chưa biên dịch và chưa chạy trên Windows.
- Khi Explorer restart, widget có thể trống tối đa 10 giây trước khi tự khởi động lại.
- Bộ cài dùng giao diện tiếng Anh mặc định của Inno Setup; nội dung cảnh báo WebView2 là tiếng Việt.
- File .exe chưa ký số: Windows SmartScreen có thể cảnh báo khi cài.

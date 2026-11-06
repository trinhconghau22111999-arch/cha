Tivi Desktop Widget — Bước 5: hành vi khi có app trên màn hình chính

Quy tắc:
- Không có app nào hiện trên màn hình chính  -> phủ toàn bộ màn hình chính
- Có app hiện trên màn hình chính           -> thu nhỏ về góc phải dưới (480x270 px, trên taskbar)
- Đổi sang ẩn hẳn: DesktopHost.cs, đặt HideWhenAppsOpen = true

Cách đếm "app": cửa sổ top-level đang hiện, không thu nhỏ, không bị cloak (UWP ẩn),
không phải tool window, không phải shell (Progman, WorkerW, taskbar, icon desktop),
và có phần nằm trên màn hình chính. Kiểm tra mỗi giây.

Chỉnh kích thước thu nhỏ: CompactWidth, CompactHeight, CompactMargin trong DesktopHost.cs.

CHƯA kiểm thử trên Windows. Lưu ý: khi thu nhỏ, WebView vẫn chạy (âm thanh vẫn phát).

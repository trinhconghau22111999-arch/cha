Tivi Desktop Widget — 0.11.0: bổ sung điều khiển từ điện thoại

ĐÃ BỔ SUNG (các lệnh app điện thoại gửi mà bản trước chưa xử lý)
- quality (360/480/720): lưu chất lượng YouTube và ép player, lặp ở 1,5 s và 4 s như Android.
- fullscreen: chạm thật vào giữa video qua DevTools rồi xin toàn màn hình (cùng cách Android).
- touchlock: khoá các nút WPF và bỏ phím tại chỗ. Hiện trạng thái ở thanh dưới.
- brightness: đổi độ sáng thật của màn hình qua WMI (chỉ cách này; máy không hỗ trợ thì không chỉnh).

ĐÃ SỬA LỖI
- Nút ← và dòng trạng thái giọng nói nằm chồng lên WebView2, WPF không vẽ được. Đã chuyển xuống thanh dưới.
- Lớp đếm ngược 5-4-3-2-1 bị WebView2 che. Giờ ẩn WebView2 trong lúc đếm, xong mới hiện lại.

ĐÃ KIỂM TRA CÙNG HẠNG MỤC
- Đồng bộ YouTube: không có trong bản Windows, và không có trong module Android trên nhánh main.
  Chức năng này chỉ có ở nhánh sync-tv, không được dùng.

GIỚI HẠN
- touchlock không chặn được chạm chuột trực tiếp vào vùng WebView2 (hạn chế của WebView2).
- brightness chỉ chạy với màn hình hỗ trợ WMI (thường là laptop); PC nối màn hình ngoài/TV thường không chỉnh được.
- fullscreen dùng DevTools Input để giả thao tác người dùng. Cần kiểm thử trên Windows.
- Chưa biên dịch và chưa chạy trên Windows.

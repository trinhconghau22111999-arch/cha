Tivi Desktop Widget — Bước 8: điều khiển từ điện thoại (remote)

ĐÃ NỐI
- Trang truyentep.html + vendor/firebase-compat.js chạy trong WebView2 ẩn (RemoteHost), nguyên văn
  từ Android. Tên miền ảo tivi.local, không đổi nội dung trang.
- Mã ghép cặp cố định 654321 (khớp với app Android android_dktv). Điện thoại dùng app DK TV Gộp để điều khiển.
- Cầu nối AndroidCam được thay bằng window.chrome.webview (RemoteBridge.cs).
- Lệnh: move, click, scroll, key (up/down/left/right/ok), back, home, media, volume, screen, voice.
- textEdit: điện thoại gõ chữ vào ô đang chọn (JS TEXT_EDIT_FN nguyên văn), Enter khi ins = "\n".
- keepAwake: giữ màn hình không ngủ (SetThreadExecutionState).

CÁCH ĐỔI SANG WINDOWS
- move/click/scroll: điều khiển con trỏ thật của Windows. Widget nằm trên nền desktop nên thao tác
  đúng với những gì đang thấy. Khi cửa sổ khác che widget thì click đi vào cửa sổ đó.
- back: như Esc (đã bỏ tính năng lùi 3 lần để về trang chủ).
- home: đầu trang chọn đài -> sang YouTube; còn lại -> về trang chọn đài. Bản Android có
  thêm màn hình chính trống; Windows chưa có màn hình trống nên dùng hai trạng thái trên.
- media: play/pause, bài trước, bài sau. Windows không có phím tua nên rew/fwd đổi bài.
- volume: âm lượng mặc định của Windows (NAudio).
- screen off: tắt màn hình (monitor off). screen on: bật lại. Không khoá máy.

KHÔNG PORT (theo yêu cầu)
- Nhận và gửi tệp, nhận và xem camera, chỉnh sửa ảnh. Trang truyentep.html vẫn chứa các phần này
  nhưng các hàm cầu nối trả về "thất bại" nên không hoạt động trên Windows.

GIỚI HẠN CẦN KIỂM THỬ
- Chưa biên dịch và chưa chạy. Cần xác nhận WebRTC/Firebase chạy được trong WebView2 ẩn 1x1.
- Lan toả mDNS (.local) trên mạng của Windows có thể khác Android; cần cùng Wi-Fi.
- Thao tác chuột/phím hệ thống cần widget hiện trên desktop; kiểm tra quyền của Windows.

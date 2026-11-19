# android_tivi — app "tivi" cài trên TV

Lấy từ module `tv` của repo a3, đã chỉnh:

- Bỏ đồng bộ YouTube với điện thoại (Ái Kara), bỏ nút Menu và nút Home trên thanh dưới.
- Thêm nút **Tệp**: liệt kê tệp nhận trong 1 giờ qua (quá 1 giờ tự xoá), bấm OK để mở; tệp `.apk` mở trình cài đặt.
- Nhận được tệp `.apk` qua Firebase/WebRTC thì tự mở màn Tệp và chọn sẵn tệp đó.
- Giao diện kiểu gaming (nền tối, viền neon).

Cập nhật mới: thanh dưới thêm lại **Menu** (danh sách ứng dụng trên TV), **Ái Kara** (đồng bộ YouTube với điện thoại) và **Home** (về màn hình chính TV) như repo a3, và nút **Cập nhật**: bấm là app tải `Tivi-TV.apk` mới nhất từ
https://github.com/trinhconghau22111999-arch/cha/releases/latest/download/Tivi-TV.apk rồi tự mở trình cài đặt (hoặc hiện thông báo khi app đang ở nền); bấm "Cập nhật" lần nữa trong màn cài đặt/thông báo là app lên bản mới.

Thanh dưới: Menu | TV | YouTube | Ái Kara | Cài đặt | Tệp | Home | Cập nhật | ←.
applicationId `com.desktopmini.tivi`, ký bằng keystore cố định trong `keystore/` nên cài đè được bản cũ.
Nhận tệp và điều khiển từ điện thoại dùng mã ghép nối cố định 654321 (trùng với app "điều khiển" trong repo này, điện thoại dùng đúng mã này). Android 4.4 cài được (minSdk 19) nhưng WebView cũ nên YouTube và nhận tệp có thể lỗi; nên dùng Android 7.0 trở lên.

Build: tab Actions → "Android Tivi TV APK" (hoặc đẩy thay đổi trong `android_tivi/`), tải `tv-debug.apk` trong Artifacts hoặc Releases.

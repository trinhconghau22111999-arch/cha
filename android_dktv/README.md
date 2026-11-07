# DK TV Gộp — app điều khiển từ xa tổng hợp (Android)

Gộp 2 app điều khiển từ xa thành 1, giữ toàn bộ chức năng của cả hai:

- **DK TV** (repo `DK_TV`)
- **DK TV 2** (module `dktv` trong repo `a3`)

Điều khiển bản Windows (`windows_tivi/`) qua WebRTC + Firebase. Mã ghép cặp cố định **654321**, dùng chung với bản Windows (hai app gốc dùng 123456).

## Chức năng
- Chuột cảm ứng: trượt = di chuyển (con trỏ có gia tốc), chạm = nhấp, 2 ngón = cuộn (có quán tính).
- 5 phím ▲ ▼ ◀ ▶ OK trong khung chuột; giữ ▼ 2 giây = nhảy xuống thanh dưới.
- Nút TẮT/MỞ MÀN HÌNH, CHUYỂN ĐỔI GIAO DIỆN, GIỌNG NÓI, TẮT/BẬT CẢM ỨNG TV, PHÓNG TO MÀN HÌNH.
- Thanh ÂM LƯỢNG và ĐỘ SÁNG; chất lượng video 360p/720p; BÀI TIẾP; lùi / phát-dừng / tiến; nút LÙI.
- Chạy nền bằng dịch vụ, tự chạy lại sau khi khởi động máy.

## Build
- Workflow `.github/workflows/android-dktv-build.yml` (Actions → Android DK TV Gop APK, hoặc mục Releases).
- Cần secret `GOOGLE_SERVICES_JSON_BASE64` trong repo này (file google-services.json mã hoá base64), nếu không app không kết nối được.
- applicationId `com.hau.dktvall`: cài song song, không đè 2 app cũ.

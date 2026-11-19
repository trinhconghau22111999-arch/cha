# cha — DK TV Gộp + Tivi TV + ScreenCast (Android)

- `android_dktv/` — app điều khiển từ xa Android tổng hợp (gộp DK_TV và dktv của a3). Xem `android_dktv/README.md`.
- `android_tivi/` — app "tivi" cài trên TV (nhận tệp từ điện thoại, nút Tệp cài APK). Xem `android_tivi/README.md`.
- `android_screencast/` — app ScreenCast "Ái-kara" (Tube for me) cài trên điện thoại, chiếu lên TV qua WiFi (WebRTC); sao chép từ repo `a3` (module `app`). Xem `android_screencast/README.md`.
- `.github/workflows/build-all.yml` — CI: mỗi lần chạy build 3 app Android và tạo 1 release `build-<số>` có 3 file APK.

## Link tải APK (cố định, luôn là bản mới nhất)

- DK TV Gộp (điện thoại): https://github.com/trinhconghau22111999-arch/cha/releases/latest/download/DK-TV-Gop.apk
- Tivi TV (cài trên TV): https://github.com/trinhconghau22111999-arch/cha/releases/latest/download/Tivi-TV.apk
- ScreenCast (điện thoại): https://github.com/trinhconghau22111999-arch/cha/releases/latest/download/ScreenCast.apk

Bấm link là tự tải về. Lần đầu mở app, app sẽ hỏi bật "Cho phép cài ứng dụng từ nguồn không xác định" (Android 8+).

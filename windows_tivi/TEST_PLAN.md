# Kế hoạch kiểm thử — Tivi Desktop Widget 0.12.0

## A. Tự động (chạy trên máy hoặc CI)

Lệnh: `dotnet test TiviWidget.Tests/TiviWidget.Tests.csproj`

| Lớp test | Kiểm tra | Mong đợi |
|---|---|---|
| LunarCalendarTests | 1/1/2000, 10/2/2024, 17/2/2026, 6/11/2026 | 25/11/1999; 1/1/2024; 1/1/2026; 28/9/2026 |
| VoiceRouterTests | Câu ghép YouTube và TV; lệnh mở đơn; câu không có nội dung; chuẩn hoá | Tách đúng app và nội dung; giữ dấu trong nội dung |
| ChannelMatcherTests | "ca mau", "HTV9", tên không có | Đúng kênh; không có thì null |
| RemoteControlTests | key, bar, quality, touchlock, brightness, voice, back x3, JSON hỏng | Sự kiện đúng giá trị; lệnh rác không gây lỗi |
| YtRulesTests | URL YouTube, redirect, Google, about, file | Đúng quy tắc Android |

Test không mở cửa sổ và không gọi chuột, âm lượng, màn hình.

## B. Build

| Kiểm tra | Mong đợi |
|---|---|
| `dotnet build` Release | Không lỗi |
| `build.ps1` | Có `publish\TiviWidget.exe` |
| `TiviWidget.exe` chạy được khi thư mục `Assets` nằm cạnh | Không báo thiếu file |
| Inno Setup (nếu có) | Có `installer\Output\TiviWidget-Setup-0.12.0.exe` |

## C. Thủ công — nền desktop (Bước 4, 5)

| # | Thao tác | Mong đợi |
|---|---|---|
| C1 | Chạy app, không mở app nào | Widget phóng to hết cỡ vùng làm việc (trên taskbar, thanh nút dưới thấy đủ), nằm sau icon desktop |
| C2 | Mở Notepad (cửa sổ thường) | Widget KHÔNG thu nhỏ, vẫn phóng to tối đa phía sau Notepad |
| C3 | Đóng Notepad | Widget vẫn như cũ, thấy lại ngay |
| C4 | Bật `HideWhenAppsOpen = true` và build lại; mở Notepad | Widget ẩn; đóng Notepad thì hiện lại |
| C5 | Đổi độ phân giải hoặc vị trí/kích thước taskbar | Widget phóng to lại vừa vùng làm việc mới |
| C6 | Cửa sổ Start, Search | Không làm widget thu nhỏ (đã bỏ qua) |

## D. Thủ công — TV (Bước 6)

| # | Thao tác | Mong đợi |
|---|---|---|
| D1 | Bấm nút TV | Hiện "Chọn Kênh" với các đài, 3 cột |
| D2 | Bấm đài 1 kênh (ví dụ Vĩnh Long) | Phát luôn, không qua danh sách kênh |
| D3 | Bấm đài nhiều kênh (Việt Nam) | Hiện danh sách kênh; bấm VTV1 thì phát |
| D4 | Khi phát: đếm 5-4-3-2-1 | Lớp đen đếm ngược, sau đó hình hiện ra |
| D5 | Khi phát: nút ← ở thanh dưới; Esc; Backspace | Quay lại danh sách kênh; hết ← về danh sách đài |
| D6 | Phím N hoặc PageDown khi đang xem | Sang kênh kế tiếp trong cùng đài |
| D7 | Đồng hồ thanh dưới | Hiện giờ, thứ, ngày, "ÂL d/m" đúng với lịch Việt Nam (6/11/2026 là ÂL 28/9) |
| D8 | Kênh có quảng cáo | Quảng cáo từ domain trong blocklist không tải |

## E. Thủ công — YouTube và giọng nói (Bước 9)

| # | Thao tác | Mong đợi |
|---|---|---|
| E1 | Mở app | Vào thẳng trang chủ YouTube (tiếng Việt) |
| E2 | Mở một link không phải YouTube (nếu có) | Bị chặn, hiện "Mục này chỉ mở được YouTube" |
| E3 | Ctrl+M, nói "mở TV kênh HTV9" | Sang TV và phát HTV9 |
| E4 | Ctrl+M, nói "mở YouTube mở bài hát X" | Sang YouTube, tìm X; video đầu tiên được đánh dấu, Enter để mở |
| E5 | Ctrl+M, nói "mở TV" | Sang trang chọn đài |
| E6 | Ctrl+M, nói tên kênh khi đang ở TV | Phát kênh đó; nếu không có thì hiện "Không tìm thấy kênh" |
| E7 | Xem một video rồi bấm ← | Lùi về trang trước; hết lịch sử thì về trang chủ |

Giọng nói cần gói tiếng Việt đã cài (SETUP mục 2). Nhận dạng không chính xác bằng Google, nên có thể thử nhiều lần.

## F. Thủ công — điều khiển từ điện thoại (Bước 8, 10)

Chuẩn bị: điện thoại có app DK TV Gộp (android_dktv), cùng Wi-Fi với PC, mã ghép cặp 654321.

| # | Lệnh | Thao tác trên điện thoại | Mong đợi trên PC |
|---|---|---|---|
| F1 | Kết nối | Mở DK TV Gộp, ghép với 654321 | Trạng thái kết nối; không có lỗi trong vài phút |
| F2 | move | Vuốt trên màn hình điện thoại | Con trỏ chuột PC di chuyển |
| F3 | click | Chạm | Click tại con trỏ |
| F4 | scroll | Vuốt dọc | Cuộn trang đang mở |
| F5 | key ▲▼◀▶ OK | Bấm nút | Chuyển ô chọn (trang chọn đài) hoặc điều khiển YouTube |
| F6 | back | Bấm ← | Như Esc; ba lần liên tiếp thì về trang chủ |
| F7 | home | Bấm Màn hình chính | Đầu trang chọn đài: sang YouTube; còn lại: về trang chọn đài |
| F8 | media | play/pause, trước, sau | Phím media Windows gửi đi |
| F9 | volume | Kéo thanh âm lượng | Âm lượng Windows đổi |
| F10 | screen off | Tắt màn hình | Màn hình PC tắt; bật lại bằng chuột hoặc phím |
| F11 | voice | Nói trên dktv | Như Ctrl+M |
| F12 | textEdit | Gõ chữ khi ô nhập đang chọn trên PC | Chữ xuất hiện trong ô; Enter gửi lệnh Enter |
| F13 | quality 360/480/720 | Chọn chất lượng | Chất lượng video YouTube đổi sau vài giây |
| F14 | fullscreen | Bấm toàn màn hình | Video vào chế độ toàn màn hình trong WebView2 |
| F15 | touchlock | Bật khoá | Các nút trên widget không bấm được; bật lại thì mở |
| F16 | brightness 0–100 | Kéo độ sáng | Nội dung trang tối/sáng theo mức; 100 là bình thường |
| F17 | keepAwake | Bật camera hoặc remote | PC không tự ngủ trong lúc đó |

Ghi chú: touchlock không chặn được chạm chuột trực tiếp trong vùng WebView2. Đây là giới hạn đã biết.

## G. Thủ công — khởi động và cài đặt (Bước 7)

| # | Thao tác | Mong đợi |
|---|---|---|
| G1 | Cài bằng bộ cài, bật "Tự khởi động" | Có khoá `HKCU\...\Run\TiviWidget` |
| G2 | Đăng xuất rồi đăng nhập | Widget tự chạy sau ~3 giây |
| G3 | Chạy app lần hai | Bản thứ hai thoát ngay; chỉ một cửa sổ |
| G4 | Ctrl+Alt+Q khi widget có focus | Widget thoát |
| G5 | Restart Explorer (`taskkill /f /im explorer.exe`, rồi `explorer.exe`) | Trong ~10 giây widget tự mở lại và phủ desktop |
| G6 | Gỡ cài đặt | Widget dừng; khoá Run bị xoá |
| G7 | Máy không có WebView2 Runtime, chạy bộ cài | Hiện cảnh báo, vẫn cài được |

## H. Thủ công — hiệu năng và ổn định

- Chạy 8 giờ không tắt: không tăng bộ nhớ đáng kể (Task Manager), không treo.
- Đổi Wi-Fi giữa chừng: điều khiển từ điện thoại nối lại (hoặc ghi lại thời gian nối lại).
- Mở 10 video liên tiếp rồi ← nhiều lần: không lỗi.

## I. Ghi nhận lỗi

Khi có lỗi, ghi: số mục (ví dụ F7), các bước, kết quả thực tế, ảnh hoặc log (`%LOCALAPPDATA%` nếu có), và phiên bản (0.12.0).

## J. Chạm và lướt hai ngón từ điện thoại (touchpad)

Widget nằm sau lớp icon desktop nên chạm và lướt được đưa thẳng vào nút WPF / WebView2 khi dưới con trỏ là desktop.

| # | Thao tác | Mong đợi |
|---|---|---|
| J1 | Di con trỏ lên nút "TV" hoặc "YouTube" ở thanh dưới rồi chạm 1 ngón | Nút được bấm |
| J2 | Ở YouTube, di con trỏ lên một video rồi chạm | Mở video |
| J3 | Ở YouTube, lướt 2 ngón lên/xuống | Trang cuộn theo |
| J4 | Ở TV, di con trỏ lên một đài / kênh rồi chạm | Vào đài / kênh |
| J5 | Ở TV (danh sách kênh dài), lướt 2 ngón | Danh sách cuộn |
| J6 | Mở một app khác đè lên widget, chạm và lướt trong app đó | Vẫn hoạt động như chuột thường |

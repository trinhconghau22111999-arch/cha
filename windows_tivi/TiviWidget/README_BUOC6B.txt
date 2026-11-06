Tivi Desktop Widget — bổ sung: giọng nói + chế độ YouTube đầy đủ

GIỌNG NÓI (Voice/)
- Ctrl+M (hoặc nút mic của YouTube) bắt đầu nghe bằng System.Speech, tiếng Việt.
  Cần: Settings > Time & language > Speech > cài gói tiếng Việt.
- "mở TV" -> sang TV, hiện trang chọn đài. "mở TV kênh HTV9" -> sang TV và phát HTV9.
- "mở YouTube" -> trang chủ YouTube. "mở YouTube <từ khoá>" -> tìm kiếm.
- Nói tên kênh/đài khi đang ở TV -> phát kênh đó (ChannelMatcher, port playByName).
- Nói từ khoá khi đang ở YouTube -> tìm kiếm.

CHẾ ĐỘ YOUTUBE (Web/)
- WebScripts.cs: JS trích NGUYÊN VĂN từ Scripts.kt (chặn quảng cáo, ô chọn video, ẩn Shorts,
  không tắt tiếng, nút mic). Không sửa nội dung.
- Chặn quảng cáo ở tầng mạng (AdBlocker) và chặn sớm từ document start.
- Chỉ cho phép trang YouTube (và accounts/consent của Google); trang khác hiện thông báo.
- Remote ▲▼◀▶ OK: qua __wbVert/__wbNav/__wbOk; nếu trang không có ô chọn thì gửi phím thô.
- Esc/Backspace: quay lại theo lịch sử, hết lịch sử thì về trang chủ.

GIỚI HẠN CẦN KIỂM THỬ
- Phím: khi WebView2 đang có focus, phím có thể được trang nhận trước cửa sổ.
- Lịch sử quay lại dùng CanGoBack của WebView2, không phải danh sách URL riêng như Android.
- Lệnh "mở YouTube <tên bài>" chỉ tìm kiếm, chưa tự mở video đầu tiên như Android.
- Giọng nói phụ thuộc gói ngôn ngữ tiếng Việt đã cài trên Windows.
- Chưa biên dịch: cần dotnet publish trên máy Windows.

Build: dotnet restore && dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true

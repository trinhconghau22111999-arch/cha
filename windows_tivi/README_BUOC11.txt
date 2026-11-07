Tivi Desktop Widget — độ sáng thật của laptop

- brightness: gọi WMI (root\WMI, WmiMonitorBrightnessMethods.WmiSetBrightness) để đổi đèn nền thật
  của màn hình built-in, giống thanh độ sáng trong Cài đặt Windows.
- Nếu WMI không có (PC để bàn, màn hình ngoài HDMI/DP): dự phòng làm tối nội dung trang bằng CSS filter như cũ.
- Thêm gói NuGet System.Management 8.0.0.
- Chưa biên dịch và chưa chạy trên Windows. Kiểm thử mục F16 trong TEST_PLAN.md trên laptop thật.

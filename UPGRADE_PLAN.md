# Kế Hoạch Nâng Cấp Toàn Diện Dự Án RC Car (Front-end & Back-end)

Dự án điều khiển xe RC qua MQTT đang ở giai đoạn hoạt động tốt với các chức năng cơ bản. Tuy nhiên, để đưa sản phẩm lên mức độ thương mại hoặc tăng trải nghiệm người dùng, dưới đây là kế hoạch nâng cấp toàn diện được chia thành các giai đoạn (Phase) cụ thể.

## Phase 1: Hoàn Thiện Trải Nghiệm Người Dùng (UX/UI) & Ổn Định Core Logic
**Mục tiêu:** Củng cố các tính năng hiện tại, làm mượt các thao tác và loại bỏ các lỗi vặt.

**Front-end (Android/Mobile App):**
1. **Tinh chỉnh UI/UX Vitals:** 
   - Đồng bộ màu sắc (Design System), thay thế các nút bấm cơ bản bằng các Icon có thiết kế hiện đại.
   - Thêm Haptic Feedback (rung nhẹ thiết bị) khi bấm các nút tương tác (Ga, Phanh, Chuyển số) để tạo phản hồi chân thực.
2. **Settings Bảng Điều Khiển Nâng Cao:**
   - Cho phép người dùng tùy chỉnh độc lập tỉ lệ gia tốc (Acceleration rate) và độ nhạy tay lái (Steering sensitivity).
   - Tùy chỉnh Layout linh hoạt (cho người thuận tay trái/phải).
3. **Cơ chế Auto-Reconnect:** Hệ thống tự động kết nối lại MQTT Broker khi rớt mạng một cách rảnh tay.

**Back-end / Firmware (ESP32):**
1. **Tối ưu vòng lặp xử lý tín hiệu MQTT:** Đảm bảo không nghẽn cổ chai khi xử lý tín hiệu gửi quá nhanh.
2. **Failsafe tự động:** Tự động cắt nguồn/phanh động cơ nếu mất kết nối MQTT hoặc không nhận được tín hiệu sau 500ms để đảm bảo xe không chạy loạn.

---

## Phase 2: Chế Độ 2 Chiều (Telemetry) & Khả Năng Quan Sát
**Mục tiêu:** Biến chiếc xe từ thiết bị nhận lệnh mù thành nền tảng gửi dữ liệu về thời gian thực cho người lái.

**Front-end (Android App):**
1. **Bảng Dashboard Báo Cáo:** 
   - Hiển thị % Pin thực tế, thay vì 100% tĩnh.
   - Bổ sung thanh trạng thái báo lỗi / cảnh báo vật cản.
2. **Camera Góc Nhìn Thứ Nhất (FPV):** Thêm luồng Stream video dưới nền app để tài xế có thể nhìn đường trên điện thoại.

**Back-end / Firmware (ESP32 & Phần cứng):**
1. Đọc và truyền dữ liệu ADC từ Pin lên kênh `my_rc_car/telemetry`.
2. Tích hợp Cảm Biến Siêu Âm/Hồng Ngoại để phát hiện sắp va chạm và tự phanh gấp.
3. Tích hợp ESP32-CAM để Stream video trực tiếp bằng kỹ thuật tối ưu hóa đường truyền cục bộ (RTSP/MJPEG local).

---

## Phase 3: Nền Tảng Cloud Server Chuyên Nghiệp & Bảo Mật
**Mục tiêu:** Rời khỏi Broker trung gian công cộng miễn phí, tạo hệ thống có thể mở rộng (Scale).

**Front-end & Server Back-end:**
1. **Quản lý Tài Khoản (Node.js/Go):** Chuyển sang mô hình đăng nhập để lưu trữ cấu hình Profile tùy biến của riêng từng người dùng.
2. **Bảo Mật Kênh Giao Tiếp:** Sử dụng Private MQTT Broker (EMQX, Mosquitto SSL) hoặc WebSocket Auth. Tránh để người khác can thiệp bằng cách nhập chung Topic name.
3. **Theo Lõi Đội Xe (Fleet Management):** Dễ dàng cho phép hiển thị và điều khiển nhiều xe qua một bảng Command Center nếu người dùng muốn làm nhà quản lý đội xe drone tự hành.

**Firmware:**
1. OTA Update: Cho phép Server có thể update phần mềm cho ESP32 của xe từ xa một cách tự động khi kết nối wifi.

---

## Phase 4: Các Tính Năng Trí Tuệ Tự Động (AI Automation)
**Mục tiêu:** Mang AI vào việc điều khiển xe.

- Tính năng **Line Tracking** hoặc **Bám Đối Tượng**: Xe có thể kết hợp OpenCV và PID từ Server (nhận video feed) để tự di chuyển tới đích hoặc bám theo chủ nhân.
- Hỗ Trợ Đỗ Xe/Tự Lùi Chuồng.
- Auto-Return RTH (Return To Home) nội suy lộ trình khi đứt cáp/mất sóng để quay về chỗ xuất phát.

# 🏎️ Báo Cáo Phân Tích Kỹ Thuật: Dự Án ESP32 RC Car

## 1. TỔNG QUAN PROJECT
*   **Mục đích**: Xây dựng hệ thống điều khiển từ xa cho xe mô hình (RC Car) thông qua kết nối Internet (WiFi/4G) thay vì sóng tần số vô tuyến (RF) truyền thống.
*   **Bài toán giải quyết**: Xoá bỏ giới hạn khoảng cách vật lý của sóng RF, cho phép điều khiển phương tiện từ bất cứ đâu miễn có kết nối mạng. Cho phép tích hợp hệ thống camera linh hoạt.
*   **Đối tượng sử dụng**: Người chơi xe RC mô hình, ứng dụng giáo dục STEM/Robot, hoặc làm nền tảng cho robot vận chuyển/thám hiểm không gian hẹp.
*   **Công nghệ sử dụng nền tảng**:
    *   **Mobile App (Client)**: Lập trình Native Android bằng Kotlin, sử dụng View System XML, thư viện giao tiếp Paho MQTT (Eclipse), đồ hoạ cơ bản.
    *   **Firmware (Xe)**: Ngôn ngữ C++ dựa trên Arduino Framework chạy cho chip ESP32. Quản lý thư viện MQTT (`PubSubClient`) và bộ cài đặt sóng ảo (WiFi SmartConfig).
    *   **Hardware / Network**: Chuẩn giao thức MQTT gửi/nhận nhẹ, vi điều khiển ESP32, IC cầu H điều khiển motor L298N.

---

## 2. KIẾN TRÚC HỆ THỐNG
*   **Mô hình kiến trúc tổng thể**: Client-Server gián tiếp theo mô hình **Pub/Sub (Publish-Subscribe)** thông qua Broker trung gian.
*   **Kiến trúc Phần mềm App**:
    *   Đang áp dụng kiến trúc nguyên bản (Monolithic Component với "*God Activity*"). Cả UI Logic và Business/Physics Logic đều gom chung ở `MainActivity`.
*   **Cách tổ chức thư mục project**:
    *   `/app`: Nguyên bộ mã nguồn Android chuẩn (Gradle).
    *   `/firmware`: Mã nguồn C++ cho PlatformIO / Arduino IDE biên dịch nạp cho ESP32.
*   **Luồng dữ liệu chính (Data Flow)**:
    1.  **Nhập liệu**: Người dùng thao tác Controller ảo (Joystick hoặc phím bấm).
    2.  **Tính toán**: Khối Physics engine trên điện thoại tính toán quán tính, ra được `Throttle` (Gia tốc) và `Steering` (Góc đánh lái).
    3.  **Đóng gói mạng**: Bộ vi phân (Differential Drive) trộn kênh trái/phải, nén thành chuỗi Text *VD: `100,-80`* và Publish lên MQTT Broker (tần số tối đa 50Hz).
    4.  **Thực thi**: ESP32 subscribe Topic, nhận chuỗi Text -> Cắt chuỗi -> Dịch sang xung PWM 0-255 cấp cho Module L298N để quay bánh.

---

## 3. PHÂN TÍCH CHỨC NĂNG
Đánh giá độ quan trọng:
*   **[Core] Trộn và điều hướng (Mixing)**: Trộn tín hiệu tiến/lùi (Throttle) và rẽ (Steering) truyền dẫn thành tốc độ độc lập cho bánh trái/phải.
*   **[Core] Cầu nối viễn thông**: Firmware và App tự động duy trì nhịp nối mạng bằng MQTT Ping. ESP32 có luồng khôi phục kết nối (Auto Reconnect) đáng tin cậy.
*   **[Phụ trợ] Mạng lưới WiFi**: Nếu bật nguồn nhưng không có sóng WiFi đã lưu, ESP32 sẽ gọi `SmartConfig` để đợi nhập pass wifi qua sóng radio điện thoại (Rất thân thiện trải nghiệm mở hộp).
*   **[Phụ trợ] Add-ons**: Mở luồng Camera Stream (mjpeg viewer), còi báo (Buzzer), đo giới hạn hành trình (Speed Limit).

---

## 4. CHI TIẾT PHÂN TÍCH CODE TẦNG SÂU

### A. Phía Android App (`MainActivity.kt`)
*   **Hàm `updatePhysics()`**: Trái tim của ứng dụng điều khiển. Điểm thông minh trong code này là ứng dụng thuật toán **Smooth Interpolation** để giả lập vật lý. Tha hồ ấn hết ga thì tốc độ không tăng nhảy vọt `0 -> 100`, mà sẽ là lấy `(targetThrottle - currentThrottle) * ACCEL_RATE`. Việc giả lập độ trượt (Friction) và hãm phanh (Brake) mang lại cảm giác lái đầm chắc.
*   **Bộ Mixing `effSteering`**: Kết hợp nội suy giảm tỷ lệ lái khi đi ở tốc độ cao: `(1.0f - (speedFactor * 0.4f))`. Kỹ năng này rất phổ biến trên drone/máy bay rc gọi là "Expo/Dual rate".

### B. Phía ESP32 (`main.cpp`)
*   **Hàm `driveMotor()`**: Trừu tượng hóa hoàn toàn khối module phần cứng. Nhận Input đẹp (-100 đến 100), tự phân rã ra lệnh logic chân kĩ thuật số `IN1, IN2` (chiều quay) và `ledcWrite` API của ESP32 (cấp điện PWM).
*   **Vòng lặp `loop()` và `reconnect()`**: Thiết kế Block/Non-Blocking tốt. Kiểm tra thời gian `millis() - lastReconnectAttempt > 5000` thay vì dùng hàm `delay()` chẹn luồng.

---

## 5. ĐÁNH GIÁ GIAO DIỆN (UI/UX)
*   **Tổ chức View**: 
    1. Trải nghiệm Fullscreen Immersive đắm chìm tốt cho trải nghiệm Gaming Controller.
    2. Sử dụng linh động `Visibility` (GONE/VISIBLE) dựa trên thiết lập (JoyStick / Buttons).
*   **Trải nghiệm thao tác (UX)**: Đã giả lập tốt cảm ứng Haptic qua Âm thanh (`playClickSound`) và Cấu trúc hình khối xoay 3D `view.animate().rotationX()`. Nó khắc phục được khuyết điểm phím ảo bị vô hồn trên mặt kính cảm ứng rỗng.

---

## 6. 🔥 ALERT: GÓC NHÌN SENIOR - REVIEW BUGS & ĐÁNH GIÁ

Một senior sẽ luôn nhìn thấy "nợ kỹ thuật" (Technical Debts) và các rủi ro hệ thống:

### 🔴 1. Rò rỉ bộ nhớ (Memory Leak) & Rớt khung hình UI
*   **Vị trí**: Trình vòng lặp vật lý `gameLoop` đang gọi trên Main UI Thread ở Android:
    ```kotlin
    private val handler = Handler(Looper.getMainLooper()) // Nguy hiểm!
    handler.postDelayed(this, 20L)
    ```
*   **Phân tích**: Việc thực hiện các phép logic nổi, phân mảnh và đóng mạng MQTT **mỗi 20 phần nghìn giây** đè lên chu kì vẽ của UI (16ms per frame). Khi máy rác hay nóng, Activity sẽ lag, thậm chí hệ điều hành báo lỗi ANR (App Not Responding).
*   **Khắc phục**: `updatePhysics()` PHẢI được đẩy xuống một `HandlerThread` chạy ngầm, hoặc dùng Kotlin Coroutines (`Dispatchers.Default`).

### 🔴 2. Lỗ hổng tràn bộ đệm (Stack Overflow) dẫn đến DoS ESP32
*   **Vị trí**: Trong hàm `mqttCallback` của `main.cpp`:
    ```cpp
    char message[length + 1]; // VLA - Variable Length Array
    ```
*   **Phân tích**: Code cấp phát mảng trên Stack dựa theo dung lượng gói tin tới (Length). Kẻ tấn công hoặc lỗi mạng nếu gửi một gói MQTT Text dài vài Kilobytes vào Topic đó, không gian Stack của FreeRTOS bên trong ESP32 sẽ bị tràn lập tức -> Dẫn tới Crash (Guru Meditation Error) hoặc cháy bộ nhớ CPU.
*   **Khắc phục Cấp thiết**:
    ```cpp
    char message[64];
    int len = length < 63 ? length : 63;
    memcpy(message, payload, len);
    message[len] = '\0';
    ```

### 🔴 3. Lỗ hổng Failsafe - Mất mạng xe sẽ mất lái
*   **Phân tích**: Hiện tại ESP32 chỉ hành động nhận lệnh và đi. Nếu đang chạy đường thẳng tốc độ gắt, App Crash hoặc rớt mạng 4G. Lệnh "Dừng" (0,0) vĩnh viễn không truyền tới. Kết quả xe sẽ lao thẳng vào vật cản cho đến khi nổ máy/cháy mạch.
*   **Khắc phục**: Phải triển khai hệ thống **Heartbeat / Watchdog timer**. ESP32 cần track biến `lastReceivedPacketAt`. Trong hàm `loop()`, nếu quá 1.5 giây mà không nhận được gói MQTT nào mới -> Bắt buộc ép hàm `driveMotor(0,0)` vào phanh khẩn cấp.

### 🟢 Ưu điểm Cốt lõi
*   Sử dụng SmartConfig của ITs rất hữu ích cho người dùng thay vì hardcode thông tin Wifi.
*   Mã Android quản lý rất tốt tình trạng mạng, băng thông chỉ được Consume (Publish) nếu output đầu ra logic **Thực sự khác biệt** so với khung hình trước (`if (outL != lastPublishedLeft...)`).

---

## 7. ĐỀ XUẤT CẢI THIỆN VÀ TỐI ƯU HÓA (ROADMAP)

1.  **Chuyển đổi Clean Architecture App**: Giải phóng `MainActivity`. Bóc toàn bộ logic tính toán vật lý, SoundPool và MqttHandler vào một `DriveViewModel`. Sử dụng Android `LiveData` hoặc `StateFlow` để emit (phát) trạng thái % tốc độ lên màn hình thay vì gán TextView tay ngang. Tránh tình trạng xoay thiết bị sập App.
2.  **Ứng dụng bảo mật MQTT**: Đang sử dụng Broker công cộng mở ngách kết nối cực kì lỏng. Ai cũng scan được topic và chiếm xe. Cần tự dể cài Mosquitto Server kèm ACL Authenticated (User/Password).
3.  **Giao thức Serialized**: Dùng `sscanf()` để Parse chuỗi string "100,-50" tuy nhanh nhưng không có tầm vóc scale. Nếu tiếp tục làm các phiên bản đèn xi nhan, cảm biến tốc độ đổ về,... Nên sử dụng JSON (`ArduinoJson` library) hặc `Protobuf` siêu nhẹ đóng gói tín hiệu.

---

## 8. TÓM TẮT NGẮN GỌN DỰ ÁN
Dự án là nền tảng hệ sinh thái kẹp giữa một giao diện Remote Controller trên di động phân bổ tín hiệu chuyển động, và một bo mạch nhận SoC ESP32 giải mã mạng thành chuyển động cơ học. Lõi ứng dụng chạy trơn tru mượt mà với mô phỏng quán tính thông minh. Tuy nhiên dưới góc độ kỹ thuật chất lượng cao, kiến trúc cần phải tái cơ cấu ngay lập tức để né khỏi lỗ hổng xử lý nghẽn CPU, lỗi tràn bộ nhớ vi điều khiển và sự thiếu hụt cơ chế phanh an toàn mạng để thật sự trở thành một sản phẩm IoT cấp doanh nghiệp.

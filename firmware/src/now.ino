#include <WiFi.h>
#include <PubSubClient.h>

// ================= CẤU HÌNH WIFI & MQTT (GIỮ NGUYÊN) =================
const char* ssid = "P108A";
const char* password = "88888888";
const char* mqtt_server = "192.168.1.20";
const char* mqtt_topic = "car/control"; 

WiFiClient espClient;
PubSubClient client(espClient);

// ================= CẤU HÌNH CHÂN MOTOR (GIỮ NGUYÊN) =================
const int enA = 14; const int in1 = 27; const int in2 = 26; 
const int enB = 13; const int in3 = 25; const int in4 = 33;

// ================= CẤU HÌNH CHÂN LED & BUZZER MỚI =================
const int pinFrontL = 16;  // Đèn trước trái (Vàng)
const int pinFrontR = 17;  // Đèn trước phải (Vàng)
const int pinRearL = 18;   // Đèn sau trái (Đỏ)
const int pinRearR = 19;   // Đèn sau phải (Đỏ)
const int pinLedLeft = 2;   // Đèn xi-nhan trái (Vàng - Led Onboard)
const int pinLedRight = 4;  // Đèn xi-nhan phải (Vàng)
const int pinBuzzer = 32;   // Còi chủ động

// ================= CẤU HÌNH PWM (LEDC) CHO ESP32 (GIỮ NGUYÊN) =================
const int freq = 2000;         // Tần số băm xung 2kHz
const int resolution = 8;      // Độ phân giải 8-bit (0 - 255)

// ================= BIẾN TRẠNG THÁI HỆ THỐNG =================
int currentLeft = 0;
int currentRight = 0;

// Đèn & Còi
bool manualLightOn = false;    // Bật tắt đèn trước thủ công qua "LIGHT:1"
bool manualRearOn = false;     // Bật tắt đèn sau thủ công qua "REV:1"
bool hazardActive = false;     // Đèn cảnh báo nhấp nháy 4 đèn vàng

// Bộ nhấp nháy phi chặn (Non-blocking Blink Timers)
unsigned long lastHazardBlinkMs = 0;
bool hazardBlinkState = false;

unsigned long lastBlinkMs = 0;
bool blinkState = false;
int turnSignal = 0;            // 0=off, -1=left, 1=right
const unsigned long blinkInterval = 400; // Chu kỳ nhấp nháy xi-nhan 400ms

// Bộ tắt còi tự động
unsigned long buzzerEndMs = 0;
bool buzzerActive = false;

// Khai báo trước các hàm helper
void controlMotors(int left, int right);
void updateLEDs(int left, int right);
void setup_wifi();
void callback(char* topic, byte* payload, unsigned int length);
void reconnect();

void setup() {
  Serial.begin(115200);
  
  // Thiết lập chân Motor
  pinMode(in1, OUTPUT); 
  pinMode(in2, OUTPUT);
  pinMode(in3, OUTPUT); 
  pinMode(in4, OUTPUT);
  pinMode(enA, OUTPUT);
  pinMode(enB, OUTPUT);

  // Thiết lập chân LED & Buzzer
  pinMode(pinFrontL, OUTPUT); digitalWrite(pinFrontL, LOW);
  pinMode(pinFrontR, OUTPUT); digitalWrite(pinFrontR, LOW);
  pinMode(pinRearL, OUTPUT);  digitalWrite(pinRearL,  LOW);
  pinMode(pinRearR, OUTPUT);  digitalWrite(pinRearR,  LOW);
  pinMode(pinLedLeft, OUTPUT); digitalWrite(pinLedLeft, LOW);
  pinMode(pinLedRight, OUTPUT); digitalWrite(pinLedRight, LOW);
  pinMode(pinBuzzer, OUTPUT);  digitalWrite(pinBuzzer,  LOW);

  // ledcAttach(Chân_Pin, Tần_số, Độ_phân_giải)
  ledcAttach(enA, freq, resolution);
  ledcAttach(enB, freq, resolution);
  
  controlMotors(0, 0);
  
  setup_wifi();
  client.setServer(mqtt_server, 1883);
  client.setCallback(callback);

  // Còi kêu báo hiệu khởi động thành công
  digitalWrite(pinBuzzer, HIGH); delay(150); digitalWrite(pinBuzzer, LOW);
}

void setup_wifi() {
  delay(10);
  Serial.println("\nKet noi WiFi...");
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500); Serial.print(".");
  }
  Serial.println("\nWiFi connected!");
}

// Hàm xử lý dữ liệu khi nhận được tin nhắn từ MQTT
void callback(char* topic, byte* payload, unsigned int length) {
  String message = "";
  for (int i = 0; i < length; i++) {
    message += (char)payload[i];
  }
  
  Serial.print("Lenh nhan duoc: ");
  Serial.println(message);

  // 1. XỬ LÝ LỆNH ĐỘNG CƠ HỖ TRỢ ĐỊNH DẠNG "CMD:left,right" MỚI
  if (message.startsWith("CMD:")) {
    String values = message.substring(4);
    int commaIndex = values.indexOf(',');
    if (commaIndex > 0) {
      int leftVal = values.substring(0, commaIndex).toInt();
      int rightVal = values.substring(commaIndex + 1).toInt();
      
      currentLeft = leftVal;
      currentRight = rightVal;
      controlMotors(leftVal, rightVal);
    }
  }
  // 2. XỬ LÝ LỆNH BẬT TẮT ĐÈN TRƯỚC "LIGHT:1" / "LIGHT:0"
  else if (message.startsWith("LIGHT:")) {
    manualLightOn = (message.substring(6).toInt() == 1);
    controlMotors(currentLeft, currentRight); // Cập nhật lại trạng thái đèn
  }
  // 3. XỬ LÝ LỆNH SỐ LÙI "REV:1" / "REV:0"
  else if (message.startsWith("REV:")) {
    manualRearOn = (message.substring(4).toInt() == 1);
    controlMotors(currentLeft, currentRight); // Cập nhật lại trạng thái đèn
  }
  // 4. XỬ LÝ LỆNH CÒI "BUZ:1"
  else if (message.startsWith("BUZ:")) {
    int val = message.substring(4).toInt();
    if (val == 1) {
      buzzerActive = true;
      buzzerEndMs = millis() + 400; // Tự động tắt còi sau 400ms
      digitalWrite(pinBuzzer, HIGH);
    } else {
      buzzerActive = false;
      digitalWrite(pinBuzzer, LOW);
    }
  }
  // 5. XỬ LÝ LỆNH CẢNH BÁO HAZARD "HAZARD:1" / "HAZARD:0"
  else if (message.startsWith("HAZARD:")) {
    hazardActive = (message.substring(7).toInt() == 1);
    if (!hazardActive) {
      // Khôi phục trạng thái đèn chỉ hướng bình thường khi tắt Hazard
      controlMotors(currentLeft, currentRight);
      digitalWrite(pinLedLeft, LOW);
      digitalWrite(pinLedRight, LOW);
    }
  }
  // 6. TƯƠNG THÍCH NGƯỢC: Parse chuỗi dạng cũ "left,right" (Ví dụ: "200,-150")
  else {
    int commaIndex = message.indexOf(',');
    if (commaIndex > 0 && (message[0] == '-' || isdigit(message[0]))) {
      int leftVal = message.substring(0, commaIndex).toInt();
      int rightVal = message.substring(commaIndex + 1).toInt();
      
      currentLeft = leftVal;
      currentRight = rightVal;
      controlMotors(leftVal, rightVal);
    }
  }
}

void reconnect() {
  while (!client.connected()) {
    Serial.print("Dang ket noi MQTT...");
    if (client.connect("ESP32_Robot_Client")) {
      Serial.println("Da ket noi!");
      client.subscribe(mqtt_topic);
    } else {
      Serial.print("Loi: "); Serial.print(client.state());
      delay(5000);
    }
  }
}

void loop() {
  if (!client.connected()) reconnect();
  client.loop();

  unsigned long nowMs = millis();

  // 1. TỰ ĐỘNG TẮT CÒI KHI HẾT HẠN
  if (buzzerActive && nowMs >= buzzerEndMs) {
    buzzerActive = false;
    digitalWrite(pinBuzzer, LOW);
  }

  // 2. NHẤP NHÁY ĐỒNG BỘ 4 ĐÈN VÀNG (HAZARD) HOẶC ĐÈN XI-NHAN CHỈ HƯỚNG
  if (hazardActive) {
    if (nowMs - lastHazardBlinkMs >= 300) { // Nhấp nháy chu kỳ 300ms theo yêu cầu
      lastHazardBlinkMs = nowMs;
      hazardBlinkState = !hazardBlinkState;
      int state = hazardBlinkState ? HIGH : LOW;
      
      digitalWrite(pinFrontL, state);
      digitalWrite(pinFrontR, state);
      digitalWrite(pinLedLeft, state);
      digitalWrite(pinLedRight, state);
    }
  } else {
    // Nhấp nháy đèn xi-nhan thông thường khi rẽ
    if (turnSignal != 0) {
      if (nowMs - lastBlinkMs >= blinkInterval) {
        lastBlinkMs = nowMs;
        blinkState = !blinkState;
        
        if (turnSignal == -1) { // Rẽ trái
          digitalWrite(pinLedLeft, blinkState ? HIGH : LOW);
          digitalWrite(pinLedRight, LOW);
        } else if (turnSignal == 1) { // Rẽ phải
          digitalWrite(pinLedRight, blinkState ? HIGH : LOW);
          digitalWrite(pinLedLeft, LOW);
        }
      }
    } else {
      // Tắt xi-nhan khi đi thẳng hoặc dừng xe
      digitalWrite(pinLedLeft, LOW);
      digitalWrite(pinLedRight, LOW);
    }
  }
}

// ================= LOGIC ĐIỀU KHIỂN ĐỘNG CƠ & ĐÈN =================

void controlMotors(int left, int right) {
  // Điều khiển Motor TRÁI (Kênh A)
  if (left > 0) {
    ledcWrite(enA, abs(left));
    digitalWrite(in1, HIGH); 
    digitalWrite(in2, LOW);
  } else if (left < 0) {
    ledcWrite(enA, abs(left));
    digitalWrite(in1, LOW); 
    digitalWrite(in2, HIGH);
  } else {
    ledcWrite(enA, 0); 
    digitalWrite(in1, LOW);
    digitalWrite(in2, LOW);
  }

  // Điều khiển Motor PHẢI (Kênh B)
  if (right > 0) {
    ledcWrite(enB, abs(right));
    digitalWrite(in3, HIGH); 
    digitalWrite(in4, LOW);
  } else if (right < 0) {
    ledcWrite(enB, abs(right));
    digitalWrite(in3, LOW); 
    digitalWrite(in4, HIGH);
  } else {
    ledcWrite(enB, 0); 
    digitalWrite(in3, LOW); 
    digitalWrite(in4, LOW);
  }

  // CẬP NHẬT TRẠNG THÁI HỆ THỐNG ĐÈN CHỈ HƯỚNG/CHUYỂN ĐỘNG
  updateLEDs(left, right);
}

void updateLEDs(int left, int right) {
  // Nếu Hazard đang bật, bỏ qua cập nhật đèn thông thường (Hazard có mức ưu tiên tuyệt đối)
  if (hazardActive) return;

  // 1. Logic Đèn Trước: Sáng khi cả 2 bên cùng tiến (left > 0 && right > 0) HOẶC khi bật thủ công (manualLightOn)
  if ((left > 0 && right > 0) || manualLightOn) {
    digitalWrite(pinFrontL, HIGH);
    digitalWrite(pinFrontR, HIGH);
  } else {
    digitalWrite(pinFrontL, LOW);
    digitalWrite(pinFrontR, LOW);
  }

  // 2. Logic Đèn Sau: Sáng khi lùi (left < 0 && right < 0) HOẶC khi vào số lùi (manualRearOn)
  if ((left < 0 && right < 0) || manualRearOn) {
    digitalWrite(pinRearL, HIGH);
    digitalWrite(pinRearR, HIGH);
  } else {
    digitalWrite(pinRearL, LOW);
    digitalWrite(pinRearR, LOW);
  }

  // 3. Logic Đèn Xi-nhan rẽ:
  // Xe rẽ trái khi bánh phải quay nhanh hơn bánh trái
  if (right > left && right != 0) {
    turnSignal = -1;
  } 
  // Xe rẽ phải khi bánh trái quay nhanh hơn bánh phải
  else if (left > right && left != 0) {
    turnSignal = 1;
  } 
  // Đi thẳng hoặc dừng xe
  else {
    turnSignal = 0;
  }
}

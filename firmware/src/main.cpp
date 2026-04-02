#include <Arduino.h>
#include <WiFi.h>
#include <PubSubClient.h>

const char* ssid = "YOUR_WIFI_SSID";
const char* password = "YOUR_WIFI_PASSWORD";
const char* mqtt_server = "broker.hivemq.com";
const char* mqtt_topic = "my_rc_car/control";

WiFiClient espClient;
PubSubClient mqttClient(espClient);

// Motor Pins (L298N)
#define IN1 25
#define IN2 26
#define ENA 27

#define IN3 14
#define IN4 12
#define ENB 13

// PWM Configuration for ESP32
const int freq = 5000;
const int leftChannel = 0;
const int rightChannel = 1;
const int resolution = 8; // 0..255

unsigned long lastReconnectAttempt = 0;

void driveMotor(int left_speed, int right_speed) {
  int left_pwm = map(abs(left_speed), 0, 100, 0, 255);
  int right_pwm = map(abs(right_speed), 0, 100, 0, 255);

  // Left Motor
  if (left_speed > 0) {
    digitalWrite(IN1, HIGH);
    digitalWrite(IN2, LOW);
  } else if (left_speed < 0) {
    digitalWrite(IN1, LOW);
    digitalWrite(IN2, HIGH);
  } else {
    digitalWrite(IN1, LOW);
    digitalWrite(IN2, LOW);
    left_pwm = 0;
  }

  // Right Motor
  if (right_speed > 0) {
    digitalWrite(IN3, HIGH);
    digitalWrite(IN4, LOW);
  } else if (right_speed < 0) {
    digitalWrite(IN3, LOW);
    digitalWrite(IN4, HIGH);
  } else {
    digitalWrite(IN3, LOW);
    digitalWrite(IN4, LOW);
    right_pwm = 0;
  }

  // Write PWM
  ledcWrite(leftChannel, left_pwm);
  ledcWrite(rightChannel, right_pwm);
}

void mqttCallback(char* topic, byte* payload, unsigned int length) {
  char message[length + 1];
  for (int i = 0; i < length; i++) {
    message[i] = (char)payload[i];
  }
  message[length] = '\0';

  Serial.printf("Message arrived on topic %s: %s\n", topic, message);

  int left_val = 0;
  int right_val = 0;
  
  if (sscanf(message, "%d,%d", &left_val, &right_val) == 2) {
    driveMotor(left_val, right_val);
  } else {
    Serial.println("Error parsing payload! Use format 'Left,Right' EX: 100,-50");
  }
}

void reconnect() {
  if (WiFi.status() != WL_CONNECTED) return;
  
  if (!mqttClient.connected()) {
    Serial.print("Attempting MQTT connection...");
    
    String clientId = "ESP32Client-";
    clientId += String(random(0xffff), HEX);
    
    if (mqttClient.connect(clientId.c_str())) {
      Serial.println("connected");
      mqttClient.subscribe(mqtt_topic);
      driveMotor(0, 0); 
    } else {
      Serial.print("failed, rc=");
      Serial.println(mqttClient.state());
    }
  }
}

void setup() {
  Serial.begin(115200);

  // Initialize motor pins
  pinMode(IN1, OUTPUT);
  pinMode(IN2, OUTPUT);
  pinMode(IN3, OUTPUT);
  pinMode(IN4, OUTPUT);

  // Initialize PWM channels
  ledcSetup(leftChannel, freq, resolution);
  ledcSetup(rightChannel, freq, resolution);
  ledcAttachPin(ENA, leftChannel);
  ledcAttachPin(ENB, rightChannel);
  
  driveMotor(0, 0);

  // Setup WiFi
  WiFi.begin(ssid, password);
  Serial.print("\nConnecting to WiFi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500); 
    Serial.print(".");
  }
  Serial.printf("\nWiFi Connected! IP: %s\n", WiFi.localIP().toString().c_str());

  mqttClient.setServer(mqtt_server, 1883);
  mqttClient.setCallback(mqttCallback);
}

void loop() {
  if (!mqttClient.connected()) {
    unsigned long now = millis();
    if (now - lastReconnectAttempt > 5000) {
      lastReconnectAttempt = now;
      reconnect();
    }
  } else {
    mqttClient.loop();
  }
}

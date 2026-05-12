/**
 * =============================================================
 * RC Car ESP32 Firmware - Full Featured
 * =============================================================
 * Supports:
 *  - Dual transport: MQTT (via router) + UDP (direct via AP)
 *  - Motor control via L298N (differential drive)
 *  - Headlights, Brake/Tail lights, Left/Right turn signals
 *  - Active buzzer (horn)
 *  - Failsafe watchdog: auto-stop if no command for 1500ms
 *  - WiFi credentials stored in NVS (Preferences)
 *  - Configure WiFi via command: WIFI:ssid,pass
 * 
 * Command Protocol (same for MQTT and UDP):
 *  Single command:  CMD:ARG
 *  Multi command:   CMD1:ARG1|CMD2:ARG2   (pipe separated)
 *
 *  MOTOR:left,right  → Drive motors. Range -100..100. e.g. MOTOR:80,-60
 *  BUZ:1             → Honk (auto-off after 300ms)
 *  LED:1 / LED:0     → Headlights ON/OFF
 *  TRN:L             → Left turn signal ON
 *  TRN:R             → Right turn signal ON
 *  TRN:0             → Turn signal OFF
 *  STOP              → Emergency stop (motors off)
 *  WIFI:ssid,pass    → Save new WiFi credentials and reconnect
 *
 *  Legacy motor format:  "left,right"  e.g. "80,-60" (backward compat)
 *
 * WiFi AP (always on):
 *  SSID     : RCCar_AP
 *  Password : 12345678
 *  IP       : 192.168.4.1
 *  UDP Port : 4210
 * =============================================================
 */

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUDP.h>
#include <PubSubClient.h>
#include <Preferences.h>

// =============================================================
// PIN DEFINITIONS
// =============================================================

// --- L298N Motor Driver ---
#define PIN_IN1   25   // Left motor  - forward
#define PIN_IN2   26   // Left motor  - backward
#define PIN_ENA   27   // Left motor  - PWM enable
#define PIN_IN3   14   // Right motor - forward
#define PIN_IN4   19   // Right motor - backward  ⚠ GPIO19 (GPIO12 is strapping pin, avoid)
#define PIN_ENB   13   // Right motor - PWM enable

// --- Peripherals ---
#define PIN_BUZZER     32   // Active buzzer  (HIGH = ON)
#define PIN_LED_HEAD   33   // Headlights     (HIGH = ON)
#define PIN_LED_TAIL   15   // Brake / Tail   (HIGH = ON)
#define PIN_LED_LEFT    2   // Left turn LED  (HIGH = ON)
#define PIN_LED_RIGHT   4   // Right turn LED (HIGH = ON)

// =============================================================
// PWM CONFIG (LEDC)
// =============================================================
#define PWM_FREQ    5000
#define PWM_RES        8   // 8-bit → 0..255
#define PWM_CH_L       0   // LEDC channel for left  motor
#define PWM_CH_R       1   // LEDC channel for right motor

#if defined(ESP_ARDUINO_VERSION_MAJOR) && (ESP_ARDUINO_VERSION_MAJOR >= 3)
bool setupMotorPwm(uint8_t pin, uint8_t channel) {
    return ledcAttachChannel(pin, PWM_FREQ, PWM_RES, channel);
}

void writeMotorPwm(uint8_t pin, uint8_t duty) {
    ledcWrite(pin, duty);
}
#else
bool setupMotorPwm(uint8_t pin, uint8_t channel) {
    ledcSetup(channel, PWM_FREQ, PWM_RES);
    ledcAttachPin(pin, channel);
    return true;
}

void writeMotorPwm(uint8_t channel, uint8_t duty) {
    ledcWrite(channel, duty);
}
#endif

// =============================================================
// NETWORK CONFIG
// =============================================================
// Fixed WiFi AP (always broadcasts)
const char* AP_SSID     = "RCCar_AP";
const char* AP_PASS     = "12345678";
const int   UDP_PORT    = 4210;

// STA WiFi + MQTT (configurable, saved in NVS)
char staSsid[64]     = "";
char staPass[64]     = "";
char mqttServerAddr[64] = "broker.hivemq.com";
char mqttTopic[80]   = "my_rc_car/control";
int  mqttPort        = 1883;

// =============================================================
// OBJECTS
// =============================================================
WiFiUDP      udpServer;
WiFiClient   wifiClient;
PubSubClient mqttClient(wifiClient);
Preferences  prefs;

// =============================================================
// STATE
// =============================================================
unsigned long lastCmdMs      = 0;
const unsigned long FAILSAFE_MS = 1500;  // ms without command → stop

bool buzzerActive   = false;
unsigned long buzzerEndMs = 0;

bool headlightsOn   = false;
int  turnSignal     = 0;        // 0=off, -1=left, 1=right

unsigned long lastBlinkMs = 0;
bool          blinkState  = false;
const unsigned long BLINK_INTERVAL = 400;  // ms per blink half-cycle

unsigned long lastReconnectMs = 0;
const unsigned long RECONNECT_INTERVAL = 5000;

// =============================================================
// MOTOR CONTROL
// =============================================================
void driveMotor(int leftSpeed, int rightSpeed) {
    leftSpeed  = constrain(leftSpeed,  -100, 100);
    rightSpeed = constrain(rightSpeed, -100, 100);

    // --- Left motor ---
    if (leftSpeed > 0) {
        digitalWrite(PIN_IN1, HIGH);
        digitalWrite(PIN_IN2, LOW);
    } else if (leftSpeed < 0) {
        digitalWrite(PIN_IN1, LOW);
        digitalWrite(PIN_IN2, HIGH);
    } else {
        digitalWrite(PIN_IN1, LOW);
        digitalWrite(PIN_IN2, LOW);
    }
    writeMotorPwm(
#if defined(ESP_ARDUINO_VERSION_MAJOR) && (ESP_ARDUINO_VERSION_MAJOR >= 3)
        PIN_ENA,
#else
        PWM_CH_L,
#endif
        map(abs(leftSpeed), 0, 100, 0, 255)
    );

    // --- Right motor ---
    if (rightSpeed > 0) {
        digitalWrite(PIN_IN3, HIGH);
        digitalWrite(PIN_IN4, LOW);
    } else if (rightSpeed < 0) {
        digitalWrite(PIN_IN3, LOW);
        digitalWrite(PIN_IN4, HIGH);
    } else {
        digitalWrite(PIN_IN3, LOW);
        digitalWrite(PIN_IN4, LOW);
    }
    writeMotorPwm(
#if defined(ESP_ARDUINO_VERSION_MAJOR) && (ESP_ARDUINO_VERSION_MAJOR >= 3)
        PIN_ENB,
#else
        PWM_CH_R,
#endif
        map(abs(rightSpeed), 0, 100, 0, 255)
    );

    // Brake light: ON when both motors stopped
    bool isStopped = (leftSpeed == 0 && rightSpeed == 0);
    digitalWrite(PIN_LED_TAIL, isStopped ? HIGH : LOW);
}

// =============================================================
// COMMAND PARSER
// =============================================================
void handleSingleCmd(const char* cmd) {
    // --- Legacy format: "left,right" (pure numbers) ---
    if (cmd[0] == '-' || isdigit((unsigned char)cmd[0])) {
        int l = 0, r = 0;
        if (sscanf(cmd, "%d,%d", &l, &r) == 2) {
            driveMotor(l, r);
            lastCmdMs = millis();
        }
        return;
    }

    // --- MOTOR:left,right ---
    if (strncmp(cmd, "MOTOR:", 6) == 0) {
        int l = 0, r = 0;
        if (sscanf(cmd + 6, "%d,%d", &l, &r) == 2) {
            driveMotor(l, r);
            lastCmdMs = millis();
        }

    // --- BUZ:1 / BUZ:0 ---
    } else if (strncmp(cmd, "BUZ:", 4) == 0) {
        if (cmd[4] == '1') {
            buzzerActive = true;
            buzzerEndMs  = millis() + 400;  // 400ms honk
            digitalWrite(PIN_BUZZER, HIGH);
        } else {
            buzzerActive = false;
            digitalWrite(PIN_BUZZER, LOW);
        }
        lastCmdMs = millis();

    // --- LED:1 / LED:0  (headlights) ---
    } else if (strncmp(cmd, "LED:", 4) == 0) {
        headlightsOn = (cmd[4] == '1');
        digitalWrite(PIN_LED_HEAD, headlightsOn ? HIGH : LOW);
        lastCmdMs = millis();

    // --- TRN:L / TRN:R / TRN:0  (turn signals) ---
    } else if (strncmp(cmd, "TRN:", 4) == 0) {
        char dir = cmd[4];
        if      (dir == 'L') turnSignal = -1;
        else if (dir == 'R') turnSignal =  1;
        else {
            turnSignal = 0;
            digitalWrite(PIN_LED_LEFT,  LOW);
            digitalWrite(PIN_LED_RIGHT, LOW);
        }
        lastCmdMs = millis();

    // --- STOP (emergency) ---
    } else if (strcmp(cmd, "STOP") == 0) {
        driveMotor(0, 0);
        lastCmdMs = millis();

    // --- WIFI:ssid,pass (reconfigure STA WiFi, saved to NVS) ---
    } else if (strncmp(cmd, "WIFI:", 5) == 0) {
        const char* data  = cmd + 5;
        const char* comma = strchr(data, ',');
        if (comma != nullptr) {
            int ssidLen = (int)(comma - data);
            if (ssidLen > 0 && ssidLen < 63) {
                strncpy(staSsid, data, ssidLen);
                staSsid[ssidLen] = '\0';
                strncpy(staPass, comma + 1, 63);
                staPass[63] = '\0';

                // Save to NVS flash
                prefs.begin("rccar", false);
                prefs.putString("sta_ssid", staSsid);
                prefs.putString("sta_pass", staPass);
                prefs.end();

                Serial.printf("[WIFI] New credentials saved: '%s'\n", staSsid);
                WiFi.disconnect();
                delay(200);
                WiFi.begin(staSsid, staPass);
            }
        }
    } else {
        Serial.printf("[CMD] Unknown command: %s\n", cmd);
    }
}

/**
 * Process a full packet. Supports pipe-separated multi-commands.
 * Example: "MOTOR:80,-60|BUZ:1|TRN:L"
 */
void processPacket(const char* packet) {
    char buf[128];
    strncpy(buf, packet, 127);
    buf[127] = '\0';

    char* token = strtok(buf, "|");
    while (token != nullptr) {
        while (*token == ' ') token++;  // trim leading spaces
        if (*token != '\0') {
            handleSingleCmd(token);
        }
        token = strtok(nullptr, "|");
    }
}

// =============================================================
// MQTT CALLBACK  (VLA bug fixed: fixed-size 128-byte buffer)
// =============================================================
void mqttCallback(char* topic, byte* payload, unsigned int length) {
    char message[128];
    int safeLen = min((int)length, 127);
    memcpy(message, payload, safeLen);
    message[safeLen] = '\0';

    Serial.printf("[MQTT] ← %s : %s\n", topic, message);
    processPacket(message);
}

// =============================================================
// MQTT RECONNECT
// =============================================================
void reconnectMQTT() {
    if (WiFi.status() != WL_CONNECTED) return;
    if (mqttClient.connected()) return;

    // Unique client ID based on chip MAC
    char clientId[32];
    snprintf(clientId, sizeof(clientId), "ESP32RC_%08X", (uint32_t)(ESP.getEfuseMac() & 0xFFFFFFFF));

    Serial.printf("[MQTT] Connecting to %s as %s ...\n", mqttServerAddr, clientId);

    if (mqttClient.connect(clientId)) {
        Serial.println("[MQTT] Connected!");
        mqttClient.subscribe(mqttTopic);

        // Also listen on sub-topic for individual commands
        char subTopic[88];
        snprintf(subTopic, sizeof(subTopic), "%s/cmd", mqttTopic);
        mqttClient.subscribe(subTopic);

        driveMotor(0, 0);  // safe state on connect
    } else {
        Serial.printf("[MQTT] Failed, rc=%d\n", mqttClient.state());
    }
}

// =============================================================
// TURN SIGNAL BLINKER  (call every loop)
// =============================================================
void updateBlinker() {
    if (turnSignal == 0) return;

    unsigned long now = millis();
    if (now - lastBlinkMs >= BLINK_INTERVAL) {
        lastBlinkMs = now;
        blinkState  = !blinkState;

        if (turnSignal == -1) {   // Left
            digitalWrite(PIN_LED_LEFT,  blinkState ? HIGH : LOW);
            digitalWrite(PIN_LED_RIGHT, LOW);
        } else {                   // Right
            digitalWrite(PIN_LED_RIGHT, blinkState ? HIGH : LOW);
            digitalWrite(PIN_LED_LEFT,  LOW);
        }
    }
}

// =============================================================
// SETUP
// =============================================================
void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println("\n=============================");
    Serial.println("  RC Car ESP32 - Booting...");
    Serial.println("=============================");

    // --- Motor pins ---
    pinMode(PIN_IN1, OUTPUT); pinMode(PIN_IN2, OUTPUT);
    pinMode(PIN_IN3, OUTPUT); pinMode(PIN_IN4, OUTPUT);
    setupMotorPwm(PIN_ENA, PWM_CH_L);
    setupMotorPwm(PIN_ENB, PWM_CH_R);
    driveMotor(0, 0);
    Serial.println("[MOTOR] Initialized");

    // --- Peripheral pins ---
    pinMode(PIN_BUZZER,    OUTPUT); digitalWrite(PIN_BUZZER,    LOW);
    pinMode(PIN_LED_HEAD,  OUTPUT); digitalWrite(PIN_LED_HEAD,  LOW);
    pinMode(PIN_LED_TAIL,  OUTPUT); digitalWrite(PIN_LED_TAIL,  LOW);
    pinMode(PIN_LED_LEFT,  OUTPUT); digitalWrite(PIN_LED_LEFT,  LOW);
    pinMode(PIN_LED_RIGHT, OUTPUT); digitalWrite(PIN_LED_RIGHT, LOW);
    Serial.println("[PERIPH] Buzzer + LEDs initialized");

    // Startup blink: flash all LEDs once to confirm
    digitalWrite(PIN_LED_HEAD,  HIGH);
    digitalWrite(PIN_LED_TAIL,  HIGH);
    digitalWrite(PIN_LED_LEFT,  HIGH);
    digitalWrite(PIN_LED_RIGHT, HIGH);
    delay(300);
    digitalWrite(PIN_LED_HEAD,  LOW);
    digitalWrite(PIN_LED_TAIL,  LOW);
    digitalWrite(PIN_LED_LEFT,  LOW);
    digitalWrite(PIN_LED_RIGHT, LOW);

    // --- Load stored WiFi credentials from NVS ---
    prefs.begin("rccar", true);
    prefs.getString("sta_ssid", staSsid, sizeof(staSsid));
    prefs.getString("sta_pass", staPass, sizeof(staPass));
    prefs.getString("mqtt_srv", mqttServerAddr, sizeof(mqttServerAddr));
    prefs.getString("mqtt_top", mqttTopic, sizeof(mqttTopic));
    prefs.end();
    Serial.printf("[NVS] STA SSID: '%s'\n", staSsid);

    // --- Start WiFi: AP + STA simultaneously ---
    WiFi.mode(WIFI_AP_STA);

    // Fixed AP (always on)
    WiFi.softAP(AP_SSID, AP_PASS);
    Serial.printf("[AP] SSID: %s | IP: %s | UDP port: %d\n",
                  AP_SSID, WiFi.softAPIP().toString().c_str(), UDP_PORT);

    // STA: connect to router if credentials saved
    if (strlen(staSsid) > 0) {
        WiFi.begin(staSsid, staPass);
        Serial.printf("[STA] Connecting to '%s' ...\n", staSsid);
    } else {
        Serial.println("[STA] No WiFi saved. Send: WIFI:yourSSID,yourPass");
    }

    // --- UDP server ---
    udpServer.begin(UDP_PORT);
    Serial.printf("[UDP] Listening on port %d\n", UDP_PORT);

    // --- MQTT client ---
    mqttClient.setServer(mqttServerAddr, mqttPort);
    mqttClient.setCallback(mqttCallback);
    mqttClient.setKeepAlive(10);
    mqttClient.setSocketTimeout(5);

    // Init failsafe timer
    lastCmdMs = millis();

    // Startup beep
    digitalWrite(PIN_BUZZER, HIGH); delay(100); digitalWrite(PIN_BUZZER, LOW);

    Serial.println("[RC Car] Ready! Waiting for commands...\n");
}

// =============================================================
// LOOP
// =============================================================
void loop() {
    unsigned long now = millis();

    // --------------------------------------------------------
    // 1. UDP: receive and process packets from AP clients
    // --------------------------------------------------------
    int packetSize = udpServer.parsePacket();
    if (packetSize > 0) {
        char udpBuf[128];
        int len = udpServer.read(udpBuf, sizeof(udpBuf) - 1);
        if (len > 0) {
            udpBuf[len] = '\0';
            Serial.printf("[UDP] ← %s\n", udpBuf);
            processPacket(udpBuf);
            // Mirror response: ACK back to sender
            udpServer.beginPacket(udpServer.remoteIP(), udpServer.remotePort());
            udpServer.print("OK");
            udpServer.endPacket();
        }
    }

    // --------------------------------------------------------
    // 2. MQTT: maintain connection and pump messages
    // --------------------------------------------------------
    if (WiFi.status() == WL_CONNECTED) {
        if (mqttClient.connected()) {
            mqttClient.loop();
        } else if (now - lastReconnectMs > RECONNECT_INTERVAL) {
            lastReconnectMs = now;
            reconnectMQTT();
        }
    }

    // --------------------------------------------------------
    // 3. Failsafe watchdog: if no command received → STOP
    // --------------------------------------------------------
    if (now - lastCmdMs > FAILSAFE_MS) {
        driveMotor(0, 0);  // Emergency stop
    }

    // --------------------------------------------------------
    // 4. Buzzer auto-off timer
    // --------------------------------------------------------
    if (buzzerActive && now >= buzzerEndMs) {
        buzzerActive = false;
        digitalWrite(PIN_BUZZER, LOW);
    }

    // --------------------------------------------------------
    // 5. Turn signal blink update
    // --------------------------------------------------------
    updateBlinker();
}

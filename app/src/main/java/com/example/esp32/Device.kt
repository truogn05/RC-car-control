package com.example.esp32

import java.util.UUID

/**
 * Represents a saved RC car device profile.
 *
 * connectionType = "wifi"  → uses UDP direct to ESP32 AP (192.168.4.1:4210)
 * connectionType = "mqtt"  → uses MQTT broker
 */
data class Device(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val connectionType: String, // "wifi" or "mqtt"
    // --- MQTT fields ---
    val brokerUri: String = "tcp://broker.hivemq.com:1883",
    val mqttTopic: String = "my_rc_car/control",
    // --- WiFi Direct fields ---
    val apSsid: String = "RCCar_AP",       // SSID of the ESP32's Access Point
    val apPassword: String = "12345678",   // Password of the ESP32's Access Point
    // --- Shared ---
    val cameraUrl: String = ""             // e.g. "http://192.168.4.1:81/stream"
)

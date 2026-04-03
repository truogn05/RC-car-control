package com.example.esp32

import java.util.UUID

/**
 * Represents a saved RC car device profile.
 */
data class Device(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val connectionType: String, // "wifi" or "mqtt"
    val brokerUri: String = "tcp://broker.hivemq.com:1883",
    val mqttTopic: String = "my_rc_car/control",
    val cameraUrl: String = "", // e.g. "http://192.168.1.x:81/stream"
    val ssid: String = "" // WiFi SSID that device was configured for
)

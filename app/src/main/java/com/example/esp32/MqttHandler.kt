package com.example.esp32

import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MqttHandler(private val brokerUrl: String, private val clientId: String) {
    private var mqttClient: MqttAsyncClient? = null

    fun connect(onSuccess: () -> Unit, onFailure: (Throwable) -> Unit) {
        try {
            mqttClient = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = true
                connectionTimeout = 10
            }

            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    onSuccess()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    exception?.let { onFailure(it) }
                }
            })
        } catch (e: Exception) {
            onFailure(e)
        }
    }

    fun publish(topic: String, payload: String) {
        try {
            if (mqttClient?.isConnected == true) {
                val message = MqttMessage(payload.toByteArray()).apply {
                    qos = 0 // QoS 0 for low latency
                }
                mqttClient?.publish(topic, message)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

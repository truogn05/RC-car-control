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

    fun subscribe(topic: String, onMessage: (String) -> Unit) {
        try {
            if (mqttClient?.isConnected == true) {
                mqttClient?.subscribe(topic, 0, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        // Successfully subscribed
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        exception?.printStackTrace()
                    }
                })
                
                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {}
                    override fun messageArrived(topicArg: String?, message: MqttMessage?) {
                        if (topicArg == topic && message != null) {
                            onMessage(String(message.payload))
                        }
                    }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

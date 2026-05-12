package com.example.esp32

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * UDP transport handler for Wi-Fi Direct mode.
 * Sends control commands directly to ESP32 AP at 192.168.4.1:4210.
 * No connection handshake needed (UDP is connectionless).
 */
class UdpHandler(
    private val host: String = "192.168.4.1",
    private val port: Int = 4210
) {
    /**
     * Send a command payload over UDP.
     * Must be called from a background thread (not main thread).
     */
    fun publish(payload: String) {
        try {
            val socket = DatagramSocket()
            socket.soTimeout = 500  // 500ms timeout for send
            val data = payload.toByteArray(Charsets.UTF_8)
            val address = InetAddress.getByName(host)
            val packet = DatagramPacket(data, data.size, address, port)
            socket.send(packet)
            socket.close()
        } catch (e: Exception) {
            // Silent fail – motor commands are fire-and-forget
            // The failsafe watchdog on ESP32 will stop motors if no cmd received
        }
    }
}

package com.example.esp32

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Simple MJPEG stream viewer for ESP32-CAM.
 * Streams over HTTP on the same LAN network.
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var ivFrame: ImageView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvStatus: TextView
    private val isStreaming = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var streamThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        val cameraUrl = intent.getStringExtra("camera_url") ?: ""
        ivFrame = findViewById(R.id.ivMjpegFrame)
        pbLoading = findViewById(R.id.pbCameraLoading)
        tvStatus = findViewById(R.id.tvCameraStatus)

        findViewById<View>(R.id.btnCameraBack).setOnClickListener { finish() }

        if (cameraUrl.isBlank()) {
            tvStatus.text = "❌ URL camera chưa cấu hình"
            tvStatus.setTextColor(getColor(R.color.rc_danger))
            pbLoading.visibility = View.GONE
        } else {
            startMjpegStream(cameraUrl)
        }
    }

    private fun startMjpegStream(url: String) {
        isStreaming.set(true)
        streamThread = Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                connection.connect()

                mainHandler.post {
                    tvStatus.text = "● Live"
                    tvStatus.setTextColor(getColor(R.color.rc_success))
                    pbLoading.visibility = View.GONE
                }

                val inputStream = BufferedInputStream(connection.inputStream)
                val buffer = mutableListOf<Byte>()

                while (isStreaming.get()) {
                    val byte = inputStream.read()
                    if (byte == -1) break
                    buffer.add(byte.toByte())

                    // JPEG end marker: 0xFF 0xD9
                    val size = buffer.size
                    if (size >= 2 && buffer[size - 2] == 0xFF.toByte() && buffer[size - 1] == 0xD9.toByte()) {
                        val jpegBytes = buffer.toByteArray()
                        buffer.clear()

                        // Find JPEG start marker: 0xFF 0xD8
                        val startIdx = findJpegStart(jpegBytes)
                        if (startIdx >= 0) {
                            val bmp: Bitmap? = BitmapFactory.decodeByteArray(jpegBytes, startIdx, jpegBytes.size - startIdx)
                            if (bmp != null) {
                                mainHandler.post { ivFrame.setImageBitmap(bmp) }
                            }
                        }
                    }

                    // Prevent buffer from growing too large
                    if (buffer.size > 200_000) buffer.clear()
                }

                connection.disconnect()
            } catch (e: Exception) {
                mainHandler.post {
                    tvStatus.text = "❌ Lỗi kết nối camera"
                    tvStatus.setTextColor(getColor(R.color.rc_danger))
                    pbLoading.visibility = View.GONE
                }
            }
        }.also { it.start() }
    }

    private fun findJpegStart(data: ByteArray): Int {
        for (i in 0 until data.size - 1) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte()) return i
        }
        return -1
    }

    override fun onDestroy() {
        super.onDestroy()
        isStreaming.set(false)
        streamThread?.interrupt()
    }
}

package com.example.esp32

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.AsyncTask
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.espressif.iot.esptouch.EsptouchTask
import com.espressif.iot.esptouch.IEsptouchTask
import com.espressif.iot.esptouch.IEsptouchResult

class SetupActivity : AppCompatActivity() {

    private lateinit var etSsid: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnSmartConfig: Button
    private lateinit var etBroker: EditText
    private lateinit var etTopic: EditText
    private lateinit var btnStart: Button

    private lateinit var sharedPrefs: SharedPreferences
    private var esptouchTask: IEsptouchTask? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        sharedPrefs = getSharedPreferences("RcCarPrefs", Context.MODE_PRIVATE)

        etSsid = findViewById(R.id.etSsid)
        etPassword = findViewById(R.id.etPassword)
        btnSmartConfig = findViewById(R.id.btnSmartConfig)
        etBroker = findViewById(R.id.etBroker)
        etTopic = findViewById(R.id.etTopic)
        btnStart = findViewById(R.id.btnStart)

        // Load Default or check previous
        etBroker.setText(sharedPrefs.getString("brokerUri", "tcp://broker.hivemq.com:1883"))
        etTopic.setText(sharedPrefs.getString("mqttTopic", "my_rc_car/control"))

        btnSmartConfig.setOnClickListener {
            val ssid = etSsid.text.toString()
            val password = etPassword.text.toString()

            if (ssid.isEmpty()) {
                Toast.makeText(this, "Hãy nhập Wi-Fi SSID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                Toast.makeText(this, "Hãy nhập Mật khẩu Wi-Fi", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            EsptouchAsyncTask().execute(ssid, password)
        }

        btnStart.setOnClickListener {
            // Save Settings
            sharedPrefs.edit()
                .putString("brokerUri", etBroker.text.toString())
                .putString("mqttTopic", etTopic.text.toString())
                .apply()

            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish() // Đóng SetupActivity, không cho ấn Back trở lại
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        var needRequest = false
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true
                break
            }
        }

        if (needRequest) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
        } else {
            autoFillSsid()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                autoFillSsid()
            } else {
                Toast.makeText(this, "Bạn cần cấp quyền Location để tự động lấy SSID", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun autoFillSsid() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifiManager.connectionInfo
        if (info != null && info.ssid != null && info.ssid != "<unknown ssid>") {
            val ssid = info.ssid.replace("\"", "")
            etSsid.setText(ssid)
        }
    }

    inner class EsptouchAsyncTask : AsyncTask<String, Void, List<IEsptouchResult>>() {
        override fun onPreExecute() {
            btnSmartConfig.isEnabled = false
            btnSmartConfig.text = "Đang gửi cấu hình..."
        }

        override fun doInBackground(vararg params: String?): List<IEsptouchResult> {
            val ssid = params[0] ?: ""
            val password = params[1] ?: ""
            val bssid = "00:00:00:00:00:00" // We can leave it empty or all zeros if not fetched

            esptouchTask = EsptouchTask(ssid, bssid, password, this@SetupActivity)
            // Lắng nghe liên tục trong 1 phút hoặc đến khi có 1 thiết bị kết nối thành công
            return esptouchTask!!.executeForResults(1)
        }

        override fun onPostExecute(results: List<IEsptouchResult>?) {
            btnSmartConfig.isEnabled = true
            btnSmartConfig.text = "Gửi cấu hình Wi-Fi xuống xe"

            var isSuccess = false
            if (results != null) {
                for (result in results) {
                    if (result.isSuc) {
                        isSuccess = true
                        break
                    }
                }
            }

            if (isSuccess) {
                Toast.makeText(this@SetupActivity, "Cấp mạng Wi-Fi thành công!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@SetupActivity, "Không tìm thấy thiết bị ESP32 (SmartConfig fail)", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

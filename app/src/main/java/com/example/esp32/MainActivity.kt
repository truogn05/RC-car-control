package com.example.esp32

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import io.github.controlwear.virtual.joystick.android.JoystickView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var mqttHandler: MqttHandler
    private lateinit var sharedPrefs: SharedPreferences

    // UI Elements
    private lateinit var tvStatus: TextView
    private lateinit var tvPower: TextView
    private lateinit var tvDebug: TextView
    private lateinit var sbSpeedLimit: SeekBar
    private lateinit var pbSpeedIndicator: ProgressBar
    private lateinit var llSpeedLimitJoystick: View
    private lateinit var llTopCenter: View
    
    // Layout groups for toggling modes
    private lateinit var layoutDpad: View
    private lateinit var joystickView: JoystickView
    private lateinit var layoutThrottle: View
    
    private lateinit var rgGear: RadioGroup

    // Physics Loop configs
    private val TICK_RATE_MS = 20L
    private val handler = Handler(Looper.getMainLooper())
    private val ACCEL_RATE = 0.015f 
    private val BRAKE_RATE = 5.0f
    private val FRICTION_RATE = 0.5f
    private val STEER_RATE = 0.15f

    // Inputs state
    private var isGasPressed = false
    private var isBrakePressed = false
    private var isLeftPressed = false
    private var isRightPressed = false
    private var joyX = 0f
    private var joyY = 0f

    // Physics Engine state
    private var currentThrottle = 0f
    private var currentSteering = 0f
    private var lastPublishedLeft = 0
    private var lastPublishedRight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Hide status bar and navigation bar
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
                
        setContentView(R.layout.activity_main)

        sharedPrefs = getSharedPreferences("RcCarPrefs", Context.MODE_PRIVATE)

        // View Binding
        tvStatus = findViewById(R.id.tvStatus)
        tvPower = findViewById(R.id.tvPower)
        tvDebug = findViewById(R.id.tvDebug)
        sbSpeedLimit = findViewById(R.id.sbSpeedLimit)
        pbSpeedIndicator = findViewById(R.id.pbSpeedIndicator)
        llSpeedLimitJoystick = findViewById(R.id.llSpeedLimitJoystick)
        llTopCenter = findViewById(R.id.llTopCenter)
        
        layoutDpad = findViewById(R.id.layoutDpad)
        joystickView = findViewById(R.id.joystickView)
        layoutThrottle = findViewById(R.id.layoutThrottle)
        rgGear = findViewById(R.id.rgGear)

        setupTouchListeners()
        setupSettingsDialog()

        applyUIFromSettings()
        initAndConnectMQTT()

        // Start Physics Engine Loop
        handler.post(gameLoop)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(gameLoop)
    }

    private fun applyUIFromSettings() {
        val isJoystickMode = sharedPrefs.getBoolean("isJoystickMode", false)
        if (isJoystickMode) {
            layoutDpad.visibility = View.GONE
            layoutThrottle.visibility = View.GONE
            joystickView.visibility = View.VISIBLE
            llSpeedLimitJoystick.visibility = View.VISIBLE
            llTopCenter.visibility = View.GONE
        } else {
            layoutDpad.visibility = View.VISIBLE
            layoutThrottle.visibility = View.VISIBLE
            joystickView.visibility = View.GONE
            llSpeedLimitJoystick.visibility = View.GONE
            llTopCenter.visibility = View.VISIBLE
            // Bỏ limit speed khi ở chế độ bằng nút
            sbSpeedLimit.progress = 100
        }
    }

    private fun initAndConnectMQTT() {
        val brokerUri = sharedPrefs.getString("brokerUri", "tcp://broker.hivemq.com:1883") ?: "tcp://broker.hivemq.com:1883"
        val clientId = "AndroidRC_" + System.currentTimeMillis()
        
        mqttHandler = MqttHandler(brokerUri, clientId)
        tvStatus.text = "Status: Connecting..."
        tvStatus.setTextColor(Color.parseColor("#FBC02D"))
        
        mqttHandler.connect(
            onSuccess = {
                runOnUiThread { 
                    tvStatus.text = "Status: Connected MQTT"
                    tvStatus.setTextColor(Color.parseColor("#388E3C"))
                }
            },
            onFailure = { error ->
                runOnUiThread { 
                    tvStatus.text = "Status: Error - ${error.message}"
                    tvStatus.setTextColor(Color.parseColor("#D32F2F"))
                }
            }
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListeners() {
        setButtonTouchListener(findViewById(R.id.btnGas)) { isGasPressed = it }
        setButtonTouchListener(findViewById(R.id.btnBrake)) { isBrakePressed = it }
        setButtonTouchListener(findViewById(R.id.btnLeft)) { isLeftPressed = it }
        setButtonTouchListener(findViewById(R.id.btnRight)) { isRightPressed = it }

        joystickView.setOnMoveListener { angle, strength ->
            val radians = Math.toRadians(angle.toDouble())
            joyX = (cos(radians) * strength).toFloat()
            joyY = (sin(radians) * strength).toFloat()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setButtonTouchListener(view: View, onAction: (Boolean) -> Unit) {
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.alpha = 0.7f
                    if (view.id == R.id.btnGas || view.id == R.id.btnBrake) {
                        view.rotationX = 0f
                    }
                    onAction(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.alpha = 1.0f
                    if (view.id == R.id.btnGas || view.id == R.id.btnBrake) {
                        view.rotationX = -15f
                    }
                    onAction(false)
                }
            }
            true // Consume event
        }
    }

    // GAME LOOP: Chạy mỗi `TICK_RATE_MS` (50Hz) để tạo chuyển động mượt mà
    private val gameLoop = object : Runnable {
        override fun run() {
            updatePhysics()
            handler.postDelayed(this, TICK_RATE_MS)
        }
    }

    private fun updatePhysics() {
        val isJoystickMode = sharedPrefs.getBoolean("isJoystickMode", false)
        val limitPct = sbSpeedLimit.progress / 100f

        var targetThrottle = 0f
        var targetSteering = 0f

        if (isJoystickMode) {
            // JOYSTICK MODE
            targetThrottle = joyY
            targetSteering = joyX

            // Áp dụng thuật toán Deadzone chống trôi (jittering)
            if (abs(targetThrottle) < 15f) targetThrottle = 0f
            if (abs(targetSteering) < 15f) targetSteering = 0f

            // Nội suy (Lerp) làm mượt chuyển động Joystick
            currentThrottle += (targetThrottle - currentThrottle) * 0.2f
            currentSteering += (targetSteering - currentSteering) * 0.3f
        } else {
            // BUTTON MODE
            val gear = when (rgGear.checkedRadioButtonId) {
                R.id.rbDrive -> 1f
                R.id.rbReverse -> -1f
                else -> 0f // Neutral
            }

            // Xử lý Throttle (Gas/Brake)
            if (isGasPressed && gear != 0f) {
                targetThrottle = 100f * gear
                currentThrottle += (targetThrottle - currentThrottle) * ACCEL_RATE
            } else if (isBrakePressed) {
                // Phanh gấp -> Chuyển gia tốc nhanh về 0
                val brakeAmount = Math.min(abs(currentThrottle), BRAKE_RATE)
                currentThrottle -= Math.signum(currentThrottle) * brakeAmount
            } else {
                // Ma sát tự nhiên (chạy chậm dần khi buông chân ga)
                val friction = Math.min(abs(currentThrottle), FRICTION_RATE)
                currentThrottle -= Math.signum(currentThrottle) * friction
            }

            // Xử lý Steering (Left/Right)
            if (isLeftPressed) targetSteering = -100f
            else if (isRightPressed) targetSteering = 100f
            else targetSteering = 0f

            currentSteering += (targetSteering - currentSteering) * STEER_RATE
        }

        if (!isJoystickMode) {
            pbSpeedIndicator.progress = abs(currentThrottle).roundToInt()
        }

        // --- TRỘN TÍN HIỆU (MIXING) ---
        
        // Thuật toán: Giảm độ nhạy tay lái khi chạy tốc độ cao để xe khỏi lật
        val speedFactor = abs(currentThrottle) / 100f 
        val effSteering = currentSteering * (1.0f - (speedFactor * 0.4f)) // Giảm tối đa 40% góc lái khi ở Max Speed

        // Mix: Left = T + S (Nếu lái trái thì Steering âm -> bánh trái quay chậm hơn bánh phải)
        var left = currentThrottle + effSteering
        var right = currentThrottle - effSteering

        // Cắt gọt (Clamp) và nhân với Max Speed Slider
        left = left.coerceIn(-100f, 100f) * limitPct
        right = right.coerceIn(-100f, 100f) * limitPct

        val outL = left.roundToInt()
        val outR = right.roundToInt()

        // Chỉ Publish nếu giá trị thay đổi để tiết kiệm băng thông MQTT
        if (outL != lastPublishedLeft || outR != lastPublishedRight) {
            lastPublishedLeft = outL
            lastPublishedRight = outR
            
            tvDebug.text = "L: $outL | R: $outR (Limit: ${(limitPct*100).toInt()}%)"
            
            val topic = sharedPrefs.getString("mqttTopic", "my_rc_car/control") ?: "my_rc_car/control"
            mqttHandler.publish(topic, "$outL,$outR")
        }
    }

    private fun setupSettingsDialog() {
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        btnSettings.setOnClickListener {
            val view = layoutInflater.inflate(R.layout.dialog_settings, null)
            val etBroker = view.findViewById<EditText>(R.id.etBroker)
            val etTopic = view.findViewById<EditText>(R.id.etTopic)
            val switchMode = view.findViewById<Switch>(R.id.switchMode)

            etBroker.setText(sharedPrefs.getString("brokerUri", "tcp://broker.hivemq.com:1883"))
            etTopic.setText(sharedPrefs.getString("mqttTopic", "my_rc_car/control"))
            switchMode.isChecked = sharedPrefs.getBoolean("isJoystickMode", false)

            AlertDialog.Builder(this)
                .setTitle("Cấu hình ESP32 RC Car")
                .setView(view)
                .setPositiveButton("Lưu") { _, _ ->
                    sharedPrefs.edit()
                        .putString("brokerUri", etBroker.text.toString())
                        .putString("mqttTopic", etTopic.text.toString())
                        .putBoolean("isJoystickMode", switchMode.isChecked)
                        .apply()
                        
                    applyUIFromSettings()
                    initAndConnectMQTT() // Reconnect with new settings
                }
                .setNegativeButton("Hủy", null)
                .show()
        }
    }
}

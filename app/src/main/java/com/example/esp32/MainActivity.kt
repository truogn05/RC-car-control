package com.example.esp32

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.SoundPool
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

    // Device info from Intent
    private var brokerUri = "tcp://broker.hivemq.com:1883"
    private var mqttTopic = "my_rc_car/control"
    private var cameraUrl = ""

    // UI
    private lateinit var tvStatus: TextView
    private lateinit var tvDebug: TextView
    private lateinit var tvPower: TextView
    private lateinit var sbSpeedLimit: SeekBar
    private lateinit var pbSpeedIndicator: ProgressBar
    private lateinit var llSpeedLimitJoystick: View
    private lateinit var llTopCenter: View
    private lateinit var layoutDpad: View
    private lateinit var joystickView: JoystickView
    private lateinit var layoutThrottle: View
    private lateinit var rgGear: RadioGroup

    // Physics constants
    private val TICK_RATE_MS = 20L
    private val ACCEL_RATE = 0.015f
    private val BRAKE_RATE = 5.0f
    private val FRICTION_RATE = 0.5f
    private val STEER_RATE = 0.15f

    // Input state
    private var isGasPressed = false
    private var isBrakePressed = false
    private var isLeftPressed = false
    private var isRightPressed = false
    private var steerTarget = 0f
    private var joyX = 0f
    private var joyY = 0f

    // Physics state
    private var currentThrottle = 0f
    private var currentSteering = 0f
    private var lastPublishedLeft = 0
    private var lastPublishedRight = 0

    // Settings
    private var isJoystickMode = false
    private var isSoundEnabled = true

    // SoundPool
    private var soundPool: SoundPool? = null
    private var clickSoundId = 0

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setImmersive()
        setContentView(R.layout.activity_main)

        // Read device info from intent
        brokerUri = intent.getStringExtra("broker_uri") ?: "tcp://broker.hivemq.com:1883"
        mqttTopic = intent.getStringExtra("mqtt_topic") ?: "my_rc_car/control"
        cameraUrl = intent.getStringExtra("camera_url") ?: ""

        // Read settings
        val prefs = getSharedPreferences("RcCarPrefs", Context.MODE_PRIVATE)
        isJoystickMode = prefs.getBoolean("isJoystickMode", false)
        isSoundEnabled = prefs.getBoolean("sound_enabled", true)

        bindViews()
        setupSoundPool()
        setupTouchListeners()
        applyUIFromSettings()
        connectMqtt()

        handler.post(gameLoop)
    }

    private fun setImmersive() {
        window.statusBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvDebug = findViewById(R.id.tvDebug)
        tvPower = findViewById(R.id.tvPower)
        sbSpeedLimit = findViewById(R.id.sbSpeedLimit)
        pbSpeedIndicator = findViewById(R.id.pbSpeedIndicator)
        llSpeedLimitJoystick = findViewById(R.id.llSpeedLimitJoystick)
        llTopCenter = findViewById(R.id.llTopCenter)
        layoutDpad = findViewById(R.id.layoutDpad)
        joystickView = findViewById(R.id.joystickView)
        layoutThrottle = findViewById(R.id.layoutThrottle)
        rgGear = findViewById(R.id.rgGear)
    }

    private fun setupSoundPool() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
        // We'll use a synthesized tick sound - no asset file needed at minimal
        // clickSoundId loaded from raw if exists
    }

    private fun playClickSound() {
        if (isSoundEnabled && clickSoundId != 0) {
            soundPool?.play(clickSoundId, 1f, 1f, 0, 0, 1f)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListeners() {
        // Gas / Brake buttons
        setButtonTouchListener(findViewById(R.id.btnGas)) { isGasPressed = it; if (it) playClickSound() }
        setButtonTouchListener(findViewById(R.id.btnBrake)) { isBrakePressed = it; if (it) playClickSound() }

        // Buzzer button
        findViewById<View>(R.id.btnBuzzer).setOnClickListener {
            playClickSound()
            mqttHandler.publish("$mqttTopic/buzzer", "1")
        }

        // Camera button
        findViewById<View>(R.id.btnCamera).setOnClickListener {
            if (cameraUrl.isNotBlank()) {
                startActivity(Intent(this, CameraActivity::class.java).apply {
                    putExtra("camera_url", cameraUrl)
                })
            } else {
                Toast.makeText(this, "URL Camera chưa được cấu hình cho xe này", Toast.LENGTH_SHORT).show()
            }
        }

        // Settings button
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            SettingsBottomSheet(
                brokerUri = brokerUri,
                mqttTopic = mqttTopic,
                isJoystickMode = isJoystickMode,
                onSettingsChanged = { joystick, sound ->
                    isJoystickMode = joystick
                    isSoundEnabled = sound
                    applyUIFromSettings()
                    setupSteering()
                },
                onNavigateBack = { finish() }
            ).show(supportFragmentManager, "settings")
        }

        // Joystick
        joystickView.setOnMoveListener { angle, strength ->
            val radians = Math.toRadians(angle.toDouble())
            joyX = (cos(radians) * strength).toFloat()
            joyY = (sin(radians) * strength).toFloat()
        }

        // Steering touch (for D-pad mode)
        setupSteering()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSteering() {
        val touchListener = View.OnTouchListener { _, event ->
            val rawX = event.rawX.toInt()
            val rawY = event.rawY.toInt()

            val btnLeft = layoutDpad.findViewById<View>(R.id.btnLeft)
            val btnRight = layoutDpad.findViewById<View>(R.id.btnRight)

            val leftRect = android.graphics.Rect()
            btnLeft.getGlobalVisibleRect(leftRect)

            val rightRect = android.graphics.Rect()
            btnRight.getGlobalVisibleRect(rightRect)

            val isLeft = leftRect.contains(rawX, rawY)
            val isRight = rightRect.contains(rawX, rawY)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (isLeft && !isLeftPressed) playClickSound()
                    if (isRight && !isRightPressed) playClickSound()

                    isLeftPressed = isLeft
                    isRightPressed = isRight

                    btnLeft.alpha = if (isLeftPressed) 0.5f else 1.0f
                    btnRight.alpha = if (isRightPressed) 0.5f else 1.0f

                    if (isLeftPressed) btnLeft.animate().rotationX(5f).setDuration(100).start()
                    else btnLeft.animate().rotationX(0f).setDuration(100).start()

                    if (isRightPressed) btnRight.animate().rotationX(5f).setDuration(100).start()
                    else btnRight.animate().rotationX(0f).setDuration(100).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isLeftPressed = false
                    isRightPressed = false
                    btnLeft.alpha = 1.0f
                    btnRight.alpha = 1.0f
                    btnLeft.animate().rotationX(0f).setDuration(100).start()
                    btnRight.animate().rotationX(0f).setDuration(100).start()
                }
            }
            true
        }

        layoutDpad.setOnTouchListener(touchListener)
        layoutDpad.findViewById<View>(R.id.btnLeft).setOnTouchListener(touchListener)
        layoutDpad.findViewById<View>(R.id.btnRight).setOnTouchListener(touchListener)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setButtonTouchListener(view: View, onAction: (Boolean) -> Unit) {
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.alpha = 0.6f
                    view.animate().rotationX(5f).setDuration(100).start()
                    onAction(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.alpha = 1.0f
                    view.animate().rotationX(-15f).setDuration(100).start()
                    onAction(false)
                }
            }
            true
        }
    }

    private fun applyUIFromSettings() {
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
            sbSpeedLimit.progress = 100
        }
    }

    private fun connectMqtt() {
        val clientId = "AndroidRC_${System.currentTimeMillis()}"
        mqttHandler = MqttHandler(brokerUri, clientId)
        tvStatus.text = "● Đang kết nối..."
        tvStatus.setTextColor(Color.parseColor("#FBC02D"))

        mqttHandler.connect(
            onSuccess = {
                runOnUiThread {
                    tvStatus.text = "● Đã kết nối"
                    tvStatus.setTextColor(Color.parseColor("#4CAF50"))
                }
                mqttHandler.subscribe("$mqttTopic/battery") { batteryPct ->
                    runOnUiThread {
                        val currentText = tvStatus.text.toString().split(" | ")[0]
                        tvStatus.text = "$currentText | Pin: ${batteryPct.trim()}%"
                    }
                }
            },
            onFailure = { error ->
                runOnUiThread {
                    tvStatus.text = "● Lỗi kết nối"
                    tvStatus.setTextColor(Color.parseColor("#FF5252"))
                }
            }
        )
    }

    private val gameLoop = object : Runnable {
        override fun run() {
            updatePhysics()
            handler.postDelayed(this, TICK_RATE_MS)
        }
    }

    private fun updatePhysics() {
        val limitPct = sbSpeedLimit.progress / 100f

        var targetThrottle = 0f
        var targetSteering = 0f

        if (isJoystickMode) {
            targetThrottle = joyY
            targetSteering = joyX

            if (abs(targetThrottle) < 15f) targetThrottle = 0f
            if (abs(targetSteering) < 15f) targetSteering = 0f

            currentThrottle += (targetThrottle - currentThrottle) * 0.2f
            currentSteering += (targetSteering - currentSteering) * 0.3f
        } else {
            val gear = when (rgGear.checkedRadioButtonId) {
                R.id.rbDrive -> 1f
                R.id.rbReverse -> -1f
                else -> 0f
            }

            if (isGasPressed && gear != 0f) {
                targetThrottle = 100f * gear
                currentThrottle += (targetThrottle - currentThrottle) * ACCEL_RATE
            } else if (isBrakePressed) {
                val brakeAmount = Math.min(abs(currentThrottle), BRAKE_RATE)
                currentThrottle -= Math.signum(currentThrottle) * brakeAmount
            } else {
                val friction = Math.min(abs(currentThrottle), FRICTION_RATE)
                currentThrottle -= Math.signum(currentThrottle) * friction
            }

            if (gear != 0f) {
                if (isLeftPressed) targetSteering = -100f
                else if (isRightPressed) targetSteering = 100f
                else targetSteering = 0f

                if (gear < 0f) {
                    targetSteering = -targetSteering
                }
            } else {
                targetSteering = 0f
            }

            currentSteering += (targetSteering - currentSteering) * STEER_RATE
        }

        if (!isJoystickMode) {
            val speedPct = abs(currentThrottle).roundToInt()
            pbSpeedIndicator.progress = speedPct
            tvPower.text = "$speedPct%"
        }

        // --- TRỘN TÍN HIỆU (MIXING) ---
        val speedFactor = abs(currentThrottle) / 100f
        val effSteering = currentSteering * (1.0f - (speedFactor * 0.4f))

        var left = currentThrottle + effSteering
        var right = currentThrottle - effSteering

        left = left.coerceIn(-100f, 100f) * limitPct
        right = right.coerceIn(-100f, 100f) * limitPct

        val outL = left.roundToInt()
        val outR = right.roundToInt()

        if (outL != lastPublishedLeft || outR != lastPublishedRight) {
            lastPublishedLeft = outL
            lastPublishedRight = outR
            tvDebug.text = "L: $outL | R: $outR"
            mqttHandler.publish(mqttTopic, "$outL,$outR")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(gameLoop)
        soundPool?.release()
    }
}

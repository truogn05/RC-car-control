package com.example.esp32

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import io.github.controlwear.virtual.joystick.android.JoystickView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // Transport layer – only one is active depending on connectionType
    private lateinit var mqttHandler: MqttHandler
    private lateinit var udpHandler: UdpHandler

    // Background executor for UDP sends (must not run on main thread)
    private lateinit var udpExecutor: ExecutorService

    // Device info from Intent
    private var deviceId: String? = null
    private lateinit var deviceRepository: DeviceRepository
    private var connectionType = "mqtt"   // "mqtt" | "wifi"
    private var brokerUri = "tcp://broker.hivemq.com:1883"
    private var mqttTopic = "my_rc_car/control"
    private var cameraUrl = ""
    private var apSsid = "RCCar_AP"

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
    private lateinit var btnLight: com.google.android.material.button.MaterialButton
    private lateinit var btnHazard: com.google.android.material.button.MaterialButton

    // Physics constants
    private val TICK_RATE_MS = 20L
    private val ACCEL_STEP = 3.0f      // Increase speed by 3% per tick (reaches 100% in ~0.66s)
    private val DECEL_STEP = 5.0f      // Friction drag: decreases speed by 5% per tick (stops in ~0.4s)
    private val BRAKE_STEP = 10.0f     // Active braking: decreases speed by 10% per tick (stops in ~0.2s)
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
    private var isLightActive = false
    private var isHazardActive = false
    private var lastGear = 0f

    // Sound and vibration
    private lateinit var engineSoundPlayer: EngineSoundPlayer
    private var vibeTickCounter = 0

    // SoundPool
    private var soundPool: SoundPool? = null
    private var clickSoundId = 0

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setImmersive()
        setContentView(R.layout.activity_main)

        // Read device info from intent
        deviceId = intent.getStringExtra("device_id")
        deviceRepository = DeviceRepository(this)
        connectionType = intent.getStringExtra("connection_type") ?: "mqtt"
        brokerUri = intent.getStringExtra("broker_uri") ?: "tcp://broker.hivemq.com:1883"
        mqttTopic = intent.getStringExtra("mqtt_topic") ?: "my_rc_car/control"
        cameraUrl = intent.getStringExtra("camera_url") ?: ""
        apSsid = intent.getStringExtra("ap_ssid") ?: "RCCar_AP"

        // Read settings
        val prefs = getSharedPreferences("RcCarPrefs", Context.MODE_PRIVATE)
        isJoystickMode = prefs.getBoolean("isJoystickMode", false)
        isSoundEnabled = prefs.getBoolean("sound_enabled", true)

        bindViews()
        setupSoundPool()
        engineSoundPlayer = EngineSoundPlayer()
        setupTouchListeners()
        applyUIFromSettings()
        setupTransport(apSsid)

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
        btnLight = findViewById(R.id.btnLight)
        btnHazard = findViewById(R.id.btnHazard)
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
            val payload = "BUZ:1"
            if (connectionType == "wifi") {
                udpExecutor.execute { udpHandler.publish(payload) }
            } else {
                mqttHandler.publish(mqttTopic, payload)
            }
        }

        // Light button
        btnLight.setOnClickListener {
            playClickSound()
            isLightActive = !isLightActive
            val payload = if (isLightActive) "LIGHT:1" else "LIGHT:0"
            if (connectionType == "wifi") {
                udpExecutor.execute { udpHandler.publish(payload) }
            } else {
                mqttHandler.publish(mqttTopic, payload)
            }
            // Đổi màu nền nút để báo hiệu
            if (isLightActive) {
                btnLight.setBackgroundColor(Color.parseColor("#00C8FF")) // rc_accent_cyan
                btnLight.setTextColor(Color.parseColor("#080D1A")) // rc_bg_deep
            } else {
                btnLight.setBackgroundColor(Color.parseColor("#1E2A44")) // rc_bg_card_elevated
                btnLight.setTextColor(Color.parseColor("#ECEFF4")) // rc_text_primary
            }
        }

        // Hazard button
        btnHazard.setOnClickListener {
            playClickSound()
            isHazardActive = !isHazardActive
            val payload = if (isHazardActive) "HAZARD:1" else "HAZARD:0"
            if (connectionType == "wifi") {
                udpExecutor.execute { udpHandler.publish(payload) }
            } else {
                mqttHandler.publish(mqttTopic, payload)
            }
            // Đổi màu nền nút để báo hiệu
            if (isHazardActive) {
                btnHazard.setBackgroundColor(Color.parseColor("#FF5252")) // rc_danger
                btnHazard.setTextColor(Color.parseColor("#ECEFF4"))
            } else {
                btnHazard.setBackgroundColor(Color.parseColor("#1E2A44")) // rc_bg_card_elevated
                btnHazard.setTextColor(Color.parseColor("#ECEFF4"))
            }
        }



        // Settings button
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            SettingsBottomSheet(
                connectionType = connectionType,
                brokerUriOrSsid = if (connectionType == "wifi") apSsid else brokerUri,
                mqttTopicOrCameraUrl = if (connectionType == "wifi") cameraUrl else mqttTopic,
                isJoystickMode = isJoystickMode,
                onSettingsChanged = { joystick, sound ->
                    isJoystickMode = joystick
                    if (isSoundEnabled != sound) {
                        isSoundEnabled = sound
                        if (isSoundEnabled) {
                            engineSoundPlayer.start()
                        } else {
                            engineSoundPlayer.stop()
                        }
                    }
                    applyUIFromSettings()
                    setupSteering()
                },
                onMqttConfigChanged = { newField1, newField2 ->
                    deviceId?.let { id ->
                        val devices = deviceRepository.getAllDevices()
                        val device = devices.find { it.id == id }
                        if (device != null) {
                            val updated = if (connectionType == "wifi") {
                                device.copy(apSsid = newField1, cameraUrl = newField2)
                            } else {
                                device.copy(brokerUri = newField1, mqttTopic = newField2)
                            }
                            deviceRepository.updateDevice(updated)
                            Toast.makeText(this@MainActivity, "Đã lưu cấu hình kết nối mới!", Toast.LENGTH_SHORT).show()
                        }
                    }

                    if (connectionType == "wifi") {
                        apSsid = newField1
                        cameraUrl = newField2
                        Toast.makeText(this@MainActivity, "Đã cập nhật WiFi: $apSsid", Toast.LENGTH_SHORT).show()
                    } else {
                        brokerUri = newField1
                        mqttTopic = newField2

                        if (::mqttHandler.isInitialized) {
                            mqttHandler.disconnect()
                        }
                        connectMqtt()
                    }
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
        sbSpeedLimit.progress = 0
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
        }
    }

    /**
     * Initialize the appropriate transport depending on [connectionType].
     * - "wifi"  → UDP direct to ESP32 AP at 192.168.4.1:4210
     * - "mqtt"  → MQTT via broker
     */
    private fun setupTransport(apSsid: String) {
        if (connectionType == "wifi") {
            setupWifiTransport(apSsid)
        } else {
            connectMqtt()
        }
    }

    private fun setupWifiTransport(apSsid: String) {
        udpExecutor = Executors.newSingleThreadExecutor()
        udpHandler = UdpHandler()  // default: 192.168.4.1:4210

        tvStatus.text = "● WiFi Direct"
        tvStatus.setTextColor(Color.parseColor("#4CAF50"))

        // Remind user to connect to the AP manually
        runOnUiThread {
            Toast.makeText(
                this,
                "Hãy đảm bảo điện thoại đang kết nối WiFi: $apSsid",
                Toast.LENGTH_LONG
            ).show()
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
                    tvStatus.text = "● Đã kết nối MQTT"
                    tvStatus.setTextColor(Color.parseColor("#4CAF50"))
                }
                mqttHandler.subscribe("$mqttTopic/battery") { batteryPct ->
                    runOnUiThread {
                        val currentText = tvStatus.text.toString().split(" | ")[0]
                        tvStatus.text = "$currentText | Pin: ${batteryPct.trim()}%"
                    }
                }
            },
            onFailure = { _ ->
                runOnUiThread {
                    tvStatus.text = "● Lỗi kết nối MQTT"
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
        // --- KHỞI TẠO VÀ TÍNH TOÁN GIỚI HẠN TỐC ĐỘ ---
        // limitPct: Tỷ lệ phần trăm giới hạn tốc độ tối đa từ thanh SeekBar trong chế độ Joystick (Dpad luôn đạt 100%)
        val limitPct = if (isJoystickMode) sbSpeedLimit.progress / 100f else 1.0f

        // targetThrottle & targetSteering: Giá trị mục tiêu (đích đến) của Ga và Hướng Lái (-100f đến 100f)
        var targetThrottle = 0f
        var targetSteering = 0f

        // --- CHẾ ĐỘ 1: ĐIỀU KHIỂN BẰNG JOYSTICK ---
        if (isJoystickMode) {
            // Kiểm tra xem ngón tay có thực sự kéo Joystick ra khỏi tâm hay không (ngưỡng nhiễu là 15f)
            val isJoyActiveX = abs(joyX) >= 15f
            val isJoyActiveY = abs(joyY) >= 15f

            if (isJoyActiveY) {
                targetThrottle = Math.signum(joyY) * 100f
            } else {
                targetThrottle = 0f
            }

            if (isJoyActiveX) {
                targetSteering = Math.signum(joyX) * 100f
            } else {
                targetSteering = 0f
            }

            currentThrottle = targetThrottle
            currentSteering = targetSteering

        } else {
            // --- CHẾ ĐỘ 2: ĐIỀU KHIỂN BẰNG NÚT BẤM (BUTTON MODE) ---

            // gear: Xác định hệ số chiều chuyển động dựa trên RadioGroup chọn số (D: 1f, R: -1f, N: 0f)
            val gear = when (rgGear.checkedRadioButtonId) {
                R.id.rbDrive -> 1f
                R.id.rbReverse -> -1f
                else -> 0f
            }

            // Xử lý biến Tốc độ (Throttle) bằng Linear Ramping (Option C)
            if (isGasPressed && gear != 0f) {
                targetThrottle = 100f * gear
                if (gear > 0f) {
                    currentThrottle = (currentThrottle + ACCEL_STEP).coerceAtMost(targetThrottle)
                } else {
                    currentThrottle = (currentThrottle - ACCEL_STEP).coerceAtLeast(targetThrottle)
                }
            } else if (isBrakePressed) {
                // Nhấn phanh: giảm tốc cực nhanh về 0
                if (currentThrottle > 0f) {
                    currentThrottle = (currentThrottle - BRAKE_STEP).coerceAtLeast(0f)
                } else if (currentThrottle < 0f) {
                    currentThrottle = (currentThrottle + BRAKE_STEP).coerceAtMost(0f)
                }
            } else {
                // TEMPORARY: Drop throttle immediately to 0 when gas is released (requested by user)
                // TẠM THỜI: Khi bỏ ga, lập tức đưa tốc độ (Throttle) về 0 ngay lập tức
                currentThrottle = 0f
            }

            // Xử lý biến Hướng lái (Steering) và Logic Trộn xung Mới theo yêu cầu của bạn
            if (gear != 0f) {
                // Khi đang cài số (D hoặc R) và xe có xu hướng chuyển động
                if (isLeftPressed) targetSteering = -100f
                else if (isRightPressed) targetSteering = 100f
                else targetSteering = 0f

                // Đảo hướng lái mục tiêu nếu xe đang cài số lùi (R) để trải nghiệm lái giống thực tế
                if (gear < 0f) {
                    targetSteering = -targetSteering
                }
            } else {
                // Nếu ở số N (gear == 0f), mục tiêu hướng lái thông thường bằng 0
                targetSteering = 0f
            }

            // Ở chế độ nút bấm Dpad, gán trực tiếp để tốc độ bánh xe phản hồi lập tức (Snap) khi rẽ hoặc nhả rẽ theo yêu cầu
            currentSteering = targetSteering
        }

        // Cập nhật giao diện thanh hiển thị tốc độ cho chế độ nút bấm (Joystick cập nhật riêng dựa trên thanh tốc độ)
        if (!isJoystickMode) {
            val speedPct = abs(currentThrottle).roundToInt()
            pbSpeedIndicator.progress = speedPct
            tvPower.text = "$speedPct%"
        } else {
            // Trong chế độ Joystick, hiển thị tiến trình tốc độ dựa thẳng vào giá trị tuyệt đối của currentThrottle
            val speedPct = abs(currentThrottle).roundToInt()
            pbSpeedIndicator.progress = speedPct
            tvPower.text = "$speedPct%"
        }

        // --- TRỘN TÍN HIỆU RA 2 MOTOR (DIFFERENTIAL MIXING LOGIC) ---
        var left = 0f
        var right = 0f
        var offsetL = 0
        var offsetR = 0
        if (isJoystickMode) {
            // LOGIC TRỘN JOYSTICK MỚI: Luôn ăn theo Max tốc độ của thanh Limit
            // Khởi tạo nền tảng tốc độ bằng Ga hiện tại (đã ép về -100f hoặc 100f)
            left = currentThrottle
            right = currentThrottle

            if (currentSteering != 0f) {
                if (currentThrottle != 0f) {
                    // Trường hợp 1: Joystick vừa đẩy tiến/lùi vừa bẻ cua -> Giảm bánh bên rẽ xuống 65%
                    if (currentSteering > 0f) {
                        right = currentThrottle * 0.75f  // Rẽ phải: Giảm lực xích/bánh bên phải
                        offsetR = -50
                    } else {
                        left = currentThrottle * 0.75f   // Rẽ trái: Giảm lực xích/bánh bên trái
                        offsetL = -50
                    }
                } else {
                    // Trường hợp 2: Joystick chỉ gạt ngang Trái/Phải (Không có ga) -> Hai bánh xoay ngược chiều nhau ở mức 30% để quay xe tại chỗ (Tank Turn)
                    if (currentSteering > 0f) {
                        left = 30f    // Rẽ phải tại chỗ: Bánh trái quay tiến
                        right = -30f  // Bánh phải quay lùi
                    } else {
                        left = -30f   // Rẽ trái tại chỗ: Bánh trái quay lùi
                        right = 30f   // Bánh phải quay tiến
                    }
                }
            }
        } else {
            // LOGIC TRỘN NÚT BẤM MỚI: Phản hồi tức thì (Snap) không qua bộ lọc tuyến tính cũ
            if (isGasPressed && (isLeftPressed || isRightPressed)) {
                // YÊU CẦU 1: Đang giữ Ga + Bấm Rẽ -> LẬP TỨC giảm bên rẽ xuống 65%, bên còn lại giữ nguyên tốc độ Ga hiện tại
                left = currentThrottle
                right = currentThrottle

                if (isRightPressed) {
                    // Nếu rẽ phải: Ép bánh phải tụt xuống còn 65% của tốc độ hiện tại ngay lập tức
                    right = currentThrottle * 0.65f
                    offsetR = -50
                } else if (isLeftPressed) {
                    // Nếu rẽ trái: Ép bánh trái tụt xuống còn 65% của tốc độ hiện tại ngay lập tức
                    left = currentThrottle * 0.65f
                    offsetL = -50
                }
            } else if (!isGasPressed && (isLeftPressed || isRightPressed)) {
                // YÊU CẦU 2: KHÔNG giữ Ga + Bấm Rẽ -> Hai bên bánh quay ngược chiều nhau cố định ở mức 30% để quay xe tại chỗ
                if (isRightPressed) {
                    left = 30f
                    right = -30f
                } else if (isLeftPressed) {
                    left = -30f
                    right = 30f
                }
            } else {
                // Trường hợp 3: Chạy thẳng bình thường hoặc xe đang trôi tự do (Dùng thuật toán mixing cũ của bạn)
                val speedFactor = abs(currentThrottle) / 100f
                val effSteering = currentSteering * (1.0f - (speedFactor * 0.4f))
                left = currentThrottle + effSteering
                right = currentThrottle - effSteering
            }
        }

        // --- ĐẦU RA VÀ TRUYỀN DỮ LIỆU ĐIỀU KHIỂN ---
        // Giới hạn dữ liệu thô trong khoảng [-100, 100] rồi nhân với tỷ lệ phần trăm của thanh Limit tốc độ sbSpeedLimit
        left = left.coerceIn(-100f, 100f) * limitPct
        right = right.coerceIn(-100f, 100f) * limitPct

        // Làm tròn giá trị cua/ga dạng số thực về dạng số nguyên (Integer) để chuẩn bị đóng gói payload
        val outL = left.roundToInt()
        val outR = right.roundToInt()

        // mapSpeed(): Hàm băm tỷ lệ hoặc chuyển đổi giá trị từ thang độ [-100, 100] sang dải xung PWM của vi điều khiển (Ví dụ: 0 -> 255)
        val spdL = mapSpeed(outL) + offsetL
        val spdR = mapSpeed(outR) + offsetR

        // Cập nhật âm thanh động cơ
        if (isSoundEnabled) {
            engineSoundPlayer.setThrottle(currentThrottle)
        }

        // Kiểm tra trùng lặp dữ liệu: Chỉ gửi gói tin đi khi có sự thay đổi thực sự ở bánh Trái hoặc bánh Phải nhằm tiết kiệm băng thông mạng
        // Tự động phát hiện trạng thái lùi hoặc phanh để gửi lệnh REV làm sáng đèn sau
        val currentGear = if (!isJoystickMode) {
            when (rgGear.checkedRadioButtonId) {
                R.id.rbDrive -> 1f
                R.id.rbReverse -> -1f
                else -> 0f
            }
        } else 0f
        val isRearLightActive = if (isJoystickMode) {
            (currentThrottle < -15f)
        } else {
            (currentGear < 0f || isBrakePressed)
        }
        val isRearLightInt = if (isRearLightActive) 1f else 0f
        if (isRearLightInt != lastGear) {
            lastGear = isRearLightInt
            val revPayload = if (isRearLightActive) "REV:1" else "REV:0"
            if (connectionType == "wifi") {
                udpExecutor.execute { udpHandler.publish(revPayload) }
            } else {
                mqttHandler.publish(mqttTopic, revPayload)
            }
        }

        // Kiểm tra trùng lặp dữ liệu: Chỉ gửi gói tin đi khi có sự thay đổi thực sự ở bánh Trái hoặc bánh Phải nhằm tiết kiệm băng thông mạng
        if (outL != lastPublishedLeft || outR != lastPublishedRight) {
            lastPublishedLeft = outL
            lastPublishedRight = outR
            tvDebug.text = "L: $outL | R: $outR"

            // Đóng gói payload gửi qua giao thức mạng đã chọn (Hỗ trợ UDP và MQTT) với định dạng CMD:
            val payload = "CMD:$spdL,$spdR"
            if (connectionType == "wifi") {
                udpExecutor.execute { udpHandler.publish(payload) }
            } else {
                mqttHandler.publish(mqttTopic, payload)
            }
        }
    }
    private fun mapSpeed(percent: Int): Int {
        val absVal = kotlin.math.abs(percent)
        if (absVal == 0) return 0

        val pwm = when {
            absVal == 0 -> 0
            absVal <= 30 -> 100
            absVal <= 65 -> 140
            else -> 180
        }
        return pwm * (if (percent >= 0) 1 else -1)
    }
    override fun onResume() {
        super.onResume()
        setImmersive()
        if (isSoundEnabled) {
            engineSoundPlayer.start()
        }
    }

    override fun onPause() {
        super.onPause()
        engineSoundPlayer.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(gameLoop)
        if (::engineSoundPlayer.isInitialized) {
            engineSoundPlayer.stop()
        }
        soundPool?.release()
        if (::udpExecutor.isInitialized) udpExecutor.shutdown()
    }

    private inner class EngineSoundPlayer {
        private var audioTrack: AudioTrack? = null
        private var isPlaying = false
        private var thread: Thread? = null
        @Volatile
        private var throttle = 0f // 0 to 100

        fun start() {
            if (isPlaying) return
            isPlaying = true

            thread = Thread {
                val sampleRate = 22050
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                try {
                    audioTrack = AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufferSize,
                        AudioTrack.MODE_STREAM
                    )
                    audioTrack?.play()
                } catch (e: Exception) {
                    e.printStackTrace()
                    isPlaying = false
                    return@Thread
                }

                val bufferSize = 1024
                val buffer = ShortArray(bufferSize)
                var phase = 0.0

                while (isPlaying) {
                    val currentSpeed = kotlin.math.abs(throttle)
                    if (currentSpeed > 40f) {
                        val targetFreq = 45.0 + (currentSpeed / 100.0) * 135.0

                        for (i in 0 until bufferSize) {
                            val value = (kotlin.math.sin(phase) * 11000.0 + 
                                         kotlin.math.sin(phase * 2.0) * 5000.0 + 
                                         (if (phase % (2 * Math.PI) > Math.PI) 2500.0 else -2500.0))

                            buffer[i] = value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

                            val angleIncrement = (2 * Math.PI * targetFreq) / sampleRate
                            phase += angleIncrement
                            if (phase > 2 * Math.PI) {
                                phase -= 2 * Math.PI
                            }
                        }
                    } else {
                        // Output silence when throttle <= 40%
                        for (i in 0 until bufferSize) {
                            buffer[i] = 0
                        }
                    }
                    try {
                        audioTrack?.write(buffer, 0, bufferSize)
                    } catch (e: Exception) {
                        break
                    }
                }
            }.apply {
                name = "EngineSoundThread"
                start()
            }
        }

        fun setThrottle(value: Float) {
            throttle = value
        }

        fun stop() {
            isPlaying = false
            try {
                thread?.join(200)
            } catch (e: Exception) {}
            thread = null

            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {}
            audioTrack = null
        }
    }
}

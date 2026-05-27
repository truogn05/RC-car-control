package com.example.esp32

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SettingsBottomSheet(
    private val connectionType: String,
    private val brokerUriOrSsid: String,
    private val mqttTopicOrCameraUrl: String,
    private val isJoystickMode: Boolean,
    private val onSettingsChanged: (joystick: Boolean, sound: Boolean) -> Unit,
    private val onMqttConfigChanged: (newField1: String, newField2: String) -> Unit,
    private val onNavigateBack: () -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = requireContext().getSharedPreferences("RcCarPrefs", Context.MODE_PRIVATE)

        val switchJoystick = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchJoystick)
        val switchSound = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchSound)
        
        val tilBroker = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilBroker)
        val tilTopic = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilTopic)
        val etBroker = view.findViewById<EditText>(R.id.etSettingsBroker)
        val etTopic = view.findViewById<EditText>(R.id.etSettingsTopic)
        
        val tvHeader = view.findViewById<android.widget.TextView>(R.id.tvSettingsConnHeader)
        val btnSaveConfig = view.findViewById<View>(R.id.btnSaveConfig)
        val btnBack = view.findViewById<View>(R.id.btnSettingsBack)

        // Dynamically adjust hints and input types depending on connectionType
        if (connectionType == "wifi") {
            tvHeader.text = "CẤU HÌNH KẾT NỐI WIFI"
            tilBroker.hint = "WiFi SSID của xe"
            tilTopic.hint = "URL Camera Stream (MJPEG)"
            etBroker.inputType = android.text.InputType.TYPE_CLASS_TEXT
            etTopic.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        } else {
            tvHeader.text = "CẤU HÌNH KẾT NỐI MQTT"
            tilBroker.hint = "MQTT Broker (tcp://...)"
            tilTopic.hint = "MQTT Control Topic"
            etBroker.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            etTopic.inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        switchJoystick.isChecked = isJoystickMode
        switchSound.isChecked = prefs.getBoolean("sound_enabled", true)
        etBroker.setText(brokerUriOrSsid)
        etTopic.setText(mqttTopicOrCameraUrl)

        switchJoystick.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("isJoystickMode", checked).apply()
            onSettingsChanged(checked, switchSound.isChecked)
        }

        switchSound.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("sound_enabled", checked).apply()
            onSettingsChanged(switchJoystick.isChecked, checked)
        }

        btnSaveConfig.setOnClickListener {
            val field1 = etBroker.text.toString().trim()
            val field2 = etTopic.text.toString().trim()

            if (field1.isEmpty() || field2.isEmpty()) {
                android.widget.Toast.makeText(context, "Vui lòng nhập đầy đủ thông tin", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            onMqttConfigChanged(field1, field2)
            dismiss()
        }

        btnBack.setOnClickListener {
            dismiss()
            onNavigateBack()
        }
    }
}

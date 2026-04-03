package com.example.esp32

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsBottomSheet(
    private val brokerUri: String,
    private val mqttTopic: String,
    private val isJoystickMode: Boolean,
    private val onSettingsChanged: (joystick: Boolean, sound: Boolean) -> Unit,
    private val onNavigateBack: () -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = requireContext().getSharedPreferences("RcCarPrefs", Context.MODE_PRIVATE)

        val switchJoystick = view.findViewById<SwitchMaterial>(R.id.switchJoystick)
        val switchSound = view.findViewById<SwitchMaterial>(R.id.switchSound)
        val tvBroker = view.findViewById<TextView>(R.id.tvSettingsBroker)
        val tvTopic = view.findViewById<TextView>(R.id.tvSettingsTopic)
        val btnBack = view.findViewById<View>(R.id.btnSettingsBack)

        switchJoystick.isChecked = isJoystickMode
        switchSound.isChecked = prefs.getBoolean("sound_enabled", true)
        tvBroker.text = brokerUri
        tvTopic.text = mqttTopic

        switchJoystick.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("isJoystickMode", checked).apply()
            onSettingsChanged(checked, switchSound.isChecked)
        }

        switchSound.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("sound_enabled", checked).apply()
        }

        btnBack.setOnClickListener {
            dismiss()
            onNavigateBack()
        }
    }
}

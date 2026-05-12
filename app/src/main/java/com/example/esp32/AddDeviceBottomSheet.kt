package com.example.esp32

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import java.util.UUID

class AddDeviceBottomSheet(
    private val editDevice: Device? = null,
    private val onSaved: (Device) -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var repo: DeviceRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_add_device, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = DeviceRepository(requireContext())

        val tabLayout = view.findViewById<TabLayout>(R.id.tabLayoutAdd)
        val viewPager = view.findViewById<ViewPager2>(R.id.vpAddDevice)

        viewPager.adapter = AddDevicePagerAdapter(requireActivity())
        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = if (pos == 0) "📶 WiFi Direct" else "🔗 MQTT"
        }.attach()

        // If editing, jump to the right tab
        if (editDevice?.connectionType == "mqtt") {
            viewPager.setCurrentItem(1, false)
        }
    }

    /** Called by WiFi page fragment when user taps Save */
    fun saveWifiDevice(name: String, apSsid: String, apPassword: String, camera: String) {
        if (name.isBlank() || apSsid.isBlank()) {
            Toast.makeText(requireContext(), "Vui lòng điền đầy đủ thông tin", Toast.LENGTH_SHORT).show()
            return
        }
        val device = Device(
            id = editDevice?.id ?: UUID.randomUUID().toString(),
            name = name,
            connectionType = "wifi",
            apSsid = apSsid,
            apPassword = apPassword,
            cameraUrl = camera
        )
        if (editDevice == null) repo.addDevice(device) else repo.updateDevice(device)
        onSaved(device)
        dismiss()
    }

    /** Called by MQTT page fragment when user taps Save */
    fun saveMqttDevice(name: String, broker: String, topic: String, camera: String) {
        if (name.isBlank() || broker.isBlank()) {
            Toast.makeText(requireContext(), "Vui lòng điền đầy đủ thông tin", Toast.LENGTH_SHORT).show()
            return
        }
        val device = Device(
            id = editDevice?.id ?: UUID.randomUUID().toString(),
            name = name,
            connectionType = "mqtt",
            brokerUri = broker,
            mqttTopic = topic.ifBlank { "my_rc_car/control" },
            cameraUrl = camera
        )
        if (editDevice == null) repo.addDevice(device) else repo.updateDevice(device)
        onSaved(device)
        dismiss()
    }

    private inner class AddDevicePagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount() = 2
        override fun createFragment(position: Int): Fragment =
            if (position == 0) AddWifiPageFragment(editDevice, this@AddDeviceBottomSheet)
            else AddMqttPageFragment(editDevice, this@AddDeviceBottomSheet)
    }
}

// ─────────────────────────────────────────────
// WiFi Direct tab fragment
// ─────────────────────────────────────────────
class AddWifiPageFragment(
    private val editDevice: Device?,
    private val sheet: AddDeviceBottomSheet
) : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.page_add_wifi, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etName   = view.findViewById<TextInputEditText>(R.id.etWifiName)
        val etSsid   = view.findViewById<TextInputEditText>(R.id.etWifiSsid)
        val etPwd    = view.findViewById<TextInputEditText>(R.id.etWifiPassword)
        val etCamera = view.findViewById<TextInputEditText>(R.id.etWifiCamera)
        val btnSave  = view.findViewById<MaterialButton>(R.id.btnWifiSave)

        // Prefill when editing
        editDevice?.takeIf { it.connectionType == "wifi" }?.let {
            etName.setText(it.name)
            etSsid.setText(it.apSsid)
            etPwd.setText(it.apPassword)
            etCamera.setText(it.cameraUrl)
        }

        btnSave.setOnClickListener {
            sheet.saveWifiDevice(
                name       = etName.text.toString(),
                apSsid     = etSsid.text.toString(),
                apPassword = etPwd.text.toString(),
                camera     = etCamera.text.toString()
            )
        }
    }
}

// ─────────────────────────────────────────────
// MQTT tab fragment  (unchanged)
// ─────────────────────────────────────────────
class AddMqttPageFragment(
    private val editDevice: Device?,
    private val sheet: AddDeviceBottomSheet
) : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.page_add_mqtt, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etName   = view.findViewById<TextInputEditText>(R.id.etMqttName)
        val etBroker = view.findViewById<TextInputEditText>(R.id.etMqttBroker)
        val etTopic  = view.findViewById<TextInputEditText>(R.id.etMqttTopic)
        val etCamera = view.findViewById<TextInputEditText>(R.id.etMqttCamera)
        val btnSave  = view.findViewById<MaterialButton>(R.id.btnMqttSave)

        editDevice?.takeIf { it.connectionType == "mqtt" }?.let {
            etName.setText(it.name)
            etBroker.setText(it.brokerUri)
            etTopic.setText(it.mqttTopic)
            etCamera.setText(it.cameraUrl)
        }

        btnSave.setOnClickListener {
            sheet.saveMqttDevice(
                name   = etName.text.toString(),
                broker = etBroker.text.toString(),
                topic  = etTopic.text.toString(),
                camera = etCamera.text.toString()
            )
        }
    }
}

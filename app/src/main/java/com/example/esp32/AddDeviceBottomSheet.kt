package com.example.esp32

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.AsyncTask
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.espressif.iot.esptouch.EsptouchTask
import com.espressif.iot.esptouch.IEsptouchTask
import com.espressif.iot.esptouch.IEsptouchResult
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import androidx.viewpager2.widget.ViewPager2
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
            tab.text = if (pos == 0) "📶 WiFi" else "🔗 MQTT"
        }.attach()

        // If editing, jump to the right tab and prefill later via fragments (simplified)
        if (editDevice?.connectionType == "mqtt") {
            viewPager.setCurrentItem(1, false)
        }
    }

    /** Called by page fragments when user taps Save (WiFi tab) */
    fun saveWifiDevice(name: String, ssid: String, broker: String, topic: String, camera: String) {
        if (name.isBlank() || ssid.isBlank()) {
            Toast.makeText(requireContext(), "Vui lòng điền đầy đủ thông tin", Toast.LENGTH_SHORT).show()
            return
        }
        val device = Device(
            id = editDevice?.id ?: UUID.randomUUID().toString(),
            name = name,
            connectionType = "wifi",
            brokerUri = broker.ifBlank { "tcp://broker.hivemq.com:1883" },
            mqttTopic = topic.ifBlank { "my_rc_car/control" },
            cameraUrl = camera,
            ssid = ssid
        )
        if (editDevice == null) repo.addDevice(device) else repo.updateDevice(device)
        onSaved(device)
        dismiss()
    }

    /** Called by page fragments when user taps Save (MQTT tab) */
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

/** WiFi tab fragment */
class AddWifiPageFragment(
    private val editDevice: Device?,
    private val sheet: AddDeviceBottomSheet
) : Fragment() {

    private var esptouchTask: IEsptouchTask? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.page_add_wifi, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val etName = view.findViewById<TextInputEditText>(R.id.etWifiName)
        val etSsid = view.findViewById<TextInputEditText>(R.id.etWifiSsid)
        val etPwd = view.findViewById<TextInputEditText>(R.id.etWifiPassword)
        val etBroker = view.findViewById<TextInputEditText>(R.id.etWifiBroker)
        val etTopic = view.findViewById<TextInputEditText>(R.id.etWifiTopic)
        val etCamera = view.findViewById<TextInputEditText>(R.id.etWifiCamera)
        val btnSc = view.findViewById<MaterialButton>(R.id.btnWifiSmartConfig)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnWifiSave)

        // Prefill edit mode
        editDevice?.takeIf { it.connectionType == "wifi" }?.let {
            etName.setText(it.name)
            etSsid.setText(it.ssid)
            etBroker.setText(it.brokerUri)
            etTopic.setText(it.mqttTopic)
            etCamera.setText(it.cameraUrl)
        }

        // Auto-fill SSID if permission granted
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val wm = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wm.connectionInfo?.ssid?.replace("\"", "")
            if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>") {
                etSsid.setText(ssid)
            }
        }

        btnSc.setOnClickListener {
            val ssid = etSsid.text.toString()
            val pwd = etPwd.text.toString()
            if (ssid.isBlank()) { Toast.makeText(requireContext(), "Nhập SSID trước", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            btnSc.isEnabled = false
            btnSc.text = "Đang gửi..."
            EsptouchAsyncTask(ssid, pwd, btnSc, requireContext()).execute()
        }

        btnSave.setOnClickListener {
            sheet.saveWifiDevice(
                etName.text.toString(), etSsid.text.toString(),
                etBroker.text.toString(), etTopic.text.toString(), etCamera.text.toString()
            )
        }
    }

    inner class EsptouchAsyncTask(
        private val ssid: String,
        private val pwd: String,
        private val btn: MaterialButton,
        private val ctx: Context
    ) : AsyncTask<Unit, Unit, Boolean>() {
        override fun doInBackground(vararg p0: Unit?): Boolean {
            val task = EsptouchTask(ssid, "00:00:00:00:00:00", pwd, ctx)
            val results = task.executeForResults(1)
            return results?.any { it.isSuc } == true
        }
        override fun onPostExecute(result: Boolean) {
            btn.isEnabled = true
            btn.text = "Gửi cấu hình Wi-Fi xuống ESP32"
            val msg = if (result) "✅ Cấp mạng Wi-Fi thành công!" else "❌ Không tìm thấy ESP32"
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }
}

/** MQTT tab fragment */
class AddMqttPageFragment(
    private val editDevice: Device?,
    private val sheet: AddDeviceBottomSheet
) : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.page_add_mqtt, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val etName = view.findViewById<TextInputEditText>(R.id.etMqttName)
        val etBroker = view.findViewById<TextInputEditText>(R.id.etMqttBroker)
        val etTopic = view.findViewById<TextInputEditText>(R.id.etMqttTopic)
        val etCamera = view.findViewById<TextInputEditText>(R.id.etMqttCamera)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnMqttSave)

        editDevice?.takeIf { it.connectionType == "mqtt" }?.let {
            etName.setText(it.name)
            etBroker.setText(it.brokerUri)
            etTopic.setText(it.mqttTopic)
            etCamera.setText(it.cameraUrl)
        }

        btnSave.setOnClickListener {
            sheet.saveMqttDevice(
                etName.text.toString(), etBroker.text.toString(),
                etTopic.text.toString(), etCamera.text.toString()
            )
        }
    }
}

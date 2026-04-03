package com.example.esp32

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class DeviceListActivity : AppCompatActivity() {

    private lateinit var repo: DeviceRepository
    private lateinit var adapter: DeviceAdapter
    private lateinit var rvDevices: RecyclerView
    private lateinit var llEmptyState: View
    private lateinit var fabAdd: ExtendedFloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_list)

        // Immersive status bar
        window.statusBarColor = getColor(R.color.rc_bg_deep)

        repo = DeviceRepository(this)
        rvDevices = findViewById(R.id.rvDevices)
        llEmptyState = findViewById(R.id.llEmptyState)
        fabAdd = findViewById(R.id.fabAddDevice)

        adapter = DeviceAdapter(
            context = this,
            devices = mutableListOf(),
            onConnect = { device -> openController(device) },
            onEdit = { device -> showAddSheet(editDevice = device) },
            onDelete = { device -> confirmDelete(device) }
        )

        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = adapter

        fabAdd.setOnClickListener { showAddSheet() }
        refreshDeviceList()
    }

    override fun onResume() {
        super.onResume()
        refreshDeviceList()
    }

    private fun refreshDeviceList() {
        val devices = repo.getAllDevices()
        adapter.refreshData(devices)
        if (devices.isEmpty()) {
            rvDevices.visibility = View.GONE
            llEmptyState.visibility = View.VISIBLE
        } else {
            rvDevices.visibility = View.VISIBLE
            llEmptyState.visibility = View.GONE
        }
    }

    private fun showAddSheet(editDevice: Device? = null) {
        AddDeviceBottomSheet(editDevice = editDevice, onSaved = { refreshDeviceList() })
            .show(supportFragmentManager, "add_device")
    }

    private fun confirmDelete(device: Device) {
        AlertDialog.Builder(this, R.style.AlertDialogDark)
            .setTitle("Xóa xe?")
            .setMessage("Bạn muốn xóa \"${device.name}\" không?")
            .setPositiveButton("Xóa") { _, _ ->
                repo.deleteDevice(device.id)
                refreshDeviceList()
                Toast.makeText(this, "Đã xóa", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun openController(device: Device) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("device_id", device.id)
            putExtra("device_name", device.name)
            putExtra("broker_uri", device.brokerUri)
            putExtra("mqtt_topic", device.mqttTopic)
            putExtra("camera_url", device.cameraUrl)
        }
        startActivity(intent)
    }
}

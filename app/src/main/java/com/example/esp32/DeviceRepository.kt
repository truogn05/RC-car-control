package com.example.esp32

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Repository for persisting Device profiles using SharedPreferences + Gson.
 */
class DeviceRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "saved_devices"

    fun getAllDevices(): MutableList<Device> {
        val json = prefs.getString(key, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<Device>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    fun addDevice(device: Device) {
        val list = getAllDevices()
        list.add(device)
        save(list)
    }

    fun updateDevice(updated: Device) {
        val list = getAllDevices()
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            list[idx] = updated
            save(list)
        }
    }

    fun deleteDevice(deviceId: String) {
        val list = getAllDevices()
        list.removeAll { it.id == deviceId }
        save(list)
    }

    private fun save(list: List<Device>) {
        prefs.edit().putString(key, gson.toJson(list)).apply()
    }
}

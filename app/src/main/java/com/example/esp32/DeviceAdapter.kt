package com.example.esp32

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(
    private val context: Context,
    private val devices: MutableList<Device>,
    private val onConnect: (Device) -> Unit,
    private val onEdit: (Device) -> Unit,
    private val onDelete: (Device) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvSubtitle: TextView = view.findViewById(R.id.tvDeviceSubtitle)
        val tvType: TextView = view.findViewById(R.id.tvDeviceType)
        val btnMore: ImageButton = view.findViewById(R.id.btnDeviceMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.tvName.text = device.name
        holder.tvSubtitle.text = when (device.connectionType) {
            "wifi" -> "WiFi: ${device.ssid} · ${device.brokerUri}"
            else -> device.brokerUri
        }
        holder.tvType.text = if (device.connectionType == "wifi") "WiFi" else "MQTT"
        holder.tvType.setBackgroundResource(
            if (device.connectionType == "wifi") R.drawable.bg_badge_wifi
            else R.drawable.bg_badge_mqtt
        )

        // Connect on item click
        holder.itemView.setOnClickListener { onConnect(device) }

        // "..." menu
        holder.btnMore.setOnClickListener { v ->
            val wrapper = android.view.ContextThemeWrapper(context, R.style.DarkPopupMenuStyle)
            val popup = PopupMenu(wrapper, v)
            popup.inflate(R.menu.menu_device)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_edit -> { onEdit(device); true }
                    R.id.menu_delete -> { onDelete(device); true }
                    R.id.menu_connect -> { onConnect(device); true }
                    else -> false
                }
            }
            popup.show()
        }
    }

    override fun getItemCount() = devices.size

    fun refreshData(newList: List<Device>) {
        devices.clear()
        devices.addAll(newList)
        notifyDataSetChanged()
    }
}

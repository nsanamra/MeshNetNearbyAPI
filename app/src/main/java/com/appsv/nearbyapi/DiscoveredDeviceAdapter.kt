package com.appsv.nearbyapi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.offlinechatapp.R
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo

class DiscoveredDeviceAdapter(
    private val discoveredDevices: List<Pair<String, DiscoveredEndpointInfo>>,
    private val connectClickListener: (String) -> Unit
) : RecyclerView.Adapter<DiscoveredDeviceAdapter.DeviceViewHolder>() {

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val deviceNameTextView: TextView = view.findViewById(R.id.deviceNameTextView)
        val connectButton: Button = view.findViewById(R.id.connectButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_discovered_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val (endpointId, endpointInfo) = discoveredDevices[position]
        holder.deviceNameTextView.text = endpointInfo.endpointName
        holder.connectButton.setOnClickListener {
            connectClickListener(endpointId)
        }
    }

    override fun getItemCount() = discoveredDevices.size
}

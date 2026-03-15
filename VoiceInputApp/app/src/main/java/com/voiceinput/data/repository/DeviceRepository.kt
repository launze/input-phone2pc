package com.voiceinput.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.voiceinput.data.model.Device

class DeviceRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("devices", Context.MODE_PRIVATE)

    fun savePairedDevice(device: Device) {
        prefs.edit()
            .putString("device_id", device.deviceId)
            .putString("device_name", device.deviceName)
            .putString("device_ip", device.ip)
            .putInt("device_port", device.port)
            .apply()
    }

    fun getPairedDevice(): Device? {
        val deviceId = prefs.getString("device_id", null) ?: return null
        val deviceName = prefs.getString("device_name", null) ?: return null
        val deviceIp = prefs.getString("device_ip", null) ?: return null
        val devicePort = prefs.getInt("device_port", 0)

        return Device(
            deviceId = deviceId,
            deviceName = deviceName,
            ip = deviceIp,
            port = devicePort,
            version = "1.0.0"
        )
    }

    fun clearPairedDevice() {
        prefs.edit().clear().apply()
    }
}

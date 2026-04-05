package com.voiceinput.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.voiceinput.data.model.ServerConfig

class ConfigManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "voice_input_config",
        Context.MODE_PRIVATE
    )
    private val gson = Gson()

    companion object {
        private const val KEY_SERVER_CONFIG = "server_config"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
        private const val KEY_PAIRED_DEVICES = "paired_devices"
        private const val KEY_LAST_TARGET_DEVICE = "last_target_device"
    }

    // 保存服务器配置
    fun saveServerConfig(config: ServerConfig) {
        val json = gson.toJson(config)
        prefs.edit().putString(KEY_SERVER_CONFIG, json).apply()
    }

    // 加载服务器配置
    fun loadServerConfig(): ServerConfig {
        val json = prefs.getString(KEY_SERVER_CONFIG, null)
        return if (json != null) {
            gson.fromJson(json, ServerConfig::class.java)
        } else {
            ServerConfig() // 返回默认配置
        }
    }

    // 保存服务器地址
    fun saveServerUrl(url: String) {
        val config = loadServerConfig()
        val newConfig = config.copy(serverUrl = url)
        saveServerConfig(newConfig)
    }

    // 获取服务器地址
    fun getServerUrl(): String {
        return loadServerConfig().serverUrl
    }

    // 保存服务器模式状态
    fun saveServerModeEnabled(enabled: Boolean) {
        val config = loadServerConfig()
        val newConfig = config.copy(enabled = enabled)
        saveServerConfig(newConfig)
    }

    // 获取服务器模式状态
    fun isServerModeEnabled(): Boolean {
        return loadServerConfig().enabled
    }

    // 保存设备ID
    fun saveDeviceId(deviceId: String) {
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    // 获取设备ID
    fun getDeviceId(): String {
        return prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val newId = java.util.UUID.randomUUID().toString()
            saveDeviceId(newId)
            newId
        }
    }

    // 保存设备名称
    fun saveDeviceName(name: String) {
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
    }

    // 获取设备名称
    fun getDeviceName(): String {
        return prefs.getString(KEY_DEVICE_NAME, null) ?: run {
            val name = android.os.Build.MODEL
            saveDeviceName(name)
            name
        }
    }

    // 保存通知转发状态
    fun saveNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_ENABLED, enabled).apply()
    }

    // 获取通知转发状态
    fun isNotificationEnabled(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATION_ENABLED, false)
    }

    // 保存配对设备
    fun savePairedDevice(deviceId: String, deviceName: String, deviceType: String, localIp: String = "", localPort: Int = 0) {
        val devices = getPairedDevices().toMutableMap()
        devices[deviceId] = PairedDevice(deviceId, deviceName, deviceType, localIp, localPort)
        prefs.edit().putString(KEY_PAIRED_DEVICES, gson.toJson(devices)).apply()
    }

    // 获取所有配对设备
    fun getPairedDevices(): Map<String, PairedDevice> {
        val json = prefs.getString(KEY_PAIRED_DEVICES, null) ?: return emptyMap()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, PairedDevice>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // 移除配对设备
    fun removePairedDevice(deviceId: String) {
        val devices = getPairedDevices().toMutableMap()
        devices.remove(deviceId)
        prefs.edit().putString(KEY_PAIRED_DEVICES, gson.toJson(devices)).apply()

        val lastTarget = getLastTargetDevice()
        if (lastTarget?.deviceId == deviceId) {
            clearLastTargetDevice()
        }
    }

    fun saveLastTargetDevice(deviceId: String, deviceName: String) {
        val selected = LastTargetDevice(deviceId = deviceId, deviceName = deviceName)
        prefs.edit().putString(KEY_LAST_TARGET_DEVICE, gson.toJson(selected)).apply()
    }

    fun getLastTargetDevice(): LastTargetDevice? {
        val json = prefs.getString(KEY_LAST_TARGET_DEVICE, null) ?: return null
        return try {
            gson.fromJson(json, LastTargetDevice::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun clearLastTargetDevice() {
        prefs.edit().remove(KEY_LAST_TARGET_DEVICE).apply()
    }

    // 清除所有配置
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    data class PairedDevice(
        val deviceId: String,
        val deviceName: String,
        val deviceType: String,
        val localIp: String = "",
        val localPort: Int = 0
    )

    data class LastTargetDevice(
        val deviceId: String,
        val deviceName: String
    )
}

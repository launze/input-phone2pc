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
        private const val KEY_NOTIFICATION_INCLUDE_PACKAGES = "notification_include_packages"
        private const val KEY_NOTIFICATION_EXCLUDE_PACKAGES = "notification_exclude_packages"
        private const val KEY_NOTIFICATION_EXCLUDE_KEYWORDS = "notification_exclude_keywords"
        private const val KEY_NOTIFICATION_FILTER_EMPTY = "notification_filter_empty"
        private const val KEY_NOTIFICATION_FILTER_ONGOING = "notification_filter_ongoing"
        private const val KEY_NOTIFICATION_FILTER_LOW_IMPORTANCE = "notification_filter_low_importance"
        private const val KEY_NOTIFICATION_ALLOW_SENSITIVE_APPS = "notification_allow_sensitive_apps"
        private const val KEY_NOTIFICATION_REDACT_SENSITIVE_CONTENT = "notification_redact_sensitive_content"
        private const val KEY_NOTIFICATION_REDACT_KEYWORDS = "notification_redact_keywords"
        private const val KEY_NOTIFICATION_FORWARD_MODE = "notification_forward_mode"
        private const val KEY_FOREGROUND_CONNECTION_STATUS = "foreground_connection_status"
        private const val KEY_PAIRED_DEVICES = "paired_devices"
        private const val KEY_LAST_TARGET_DEVICE = "last_target_device"
        private const val KEY_INPUT_DRAFT = "input_draft"
        private val LEGACY_SERVER_URLS = setOf(
            "wss://nas.smarthome2020.top:7070",
            "wss://ha.wwszxc.tax:16908"
        )
        private const val DEFAULT_SERVER_URL = "wss://8.153.163.104:16908"
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
            val config = gson.fromJson(json, ServerConfig::class.java)
            if (config.serverUrl in LEGACY_SERVER_URLS) {
                val migrated = config.copy(serverUrl = DEFAULT_SERVER_URL)
                saveServerConfig(migrated)
                migrated
            } else {
                config
            }
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

    fun saveNotificationIncludePackages(value: String) {
        prefs.edit().putString(KEY_NOTIFICATION_INCLUDE_PACKAGES, value).apply()
    }

    fun getNotificationIncludePackages(): String {
        return prefs.getString(KEY_NOTIFICATION_INCLUDE_PACKAGES, "") ?: ""
    }

    fun saveNotificationExcludePackages(value: String) {
        prefs.edit().putString(KEY_NOTIFICATION_EXCLUDE_PACKAGES, value).apply()
    }

    fun getNotificationExcludePackages(): String {
        return prefs.getString(KEY_NOTIFICATION_EXCLUDE_PACKAGES, "") ?: ""
    }

    fun saveNotificationExcludeKeywords(value: String) {
        prefs.edit().putString(KEY_NOTIFICATION_EXCLUDE_KEYWORDS, value).apply()
    }

    fun getNotificationExcludeKeywords(): String {
        return prefs.getString(KEY_NOTIFICATION_EXCLUDE_KEYWORDS, "") ?: ""
    }

    fun saveNotificationFilterEmpty(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_FILTER_EMPTY, enabled).apply()
    }

    fun shouldFilterEmptyNotifications(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATION_FILTER_EMPTY, true)
    }

    fun saveNotificationFilterOngoing(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_FILTER_ONGOING, enabled).apply()
    }

    fun shouldFilterOngoingNotifications(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATION_FILTER_ONGOING, true)
    }

    fun saveNotificationFilterLowImportance(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_FILTER_LOW_IMPORTANCE, enabled).apply()
    }

    fun shouldFilterLowImportanceNotifications(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATION_FILTER_LOW_IMPORTANCE, false)
    }

    fun saveNotificationAllowSensitiveApps(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_ALLOW_SENSITIVE_APPS, enabled).apply()
    }

    fun allowSensitiveNotificationApps(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATION_ALLOW_SENSITIVE_APPS, false)
    }

    fun saveNotificationRedactSensitiveContent(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_REDACT_SENSITIVE_CONTENT, enabled).apply()
    }

    fun shouldRedactSensitiveNotificationContent(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATION_REDACT_SENSITIVE_CONTENT, true)
    }

    fun saveNotificationRedactKeywords(value: String) {
        prefs.edit().putString(KEY_NOTIFICATION_REDACT_KEYWORDS, value).apply()
    }

    fun getNotificationRedactKeywords(): String {
        return prefs.getString(KEY_NOTIFICATION_REDACT_KEYWORDS, "") ?: ""
    }

    fun redactNotificationContent(value: String): String {
        if (!shouldRedactSensitiveNotificationContent() || value.isBlank()) {
            return value
        }
        return NotificationFilterRules.redactSensitiveContent(
            value = value,
            customKeywords = getNotificationRedactKeywords()
        )
    }

    fun saveNotificationForwardMode(mode: String) {
        val normalized = when (mode) {
            "history_only", "pc_center", "clipboard", "ai_silent" -> mode
            else -> "pc_center"
        }
        prefs.edit().putString(KEY_NOTIFICATION_FORWARD_MODE, normalized).apply()
    }

    fun getNotificationForwardMode(): String {
        return prefs.getString(KEY_NOTIFICATION_FORWARD_MODE, "pc_center") ?: "pc_center"
    }

    fun saveForegroundConnectionStatus(connected: Boolean, deviceName: String) {
        val status = ForegroundConnectionStatus(
            connected = connected,
            deviceName = deviceName,
            updatedAt = System.currentTimeMillis()
        )
        prefs.edit().putString(KEY_FOREGROUND_CONNECTION_STATUS, gson.toJson(status)).apply()
    }

    fun getForegroundConnectionStatus(): ForegroundConnectionStatus {
        val json = prefs.getString(KEY_FOREGROUND_CONNECTION_STATUS, null)
        return try {
            if (json.isNullOrBlank()) {
                ForegroundConnectionStatus()
            } else {
                gson.fromJson(json, ForegroundConnectionStatus::class.java) ?: ForegroundConnectionStatus()
            }
        } catch (e: Exception) {
            ForegroundConnectionStatus()
        }
    }

    fun shouldForwardNotification(
        appPackage: String,
        title: String,
        text: String,
        isOngoing: Boolean = false,
        importance: Int = android.app.NotificationManager.IMPORTANCE_DEFAULT
    ): Boolean {
        return NotificationFilterRules.shouldForwardNotification(
            appPackage = appPackage,
            title = title,
            text = text,
            isOngoing = isOngoing,
            importance = importance,
            options = NotificationFilterRules.ForwardOptions(
                includePackages = getNotificationIncludePackages(),
                excludePackages = getNotificationExcludePackages(),
                excludeKeywords = getNotificationExcludeKeywords(),
                filterEmpty = shouldFilterEmptyNotifications(),
                filterOngoing = shouldFilterOngoingNotifications(),
                filterLowImportance = shouldFilterLowImportanceNotifications(),
                lowImportanceThreshold = android.app.NotificationManager.IMPORTANCE_LOW,
                allowSensitiveApps = allowSensitiveNotificationApps()
            )
        )
    }

    // 保存配对设备
    fun savePairedDevice(
        deviceId: String,
        deviceName: String,
        deviceType: String,
        localIp: String = "",
        localPort: Int = 0,
        lastConnectedAt: Long? = null
    ) {
        val devices = getPairedDevices().toMutableMap()
        val existing = devices[deviceId]
        devices[deviceId] = PairedDevice(
            deviceId = deviceId,
            deviceName = deviceName.ifBlank { existing?.deviceName.orEmpty() },
            deviceType = deviceType.ifBlank { existing?.deviceType ?: "desktop" },
            localIp = localIp.ifBlank { existing?.localIp.orEmpty() },
            localPort = if (localPort > 0) localPort else existing?.localPort ?: 0,
            lastConnectedAt = lastConnectedAt ?: existing?.lastConnectedAt ?: 0L
        )
        prefs.edit().putString(KEY_PAIRED_DEVICES, gson.toJson(devices)).apply()
    }

    fun markPairedDeviceConnected(deviceId: String, connectedAt: Long = System.currentTimeMillis()) {
        val device = getPairedDevices()[deviceId] ?: return
        savePairedDevice(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            deviceType = device.deviceType,
            localIp = device.localIp,
            localPort = device.localPort,
            lastConnectedAt = connectedAt
        )
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

    fun saveInputDraft(text: String) {
        prefs.edit().putString(KEY_INPUT_DRAFT, text).apply()
    }

    fun getInputDraft(): String {
        return prefs.getString(KEY_INPUT_DRAFT, "") ?: ""
    }

    fun clearInputDraft() {
        prefs.edit().remove(KEY_INPUT_DRAFT).apply()
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
        val localPort: Int = 0,
        val lastConnectedAt: Long = 0L
    )

    data class LastTargetDevice(
        val deviceId: String,
        val deviceName: String
    )

    data class ForegroundConnectionStatus(
        val connected: Boolean = false,
        val deviceName: String = "",
        val updatedAt: Long = 0L
    )
}

package com.voiceinput.data

import com.voiceinput.data.model.ServerDeviceInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PairedDeviceUiState(
    val deviceId: String,
    val deviceName: String,
    val online: Boolean,
    val isDefault: Boolean,
    val routeText: String,
    val statusText: String,
    val lastConnectedText: String,
    val badgeText: String,
    val defaultActionText: String,
    val defaultActionEnabled: Boolean,
    val reconnectActionText: String,
    val reconnectFeedbackText: String
) {
    companion object {
        fun from(
            device: ConfigManager.PairedDevice,
            serverDevice: ServerDeviceInfo?,
            defaultDeviceId: String?,
            nowMillis: Long = System.currentTimeMillis(),
            absoluteTimeFormatter: (Long) -> String = { timestamp ->
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
            }
        ): PairedDeviceUiState {
            val online = serverDevice?.online == true
            val route = if (device.localIp.isNotBlank() && device.localPort > 0) {
                "局域网 ${device.localIp}:${device.localPort}"
            } else {
                "服务器中转"
            }
            val isDefault = defaultDeviceId == device.deviceId
            return PairedDeviceUiState(
                deviceId = device.deviceId,
                deviceName = device.deviceName,
                online = online,
                isDefault = isDefault,
                routeText = if (online) "$route · 最近在线" else route,
                statusText = if (online) {
                    "服务器在线"
                } else {
                    "当前未在线，可继续离线暂存或稍后重连"
                },
                lastConnectedText = formatLastConnectedAt(
                    timestamp = device.lastConnectedAt,
                    nowMillis = nowMillis,
                    absoluteTimeFormatter = absoluteTimeFormatter
                ),
                badgeText = if (online) "在线" else "离线",
                defaultActionText = if (isDefault) "当前默认" else "设为默认",
                defaultActionEnabled = !isDefault,
                reconnectActionText = "一键重连",
                reconnectFeedbackText = if (online) {
                    "已重新选择：${device.deviceName}"
                } else {
                    "已设为目标电脑，正在刷新在线状态"
                }
            )
        }

        fun formatLastConnectedAt(
            timestamp: Long,
            nowMillis: Long = System.currentTimeMillis(),
            absoluteTimeFormatter: (Long) -> String = { value ->
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(value))
            }
        ): String {
            if (timestamp <= 0L) return "暂无记录"
            val diff = nowMillis - timestamp
            return when {
                diff < 60_000 -> "刚刚"
                diff < 3_600_000 -> "${diff / 60_000} 分钟前"
                diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
                else -> absoluteTimeFormatter(timestamp)
            }
        }
    }
}

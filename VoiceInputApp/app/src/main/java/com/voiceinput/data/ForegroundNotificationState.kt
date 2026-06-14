package com.voiceinput.data

data class ForegroundNotificationUiState(
    val statusText: String,
    val toggleActionLabel: String,
    val forwardingEnabled: Boolean
)

object ForegroundNotificationState {
    fun from(
        forwardingEnabled: Boolean,
        connected: Boolean,
        connectedDeviceName: String,
        targetDeviceName: String?
    ): ForegroundNotificationUiState {
        val connectedName = connectedDeviceName.trim()
        val targetName = targetDeviceName?.trim().orEmpty()
        val statusText = when {
            !forwardingEnabled -> "通知转发已暂停，点击继续"
            connected && connectedName.isNotEmpty() -> "已连接：$connectedName"
            targetName.isNotEmpty() -> "电脑离线，通知和输入会暂存：$targetName"
            else -> "未选择电脑，输入和通知会先保留"
        }
        return ForegroundNotificationUiState(
            statusText = statusText,
            toggleActionLabel = if (forwardingEnabled) "暂停通知转发" else "继续通知转发",
            forwardingEnabled = forwardingEnabled
        )
    }
}

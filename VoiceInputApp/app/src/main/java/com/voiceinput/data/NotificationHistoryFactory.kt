package com.voiceinput.data

import com.voiceinput.data.model.HistoryItem
import com.voiceinput.data.model.NotificationData
import com.voiceinput.data.model.SyncStatus
import com.voiceinput.service.NotificationForwarding

data class NotificationTargetDevice(
    val deviceId: String,
    val deviceName: String
)

object NotificationHistoryFactory {
    fun createHistoryItem(
        notification: NotificationData,
        target: NotificationTargetDevice?,
        forwardMode: String,
        now: Long = System.currentTimeMillis()
    ): HistoryItem {
        return HistoryItem(
            id = buildHistoryId(notification),
            text = NotificationForwarding.historyContent(notification),
            timestamp = notification.timestamp.takeIf { it > 0 } ?: now,
            targetDeviceId = target?.deviceId.orEmpty(),
            targetDeviceName = target?.deviceName.orEmpty(),
            contentType = "notification",
            syncStatus = SyncStatus.PENDING,
            channel = channelForForwardMode(forwardMode),
            errorMessage = null,
            sourceApp = notification.appName,
            sourcePackage = notification.appPackage,
            metadata = NotificationForwarding.historyMetadata(notification.copy(forwardMode = forwardMode))
        )
    }

    fun channelForForwardMode(forwardMode: String): String {
        return if (forwardMode == "history_only") "notification" else "server"
    }

    fun buildHistoryId(notification: NotificationData): String {
        val stableKey = notification.notificationKey.ifBlank {
            "${notification.appPackage}:${notification.timestamp}:${notification.title}:${notification.text}"
        }
        return "notification-${stableKey.hashCode()}-${notification.timestamp}"
    }
}

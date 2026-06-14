package com.voiceinput.service

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.voiceinput.data.ConfigManager
import com.voiceinput.data.NotificationHistoryFactory
import com.voiceinput.data.NotificationTargetDevice
import com.voiceinput.data.RelayAckState
import com.voiceinput.data.model.HistoryItem
import com.voiceinput.data.model.NotificationData
import com.voiceinput.data.model.ServerConfig
import com.voiceinput.data.model.SyncStatus
import com.voiceinput.data.repository.HistoryRepository
import com.voiceinput.network.ServerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object NotificationForwarding {
    fun safeNotification(configManager: ConfigManager, notification: NotificationData): NotificationData {
        return notification.copy(
            title = configManager.redactNotificationContent(notification.title),
            text = configManager.redactNotificationContent(notification.text),
            subText = configManager.redactNotificationContent(notification.subText),
            bigText = configManager.redactNotificationContent(notification.bigText),
            conversationTitle = configManager.redactNotificationContent(notification.conversationTitle)
        )
    }

    fun historyContent(notification: NotificationData): String {
        val safeTitle = notification.title.trim()
        val safeText = notification.text.trim()
        return buildString {
            append("[通知] ")
            append(notification.appName)
            if (safeTitle.isNotBlank()) {
                append(" - ")
                append(safeTitle)
            }
            if (safeText.isNotBlank()) {
                append("\n")
                append(safeText)
            }
        }
    }

    fun relayPayload(notification: NotificationData, forwardMode: String): JsonObject {
        return JsonObject().apply {
            addProperty("type", "NOTIFICATION_INPUT")
            addProperty("app_name", notification.appName)
            addProperty("app_package", notification.appPackage)
            addProperty("title", notification.title)
            addProperty("text", notification.text)
            addProperty("timestamp", notification.timestamp)
            addProperty("notification_key", notification.notificationKey)
            addProperty("channel_id", notification.channelId)
            addProperty("group_key", notification.groupKey)
            addProperty("category", notification.category)
            addProperty("sub_text", notification.subText)
            addProperty("big_text", notification.bigText)
            addProperty("conversation_title", notification.conversationTitle)
            addProperty("post_time", notification.postTime)
            addProperty("is_ongoing", notification.isOngoing)
            addProperty("is_clearable", notification.isClearable)
            addProperty("importance", notification.importance)
            addProperty("forward_mode", forwardMode)
            addProperty("silent", forwardMode == "ai_silent")
            addProperty("copy_to_clipboard", forwardMode == "clipboard")
            notification.icon?.let { addProperty("icon", it) }
        }
    }

    fun historyMetadata(notification: NotificationData): String {
        return JsonObject().apply {
            addProperty("app_name", notification.appName)
            addProperty("app_package", notification.appPackage)
            addProperty("title", notification.title)
            addProperty("text", notification.text)
            addProperty("notification_key", notification.notificationKey)
            addProperty("channel_id", notification.channelId)
            addProperty("group_key", notification.groupKey)
            addProperty("category", notification.category)
            addProperty("sub_text", notification.subText)
            addProperty("big_text", notification.bigText)
            addProperty("conversation_title", notification.conversationTitle)
            addProperty("post_time", notification.postTime)
            addProperty("is_ongoing", notification.isOngoing)
            addProperty("is_clearable", notification.isClearable)
            addProperty("importance", notification.importance)
            addProperty("forward_mode", notification.forwardMode)
        }.toString()
    }
}

class BackgroundNotificationForwarder(context: Context) {
    private val appContext = context.applicationContext
    private val configManager = ConfigManager(appContext)
    private val historyRepository = HistoryRepository(appContext)
    private val serverConnection = ServerConnection(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingRelayHistoryIds = ArrayDeque<String>()

    init {
        serverConnection.onRelayStored = { messageId, toDeviceId, storedAt, queued, clientMessageId ->
            val historyItemId = clientMessageId
                ?.takeIf { it.isNotBlank() }
                ?: synchronized(pendingRelayHistoryIds) {
                    if (pendingRelayHistoryIds.isEmpty()) null else pendingRelayHistoryIds.removeFirst()
                }
            if (!clientMessageId.isNullOrBlank()) {
                synchronized(pendingRelayHistoryIds) {
                    pendingRelayHistoryIds.remove(clientMessageId)
                }
            }
            historyItemId?.let {
                scope.launch {
                    historyRepository.updateHistoryItem(it) { item ->
                        RelayAckState.applyStoredAck(
                            item = item,
                            messageId = messageId,
                            toDeviceId = toDeviceId,
                            storedAt = storedAt,
                            queued = queued
                        )
                    }
                }
            }
        }

        serverConnection.onRelayMessageReceived = { fromDeviceId, payload ->
            if (payload.get("type")?.asString == "INPUT_ACK") {
                val serverMessageId = payload.get("server_message_id")?.asString
                val clientMessageId = payload.get("client_message_id")?.takeIf { !it.isJsonNull }?.asString
                if (!serverMessageId.isNullOrBlank()) {
                    val success = payload.get("success")?.asBoolean ?: false
                    scope.launch {
                        val update: (HistoryItem) -> HistoryItem = { item ->
                            RelayAckState.applyInputAck(
                                item = item,
                                serverMessageId = serverMessageId,
                                fromDeviceId = fromDeviceId,
                                success = success,
                                now = System.currentTimeMillis()
                            )
                        }
                        if (!clientMessageId.isNullOrBlank()) {
                            historyRepository.updateHistoryItem(clientMessageId, update)
                        } else {
                            historyRepository.updateHistoryByServerMessageId(serverMessageId, update)
                        }
                    }
                }
            }
        }
    }

    suspend fun forwardOrStore(notification: NotificationData) {
        if (!configManager.isNotificationEnabled()) {
            return
        }

        val title = notification.title.trim()
        val text = notification.text.trim()
        if (!configManager.shouldForwardNotification(
                appPackage = notification.appPackage,
                title = title,
                text = text,
                isOngoing = notification.isOngoing,
                importance = notification.importance
            )
        ) {
            Log.d(TAG, "通知已按规则过滤: ${notification.appName}")
            return
        }

        val safeNotification = NotificationForwarding.safeNotification(configManager, notification)
        val target = resolveTargetDevice()
        val forwardMode = configManager.getNotificationForwardMode()
        val item = NotificationHistoryFactory.createHistoryItem(
            notification = safeNotification,
            target = target?.let {
                NotificationTargetDevice(
                    deviceId = it.deviceId,
                    deviceName = it.deviceName
                )
            },
            forwardMode = forwardMode
        )
        historyRepository.upsertHistoryItem(item)

        if (forwardMode == "history_only") {
            markStored(item.id, "通知仅保存到手机历史")
            Log.d(TAG, "通知仅保存到手机历史: ${safeNotification.appName}")
            return
        }

        if (target == null) {
            markStored(item.id, "未选择目标电脑，通知已在手机暂存")
            Log.d(TAG, "未选择目标电脑，通知已暂存: ${safeNotification.appName}")
            return
        }

        if (!ensureServerConnected()) {
            markStored(item.id, "电脑离线，通知已在手机暂存")
            Log.d(TAG, "后台通知转发未连接服务器，已暂存: ${safeNotification.appName}")
            return
        }

        synchronized(pendingRelayHistoryIds) {
            pendingRelayHistoryIds.addLast(item.id)
        }
        val relayPayload = NotificationForwarding.relayPayload(safeNotification, forwardMode).apply {
            addProperty("client_message_id", item.id)
        }
        val sent = serverConnection.sendRelayMessage(
            target.deviceId,
            relayPayload
        )
        if (sent) {
            Log.d(TAG, "后台通知已提交服务器中转: ${safeNotification.appName}")
        } else {
            synchronized(pendingRelayHistoryIds) {
                pendingRelayHistoryIds.remove(item.id)
            }
            historyRepository.updateHistoryItem(item.id) { history ->
                history.copy(
                    syncStatus = SyncStatus.FAILED,
                    errorMessage = "通知发送失败，未能提交到服务器"
                )
            }
            Log.d(TAG, "后台通知提交服务器失败: ${safeNotification.appName}")
        }
    }

    fun release() {
        serverConnection.release()
        scope.cancel()
    }

    private suspend fun ensureServerConnected(): Boolean {
        if (serverConnection.isConnected()) {
            return true
        }

        val config = configManager.loadServerConfig()
        if (config.serverUrl.isBlank()) {
            return false
        }

        val deviceId = configManager.getDeviceId()
        val deviceName = configManager.getDeviceName()
        serverConnection.connect(
            ServerConfig(
                enabled = true,
                serverUrl = config.serverUrl,
                deviceId = deviceId,
                sessionId = config.sessionId
            ),
            deviceId,
            deviceName
        )

        repeat(50) {
            if (serverConnection.isConnected()) {
                serverConnection.requestDeviceList()
                return true
            }
            delay(200)
        }
        return serverConnection.isConnected()
    }

    private suspend fun markStored(itemId: String, reason: String) {
        historyRepository.updateHistoryItem(itemId) { history ->
            history.copy(
                syncStatus = SyncStatus.STORED,
                errorMessage = reason
            )
        }
    }

    private fun resolveTargetDevice(): ConfigManager.LastTargetDevice? {
        configManager.getLastTargetDevice()?.let { return it }
        val firstPaired = configManager.getPairedDevices().values.firstOrNull() ?: return null
        return ConfigManager.LastTargetDevice(
            deviceId = firstPaired.deviceId,
            deviceName = firstPaired.deviceName
        )
    }

    companion object {
        private const val TAG = "BackgroundNotification"
    }
}

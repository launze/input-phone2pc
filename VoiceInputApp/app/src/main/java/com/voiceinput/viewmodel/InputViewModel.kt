package com.voiceinput.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceinput.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.voiceinput.data.AiAssistantFormatter
import com.voiceinput.data.AiAssistantMessage
import com.voiceinput.data.AiAssistantReducer
import com.voiceinput.data.AiAssistantState
import com.voiceinput.data.AiAssistantToolEvent
import com.voiceinput.data.ConfigManager
import com.voiceinput.data.HistoryExportFormatter
import com.voiceinput.data.InputHistoryScope
import com.voiceinput.data.RelayAckState
import com.voiceinput.data.model.ClipboardImagePayload
import com.voiceinput.data.model.ClipboardFilePayload
import com.voiceinput.data.model.Device
import com.voiceinput.data.model.HistoryItem
import com.voiceinput.data.model.NotificationData
import com.voiceinput.data.model.ServerConfig
import com.voiceinput.data.model.ServerDeviceInfo
import com.voiceinput.data.model.SyncStatus
import com.voiceinput.data.repository.HistoryRepository
import com.voiceinput.network.NetworkManager
import com.voiceinput.network.ServerConnection
import com.voiceinput.service.NotificationForwarding
import com.voiceinput.service.NotificationListenerService
import com.voiceinput.service.VoiceInputForegroundService
import com.voiceinput.util.ImageTransferCodec
import com.voiceinput.util.FileTransferCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InputViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "InputViewModel"
        private const val HISTORY_PAGE_SIZE = 40
    }

    private val gson = Gson()
    private val configManager = ConfigManager(application.applicationContext)
    private val historyRepository = HistoryRepository(application.applicationContext)
    private val networkManager = NetworkManager()
    private val serverConnection = ServerConnection(application.applicationContext)
    private val pendingRelayHistoryIds = ArrayDeque<String>()

    val serverConnectionState: StateFlow<ServerConnection.ConnectionState> =
        serverConnection.connectionState
    val serverInfo: StateFlow<ServerConnection.ServerInfo?> = serverConnection.serverInfo
    val serverDevices: StateFlow<List<ServerDeviceInfo>> = serverConnection.serverDevices

    private val _connectionStatus =
        MutableStateFlow(ConnectionStatus(connected = false, deviceName = ""))
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _sendAvailable = MutableStateFlow(false)
    val sendAvailable: StateFlow<Boolean> = _sendAvailable.asStateFlow()

    private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val uiMessages: SharedFlow<String> = _uiMessages.asSharedFlow()

    private val _historyItems = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyItems: StateFlow<List<HistoryItem>> = _historyItems.asStateFlow()

    private val _visibleHistoryItems = MutableStateFlow<List<HistoryItem>>(emptyList())
    val visibleHistoryItems: StateFlow<List<HistoryItem>> = _visibleHistoryItems.asStateFlow()

    private val _historyHasMore = MutableStateFlow(false)
    val historyHasMore: StateFlow<Boolean> = _historyHasMore.asStateFlow()

    private val _historyLoadingMore = MutableStateFlow(false)
    val historyLoadingMore: StateFlow<Boolean> = _historyLoadingMore.asStateFlow()

    private val _historyInitialLoaded = MutableStateFlow(false)
    val historyInitialLoaded: StateFlow<Boolean> = _historyInitialLoaded.asStateFlow()

    private val _aiAssistantState = MutableStateFlow(AiAssistantState())
    val aiAssistantState: StateFlow<AiAssistantState> = _aiAssistantState.asStateFlow()

    private val _inputText = MutableStateFlow(configManager.getInputDraft())
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<Device>>(emptyList())
    val discoveredDevices: StateFlow<List<Device>> = _discoveredDevices.asStateFlow()

    private val _connectionLog = MutableStateFlow<List<String>>(emptyList())
    val connectionLog: StateFlow<List<String>> = _connectionLog.asStateFlow()

    private val _pairedDevices =
        MutableStateFlow<Map<String, ConfigManager.PairedDevice>>(emptyMap())
    val pairedDevices: StateFlow<Map<String, ConfigManager.PairedDevice>> = _pairedDevices.asStateFlow()

    private val _lastTargetDevice =
        MutableStateFlow<ConfigManager.LastTargetDevice?>(configManager.getLastTargetDevice())
    val lastTargetDevice: StateFlow<ConfigManager.LastTargetDevice?> = _lastTargetDevice.asStateFlow()

    private var relayTargetDeviceId: String? = null
    private var relayTargetDeviceName: String? = null
    private var pendingScannedPair: PendingScannedPair? = null
    private var heartbeatJob: Job? = null
    private var deviceListRefreshJob: Job? = null
    private var pendingAiRequestId: String? = null

    init {
        refreshPairedDevices()
        restoreSelectedRelayDevice()
        updateSendAvailability()

        viewModelScope.launch {
            loadInitialVisibleHistory(force = true)
        }

        viewModelScope.launch {
            serverConnection.connectionState.collect {
                syncSelectedRelayDeviceStatus()
                updateSendAvailability()
            }
        }

        viewModelScope.launch {
            serverConnection.serverDevices.collect {
                syncSelectedRelayDeviceStatus()
                updateSendAvailability()
            }
        }

        viewModelScope.launch {
            networkManager.isConnected.collect {
                updateSendAvailability()
            }
        }

        viewModelScope.launch {
            connectionStatus.collect { status ->
                configManager.saveForegroundConnectionStatus(status.connected, status.deviceName)
                if (configManager.isNotificationEnabled()) {
                    VoiceInputForegroundService.start(application.applicationContext)
                }
            }
        }

        registerServerCallbacks()
        registerNotificationCallbacks()

        if (configManager.isServerModeEnabled()) {
            val url = configManager.getServerUrl()
            if (url.isNotBlank()) {
                viewModelScope.launch {
                    delay(1000)
                    addLog("自动连接服务器...")
                    connectToServer(url)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        NotificationListenerService.onNotificationReceived = null
    }

    private fun registerNotificationCallbacks() {
        NotificationListenerService.onNotificationReceived = { notification ->
            handleNotificationReceived(notification)
        }
    }

    private fun handleNotificationReceived(notification: NotificationData) {
        if (!configManager.isNotificationEnabled()) {
            return
        }

        viewModelScope.launch {
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
                addLog("通知已按规则过滤: ${notification.appName}")
                return@launch
            }

            val safeNotification = NotificationForwarding.safeNotification(configManager, notification)
            val forwardMode = configManager.getNotificationForwardMode()
            val item = createHistoryItem(
                text = NotificationForwarding.historyContent(safeNotification),
                targetDeviceId = relayTargetDeviceId.orEmpty(),
                targetDeviceName = relayTargetDeviceName.orEmpty(),
                contentType = "notification",
                channel = "notification",
                syncStatus = SyncStatus.PENDING,
                sourceApp = safeNotification.appName,
                sourcePackage = safeNotification.appPackage,
                metadata = NotificationForwarding.historyMetadata(
                    safeNotification.copy(forwardMode = forwardMode)
                )
            )
            addHistoryItem(item)

            if (forwardMode == "history_only") {
                updateHistoryItem(item.id) { history ->
                    history.copy(
                        syncStatus = SyncStatus.STORED,
                        errorMessage = "通知仅保存到手机历史"
                    )
                }
                addLog("通知仅保存到手机历史: ${safeNotification.appName}")
                _uiMessages.tryEmit("已保存通知：${safeNotification.appName}")
                return@launch
            }

            when {
                relayTargetDeviceId != null && serverConnection.isConnected() ->
                    sendNotificationViaRelay(safeNotification, item.id, forwardMode)

                networkManager.isConnected.value ->
                    sendNotificationViaLan(safeNotification, item.id, forwardMode)

                else -> {
                    updateHistoryItem(item.id) { history ->
                        history.copy(
                            syncStatus = SyncStatus.STORED,
                            errorMessage = "电脑未连接，已在手机暂存"
                        )
                    }
                    addLog("已暂存通知，电脑连接后可继续同步: ${safeNotification.appName}")
                    _uiMessages.tryEmit("已记录通知：${safeNotification.appName}")
                }
            }
        }
    }

    private fun registerServerCallbacks() {
        serverConnection.onRelayMessageReceived = { fromDeviceId, payload ->
            Log.d(TAG, "Relay message from $fromDeviceId: $payload")
            when (payload.get("type")?.asString) {
                "INPUT_ACK" -> handleRelayAck(fromDeviceId, payload)
                "AI_ASSISTANT_DELTA" -> handleAiAssistantDelta(payload)
                "AI_ASSISTANT_EVENT" -> handleAiAssistantEvent(payload)
                "AI_ASSISTANT_RESPONSE" -> handleAiAssistantResponse(fromDeviceId, payload)
            }
        }

        serverConnection.onRelayStored = { messageId, toDeviceId, storedAt, queued, clientMessageId ->
            viewModelScope.launch {
                val targetName =
                    relayTargetDeviceName ?: pairedDevices.value[toDeviceId]?.deviceName ?: toDeviceId
                clientMessageId?.takeIf { it.isNotBlank() }?.let {
                    pendingRelayHistoryIds.remove(it)
                }
                val localHistoryId = clientMessageId
                    ?.takeIf { it.isNotBlank() }
                    ?: takePendingRelayHistoryId()
                    ?: _visibleHistoryItems.value.lastOrNull {
                        it.channel == "server" &&
                            it.targetDeviceId == toDeviceId &&
                            it.serverMessageId == null &&
                            (it.syncStatus == SyncStatus.PENDING || it.syncStatus == SyncStatus.STORED)
                    }?.id
                    ?: _historyItems.value.lastOrNull {
                        it.channel == "server" &&
                            it.targetDeviceId == toDeviceId &&
                            it.serverMessageId == null &&
                            (it.syncStatus == SyncStatus.PENDING || it.syncStatus == SyncStatus.STORED)
                    }?.id

                if (localHistoryId != null) {
                    updateHistoryItem(localHistoryId) { item ->
                        RelayAckState.applyStoredAck(
                            item = item,
                            messageId = messageId,
                            toDeviceId = toDeviceId,
                            storedAt = storedAt,
                            queued = queued
                        )
                    }
                }

                if (queued) {
                    addLog("电脑离线，消息已暂存到服务器: $targetName")
                    _uiMessages.tryEmit("已暂存到服务器，电脑上线后会自动同步")
                } else {
                    addLog("服务器已接收消息，等待电脑确认: $targetName")
                }
            }
        }

        serverConnection.onPairConfirmed =
            { success, fromDeviceId, fromDeviceName, toDeviceId, toDeviceName, message ->
                if (success) {
                    addLog("配对成功: $message")

                    val myDeviceId = configManager.getDeviceId()
                    val otherId = if (fromDeviceId == myDeviceId) toDeviceId else fromDeviceId
                    val otherName =
                        if (fromDeviceId == myDeviceId) toDeviceName else fromDeviceName
                    val pending = pendingScannedPair

                    if (otherId.isNotBlank()) {
                        if (pending != null && pending.deviceId == otherId) {
                            configManager.savePairedDevice(
                                deviceId = pending.deviceId,
                                deviceName = pending.deviceName,
                                deviceType = "desktop",
                                localIp = pending.localIp,
                                localPort = pending.localPort,
                                lastConnectedAt = System.currentTimeMillis()
                            )
                            pendingScannedPair = null
                        } else {
                            configManager.savePairedDevice(
                                deviceId = otherId,
                                deviceName = otherName.ifBlank { "Desktop" },
                                deviceType = "desktop",
                                lastConnectedAt = System.currentTimeMillis()
                            )
                        }
                        refreshPairedDevices()
                        connectToServerDevice(
                            ServerDeviceInfo(
                                deviceId = otherId,
                                deviceName = otherName.ifBlank { "Desktop" },
                                deviceType = "desktop",
                                online = true
                            )
                        )
                    }
                } else {
                    addLog("配对失败: $message")
                    pendingScannedPair = null
                }
            }

        serverConnection.onPairedDeviceOnline = { deviceId, deviceName, deviceType ->
            addLog("配对设备上线: $deviceName ($deviceType)")
            configManager.savePairedDevice(
                deviceId = deviceId,
                deviceName = deviceName,
                deviceType = deviceType,
                lastConnectedAt = System.currentTimeMillis()
            )
            refreshPairedDevices()
            if (relayTargetDeviceId == null || relayTargetDeviceId == deviceId) {
                connectToServerDevice(
                    ServerDeviceInfo(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        deviceType = deviceType,
                        online = true
                    )
                )
                addLog("已自动连接到配对设备: $deviceName")
            } else {
                syncSelectedRelayDeviceStatus()
            }
        }

        serverConnection.onPairedDeviceOffline = { deviceId, deviceName, deviceType ->
            addLog("配对设备离线: $deviceName ($deviceType)")
            if (relayTargetDeviceId == deviceId) {
                syncSelectedRelayDeviceStatus()
                addLog("设备 $deviceName 已离线，连接已断开")
            }
            updateSendAvailability()
        }

        serverConnection.onUnpairNotify = { deviceId, deviceName ->
            addLog("对方已取消配对: $deviceName")
            configManager.removePairedDevice(deviceId)
            refreshPairedDevices()
            if (relayTargetDeviceId == deviceId) {
                relayTargetDeviceId = null
                relayTargetDeviceName = null
                restoreSelectedRelayDevice()
            }
            updateSendAvailability()
        }
    }

    private fun handleRelayAck(fromDeviceId: String, payload: JsonObject) {
        val contentType = payload.get("content_type")?.asString ?: "text"
        val success = payload.get("success")?.asBoolean ?: false
        val serverMessageId = payload.get("server_message_id")?.asString
        val clientMessageId = payload.get("client_message_id")?.takeIf { !it.isJsonNull }?.asString

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val updatedByClientId = clientMessageId
                ?.takeIf { it.isNotBlank() }
                ?.let { itemId ->
                    updateHistoryItem(itemId) { item ->
                        RelayAckState.applyInputAck(
                            item = item,
                            serverMessageId = serverMessageId,
                            fromDeviceId = fromDeviceId,
                            success = success,
                            now = now
                        )
                    }
                    true
                } ?: false

            if (!updatedByClientId && serverMessageId != null) {
                val updated = historyRepository.updateHistoryByServerMessageId(serverMessageId) { item ->
                    RelayAckState.applyInputAck(
                        item = item,
                        serverMessageId = serverMessageId,
                        fromDeviceId = fromDeviceId,
                        success = success,
                        now = now
                    )
                }
                updated?.let { replaceHistoryItemInState(it) }
            }

            if (success) {
                when (contentType) {
                    "image" -> addLog("电脑已接收图片 from $fromDeviceId")
                    "file" -> addLog("电脑已保存文件 from $fromDeviceId")
                    "notification" -> addLog("电脑已记录通知 from $fromDeviceId")
                    else -> addLog("电脑已接收文本 from $fromDeviceId")
                }
            } else {
                addLog("电脑处理失败 from $fromDeviceId")
            }
        }
    }

    fun askAiAssistant(
        question: String,
        filters: JsonObject? = null,
        continueCurrentSession: Boolean = true
    ) {
        val normalized = question.trim()
        if (normalized.isBlank()) {
            _uiMessages.tryEmit("请输入要询问 AI 助手的问题")
            return
        }
        val targetId = relayTargetDeviceId
        val targetName = relayTargetDeviceName ?: targetId.orEmpty()
        if (targetId.isNullOrBlank() || !serverConnection.isConnected()) {
            _aiAssistantState.value = _aiAssistantState.value.copy(
                loading = false,
                error = "需要先通过服务器中转连接到电脑",
                status = "未连接电脑"
            )
            _uiMessages.tryEmit("请先连接服务器并选择电脑")
            return
        }

        val requestId = "app-ai-${System.currentTimeMillis()}-${(1000..9999).random()}"
        val payload = JsonObject().apply {
            addProperty("type", "AI_ASSISTANT_REQUEST")
            addProperty("request_id", requestId)
            addProperty("question", normalized)
            filters?.let { add("filters", it) }
            if (continueCurrentSession) {
                _aiAssistantState.value.sessionId?.let { addProperty("session_id", it) }
            }
            addProperty("timestamp", System.currentTimeMillis())
        }
        pendingAiRequestId = requestId
        val userMessage = AiAssistantMessage(
            id = "$requestId-user",
            role = "user",
            content = normalized,
            timestamp = System.currentTimeMillis()
        )
        val assistantPlaceholder = AiAssistantMessage(
            id = "$requestId-assistant",
            role = "assistant",
            content = "",
            timestamp = System.currentTimeMillis(),
            streaming = true
        )
        val existingMessages = if (continueCurrentSession) {
            _aiAssistantState.value.messages
        } else {
            emptyList()
        }
        _aiAssistantState.value = _aiAssistantState.value.copy(
            question = normalized,
            sessionId = if (continueCurrentSession) _aiAssistantState.value.sessionId else null,
            messages = existingMessages + userMessage + assistantPlaceholder,
            toolEvents = emptyList(),
            loading = true,
            error = null,
            status = "已发送到 $targetName，等待 PC AI 助手返回"
        )

        if (serverConnection.sendRelayMessage(targetId, payload)) {
            addLog("已发送 AI 请求到 $targetName")
        } else {
            pendingAiRequestId = null
            val failedMessages = _aiAssistantState.value.messages.map { message ->
                if (message.id == "$requestId-assistant") {
                    message.copy(streaming = false)
                } else {
                    message
                }
            }
            _aiAssistantState.value = _aiAssistantState.value.copy(
                messages = failedMessages,
                loading = false,
                error = "AI 请求发送失败",
                status = "发送失败"
            )
            addLog("AI 请求发送失败")
        }
    }

    fun cancelAiAssistant() {
        val requestId = pendingAiRequestId
        val targetId = relayTargetDeviceId
        if (requestId.isNullOrBlank() || targetId.isNullOrBlank()) {
            _aiAssistantState.value = _aiAssistantState.value.copy(
                loading = false,
                status = "没有正在生成的 AI 请求"
            )
            return
        }

        val payload = JsonObject().apply {
            addProperty("type", "AI_ASSISTANT_CANCEL")
            addProperty("request_id", requestId)
            addProperty("timestamp", System.currentTimeMillis())
        }
        val sent = serverConnection.sendRelayMessage(targetId, payload)
        val updatedMessages = _aiAssistantState.value.messages.map { message ->
            if (message.id == "$requestId-assistant") {
                message.copy(streaming = false)
            } else {
                message
            }
        }
        _aiAssistantState.value = _aiAssistantState.value.copy(
            messages = updatedMessages,
            loading = false,
            status = if (sent) "已请求 PC 停止本次生成" else "停止请求发送失败",
            error = if (sent) null else "停止请求发送失败"
        )
        if (sent) {
            addLog("已请求停止 PC AI 助手生成")
        } else {
            addLog("停止 PC AI 助手生成失败")
        }
    }

    fun startNewAiAssistantSession() {
        pendingAiRequestId = null
        _aiAssistantState.value = AiAssistantState(
            status = "已开始新会话，下一次提问不会沿用上一轮 PC AI 上下文"
        )
    }

    fun requestAiAssistantWordExport() {
        val latestAnswer = _aiAssistantState.value.messages
            .lastOrNull { it.role != "user" && it.content.isNotBlank() }
            ?.content
            ?.trim()
        if (latestAnswer.isNullOrBlank()) {
            _uiMessages.tryEmit("没有可导出 Word 的 AI 回答")
            return
        }

        val question = AiAssistantFormatter.buildWordExportRequest()
        askAiAssistant(question, continueCurrentSession = true)
    }

    private fun handleAiAssistantResponse(fromDeviceId: String, payload: JsonObject) {
        val requestId = payload.get("request_id")?.asString
        if (!shouldAcceptAiAssistantPayload(requestId)) {
            return
        }
        val success = payload.get("success")?.asBoolean ?: false
        viewModelScope.launch {
            if (success) {
                val answer = payload.get("content")?.asString.orEmpty()
                val sessionId = payload.get("session_id")?.asString
                val recordCount = payload.get("record_count")?.asInt ?: 0
                val exportedFilePath = payload.getAsJsonObject("exported_file")
                    ?.get("saved_path")
                    ?.takeIf { !it.isJsonNull }
                    ?.asString
                    ?.takeIf { it.isNotBlank() }
                val reduction = AiAssistantReducer.applyResponseSuccess(
                    state = _aiAssistantState.value,
                    requestId = requestId,
                    answer = answer,
                    sessionId = sessionId,
                    recordCount = recordCount,
                    exportedFilePath = exportedFilePath,
                    timestamp = System.currentTimeMillis()
                )
                _aiAssistantState.value = reduction.state
                addLog("收到 PC AI 助手回答 from $fromDeviceId")
            } else {
                val error = payload.get("error")?.asString ?: "AI 助手执行失败"
                val reduction = AiAssistantReducer.applyResponseFailure(
                    state = _aiAssistantState.value,
                    requestId = requestId,
                    error = error
                )
                _aiAssistantState.value = reduction.state
                addLog("PC AI 助手执行失败: $error")
            }
            pendingAiRequestId = null
        }
    }

    private fun handleAiAssistantDelta(payload: JsonObject) {
        val requestId = payload.get("request_id")?.asString ?: return
        if (!shouldAcceptAiAssistantPayload(requestId)) return
        val delta = payload.get("delta")?.asString.orEmpty()
        if (delta.isBlank()) return
        viewModelScope.launch {
            _aiAssistantState.value = AiAssistantReducer.applyDelta(
                state = _aiAssistantState.value,
                requestId = requestId,
                delta = delta
            )
        }
    }

    private fun handleAiAssistantEvent(payload: JsonObject) {
        val requestId = payload.get("request_id")?.asString ?: return
        if (!shouldAcceptAiAssistantPayload(requestId)) return
        val event = payload.get("event")?.asString.orEmpty()
        if (event.isBlank()) return
        val toolName = payload.get("tool_name")?.asString.orEmpty()
        val message = payload.get("message")?.asString.orEmpty()
        val toolCallId = payload.get("tool_call_id")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        val sessionId = payload.get("session_id")?.takeIf { !it.isJsonNull }?.asString
        val data = payload.getAsJsonObject("data")
        val recordCount = data?.get("record_count")?.takeIf { !it.isJsonNull }?.asInt
        val exportedFilePath = data
            ?.getAsJsonObject("exported_file")
            ?.get("saved_path")
            ?.takeIf { !it.isJsonNull }
            ?.asString
            ?.takeIf { it.isNotBlank() }
        val status = when (event) {
            "tool_call_start" -> if (toolName.isNotBlank()) "PC AI 正在调用 $toolName" else "PC AI 正在调用工具"
            "tool_call_result" -> if (toolName.isNotBlank()) "PC AI 已完成 $toolName" else "PC AI 工具调用完成"
            "assistant_done" -> if (exportedFilePath.isNullOrBlank()) "PC AI 助手已完成" else "PC AI 助手已完成，已导出 Word"
            "assistant_error" -> "PC AI 助手执行失败"
            else -> message.ifBlank { "PC AI 工具事件：$event" }
        }
        val eventItem = AiAssistantToolEvent(
            id = toolCallId.ifBlank { "$requestId-$event-${System.currentTimeMillis()}" },
            event = event,
            toolName = toolName,
            message = message,
            detail = AiAssistantFormatter.buildToolEventDetail(payload),
            timestamp = System.currentTimeMillis()
        )
        viewModelScope.launch {
            val reduction = AiAssistantReducer.applyToolEvent(
                state = _aiAssistantState.value,
                requestId = requestId,
                eventItem = eventItem,
                sessionId = sessionId,
                recordCount = recordCount,
                exportedFilePath = exportedFilePath,
                status = status
            )
            _aiAssistantState.value = reduction.state
            if (reduction.clearPendingRequest) {
                pendingAiRequestId = null
            }
        }
    }

    private fun shouldAcceptAiAssistantPayload(requestId: String?): Boolean {
        return AiAssistantReducer.shouldAcceptPayload(
            state = _aiAssistantState.value,
            pendingRequestId = pendingAiRequestId,
            requestId = requestId
        )
    }

    private fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _connectionLog.value = _connectionLog.value + "[$timestamp] $message"
        if (_connectionLog.value.size > 50) {
            _connectionLog.value = _connectionLog.value.takeLast(50)
        }
    }

    fun clearLog() {
        _connectionLog.value = emptyList()
    }

    fun refreshPairedDevices() {
        _pairedDevices.value = configManager.getPairedDevices()
        refreshLastTargetDevice()
    }

    private fun refreshLastTargetDevice() {
        _lastTargetDevice.value = configManager.getLastTargetDevice()
    }

    private fun refreshForegroundServiceIfEnabled() {
        if (configManager.isNotificationEnabled()) {
            VoiceInputForegroundService.start(getApplication<Application>().applicationContext)
        }
    }

    private fun restoreSelectedRelayDevice() {
        val devices = _pairedDevices.value
        if (devices.isEmpty()) {
            relayTargetDeviceId = null
            relayTargetDeviceName = null
            _connectionStatus.value = ConnectionStatus(connected = false, deviceName = "")
            configManager.saveForegroundConnectionStatus(false, "")
            return
        }

        val lastTarget = configManager.getLastTargetDevice()
        val restored = when {
            lastTarget != null && devices.containsKey(lastTarget.deviceId) -> {
                devices[lastTarget.deviceId]?.let { paired ->
                    lastTarget.deviceId to paired.deviceName
                }
            }

            else -> devices.entries.firstOrNull()?.let { entry ->
                entry.key to entry.value.deviceName
            }
        }

        if (restored == null) {
            relayTargetDeviceId = null
            relayTargetDeviceName = null
            _connectionStatus.value = ConnectionStatus(connected = false, deviceName = "")
            configManager.saveForegroundConnectionStatus(false, "")
            return
        }

        relayTargetDeviceId = restored.first
        relayTargetDeviceName = restored.second
        configManager.saveLastTargetDevice(restored.first, restored.second)
        refreshLastTargetDevice()
        syncSelectedRelayDeviceStatus()
        refreshForegroundServiceIfEnabled()
    }

    fun startDiscovery() {
        viewModelScope.launch {
            networkManager.startUdpDiscovery {
                _discoveredDevices.value = it
            }
        }
    }

    fun connectToDevice(device: Device) {
        viewModelScope.launch {
            val success = networkManager.connectToDevice(device)
            if (success) {
                _connectionStatus.value = ConnectionStatus(
                    connected = true,
                    deviceName = device.deviceName
                )
                configManager.saveForegroundConnectionStatus(true, device.deviceName)
            }
            updateSendAvailability()
        }
    }

    fun connectToServerDevice(device: ServerDeviceInfo) {
        relayTargetDeviceId = device.deviceId
        relayTargetDeviceName = device.deviceName
        configManager.saveLastTargetDevice(device.deviceId, device.deviceName)
        refreshLastTargetDevice()
        configManager.markPairedDeviceConnected(device.deviceId)
        refreshPairedDevices()
        syncSelectedRelayDeviceStatus()
        refreshForegroundServiceIfEnabled()
        addLog("已选择服务器设备: ${device.deviceName}")
        updateSendAvailability()
    }

    fun selectPairedDeviceAsDefault(deviceId: String, deviceName: String) {
        relayTargetDeviceId = deviceId
        relayTargetDeviceName = deviceName
        configManager.saveLastTargetDevice(deviceId, deviceName)
        refreshLastTargetDevice()
        refreshPairedDevices()
        syncSelectedRelayDeviceStatus()
        refreshForegroundServiceIfEnabled()
        addLog("已设为默认电脑: $deviceName")
        updateSendAvailability()
    }

    fun unpairDevice(deviceId: String) {
        addLog("取消配对: $deviceId")
        if (serverConnection.isConnected()) {
            val myDeviceId = configManager.getDeviceId()
            serverConnection.sendUnpairRequest(myDeviceId, deviceId)
        }
        configManager.removePairedDevice(deviceId)
        refreshPairedDevices()
        refreshLastTargetDevice()
        if (relayTargetDeviceId == deviceId) {
            relayTargetDeviceId = null
            relayTargetDeviceName = null
            restoreSelectedRelayDevice()
        }
        refreshForegroundServiceIfEnabled()
        updateSendAvailability()
    }

    fun disconnectFromServerDevice() {
        relayTargetDeviceId = null
        relayTargetDeviceName = null
        configManager.clearLastTargetDevice()
        refreshLastTargetDevice()
        _connectionStatus.value = ConnectionStatus(connected = false, deviceName = "")
        configManager.saveForegroundConnectionStatus(false, "")
        refreshForegroundServiceIfEnabled()
        updateSendAvailability()
    }

    private data class PendingScannedPair(
        val serverUrl: String,
        val deviceId: String,
        val deviceName: String,
        val localIp: String,
        val localPort: Int
    )

    fun handleQrScanResult(
        serverUrl: String,
        deviceId: String,
        deviceName: String,
        localIp: String = "",
        localPort: Int = 0
    ) {
        viewModelScope.launch {
            addLog("扫码结果: 设备=$deviceName, IP=$localIp:$localPort")
            pendingScannedPair = PendingScannedPair(
                serverUrl = serverUrl,
                deviceId = deviceId,
                deviceName = deviceName,
                localIp = localIp,
                localPort = localPort
            )

            val serverModeEnabled = configManager.isServerModeEnabled()
            if (serverModeEnabled) {
                addLog("服务器中转已启用，直接使用服务器中转...")
                if (serverUrl.isBlank()) {
                    addLog("无服务器地址")
                    pendingScannedPair = null
                    return@launch
                }

                val currentUrl = configManager.getServerUrl()
                val shouldReconnect =
                    !serverConnection.isConnected() || !currentUrl.equals(serverUrl, ignoreCase = true)

                if (!currentUrl.equals(serverUrl, ignoreCase = true)) {
                    configManager.saveServerUrl(serverUrl)
                }

                if (shouldReconnect) {
                    disconnectFromServer()
                    delay(300)
                    connectToServer(serverUrl)
                    val connected = waitForServerConnected(timeoutMs = 10_000)
                    if (!connected) {
                        addLog("服务器连接失败，无法发起配对")
                        pendingScannedPair = null
                        return@launch
                    }
                }

                if (serverConnection.isConnected()) {
                    val myDeviceId = configManager.getDeviceId()
                    val myDeviceName = configManager.getDeviceName()
                    serverConnection.sendPairRequest(myDeviceId, myDeviceName, deviceId)
                    addLog("已发送服务器配对请求到 $deviceName")
                } else {
                    addLog("服务器连接失败，无法配对")
                    pendingScannedPair = null
                }
                return@launch
            }

            if (localIp.isNotBlank() && localPort > 0) {
                addLog("尝试局域网直连 $localIp:$localPort ...")
                val device = Device(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    ip = localIp,
                    port = localPort,
                    version = BuildConfig.VERSION_NAME
                )
                val success = networkManager.connectToDevice(device)
                if (success) {
                    configManager.savePairedDevice(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        deviceType = "desktop",
                        localIp = localIp,
                        localPort = localPort,
                        lastConnectedAt = System.currentTimeMillis()
                    )
                    refreshPairedDevices()
                    pendingScannedPair = null

                    _connectionStatus.value = ConnectionStatus(
                        connected = true,
                        deviceName = "$deviceName (局域网)"
                    )
                    addLog("局域网连接成功")
                    return@launch
                }
                addLog("局域网连接失败，尝试服务器中转...")
            }

            if (serverUrl.isBlank()) {
                addLog("无服务器地址且局域网连接失败")
                pendingScannedPair = null
                return@launch
            }

            val currentUrl = configManager.getServerUrl()
            val shouldReconnect =
                !serverConnection.isConnected() || !currentUrl.equals(serverUrl, ignoreCase = true)

            if (!currentUrl.equals(serverUrl, ignoreCase = true)) {
                configManager.saveServerUrl(serverUrl)
            }
            configManager.saveServerModeEnabled(true)

            if (shouldReconnect) {
                disconnectFromServer()
                delay(300)
                connectToServer(serverUrl)
                val connected = waitForServerConnected(timeoutMs = 10_000)
                if (!connected) {
                    addLog("服务器连接失败，无法发起配对")
                    pendingScannedPair = null
                    return@launch
                }
            }

            if (serverConnection.isConnected()) {
                val myDeviceId = configManager.getDeviceId()
                val myDeviceName = configManager.getDeviceName()
                serverConnection.sendPairRequest(myDeviceId, myDeviceName, deviceId)
                addLog("已发送服务器配对请求到 $deviceName")
            } else {
                addLog("服务器连接失败，无法配对")
                pendingScannedPair = null
            }
        }
    }

    private suspend fun waitForServerConnected(timeoutMs: Long): Boolean {
        val step = 200L
        var waited = 0L
        while (waited < timeoutMs) {
            if (serverConnection.isConnected()) {
                return true
            }
            delay(step)
            waited += step
        }
        return serverConnection.isConnected()
    }

    fun onInputTextChange(text: String) {
        _inputText.value = text
        configManager.saveInputDraft(text)
    }

    fun sendText(pressEnter: Boolean = false) {
        val text = _inputText.value
        if (text.isBlank()) return
        _inputText.value = ""
        configManager.clearInputDraft()

        viewModelScope.launch {
            when {
                relayTargetDeviceId != null && serverConnection.isConnected() -> sendTextViaRelay(text, pressEnter)
                networkManager.isConnected.value -> sendTextViaLan(text, pressEnter)
                else -> {
                    addLog("未连接到任何设备，无法发送")
                    _inputText.value = text
                    configManager.saveInputDraft(text)
                }
            }
        }
    }

    fun sendImage(uri: Uri) {
        viewModelScope.launch {
            if (!ensureImageSendRoute()) {
                addLog("未连接到任何设备，无法发送图片")
                _uiMessages.tryEmit("未连接到任何设备，无法发送图片")
                return@launch
            }

            addLog("正在压缩图片...")
            val payload = ImageTransferCodec.encodeFromUri(getApplication(), uri)
            if (payload == null) {
                addLog("图片处理失败")
                _uiMessages.tryEmit("图片处理失败，请重新拍照或选择图片")
                return@launch
            }

            if (!ensureImageSendRoute()) {
                addLog("未连接到任何设备，无法发送图片")
                _uiMessages.tryEmit("未连接到任何设备，无法发送图片")
                return@launch
            }

            when {
                relayTargetDeviceId != null && serverConnection.isConnected() -> sendImageViaRelay(payload)
                networkManager.isConnected.value -> sendImageViaLan(payload)
                else -> {
                    addLog("未连接到任何设备，无法发送图片")
                    _uiMessages.tryEmit("未连接到任何设备，无法发送图片")
                }
            }
        }
    }

    fun sendFile(uri: Uri) {
        viewModelScope.launch {
            if (!ensureFileSendRoute()) {
                addLog("未连接服务器，无法发送文件")
                _uiMessages.tryEmit("未连接服务器，无法发送文件")
                return@launch
            }

            addLog("正在读取文件...")
            val payload = FileTransferCodec.encodeFromUri(getApplication(), uri)
            if (payload == null) {
                addLog("文件读取失败或文件过大")
                _uiMessages.tryEmit("文件读取失败，或超过 4.5MB")
                return@launch
            }

            if (!ensureFileSendRoute()) {
                addLog("未连接服务器，无法发送文件")
                _uiMessages.tryEmit("未连接服务器，无法发送文件")
                return@launch
            }

            sendFileViaRelay(payload)
        }
    }

    fun sendScannedFilePayload(
        fileName: String,
        mimeType: String,
        base64Data: String,
        size: Long
    ) {
        viewModelScope.launch {
            if (!ensureFileSendRoute()) {
                addLog("未连接服务器，无法发送扫码文件")
                _uiMessages.tryEmit("未连接服务器，无法发送扫码文件")
                return@launch
            }

            val payload = ClipboardFilePayload(
                mimeType = mimeType.ifBlank { "application/octet-stream" },
                fileName = fileName.ifBlank { "scanned-file-${System.currentTimeMillis()}" },
                data = base64Data,
                size = size
            )
            sendFileViaRelay(payload)
        }
    }

    private suspend fun ensureImageSendRoute(): Boolean {
        restoreRelayTargetIfNeeded()

        if (relayTargetDeviceId != null && serverConnection.isConnected()) {
            updateSendAvailability()
            return true
        }

        if (networkManager.isConnected.value) {
            updateSendAvailability()
            return true
        }

        val targetId = relayTargetDeviceId
        val serverUrl = configManager.getServerUrl()
        if (!targetId.isNullOrBlank() && serverUrl.isNotBlank()) {
            val state = serverConnection.connectionState.value
            if (state is ServerConnection.ConnectionState.Connecting ||
                state is ServerConnection.ConnectionState.Reconnecting
            ) {
                waitForServerConnected(timeoutMs = 3_000)
            }

            if (!serverConnection.isConnected()) {
                addLog("发送图片前正在恢复服务器连接...")
                reconnectServerForSend(serverUrl)
            }

            if (serverConnection.isConnected()) {
                syncSelectedRelayDeviceStatus()
                updateSendAvailability()
                return true
            }
        }

        val lanDevice = resolveLanReconnectDevice()
        if (!networkManager.isConnected.value && lanDevice != null) {
            addLog("发送图片前正在恢复局域网连接...")
            if (networkManager.connectToDevice(lanDevice)) {
                _connectionStatus.value = ConnectionStatus(
                    connected = true,
                    deviceName = "${lanDevice.deviceName} (局域网)"
                )
                updateSendAvailability()
                return true
            }
        }

        updateSendAvailability()
        return (relayTargetDeviceId != null && serverConnection.isConnected()) ||
            networkManager.isConnected.value
    }

    private suspend fun ensureFileSendRoute(): Boolean {
        restoreRelayTargetIfNeeded()

        if (relayTargetDeviceId != null && serverConnection.isConnected()) {
            updateSendAvailability()
            return true
        }

        val targetId = relayTargetDeviceId
        val serverUrl = configManager.getServerUrl()
        if (!targetId.isNullOrBlank() && serverUrl.isNotBlank()) {
            val state = serverConnection.connectionState.value
            if (state is ServerConnection.ConnectionState.Connecting ||
                state is ServerConnection.ConnectionState.Reconnecting
            ) {
                waitForServerConnected(timeoutMs = 3_000)
            }

            if (!serverConnection.isConnected()) {
                addLog("发送文件前正在恢复服务器连接...")
                reconnectServerForSend(serverUrl)
            }

            if (serverConnection.isConnected()) {
                syncSelectedRelayDeviceStatus()
                updateSendAvailability()
                return true
            }
        }

        updateSendAvailability()
        return false
    }

    private fun restoreRelayTargetIfNeeded() {
        if (!relayTargetDeviceId.isNullOrBlank()) {
            return
        }

        val lastTarget = configManager.getLastTargetDevice()
        if (lastTarget != null) {
            relayTargetDeviceId = lastTarget.deviceId
            relayTargetDeviceName = lastTarget.deviceName
            return
        }

        val firstPaired = _pairedDevices.value.entries.firstOrNull() ?: return
        relayTargetDeviceId = firstPaired.key
        relayTargetDeviceName = firstPaired.value.deviceName
    }

    private suspend fun reconnectServerForSend(serverUrl: String): Boolean {
        val deviceId = configManager.getDeviceId()
        val deviceName = configManager.getDeviceName()
        val config = ServerConfig(
            enabled = true,
            serverUrl = serverUrl,
            deviceId = deviceId,
            sessionId = ""
        )

        serverConnection.connect(config, deviceId, deviceName)
        startHeartbeat()
        startDeviceListRefresh()

        val connected = waitForServerConnected(timeoutMs = 10_000)
        if (connected) {
            serverConnection.requestDeviceList()
        }
        return connected
    }

    private fun resolveLanReconnectDevice(): Device? {
        val targetId = relayTargetDeviceId
        val paired = if (targetId != null) {
            _pairedDevices.value[targetId]
        } else {
            null
        } ?: _pairedDevices.value.values.firstOrNull {
            it.localIp.isNotBlank() && it.localPort > 0
        } ?: return null

        if (paired.localIp.isBlank() || paired.localPort <= 0) {
            return null
        }

        return Device(
            deviceId = paired.deviceId,
            deviceName = paired.deviceName,
            ip = paired.localIp,
            port = paired.localPort,
            version = BuildConfig.VERSION_NAME
        )
    }

    private suspend fun sendTextViaRelay(text: String, pressEnter: Boolean) {
        val targetId = relayTargetDeviceId ?: return
        val targetName = relayTargetDeviceName ?: targetId
        val payload = JsonObject().apply {
            addProperty("type", "TEXT_INPUT")
            addProperty("text", text)
            addProperty("press_enter", pressEnter)
            addProperty("timestamp", System.currentTimeMillis())
        }

        val historyItem = createHistoryItem(
            text = text,
            targetDeviceId = targetId,
            targetDeviceName = targetName,
            contentType = "text",
            channel = "server",
            syncStatus = SyncStatus.PENDING
        )
        payload.addProperty("client_message_id", historyItem.id)
        addHistoryItem(historyItem)

        pendingRelayHistoryIds.addLast(historyItem.id)
        if (serverConnection.sendRelayMessage(targetId, payload)) {
            addLog("已发送文本到 $targetName (中转)")
        } else {
            pendingRelayHistoryIds.remove(historyItem.id)
            updateHistoryItem(historyItem.id) { item ->
                item.copy(
                    syncStatus = SyncStatus.FAILED,
                    errorMessage = "发送失败，未能提交到服务器"
                )
            }
            addLog("文本发送失败")
            _uiMessages.tryEmit("发送失败，消息未提交到服务器")
        }
    }

    private suspend fun sendTextViaLan(text: String, pressEnter: Boolean) {
        val targetName = connectionStatus.value.deviceName.ifBlank { "局域网设备" }
        val historyItem = createHistoryItem(
            text = text,
            targetDeviceId = relayTargetDeviceId ?: "",
            targetDeviceName = targetName,
            contentType = "text",
            channel = "lan",
            syncStatus = SyncStatus.PENDING
        )
        addHistoryItem(historyItem)

        val success = networkManager.sendText(text, pressEnter)
        if (success) {
            updateHistoryItem(historyItem.id) { item ->
                item.copy(
                    syncStatus = SyncStatus.DIRECT,
                    syncedAt = System.currentTimeMillis(),
                    errorMessage = null
                )
            }
            addLog("局域网发送成功")
        } else {
            updateHistoryItem(historyItem.id) { item ->
                item.copy(
                    syncStatus = SyncStatus.FAILED,
                    errorMessage = "局域网发送失败"
                )
            }
            addLog("局域网发送失败")
        }
    }

    private suspend fun sendImageViaRelay(payload: ClipboardImagePayload) {
        val targetId = relayTargetDeviceId ?: return
        val targetName = relayTargetDeviceName ?: targetId
        val relayPayload = JsonObject().apply {
            addProperty("type", "CLIPBOARD_IMAGE")
            addProperty("mime_type", payload.mimeType)
            addProperty("file_name", payload.fileName)
            addProperty("data", payload.imageData)
            addProperty("width", payload.width)
            addProperty("height", payload.height)
            addProperty("size", payload.size)
            addProperty("timestamp", System.currentTimeMillis())
        }

        val historyItem = createHistoryItem(
            text = "[图片] ${payload.fileName}",
            targetDeviceId = targetId,
            targetDeviceName = targetName,
            contentType = "image",
            channel = "server",
            syncStatus = SyncStatus.PENDING,
            metadata = imageHistoryMetadata(payload)
        )
        relayPayload.addProperty("client_message_id", historyItem.id)
        addHistoryItem(historyItem)

        pendingRelayHistoryIds.addLast(historyItem.id)
        if (serverConnection.sendRelayMessage(targetId, relayPayload)) {
            addLog("已发送图片到 $targetName (中转)")
            _uiMessages.tryEmit("图片已发送")
        } else {
            pendingRelayHistoryIds.remove(historyItem.id)
            updateHistoryItem(historyItem.id) { item ->
                item.copy(
                    syncStatus = SyncStatus.FAILED,
                    errorMessage = "图片发送失败，未能提交到服务器"
                )
            }
            addLog("图片发送失败")
            _uiMessages.tryEmit("图片发送失败，未提交到服务器")
        }
    }

    private suspend fun sendFileViaRelay(payload: ClipboardFilePayload) {
        val targetId = relayTargetDeviceId ?: return
        val targetName = relayTargetDeviceName ?: targetId
        val relayPayload = JsonObject().apply {
            addProperty("type", "CLIPBOARD_FILE")
            addProperty("mime_type", payload.mimeType)
            addProperty("file_name", payload.fileName)
            addProperty("data", payload.data)
            addProperty("size", payload.size)
            addProperty("timestamp", System.currentTimeMillis())
        }

        val historyItem = createHistoryItem(
            text = "[文件] ${payload.fileName}",
            targetDeviceId = targetId,
            targetDeviceName = targetName,
            contentType = "file",
            channel = "server",
            syncStatus = SyncStatus.PENDING,
            metadata = fileHistoryMetadata(payload)
        )
        relayPayload.addProperty("client_message_id", historyItem.id)
        addHistoryItem(historyItem)

        pendingRelayHistoryIds.addLast(historyItem.id)
        if (serverConnection.sendRelayMessage(targetId, relayPayload)) {
            addLog("已发送文件到 $targetName (中转): ${payload.fileName}")
            _uiMessages.tryEmit("文件已发送")
        } else {
            pendingRelayHistoryIds.remove(historyItem.id)
            updateHistoryItem(historyItem.id) { item ->
                item.copy(
                    syncStatus = SyncStatus.FAILED,
                    errorMessage = "文件发送失败，未能提交到服务器"
                )
            }
            addLog("文件发送失败")
            _uiMessages.tryEmit("文件发送失败，未提交到服务器")
        }
    }

    private suspend fun sendNotificationViaRelay(
        notification: NotificationData,
        historyItemId: String,
        forwardMode: String
    ) {
        val targetId = relayTargetDeviceId ?: return
        val targetName = relayTargetDeviceName ?: targetId
        val relayPayload = NotificationForwarding.relayPayload(notification, forwardMode)
        relayPayload.addProperty("client_message_id", historyItemId)

        updateHistoryItem(historyItemId) { item ->
            item.copy(
                channel = "server",
                targetDeviceId = targetId,
                targetDeviceName = targetName
            )
        }
        pendingRelayHistoryIds.addLast(historyItemId)
        if (serverConnection.sendRelayMessage(targetId, relayPayload)) {
            addLog("已转发通知到 $targetName (中转): ${notification.appName}")
            _uiMessages.tryEmit("通知已转发：${notification.appName}")
        } else {
            pendingRelayHistoryIds.remove(historyItemId)
            updateHistoryItem(historyItemId) { item ->
                item.copy(
                    syncStatus = SyncStatus.FAILED,
                    errorMessage = "通知发送失败，未能提交到服务器"
                )
            }
            addLog("通知发送失败: ${notification.appName}")
            _uiMessages.tryEmit("通知转发失败：${notification.appName}")
        }
    }

    private suspend fun sendNotificationViaLan(
        notification: NotificationData,
        historyItemId: String,
        forwardMode: String
    ) {
        updateHistoryItem(historyItemId) { item ->
            item.copy(
                channel = "lan",
                targetDeviceName = connectionStatus.value.deviceName.ifBlank { "局域网设备" }
            )
        }
        val success = networkManager.sendNotification(notification.copy(forwardMode = forwardMode))
        if (success) {
            updateHistoryItem(historyItemId) { item ->
                item.copy(
                    syncStatus = SyncStatus.DIRECT,
                    syncedAt = System.currentTimeMillis(),
                    errorMessage = null
                )
            }
            addLog("局域网通知发送成功: ${notification.appName}")
        } else {
            updateHistoryItem(historyItemId) { item ->
                item.copy(
                    syncStatus = SyncStatus.FAILED,
                    errorMessage = "局域网通知发送失败"
                )
            }
            addLog("局域网通知发送失败: ${notification.appName}")
        }
    }

    private suspend fun sendImageViaLan(payload: ClipboardImagePayload) {
        val targetName = connectionStatus.value.deviceName.ifBlank { "局域网设备" }
        val historyItem = createHistoryItem(
            text = "[图片] ${payload.fileName}",
            targetDeviceId = relayTargetDeviceId ?: "",
            targetDeviceName = targetName,
            contentType = "image",
            channel = "lan",
            syncStatus = SyncStatus.PENDING,
            metadata = imageHistoryMetadata(payload)
        )
        addHistoryItem(historyItem)

        val success = networkManager.sendImage(payload)
        if (success) {
            updateHistoryItem(historyItem.id) { item ->
                item.copy(
                    syncStatus = SyncStatus.DIRECT,
                    syncedAt = System.currentTimeMillis(),
                    errorMessage = null
                )
            }
            addLog("局域网图片发送成功")
            _uiMessages.tryEmit("图片已发送")
        } else {
            updateHistoryItem(historyItem.id) { item ->
                item.copy(
                    syncStatus = SyncStatus.FAILED,
                    errorMessage = "局域网图片发送失败"
                )
            }
            addLog("局域网图片发送失败")
            _uiMessages.tryEmit("图片发送失败")
        }
    }

    fun onHistoryItemClick(item: HistoryItem) {
        if (item.contentType == "text") {
            _inputText.value = item.text
            configManager.saveInputDraft(item.text)
        }
    }

    fun deleteHistoryItem(itemId: String) {
        viewModelScope.launch {
            historyRepository.deleteHistoryItem(itemId)
            _historyItems.value = _historyItems.value.filter { it.id != itemId }
            val updatedVisible = _visibleHistoryItems.value.filter { it.id != itemId }
            if (updatedVisible.isEmpty()) {
                loadInitialVisibleHistory(force = true)
            } else {
                _visibleHistoryItems.value = updatedVisible
                refreshVisibleHistoryHasMore(updatedVisible)
            }
            pendingRelayHistoryIds.remove(itemId)
        }
    }

    fun deleteHistoryItems(itemIds: Set<String>) {
        if (itemIds.isEmpty()) return
        viewModelScope.launch {
            historyRepository.deleteHistoryItems(itemIds)
            _historyItems.value = _historyItems.value.filter { it.id !in itemIds }
            val updatedVisible = _visibleHistoryItems.value.filter { it.id !in itemIds }
            if (updatedVisible.isEmpty()) {
                loadInitialVisibleHistory(force = true)
            } else {
                _visibleHistoryItems.value = updatedVisible
                refreshVisibleHistoryHasMore(updatedVisible)
            }
            pendingRelayHistoryIds.removeAll(itemIds)
        }
    }

    fun toggleHistoryFavorite(itemId: String) {
        viewModelScope.launch {
            updateHistoryItem(itemId) { item ->
                item.copy(isFavorite = !item.isFavorite)
            }
        }
    }

    fun toggleHistoryPinned(itemId: String) {
        viewModelScope.launch {
            updateHistoryItem(itemId) { item ->
                item.copy(isPinned = !item.isPinned)
            }
        }
    }

    fun setHistoryItemsFavorite(itemIds: Set<String>, favorite: Boolean) {
        if (itemIds.isEmpty()) return
        viewModelScope.launch {
            historyRepository.updateHistoryItems(itemIds) { item ->
                item.copy(isFavorite = favorite)
            }
            _historyItems.value = _historyItems.value.map { item ->
                if (item.id in itemIds) item.copy(isFavorite = favorite) else item
            }
            _visibleHistoryItems.value = _visibleHistoryItems.value.map { item ->
                if (item.id in itemIds) item.copy(isFavorite = favorite) else item
            }
        }
    }

    fun setHistoryItemsPinned(itemIds: Set<String>, pinned: Boolean) {
        if (itemIds.isEmpty()) return
        viewModelScope.launch {
            historyRepository.updateHistoryItems(itemIds) { item ->
                item.copy(isPinned = pinned)
            }
            _historyItems.value = _historyItems.value.map { item ->
                if (item.id in itemIds) item.copy(isPinned = pinned) else item
            }
            _visibleHistoryItems.value = _visibleHistoryItems.value.map { item ->
                if (item.id in itemIds) item.copy(isPinned = pinned) else item
            }
        }
    }

    fun updateHistoryTags(itemId: String, tags: String) {
        val normalizedTags = tags
            .split(',', '，', '\n')
            .map { it.trim().trimStart('#') }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",")
        viewModelScope.launch {
            updateHistoryItem(itemId) { item ->
                item.copy(tags = normalizedTags)
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            historyRepository.clearHistory()
            _historyItems.value = emptyList()
            _visibleHistoryItems.value = emptyList()
            _historyHasMore.value = false
            _historyLoadingMore.value = false
            _historyInitialLoaded.value = true
            pendingRelayHistoryIds.clear()
        }
    }

    suspend fun loadInitialVisibleHistory(force: Boolean = false) {
        if (_historyInitialLoaded.value && !force) {
            return
        }

        val latestItems = latestInputHistoryPage(HISTORY_PAGE_SIZE)
        _visibleHistoryItems.value = latestItems
        _historyInitialLoaded.value = true
        refreshVisibleHistoryHasMore(latestItems)
    }

    suspend fun loadOlderVisibleHistory(): Int {
        if (_historyLoadingMore.value || !_historyInitialLoaded.value) {
            return 0
        }

        val oldestVisibleItem = _visibleHistoryItems.value.firstOrNull() ?: return 0
        if (!_historyHasMore.value) {
            return 0
        }

        _historyLoadingMore.value = true
        return try {
            val olderItems = olderInputHistoryPage(oldestVisibleItem, HISTORY_PAGE_SIZE)

            if (olderItems.isEmpty()) {
                _historyHasMore.value = false
                0
            } else {
                val existingIds = _visibleHistoryItems.value.mapTo(mutableSetOf()) { it.id }
                val itemsToInsert = olderItems.filterNot { it.id in existingIds }
                if (itemsToInsert.isNotEmpty()) {
                    _visibleHistoryItems.value = itemsToInsert + _visibleHistoryItems.value
                }
                refreshVisibleHistoryHasMore(_visibleHistoryItems.value)
                itemsToInsert.size
            }
        } finally {
            _historyLoadingMore.value = false
        }
    }

    fun searchHistory(query: String) {
        viewModelScope.launch {
            _historyItems.value = historyRepository.searchHistory(query)
        }
    }

    fun reloadHistory() {
        viewModelScope.launch {
            _historyItems.value = historyRepository.loadHistory()
        }
    }

    fun connectToServer(serverUrl: String) {
        viewModelScope.launch {
            addLog("开始连接: $serverUrl")

            val deviceId = configManager.getDeviceId()
            val deviceName = configManager.getDeviceName()
            addLog("设备ID: ${deviceId.take(8)}..., 名称: $deviceName")

            val config = ServerConfig(
                enabled = true,
                serverUrl = serverUrl,
                deviceId = deviceId,
                sessionId = ""
            )

            serverConnection.connect(
                config,
                deviceId,
                deviceName,
                object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        addLog("WebSocket连接成功，正在注册...")
                    }

                    override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                        try {
                            val msg = gson.fromJson(text, com.voiceinput.data.model.ServerMsg::class.java)
                            when (msg.type) {
                                "SERVER_REGISTER_RESPONSE" -> {
                                    if (msg.success == true) {
                                        addLog("注册成功, sessionId=${msg.sessionId?.take(8)}...")
                                    } else {
                                        addLog("注册失败: ${msg.message}")
                                    }
                                }

                                "DEVICE_LIST_RESPONSE" -> addLog("收到设备列表: ${msg.devices?.size ?: 0} 个")
                                "RELAY_MESSAGE" -> addLog("收到中转消息 from ${msg.fromDeviceId?.take(8)}...")
                                "RELAY_STORED" -> if (msg.queued == true) addLog("服务器已暂存消息")
                                "SERVER_PAIR_RESPONSE" -> {
                                    if (msg.success == true) addLog("配对成功") else addLog("配对失败: ${msg.message}")
                                }

                                "PAIRED_DEVICE_ONLINE" -> addLog("配对设备上线: ${msg.deviceName}")
                                "PAIRED_DEVICE_OFFLINE" -> addLog("配对设备离线: ${msg.deviceName}")
                                "UNPAIR_NOTIFY" -> addLog("对方取消配对: ${msg.deviceName}")
                                "ERROR" -> addLog("服务器错误: [${msg.code}] ${msg.message}")
                            }
                        } catch (_: Exception) {
                            addLog("收到消息: ${text.take(80)}")
                        }
                    }

                    override fun onFailure(
                        webSocket: okhttp3.WebSocket,
                        t: Throwable,
                        response: okhttp3.Response?
                    ) {
                        addLog("连接失败: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            )

            startHeartbeat()
            startDeviceListRefresh()
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            delay(5000)
            while (isActive) {
                if (serverConnection.isConnected()) {
                    serverConnection.sendHeartbeat()
                }
                delay(30_000)
            }
        }
    }

    private fun startDeviceListRefresh() {
        deviceListRefreshJob?.cancel()
        deviceListRefreshJob = viewModelScope.launch {
            delay(3000)
            while (isActive) {
                if (serverConnection.isConnected()) {
                    serverConnection.requestDeviceList()
                }
                delay(10_000)
            }
        }
    }

    fun disconnectFromServer() {
        heartbeatJob?.cancel()
        deviceListRefreshJob?.cancel()
        serverConnection.disconnect()
        syncSelectedRelayDeviceStatus()
        updateSendAvailability()
    }

    fun refreshServerDevices() {
        if (serverConnection.isConnected()) {
            serverConnection.requestDeviceList()
        }
    }

    fun getConnectionStatus(): String {
        return when (val state = serverConnection.connectionState.value) {
            is ServerConnection.ConnectionState.Connected -> "已连接服务器"
            is ServerConnection.ConnectionState.Connecting -> "连接中..."
            is ServerConnection.ConnectionState.Reconnecting -> "重连中...（第${state.attempt}次）"
            is ServerConnection.ConnectionState.Error -> "错误: ${state.message}"
            is ServerConnection.ConnectionState.Disconnected -> "未连接"
        }
    }

    fun isConnectedToServer(): Boolean {
        return serverConnection.isConnected()
    }

    fun buildHistoryExportContent(items: List<HistoryItem>, format: String): String {
        return HistoryExportFormatter.buildContent(items, format)
    }

    fun suggestedHistoryExportFileName(format: String, recordMode: String = "history"): String {
        val normalized = format.lowercase(Locale.ROOT)
        val extension = if (normalized in setOf("txt", "md", "csv")) normalized else "txt"
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val scope = if (recordMode == "notifications") "notifications" else "history"
        return "voice-input-$scope-$timestamp.$extension"
    }

    fun exportHistory(
        uri: Uri,
        items: List<HistoryItem>,
        format: String,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val content = buildHistoryExportContent(items, format)
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { stream ->
                        OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                            writer.write(content)
                            writer.flush()
                        }
                    } ?: error("无法打开导出文件")
                }
                onResult(true, "${format.uppercase(Locale.ROOT)} 已导出")
            } catch (e: Exception) {
                onResult(false, "导出失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    fun suggestedAiAssistantExportFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        return "voice-input-ai-$timestamp.md"
    }

    fun exportAiAssistantAnswer(
        uri: Uri,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val content = buildAiAssistantExportMarkdown(_aiAssistantState.value)
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { stream ->
                        OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                            writer.write(content)
                            writer.flush()
                        }
                    } ?: error("无法打开导出文件")
                }
                onResult(true, "AI 答案已导出")
            } catch (e: Exception) {
                onResult(false, "导出失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private fun buildAiAssistantExportMarkdown(state: AiAssistantState): String {
        return AiAssistantFormatter.buildExportMarkdown(
            AiAssistantFormatter.ExportState(
                sessionId = state.sessionId,
                recordCount = state.recordCount,
                exportedFilePath = state.exportedFilePath,
                status = state.status,
                toolEvents = state.toolEvents.map { event ->
                    AiAssistantFormatter.ToolEvent(
                        event = event.event,
                        toolName = event.toolName,
                        message = event.message,
                        detail = event.detail,
                        timestamp = event.timestamp
                    )
                },
                messages = state.messages.map { message ->
                    AiAssistantFormatter.Message(
                        role = message.role,
                        content = message.content,
                        recordCount = message.recordCount,
                        exportedFilePath = message.exportedFilePath
                    )
                }
            )
        )
    }

    private suspend fun addHistoryItem(item: HistoryItem) {
        historyRepository.addHistoryItem(item)
        _historyItems.value = _historyItems.value + item
        if (InputHistoryScope.isInputRecord(item)) {
            _visibleHistoryItems.value = _visibleHistoryItems.value + item
            refreshVisibleHistoryHasMore(_visibleHistoryItems.value)
        }
    }

    private suspend fun updateHistoryItem(
        itemId: String,
        transform: (HistoryItem) -> HistoryItem
    ) {
        historyRepository.updateHistoryItem(itemId, transform)
        _historyItems.value = _historyItems.value.map { item ->
            if (item.id == itemId) transform(item) else item
        }
        _visibleHistoryItems.value = _visibleHistoryItems.value.map { item ->
            if (item.id == itemId) transform(item) else item
        }.filter(InputHistoryScope::isInputRecord)
    }

    private fun replaceHistoryItemInState(updated: HistoryItem) {
        _historyItems.value = _historyItems.value.map { item ->
            if (item.id == updated.id) updated else item
        }
        _visibleHistoryItems.value = _visibleHistoryItems.value.map { item ->
            if (item.id == updated.id) updated else item
        }.filter(InputHistoryScope::isInputRecord)
    }

    private suspend fun refreshVisibleHistoryHasMore(items: List<HistoryItem>) {
        val oldestItem = items.firstOrNull()
        _historyHasMore.value = oldestItem != null && olderInputHistoryPage(oldestItem, 1).isNotEmpty()
    }

    private suspend fun latestInputHistoryPage(limit: Int): List<HistoryItem> {
        if (limit <= 0) return emptyList()
        return InputHistoryScope.filterInputRecords(historyRepository.loadHistory()).takeLast(limit)
    }

    private suspend fun olderInputHistoryPage(oldestVisibleItem: HistoryItem, limit: Int): List<HistoryItem> {
        if (limit <= 0) return emptyList()
        val inputItems = InputHistoryScope.filterInputRecords(historyRepository.loadHistory())
        val boundaryIndex = inputItems.indexOfFirst { item ->
            item.timestamp == oldestVisibleItem.timestamp && item.id == oldestVisibleItem.id
        }
        if (boundaryIndex <= 0) return emptyList()
        return inputItems.subList(maxOf(0, boundaryIndex - limit), boundaryIndex)
    }

    private fun createHistoryItem(
        text: String,
        targetDeviceId: String,
        targetDeviceName: String,
        contentType: String,
        channel: String,
        syncStatus: SyncStatus,
        sourceApp: String = "",
        sourcePackage: String = "",
        metadata: String = ""
    ): HistoryItem {
        val now = System.currentTimeMillis()
        return HistoryItem(
            id = "$now-${(1000..9999).random()}",
            text = text,
            timestamp = now,
            targetDeviceId = targetDeviceId,
            targetDeviceName = targetDeviceName,
            contentType = contentType,
            syncStatus = syncStatus,
            channel = channel,
            sourceApp = sourceApp,
            sourcePackage = sourcePackage,
            metadata = metadata
        )
    }

    private fun imageHistoryMetadata(payload: ClipboardImagePayload): String {
        return JsonObject().apply {
            addProperty("file_name", payload.fileName)
            addProperty("mime_type", payload.mimeType)
            addProperty("width", payload.width)
            addProperty("height", payload.height)
            addProperty("size", payload.size)
        }.toString()
    }

    private fun fileHistoryMetadata(payload: ClipboardFilePayload): String {
        return JsonObject().apply {
            addProperty("file_name", payload.fileName)
            addProperty("mime_type", payload.mimeType)
            addProperty("size", payload.size)
        }.toString()
    }

    private fun takePendingRelayHistoryId(): String? {
        return if (pendingRelayHistoryIds.isEmpty()) null else pendingRelayHistoryIds.removeFirst()
    }

    private fun buildConnectionName(deviceName: String): String {
        return deviceName
    }

    private fun syncSelectedRelayDeviceStatus() {
        val targetId = relayTargetDeviceId
        if (targetId.isNullOrBlank()) {
            _connectionStatus.value = ConnectionStatus(connected = false, deviceName = "")
            configManager.saveForegroundConnectionStatus(false, "")
            return
        }

        val paired = _pairedDevices.value[targetId]
        val resolvedName = paired?.deviceName
            ?: relayTargetDeviceName
            ?: configManager.getLastTargetDevice()?.takeIf { it.deviceId == targetId }?.deviceName
            ?: targetId

        relayTargetDeviceName = resolvedName

        val online = serverConnection.isConnected() &&
            serverDevices.value.any { it.deviceId == targetId && it.online }

        val status = ConnectionStatus(
            connected = online,
            deviceName = buildConnectionName(resolvedName)
        )
        _connectionStatus.value = status
        configManager.saveForegroundConnectionStatus(status.connected, status.deviceName)
    }

    private fun updateSendAvailability() {
        val canSendViaServer = relayTargetDeviceId != null && serverConnection.isConnected()
        val canSendViaLan = networkManager.isConnected.value
        _sendAvailable.value = canSendViaServer || canSendViaLan
    }

    data class ConnectionStatus(
        val connected: Boolean,
        val deviceName: String
    )

}

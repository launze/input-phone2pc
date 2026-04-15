package com.voiceinput.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.voiceinput.data.ConfigManager
import com.voiceinput.data.model.ClipboardImagePayload
import com.voiceinput.data.model.Device
import com.voiceinput.data.model.HistoryItem
import com.voiceinput.data.model.NotificationData
import com.voiceinput.data.model.ServerConfig
import com.voiceinput.data.model.ServerDeviceInfo
import com.voiceinput.data.model.SyncStatus
import com.voiceinput.data.repository.HistoryRepository
import com.voiceinput.network.NetworkManager
import com.voiceinput.network.ServerConnection
import com.voiceinput.service.NotificationListenerService
import com.voiceinput.util.ImageTransferCodec
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

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<Device>>(emptyList())
    val discoveredDevices: StateFlow<List<Device>> = _discoveredDevices.asStateFlow()

    private val _connectionLog = MutableStateFlow<List<String>>(emptyList())
    val connectionLog: StateFlow<List<String>> = _connectionLog.asStateFlow()

    private val _pairedDevices =
        MutableStateFlow<Map<String, ConfigManager.PairedDevice>>(emptyMap())
    val pairedDevices: StateFlow<Map<String, ConfigManager.PairedDevice>> = _pairedDevices.asStateFlow()

    private val _notificationForwardingEnabled =
        MutableStateFlow(configManager.isNotificationEnabled())
    val notificationForwardingEnabled: StateFlow<Boolean> =
        _notificationForwardingEnabled.asStateFlow()

    private var relayTargetDeviceId: String? = null
    private var relayTargetDeviceName: String? = null
    private var pendingScannedPair: PendingScannedPair? = null
    private var heartbeatJob: Job? = null
    private var deviceListRefreshJob: Job? = null

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
                syncLanConnectionStatus()
                updateSendAvailability()
            }
        }

        viewModelScope.launch {
            networkManager.connectedDevice.collect {
                syncLanConnectionStatus()
                updateSendAvailability()
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

    private fun registerServerCallbacks() {
        serverConnection.onRelayMessageReceived = { fromDeviceId, payload ->
            Log.d(TAG, "Relay message from $fromDeviceId: $payload")
            if (payload.get("type")?.asString == "INPUT_ACK") {
                handleRelayAck(fromDeviceId, payload)
            }
        }

        serverConnection.onRelayStored = { messageId, toDeviceId, storedAt, queued ->
            viewModelScope.launch {
                val targetName =
                    relayTargetDeviceName ?: pairedDevices.value[toDeviceId]?.deviceName ?: toDeviceId
                val localHistoryId = takePendingRelayHistoryId()
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
                        item.copy(
                            serverMessageId = messageId,
                            storedAt = if (queued) storedAt else null,
                            syncStatus = if (queued) SyncStatus.STORED else SyncStatus.PENDING,
                            errorMessage = null
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
                                localPort = pending.localPort
                            )
                            pendingScannedPair = null
                        } else {
                            configManager.savePairedDevice(
                                deviceId = otherId,
                                deviceName = otherName.ifBlank { "Desktop" },
                                deviceType = "desktop"
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
                deviceType = deviceType
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

        viewModelScope.launch {
            if (serverMessageId != null) {
                val updated = historyRepository.updateHistoryByServerMessageId(serverMessageId) { item ->
                    item.copy(
                        syncStatus = if (success) SyncStatus.SYNCED else SyncStatus.FAILED,
                        syncedAt = if (success) System.currentTimeMillis() else item.syncedAt,
                        storedAt = if (success) null else item.storedAt,
                        errorMessage = if (success) null else "电脑处理失败，请稍后重试"
                    )
                }
                updated?.let { replaceHistoryItemInState(it) }
            }

            if (success) {
                if (contentType == "image") {
                    addLog("电脑已接收图片 from $fromDeviceId")
                } else {
                    addLog("电脑已接收文本 from $fromDeviceId")
                }
            } else {
                addLog("电脑处理失败 from $fromDeviceId")
            }
        }
    }

    private fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _connectionLog.value = _connectionLog.value + "[$timestamp] $message"
        if (_connectionLog.value.size > 50) {
            _connectionLog.value = _connectionLog.value.takeLast(50)
        }
    }

    private fun registerNotificationCallbacks() {
        NotificationListenerService.onNotificationReceived = { notification ->
            if (_notificationForwardingEnabled.value) {
                viewModelScope.launch {
                    forwardNotification(notification)
                }
            }
        }
    }

    private suspend fun forwardNotification(notification: NotificationData) {
        val targetId = relayTargetDeviceId ?: return
        if (!serverConnection.isConnected()) {
            return
        }

        val forwarded = serverConnection.sendNotificationForward(targetId, notification)
        if (forwarded) {
            addLog("已转发通知: ${notification.appName}")
        }
    }

    fun clearLog() {
        _connectionLog.value = emptyList()
    }

    fun setNotificationForwardingEnabled(enabled: Boolean) {
        configManager.saveNotificationEnabled(enabled)
        _notificationForwardingEnabled.value = enabled
        addLog(if (enabled) "通知转发已开启" else "通知转发已关闭")
    }

    fun refreshPairedDevices() {
        _pairedDevices.value = configManager.getPairedDevices()
    }

    private fun restoreSelectedRelayDevice() {
        val devices = _pairedDevices.value
        if (devices.isEmpty()) {
            relayTargetDeviceId = null
            relayTargetDeviceName = null
            _connectionStatus.value = ConnectionStatus(connected = false, deviceName = "")
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
            return
        }

        relayTargetDeviceId = restored.first
        relayTargetDeviceName = restored.second
        configManager.saveLastTargetDevice(restored.first, restored.second)
        syncSelectedRelayDeviceStatus()
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
                relayTargetDeviceId = null
                relayTargetDeviceName = null
                configManager.saveLastTargetDevice(device.deviceId, device.deviceName)
                _connectionStatus.value = ConnectionStatus(
                    connected = true,
                    deviceName = buildLanConnectionName(device.deviceName),
                    deviceId = device.deviceId,
                    transport = "lan"
                )
                addLog("已连接局域网设备: ${device.deviceName}")
            }
            updateSendAvailability()
        }
    }

    fun connectToServerDevice(device: ServerDeviceInfo) {
        networkManager.disconnect()
        relayTargetDeviceId = device.deviceId
        relayTargetDeviceName = device.deviceName
        configManager.saveLastTargetDevice(device.deviceId, device.deviceName)
        syncSelectedRelayDeviceStatus()
        addLog("已选择远程设备: ${device.deviceName}")
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
        if (relayTargetDeviceId == deviceId) {
            relayTargetDeviceId = null
            relayTargetDeviceName = null
            restoreSelectedRelayDevice()
        }
        updateSendAvailability()
    }

    fun disconnectFromServerDevice() {
        relayTargetDeviceId = null
        relayTargetDeviceName = null
        configManager.clearLastTargetDevice()
        _connectionStatus.value = ConnectionStatus(connected = false, deviceName = "")
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
                    version = "1.0.0"
                )
                val success = networkManager.connectToDevice(device)
                if (success) {
                    configManager.savePairedDevice(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        deviceType = "desktop",
                        localIp = localIp,
                        localPort = localPort
                    )
                    configManager.saveLastTargetDevice(deviceId, deviceName)
                    relayTargetDeviceId = null
                    relayTargetDeviceName = null
                    refreshPairedDevices()
                    pendingScannedPair = null

                    _connectionStatus.value = ConnectionStatus(
                        connected = true,
                        deviceName = buildLanConnectionName(deviceName),
                        deviceId = deviceId,
                        transport = "lan"
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
    }

    fun sendText() {
        val text = _inputText.value
        if (text.isBlank()) return
        _inputText.value = ""

        viewModelScope.launch {
            when {
                relayTargetDeviceId != null && serverConnection.isConnected() -> sendTextViaRelay(text)
                networkManager.isConnected.value -> sendTextViaLan(text)
                else -> {
                    addLog("未连接到任何设备，无法发送")
                    _inputText.value = text
                }
            }
        }
    }

    fun sendImage(uri: Uri) {
        viewModelScope.launch {
            if (!sendAvailable.value) {
                addLog("未连接到任何设备，无法发送图片")
                return@launch
            }

            addLog("正在压缩图片...")
            val payload = ImageTransferCodec.encodeFromUri(getApplication(), uri)
            if (payload == null) {
                addLog("图片处理失败")
                return@launch
            }

            when {
                relayTargetDeviceId != null && serverConnection.isConnected() -> sendImageViaRelay(payload)
                networkManager.isConnected.value -> sendImageViaLan(payload)
                else -> addLog("未连接到任何设备，无法发送图片")
            }
        }
    }

    private suspend fun sendTextViaRelay(text: String) {
        val targetId = relayTargetDeviceId ?: return
        val targetName = relayTargetDeviceName ?: targetId
        val payload = JsonObject().apply {
            addProperty("type", "TEXT_INPUT")
            addProperty("text", text)
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
        addHistoryItem(historyItem)

        if (serverConnection.sendRelayMessage(targetId, payload)) {
            pendingRelayHistoryIds.addLast(historyItem.id)
            addLog("已发送文本到 $targetName (中转)")
        } else {
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

    private suspend fun sendTextViaLan(text: String) {
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

        val success = networkManager.sendText(text)
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
            syncStatus = SyncStatus.PENDING
        )
        addHistoryItem(historyItem)

        if (serverConnection.sendRelayMessage(targetId, relayPayload)) {
            pendingRelayHistoryIds.addLast(historyItem.id)
            addLog("已发送图片到 $targetName (中转)")
        } else {
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

    private suspend fun sendImageViaLan(payload: ClipboardImagePayload) {
        val targetName = connectionStatus.value.deviceName.ifBlank { "局域网设备" }
        val historyItem = createHistoryItem(
            text = "[图片] ${payload.fileName}",
            targetDeviceId = relayTargetDeviceId ?: "",
            targetDeviceName = targetName,
            contentType = "image",
            channel = "lan",
            syncStatus = SyncStatus.PENDING
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
        } else {
            updateHistoryItem(historyItem.id) { item ->
                item.copy(
                    syncStatus = SyncStatus.FAILED,
                    errorMessage = "局域网图片发送失败"
                )
            }
            addLog("局域网图片发送失败")
        }
    }

    fun retryFailedTextItems() {
        viewModelScope.launch {
            val retryableItems = _historyItems.value
                .filter { it.syncStatus == SyncStatus.FAILED && it.contentType == "text" }
                .sortedBy { it.timestamp }

            if (retryableItems.isEmpty()) {
                _uiMessages.tryEmit("没有可重试的失败文本")
                return@launch
            }

            var successCount = 0
            retryableItems.forEach { item ->
                if (retryTextItem(item)) {
                    successCount++
                }
            }

            _uiMessages.tryEmit("已重试 $successCount/${retryableItems.size} 条失败文本")
        }
    }

    fun retryHistoryItem(itemId: String) {
        viewModelScope.launch {
            val item = _historyItems.value.firstOrNull { it.id == itemId }
                ?: _visibleHistoryItems.value.firstOrNull { it.id == itemId }

            if (item == null) {
                _uiMessages.tryEmit("未找到要重试的记录")
                return@launch
            }

            if (item.contentType != "text") {
                _uiMessages.tryEmit("暂不支持重试图片记录")
                return@launch
            }

            retryTextItem(item)
        }
    }

    private suspend fun retryTextItem(item: HistoryItem): Boolean {
        val refreshedAt = System.currentTimeMillis()
        updateHistoryItem(item.id) { current ->
            current.copy(
                timestamp = refreshedAt,
                syncStatus = SyncStatus.PENDING,
                serverMessageId = null,
                storedAt = null,
                syncedAt = null,
                errorMessage = null
            )
        }

        return if (item.channel == "server") {
            retryServerTextItem(item)
        } else {
            retryLanTextItem(item)
        }
    }

    private suspend fun retryServerTextItem(item: HistoryItem): Boolean {
        val targetId = item.targetDeviceId.ifBlank { relayTargetDeviceId ?: "" }
        val targetName = item.targetDeviceName.ifBlank { relayTargetDeviceName ?: "远程设备" }

        if (targetId.isBlank()) {
            markRetryFailed(item.id, "缺少远程目标设备")
            return false
        }

        relayTargetDeviceId = targetId
        relayTargetDeviceName = targetName
        configManager.saveLastTargetDevice(targetId, targetName)
        syncSelectedRelayDeviceStatus()

        if (!serverConnection.isConnected()) {
            markRetryFailed(item.id, "远程连接未就绪")
            return false
        }

        val payload = JsonObject().apply {
            addProperty("type", "TEXT_INPUT")
            addProperty("text", item.text)
            addProperty("timestamp", System.currentTimeMillis())
        }

        return if (serverConnection.sendRelayMessage(targetId, payload)) {
            pendingRelayHistoryIds.addLast(item.id)
            addLog("已重试远程发送: $targetName")
            true
        } else {
            markRetryFailed(item.id, "未能重新提交到服务器")
            false
        }
    }

    private suspend fun retryLanTextItem(item: HistoryItem): Boolean {
        val paired = _pairedDevices.value[item.targetDeviceId]
        if (paired == null || paired.localIp.isBlank() || paired.localPort <= 0) {
            markRetryFailed(item.id, "缺少局域网地址，请重新扫码配对")
            return false
        }

        val device = Device(
            deviceId = paired.deviceId,
            deviceName = paired.deviceName,
            ip = paired.localIp,
            port = paired.localPort,
            version = "1.0.0"
        )

        val alreadyConnected = networkManager.isConnected.value &&
            networkManager.connectedDevice.value?.deviceId == device.deviceId

        if (!alreadyConnected) {
            val success = networkManager.connectToDevice(device)
            if (!success) {
                markRetryFailed(item.id, "无法重新连接局域网设备")
                return false
            }
            relayTargetDeviceId = null
            relayTargetDeviceName = null
            configManager.saveLastTargetDevice(device.deviceId, device.deviceName)
            _connectionStatus.value = ConnectionStatus(
                connected = true,
                deviceName = buildLanConnectionName(device.deviceName),
                deviceId = device.deviceId,
                transport = "lan"
            )
        }

        return if (networkManager.sendText(item.text)) {
            updateHistoryItem(item.id) { current ->
                current.copy(
                    timestamp = System.currentTimeMillis(),
                    syncStatus = SyncStatus.DIRECT,
                    syncedAt = System.currentTimeMillis(),
                    errorMessage = null
                )
            }
            addLog("已重试局域网发送: ${device.deviceName}")
            true
        } else {
            markRetryFailed(item.id, "局域网重试发送失败")
            false
        }
    }

    private suspend fun markRetryFailed(itemId: String, message: String) {
        updateHistoryItem(itemId) { current ->
            current.copy(syncStatus = SyncStatus.FAILED, errorMessage = message)
        }
        addLog(message)
    }

    fun onHistoryItemClick(item: HistoryItem) {
        if (item.contentType == "text") {
            _inputText.value = item.text
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

        val latestItems = historyRepository.loadRecentHistoryPage(HISTORY_PAGE_SIZE)
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
            val olderItems = historyRepository.loadHistoryBefore(
                beforeTimestamp = oldestVisibleItem.timestamp,
                beforeId = oldestVisibleItem.id,
                limit = HISTORY_PAGE_SIZE
            )

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
        return when (format.lowercase(Locale.ROOT)) {
            "md" -> buildHistoryExportMarkdown(items)
            "csv" -> buildHistoryExportCsv(items)
            else -> buildHistoryExportText(items)
        }
    }

    fun suggestedHistoryExportFileName(format: String): String {
        val normalized = format.lowercase(Locale.ROOT)
        val extension = if (normalized in setOf("txt", "md", "csv")) normalized else "txt"
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        return "voice-input-history-$timestamp.$extension"
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

    private fun buildHistoryExportText(items: List<HistoryItem>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val builder = StringBuilder()
        builder.appendLine("语音输入助手 - 历史记录")
        builder.appendLine("导出时间: ${dateFormat.format(Date())}")
        builder.appendLine("记录数: ${items.size}")
        builder.appendLine()

        sortedForExport(items).forEachIndexed { index, item ->
            builder.appendLine("${index + 1}. ${dateFormat.format(Date(item.timestamp))}")
            builder.appendLine("状态: ${syncStatusLabel(item.syncStatus)}")
            if (item.targetDeviceName.isNotBlank()) {
                builder.appendLine("设备: ${item.targetDeviceName}")
            }
            builder.appendLine(item.text)
            builder.appendLine()
        }

        return builder.toString().trimEnd()
    }

    private fun buildHistoryExportMarkdown(items: List<HistoryItem>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val builder = StringBuilder()
        builder.appendLine("# 语音输入助手历史记录")
        builder.appendLine()
        builder.appendLine("- 导出时间: ${dateFormat.format(Date())}")
        builder.appendLine("- 记录数: ${items.size}")
        builder.appendLine()

        sortedForExport(items).forEachIndexed { index, item ->
            builder.appendLine("## ${index + 1}. ${dateFormat.format(Date(item.timestamp))}")
            builder.appendLine()
            builder.appendLine("- 状态: ${syncStatusLabel(item.syncStatus)}")
            builder.appendLine("- 通道: ${item.channel}")
            if (item.targetDeviceName.isNotBlank()) {
                builder.appendLine("- 设备: ${item.targetDeviceName}")
            }
            builder.appendLine()
            builder.appendLine(item.text)
            builder.appendLine()
        }

        return builder.toString().trimEnd()
    }

    private fun buildHistoryExportCsv(items: List<HistoryItem>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val builder = StringBuilder()
        builder.appendLine("id,timestamp,target_device,status,channel,content_type,text")
        sortedForExport(items).forEach { item ->
            builder.appendLine(
                listOf(
                    item.id,
                    dateFormat.format(Date(item.timestamp)),
                    item.targetDeviceName.ifBlank { item.targetDeviceId },
                    syncStatusLabel(item.syncStatus),
                    item.channel,
                    item.contentType,
                    item.text
                ).joinToString(",") { escapeCsv(it) }
            )
        }
        return builder.toString().trimEnd()
    }

    private fun sortedForExport(items: List<HistoryItem>): List<HistoryItem> {
        return items.sortedBy { it.timestamp }
    }

    private fun syncStatusLabel(status: SyncStatus): String {
        return when (status) {
            SyncStatus.PENDING -> "等待电脑确认"
            SyncStatus.STORED -> "已暂存"
            SyncStatus.SYNCED -> "已发送"
            SyncStatus.FAILED -> "失败"
            SyncStatus.DIRECT -> "局域网直连"
        }
    }

    private fun escapeCsv(value: String): String {
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private suspend fun addHistoryItem(item: HistoryItem) {
        historyRepository.addHistoryItem(item)
        _historyItems.value = _historyItems.value + item
        _visibleHistoryItems.value = _visibleHistoryItems.value + item
        refreshVisibleHistoryHasMore(_visibleHistoryItems.value)
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
        }
    }

    private fun replaceHistoryItemInState(updated: HistoryItem) {
        _historyItems.value = _historyItems.value.map { item ->
            if (item.id == updated.id) updated else item
        }
        _visibleHistoryItems.value = _visibleHistoryItems.value.map { item ->
            if (item.id == updated.id) updated else item
        }
    }

    private suspend fun refreshVisibleHistoryHasMore(items: List<HistoryItem>) {
        val oldestItem = items.firstOrNull()
        _historyHasMore.value = oldestItem != null && historyRepository.hasHistoryBefore(
            beforeTimestamp = oldestItem.timestamp,
            beforeId = oldestItem.id
        )
    }

    private fun createHistoryItem(
        text: String,
        targetDeviceId: String,
        targetDeviceName: String,
        contentType: String,
        channel: String,
        syncStatus: SyncStatus
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
            channel = channel
        )
    }

    private fun takePendingRelayHistoryId(): String? {
        return if (pendingRelayHistoryIds.isEmpty()) null else pendingRelayHistoryIds.removeFirst()
    }

    private fun buildConnectionName(deviceName: String): String {
        return deviceName
    }

    private fun buildLanConnectionName(deviceName: String): String {
        return deviceName
    }

    private fun syncLanConnectionStatus() {
        val connectedDevice = networkManager.connectedDevice.value
        val lanConnected = networkManager.isConnected.value && connectedDevice != null

        if (lanConnected && relayTargetDeviceId == null) {
            val device = connectedDevice ?: return
            _connectionStatus.value = ConnectionStatus(
                connected = true,
                deviceName = buildLanConnectionName(device.deviceName),
                deviceId = device.deviceId,
                transport = "lan"
            )
            return
        }

        if (!lanConnected && _connectionStatus.value.transport == "lan") {
            if (relayTargetDeviceId != null) {
                syncSelectedRelayDeviceStatus()
            } else {
                _connectionStatus.value = ConnectionStatus(connected = false, deviceName = "")
            }
        }
    }

    private fun syncSelectedRelayDeviceStatus() {
        val targetId = relayTargetDeviceId
        if (targetId.isNullOrBlank()) {
            _connectionStatus.value = ConnectionStatus(connected = false, deviceName = "")
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

        _connectionStatus.value = ConnectionStatus(
            connected = online,
            deviceName = buildConnectionName(resolvedName),
            deviceId = targetId,
            transport = "server"
        )
    }

    private fun updateSendAvailability() {
        val canSendViaServer = relayTargetDeviceId != null && serverConnection.isConnected()
        val canSendViaLan = networkManager.isConnected.value && relayTargetDeviceId == null
        _sendAvailable.value = canSendViaServer || canSendViaLan
    }

    override fun onCleared() {
        NotificationListenerService.onNotificationReceived = null
        heartbeatJob?.cancel()
        deviceListRefreshJob?.cancel()
        serverConnection.release()
        networkManager.disconnect()
        super.onCleared()
    }

    data class ConnectionStatus(
        val connected: Boolean,
        val deviceName: String,
        val deviceId: String? = null,
        val transport: String = ""
    )
}

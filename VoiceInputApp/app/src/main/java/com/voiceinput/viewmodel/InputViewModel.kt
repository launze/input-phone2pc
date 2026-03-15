package com.voiceinput.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.voiceinput.data.ConfigManager
import com.voiceinput.data.model.Device
import com.voiceinput.data.model.HistoryItem
import com.voiceinput.data.model.ServerConfig
import com.voiceinput.data.model.ServerDeviceInfo
import com.voiceinput.network.NetworkManager
import com.voiceinput.network.ServerConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class InputViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "InputViewModel"
    }

    private val gson = Gson()
    private val configManager = ConfigManager(application.applicationContext)
    private val networkManager = NetworkManager()
    private val serverConnection = ServerConnection(application.applicationContext)

    val serverConnectionState: StateFlow<ServerConnection.ConnectionState> =
        serverConnection.connectionState
    val serverInfo: StateFlow<ServerConnection.ServerInfo?> =
        serverConnection.serverInfo
    val serverDevices: StateFlow<List<ServerDeviceInfo>> =
        serverConnection.serverDevices

    private val _connectionStatus = MutableStateFlow(ConnectionStatus(connected = false, deviceName = ""))
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _historyItems = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyItems: StateFlow<List<HistoryItem>> = _historyItems.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<Device>>(emptyList())
    val discoveredDevices: StateFlow<List<Device>> = _discoveredDevices.asStateFlow()

    private val _connectionLog = MutableStateFlow<List<String>>(emptyList())
    val connectionLog: StateFlow<List<String>> = _connectionLog.asStateFlow()

    private val _pairedDevices = MutableStateFlow<Map<String, ConfigManager.PairedDevice>>(emptyMap())
    val pairedDevices: StateFlow<Map<String, ConfigManager.PairedDevice>> = _pairedDevices.asStateFlow()

    private var relayTargetDeviceId: String? = null
    private var relayTargetDeviceName: String? = null

    private data class PendingScannedPair(
        val serverUrl: String,
        val deviceId: String,
        val deviceName: String,
        val localIp: String,
        val localPort: Int
    )

    private var pendingScannedPair: PendingScannedPair? = null

    private var heartbeatJob: Job? = null
    private var deviceListRefreshJob: Job? = null

    init {
        refreshPairedDevices()

        serverConnection.onRelayMessageReceived = { fromDeviceId, payload ->
            Log.d(TAG, "Relay message from $fromDeviceId: $payload")
            val type = payload.get("type")?.asString
            if (type == "INPUT_ACK") {
                addLog("收到输入确认 from $fromDeviceId")
            }
        }

        serverConnection.onPairConfirmed = { success, fromDeviceId, fromDeviceName, toDeviceId, toDeviceName, message ->
            if (success) {
                addLog("配对成功: $message")

                val myDeviceId = configManager.getDeviceId()
                val otherId = if (fromDeviceId == myDeviceId) toDeviceId else fromDeviceId
                val otherName = if (fromDeviceId == myDeviceId) toDeviceName else fromDeviceName
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

            val device = ServerDeviceInfo(
                deviceId = deviceId,
                deviceName = deviceName,
                deviceType = deviceType,
                online = true
            )
            connectToServerDevice(device)
            addLog("已自动连接到配对设备: $deviceName")
        }

        serverConnection.onPairedDeviceOffline = { deviceId, deviceName, deviceType ->
            addLog("配对设备离线: $deviceName ($deviceType)")
            if (relayTargetDeviceId == deviceId) {
                _connectionStatus.value = ConnectionStatus(connected = false, deviceName = "")
                addLog("设备 $deviceName 已离线，连接已断开")
            }
        }

        serverConnection.onUnpairNotify = { deviceId, deviceName ->
            addLog("对方已取消配对: $deviceName")
            configManager.removePairedDevice(deviceId)
            refreshPairedDevices()
            if (relayTargetDeviceId == deviceId) {
                disconnectFromServerDevice()
            }
        }

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

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
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
            }
        }
    }

    fun connectToServerDevice(device: ServerDeviceInfo) {
        relayTargetDeviceId = device.deviceId
        relayTargetDeviceName = device.deviceName
        _connectionStatus.value = ConnectionStatus(
            connected = true,
            deviceName = "${device.deviceName} (服务器中转)"
        )
        addLog("已选择服务器设备: ${device.deviceName}")
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
            disconnectFromServerDevice()
        }
    }

    fun disconnectFromServerDevice() {
        relayTargetDeviceId = null
        relayTargetDeviceName = null
        _connectionStatus.value = ConnectionStatus(connected = false, deviceName = "")
    }

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
            val shouldReconnect = !serverConnection.isConnected() ||
                    !currentUrl.equals(serverUrl, ignoreCase = true)

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
        if (text.isEmpty()) return

        if (relayTargetDeviceId != null && serverConnection.isConnected()) {
            sendTextViaRelay(text)
        } else if (networkManager.isConnected.value) {
            networkManager.sendMessage(text)
        } else {
            addLog("未连接到任何设备，无法发送")
            return
        }

        val historyItem = HistoryItem(
            id = System.currentTimeMillis().toString(),
            text = text,
            timestamp = System.currentTimeMillis()
        )
        _historyItems.value = _historyItems.value + listOf(historyItem)
        _inputText.value = ""
    }

    private fun sendTextViaRelay(text: String) {
        val targetId = relayTargetDeviceId ?: return
        val payload = JsonObject().apply {
            addProperty("type", "TEXT_INPUT")
            addProperty("text", text)
            addProperty("timestamp", System.currentTimeMillis())
        }
        serverConnection.sendRelayMessage(targetId, payload)
        addLog("已发送文本到 ${relayTargetDeviceName ?: targetId} (中转)")
    }

    fun onHistoryItemClick(item: HistoryItem) {
        _inputText.value = item.text
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

            serverConnection.connect(config, deviceId, deviceName, object : okhttp3.WebSocketListener() {
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
                            "DEVICE_LIST_RESPONSE" -> {
                                val count = msg.devices?.size ?: 0
                                addLog("收到设备列表: $count 个")
                            }
                            "RELAY_MESSAGE" -> {
                                addLog("收到中转消息 from ${msg.fromDeviceId?.take(8)}...")
                            }
                            "SERVER_PAIR_RESPONSE" -> {
                                if (msg.success == true) addLog("配对成功") else addLog("配对失败: ${msg.message}")
                            }
                            "PAIRED_DEVICE_ONLINE" -> addLog("配对设备上线: ${msg.deviceName}")
                            "PAIRED_DEVICE_OFFLINE" -> addLog("配对设备离线: ${msg.deviceName}")
                            "UNPAIR_NOTIFY" -> addLog("对方取消配对: ${msg.deviceName}")
                            "ERROR" -> addLog("服务器错误: [${msg.code}] ${msg.message}")
                        }
                    } catch (e: Exception) {
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
            })

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
        disconnectFromServerDevice()
        serverConnection.disconnect()
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
            is ServerConnection.ConnectionState.Error -> "错误: ${state.message}"
            is ServerConnection.ConnectionState.Disconnected -> "未连接"
        }
    }

    fun isConnectedToServer(): Boolean {
        return serverConnection.isConnected()
    }

    data class ConnectionStatus(
        val connected: Boolean,
        val deviceName: String
    )
}

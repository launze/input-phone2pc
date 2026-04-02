package com.voiceinput.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.voiceinput.data.model.ServerDeviceInfo
import com.voiceinput.data.model.ServerMsg
import com.voiceinput.data.model.ServerConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ServerConnection(private val context: Context) {
    companion object {
        private const val TAG = "ServerConnection"
        private const val FAST_RETRY_COUNT = 3
        private val FAST_RETRY_DELAYS = longArrayOf(1_000L, 2_000L, 4_000L)
        private const val SLOW_RETRY_BASE  = 30_000L
        private const val MAX_RETRY_DELAY  = 120_000L
    }

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient = createDefaultClient()

    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private val userDisconnected = AtomicBoolean(false)

    private var savedConfig: ServerConfig? = null
    private var savedDeviceId: String = ""
    private var savedDeviceName: String = ""
    private var savedListener: WebSocketListener? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo

    private val _serverDevices = MutableStateFlow<List<ServerDeviceInfo>>(emptyList())
    val serverDevices: StateFlow<List<ServerDeviceInfo>> = _serverDevices

    private var sessionId: String? = null
    private var registeredDeviceId: String? = null

    var onRelayMessageReceived: ((fromDeviceId: String, payload: JsonObject) -> Unit)? = null
    var onRelayStored: ((messageId: String, toDeviceId: String, storedAt: Long, queued: Boolean) -> Unit)? = null
    var onPairConfirmed: ((success: Boolean, fromDeviceId: String, fromDeviceName: String, toDeviceId: String, toDeviceName: String, message: String) -> Unit)? = null
    var onPairedDeviceOnline: ((deviceId: String, deviceName: String, deviceType: String) -> Unit)? = null
    var onUnpairNotify: ((deviceId: String, deviceName: String) -> Unit)? = null
    var onPairedDeviceOffline: ((deviceId: String, deviceName: String, deviceType: String) -> Unit)? = null
    var onReconnecting: ((attempt: Int, delayMs: Long) -> Unit)? = null

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val serverUrl: String) : ConnectionState()
        data class Reconnecting(val attempt: Int, val delayMs: Long) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    data class ServerInfo(
        val url: String,
        val connectedAt: Long,
        val deviceCount: Int = 0,
        val latency: Long = 0
    )
    private fun createDefaultClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun createClient(serverUrl: String): OkHttpClient {
        if (!serverUrl.startsWith("wss://", ignoreCase = true)) return createDefaultClient()
        Log.d(TAG, "WSS URL, using trust-all client for: $serverUrl")
        return UnsafeOkHttpClient.getUnsafeClient(context)
    }

    fun connect(config: ServerConfig, deviceId: String, deviceName: String, listener: WebSocketListener? = null) {
        savedConfig     = config
        savedDeviceId   = deviceId
        savedDeviceName = deviceName
        savedListener   = listener
        userDisconnected.set(false)
        cancelReconnect()
        doConnect(config.serverUrl, deviceId, deviceName, listener)
    }

    fun disconnect() {
        userDisconnected.set(true)
        cancelReconnect()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        resetState()
    }

    fun release() {
        disconnect()
        connectionScope.cancel()
    }

    fun isConnected(): Boolean = _connectionState.value is ConnectionState.Connected

    private fun doConnect(url: String, deviceId: String, deviceName: String, listener: WebSocketListener?) {
        webSocket?.close(1000, "Reconnecting")
        webSocket = null
        _connectionState.value = ConnectionState.Connecting
        registeredDeviceId = deviceId
        client = createClient(url)
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected to $url")
                _connectionState.value = ConnectionState.Connected(url)
                _serverInfo.value = ServerInfo(url = url, connectedAt = System.currentTimeMillis())
                cancelReconnect()
                sendRegister(deviceId, deviceName)
                listener?.onOpen(webSocket, response)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: ${text.take(200)}")
                handleServerMessage(text)
                listener?.onMessage(webSocket, text)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing code=$code reason=$reason")
                resetState()
                listener?.onClosing(webSocket, code, reason)
                if (code != 1000 && !userDisconnected.get()) scheduleReconnect()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Connection failed: ${t.message}")
                _connectionState.value = ConnectionState.Error(t.message ?: "Connection failed")
                resetState(false)
                listener?.onFailure(webSocket, t, response)
                if (!userDisconnected.get()) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        val config = savedConfig ?: return
        if (userDisconnected.get()) return
        reconnectJob?.cancel()
        reconnectJob = connectionScope.launch {
            reconnectAttempt++
            val delay = reconnectDelay(reconnectAttempt)
            Log.d(TAG, "Scheduling reconnect attempt $reconnectAttempt in ${delay}ms")
            _connectionState.value = ConnectionState.Reconnecting(reconnectAttempt, delay)
            onReconnecting?.invoke(reconnectAttempt, delay)
            delay(delay)
            if (!userDisconnected.get()) {
                doConnect(config.serverUrl, savedDeviceId, savedDeviceName, savedListener)
            }
        }
    }

    private fun reconnectDelay(attempt: Int): Long {
        return if (attempt <= FAST_RETRY_COUNT) {
            FAST_RETRY_DELAYS[attempt - 1]
        } else {
            val exp = SLOW_RETRY_BASE * (1L shl (attempt - FAST_RETRY_COUNT - 1))
            exp.coerceAtMost(MAX_RETRY_DELAY)
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
    }

    private fun resetState(updateStateFlow: Boolean = true) {
        if (updateStateFlow) _connectionState.value = ConnectionState.Disconnected
        _serverInfo.value = null
        _serverDevices.value = emptyList()
        sessionId = null
    }

    private fun handleServerMessage(text: String) {
        try {
            val msg = gson.fromJson(text, ServerMsg::class.java)
            when (msg.type) {
                "SERVER_REGISTER_RESPONSE" -> {
                    if (msg.success == true) {
                        sessionId = msg.sessionId
                        Log.d(TAG, "Registered, sessionId=${msg.sessionId}")
                        requestDeviceList()
                    } else Log.w(TAG, "Registration failed: ${msg.message}")
                }
                "DEVICE_LIST_RESPONSE" -> {
                    val devices = msg.devices ?: emptyList()
                    val others = devices.filter { it.deviceId != registeredDeviceId }
                    _serverDevices.value = others
                    _serverInfo.value = _serverInfo.value?.copy(deviceCount = others.size)
                }
                "RELAY_MESSAGE" -> {
                    val fromId  = msg.fromDeviceId ?: return
                    val payload = msg.payload ?: return
                    onRelayMessageReceived?.invoke(fromId, payload)
                }
                "RELAY_STORED" -> {
                    val messageId = msg.messageId ?: return
                    val toDeviceId = msg.toDeviceId ?: return
                    val storedAt = msg.storedAt ?: System.currentTimeMillis()
                    val queued = msg.queued ?: false
                    onRelayStored?.invoke(messageId, toDeviceId, storedAt, queued)
                }
                "HEARTBEAT" -> Log.d(TAG, "Heartbeat received")
                "SERVER_PAIR_RESPONSE" -> {
                    val success  = msg.success ?: false
                    val fromId   = msg.fromDeviceId ?: ""
                    val fromName = msg.fromDeviceName ?: "Unknown"
                    val toId     = msg.toDeviceId ?: ""
                    val toName   = msg.toDeviceName ?: "Unknown"
                    val message  = msg.message ?: ""
                    Log.d(TAG, "📨 SERVER_PAIR_RESPONSE: success=$success, from=$fromName($fromId), to=$toName($toId), msg=$message")
                    Log.d(TAG, "   Parsed fields: fromDeviceId=${msg.fromDeviceId}, toDeviceId=${msg.toDeviceId}, fromDeviceName=${msg.fromDeviceName}, toDeviceName=${msg.toDeviceName}")
                    onPairConfirmed?.invoke(success, fromId, fromName, toId, toName, message)
                    if (onPairConfirmed == null) {
                        Log.w(TAG, "⚠️ onPairConfirmed callback is null!")
                    }
                }
                "PAIRED_DEVICE_ONLINE" -> {
                    val deviceId   = msg.deviceId ?: return
                    val deviceName = msg.deviceName ?: "Unknown"
                    val deviceType = msg.deviceType ?: "unknown"
                    Log.d(TAG, "Paired device online: $deviceName")
                    onPairedDeviceOnline?.invoke(deviceId, deviceName, deviceType)
                }
                "PAIRED_DEVICE_OFFLINE" -> {
                    val deviceId   = msg.deviceId ?: return
                    val deviceName = msg.deviceName ?: "Unknown"
                    val deviceType = msg.deviceType ?: "unknown"
                    Log.d(TAG, "Paired device offline: $deviceName")
                    onPairedDeviceOffline?.invoke(deviceId, deviceName, deviceType)
                }
                "UNPAIR_NOTIFY" -> {
                    val deviceId   = msg.deviceId ?: return
                    val deviceName = msg.deviceName ?: "Unknown"
                    Log.d(TAG, "Unpair notify from: $deviceName")
                    onUnpairNotify?.invoke(deviceId, deviceName)
                }
                "ERROR" -> Log.e(TAG, "Server error: [${msg.code}] ${msg.message}")
                else    -> Log.d(TAG, "Unhandled message type: ${msg.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse server message: ${e.message}")
        }
    }

    private fun sendRegister(deviceId: String, deviceName: String) {
        val msg = ServerMsg(
            type = "SERVER_REGISTER", deviceId = deviceId,
            deviceName = deviceName, deviceType = "android", version = "1.2.0"
        )
        sendRaw(gson.toJson(msg))
    }

    fun requestDeviceList(deviceType: String? = null) {
        val json = JsonObject().apply {
            addProperty("type", "DEVICE_LIST_REQUEST")
            addProperty("device_type", deviceType ?: "desktop")
        }
        sendRaw(json.toString())
    }

    fun sendRelayMessage(toDeviceId: String, payload: JsonObject): Boolean {
        val json = JsonObject().apply {
            addProperty("type", "RELAY_MESSAGE")
            addProperty("from_device_id", registeredDeviceId ?: "")
            addProperty("to_device_id", toDeviceId)
            add("payload", payload)
        }
        return sendRaw(json.toString())
    }

    fun sendUnpairRequest(fromDeviceId: String, toDeviceId: String): Boolean {
        val json = JsonObject().apply {
            addProperty("type", "UNPAIR_REQUEST")
            addProperty("from_device_id", fromDeviceId)
            addProperty("to_device_id", toDeviceId)
        }
        return sendRaw(json.toString())
    }

    fun sendPairRequest(fromDeviceId: String, fromDeviceName: String, toDeviceId: String): Boolean {
        val json = JsonObject().apply {
            addProperty("type", "SERVER_PAIR_REQUEST")
            addProperty("from_device_id", fromDeviceId)
            addProperty("from_device_name", fromDeviceName)
            addProperty("to_device_id", toDeviceId)
            addProperty("pin", "")
        }
        return sendRaw(json.toString())
    }

    fun sendHeartbeat() {
        val json = JsonObject().apply {
            addProperty("type", "HEARTBEAT")
            addProperty("timestamp", System.currentTimeMillis())
        }
        sendRaw(json.toString())
    }

    private fun sendRaw(json: String): Boolean {
        val sent = webSocket?.send(json) ?: false
        if (!sent) Log.w(TAG, "Failed to send: ${json.take(100)}")
        return sent
    }
}

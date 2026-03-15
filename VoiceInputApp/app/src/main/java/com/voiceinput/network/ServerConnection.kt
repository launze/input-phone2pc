package com.voiceinput.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.voiceinput.data.model.ServerDeviceInfo
import com.voiceinput.data.model.ServerMsg
import com.voiceinput.data.model.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class ServerConnection(private val context: Context) {
    companion object {
        private const val TAG = "ServerConnection"
    }

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient = createDefaultClient()

    // Connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo

    // Server-side device list
    private val _serverDevices = MutableStateFlow<List<ServerDeviceInfo>>(emptyList())
    val serverDevices: StateFlow<List<ServerDeviceInfo>> = _serverDevices

    // Session info after registration
    private var sessionId: String? = null
    private var registeredDeviceId: String? = null

    // Listener for relay messages received from server
    var onRelayMessageReceived: ((fromDeviceId: String, payload: JsonObject) -> Unit)? = null

    // Listener for pair confirmation
    var onPairConfirmed: ((success: Boolean, fromDeviceId: String, fromDeviceName: String, toDeviceId: String, toDeviceName: String, message: String) -> Unit)? = null

    // Listener for paired device coming online
    var onPairedDeviceOnline: ((deviceId: String, deviceName: String, deviceType: String) -> Unit)? = null

    // Listener for unpair notification from the other side
    var onUnpairNotify: ((deviceId: String, deviceName: String) -> Unit)? = null

    // Listener for paired device going offline
    var onPairedDeviceOffline: ((deviceId: String, deviceName: String, deviceType: String) -> Unit)? = null

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val serverUrl: String) : ConnectionState()
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
        if (!serverUrl.startsWith("wss://", ignoreCase = true)) {
            return createDefaultClient()
        }
        Log.d(TAG, "WSS URL, using trust-all client for: $serverUrl")
        return UnsafeOkHttpClient.getUnsafeClient(context)
    }

    fun connect(config: ServerConfig, deviceId: String, deviceName: String, listener: WebSocketListener? = null) {
        if (webSocket != null) {
            disconnect()
        }

        _connectionState.value = ConnectionState.Connecting
        registeredDeviceId = deviceId

        val url = config.serverUrl
        client = createClient(url)

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected to $url")
                _connectionState.value = ConnectionState.Connected(url)
                _serverInfo.value = ServerInfo(
                    url = url,
                    connectedAt = System.currentTimeMillis()
                )
                // Auto-register with server
                sendRegister(deviceId, deviceName)
                listener?.onOpen(webSocket, response)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: ${text.take(200)}")
                handleServerMessage(text)
                listener?.onMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.Disconnected
                _serverInfo.value = null
                _serverDevices.value = emptyList()
                sessionId = null
                listener?.onClosing(webSocket, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Connection failed", t)
                _connectionState.value = ConnectionState.Error(t.message ?: "Connection failed")
                _serverInfo.value = null
                _serverDevices.value = emptyList()
                sessionId = null
                listener?.onFailure(webSocket, t, response)
            }
        })
    }

    private fun handleServerMessage(text: String) {
        try {
            val msg = gson.fromJson(text, ServerMsg::class.java)
            when (msg.type) {
                "SERVER_REGISTER_RESPONSE" -> {
                    if (msg.success == true) {
                        sessionId = msg.sessionId
                        Log.d(TAG, "Registered with server, sessionId=${msg.sessionId}")
                        // After registration, request device list
                        requestDeviceList()
                    } else {
                        Log.w(TAG, "Registration failed: ${msg.message}")
                    }
                }
                "DEVICE_LIST_RESPONSE" -> {
                    val devices = msg.devices ?: emptyList()
                    // Filter out self
                    val otherDevices = devices.filter { it.deviceId != registeredDeviceId }
                    _serverDevices.value = otherDevices
                    Log.d(TAG, "Device list: ${otherDevices.size} devices (excluding self)")
                    _serverInfo.value = _serverInfo.value?.copy(deviceCount = otherDevices.size)
                }
                "RELAY_MESSAGE" -> {
                    val fromId = msg.fromDeviceId ?: return
                    val payload = msg.payload ?: return
                    Log.d(TAG, "Relay message from $fromId")
                    onRelayMessageReceived?.invoke(fromId, payload)
                }
                "HEARTBEAT" -> {
                    Log.d(TAG, "Heartbeat response received")
                }
                "SERVER_PAIR_RESPONSE" -> {
                    val success = msg.success ?: false
                    val fromId = msg.fromDeviceId ?: ""
                    val fromName = msg.fromDeviceName ?: "Unknown"
                    val toId = msg.toDeviceId ?: ""
                    val toName = msg.toDeviceName ?: "Unknown"
                    val message = msg.message ?: ""
                    Log.d(TAG, "Pair response: success=$success, from=$fromId, to=$toId")
                    onPairConfirmed?.invoke(success, fromId, fromName, toId, toName, message)
                }
                "PAIRED_DEVICE_ONLINE" -> {
                    val deviceId = msg.deviceId ?: return
                    val deviceName = msg.deviceName ?: "Unknown"
                    val deviceType = msg.deviceType ?: "unknown"
                    Log.d(TAG, "Paired device online: $deviceName ($deviceId)")
                    onPairedDeviceOnline?.invoke(deviceId, deviceName, deviceType)
                }
                "PAIRED_DEVICE_OFFLINE" -> {
                    val deviceId = msg.deviceId ?: return
                    val deviceName = msg.deviceName ?: "Unknown"
                    val deviceType = msg.deviceType ?: "unknown"
                    Log.d(TAG, "Paired device offline: $deviceName ($deviceId)")
                    onPairedDeviceOffline?.invoke(deviceId, deviceName, deviceType)
                }
                "UNPAIR_NOTIFY" -> {
                    val deviceId = msg.deviceId ?: return
                    val deviceName = msg.deviceName ?: "Unknown"
                    Log.d(TAG, "Unpair notify from: $deviceName ($deviceId)")
                    onUnpairNotify?.invoke(deviceId, deviceName)
                }
                "ERROR" -> {
                    Log.e(TAG, "Server error: [${msg.code}] ${msg.message}")
                }
                else -> {
                    Log.d(TAG, "Unhandled message type: ${msg.type}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse server message: ${e.message}")
        }
    }

    private fun sendRegister(deviceId: String, deviceName: String) {
        val msg = ServerMsg(
            type = "SERVER_REGISTER",
            deviceId = deviceId,
            deviceName = deviceName,
            deviceType = "android",
            version = "1.2.0"
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

    fun sendRelayMessage(toDeviceId: String, payload: JsonObject) {
        val json = JsonObject().apply {
            addProperty("type", "RELAY_MESSAGE")
            addProperty("from_device_id", registeredDeviceId ?: "")
            addProperty("to_device_id", toDeviceId)
            add("payload", payload)
        }
        sendRaw(json.toString())
    }

    fun sendUnpairRequest(fromDeviceId: String, toDeviceId: String) {
        val json = JsonObject().apply {
            addProperty("type", "UNPAIR_REQUEST")
            addProperty("from_device_id", fromDeviceId)
            addProperty("to_device_id", toDeviceId)
        }
        sendRaw(json.toString())
    }

    fun sendPairRequest(fromDeviceId: String, fromDeviceName: String, toDeviceId: String) {
        val json = JsonObject().apply {
            addProperty("type", "SERVER_PAIR_REQUEST")
            addProperty("from_device_id", fromDeviceId)
            addProperty("from_device_name", fromDeviceName)
            addProperty("to_device_id", toDeviceId)
            addProperty("pin", "")
        }
        sendRaw(json.toString())
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
        if (!sent) {
            Log.w(TAG, "Failed to send: ${json.take(100)}")
        }
        return sent
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
        _serverInfo.value = null
        _serverDevices.value = emptyList()
        sessionId = null
    }

    fun isConnected(): Boolean {
        return _connectionState.value is ConnectionState.Connected
    }
}

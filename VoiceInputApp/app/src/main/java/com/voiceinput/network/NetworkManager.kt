package com.voiceinput.network

import com.voiceinput.data.model.Device
import com.voiceinput.data.model.Message
import com.voiceinput.util.EncryptionUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.SecretKey
import java.util.UUID

class NetworkManager {
    private val udpDiscovery = UdpDiscovery()
    private var tcpConnection: TcpConnection? = null
    private var discoveryJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    private val _discoveredDevices = MutableStateFlow<List<Device>>(emptyList())
    val discoveredDevices: StateFlow<List<Device>> = _discoveredDevices.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectedDevice = MutableStateFlow<Device?>(null)
    val connectedDevice: StateFlow<Device?> = _connectedDevice.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private var shouldReconnect = false
    private var lastConnectedDevice: Device? = null
    private var lastDeviceName: String? = null
    private var encryptionKey: SecretKey? = null

    fun startDiscovery(deviceName: String, scope: CoroutineScope) {
        discoveryJob?.cancel()
        discoveryJob = scope.launch(Dispatchers.IO) {
            try {
                udpDiscovery.startDiscovery(deviceName).collect { device ->
                    val currentDevices = _discoveredDevices.value.toMutableList()
                    if (!currentDevices.any { it.deviceId == device.deviceId }) {
                        currentDevices.add(device)
                        _discoveredDevices.value = currentDevices
                    }
                }
            } catch (e: Exception) {
                _connectionError.value = "设备发现失败: ${e.message}"
            }
        }
    }
    
    // 为 InputViewModel 提供的方法
    fun startUdpDiscovery(callback: (List<Device>) -> Unit) {
        val scope = CoroutineScope(Dispatchers.IO)
        startDiscovery("Android Device", scope)
        
        // 定期更新设备列表
        scope.launch {
            while (isActive) {
                delay(1000)
                callback(_discoveredDevices.value)
            }
        }
    }
    
    suspend fun connectToDevice(device: Device): Boolean {
        return connectToDevice(device, "Android Device")
    }
    
    fun sendMessage(text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            sendText(text)
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    suspend fun connectToDevice(device: Device, deviceName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                _connectionError.value = null
                tcpConnection?.disconnect()
                tcpConnection = TcpConnection(device.ip, device.port)
                
                if (!tcpConnection!!.connect()) {
                    _connectionError.value = "无法连接到设备"
                    return@withContext false
                }

                // 发送配对请求
                val pin = generatePin()
                val pairRequest = Message.PairRequest(
                    deviceId = UUID.randomUUID().toString(),
                    deviceName = deviceName,
                    pin = pin
                )

                if (!tcpConnection!!.sendMessage(pairRequest)) {
                    _connectionError.value = "发送配对请求失败"
                    tcpConnection?.disconnect()
                    return@withContext false
                }

                // 等待配对响应（超时10秒）
                val response = withTimeoutOrNull(10000) {
                    tcpConnection!!.receiveMessage()
                }

                if (response is Message.PairResponse && response.success) {
                    _isConnected.value = true
                    _connectedDevice.value = device
                    lastConnectedDevice = device
                    lastDeviceName = deviceName
                    shouldReconnect = true
                    
                    // 生成并发送加密密钥
                generateEncryptionKey()
                val keyBase64 = getEncryptionKeyBase64() ?: ""
                val keyExchange = Message.EncryptionKeyExchange(
                    deviceId = UUID.randomUUID().toString(),
                    publicKey = keyBase64
                )
                tcpConnection!!.sendMessage(keyExchange)
                
                // 设置TCP连接的加密密钥
                val secretKey = com.voiceinput.util.EncryptionUtil.base64ToKey(keyBase64)
                tcpConnection!!.setEncryptionKey(secretKey)
                    
                    // 启动心跳检测
                    startHeartbeat()
                    
                    return@withContext true
                }

                _connectionError.value = "配对失败"
                tcpConnection?.disconnect()
                false
            } catch (e: Exception) {
                _connectionError.value = "连接错误: ${e.message}"
                tcpConnection?.disconnect()
                false
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            var missedHeartbeats = 0
            
            while (isActive && _isConnected.value) {
                delay(5000) // 每5秒发送一次心跳
                
                try {
                    val heartbeat = Message.Heartbeat(
                        timestamp = System.currentTimeMillis()
                    )
                    
                    if (tcpConnection?.sendMessage(heartbeat) == true) {
                        // 尝试接收心跳响应（超时3秒）
                        val response = withTimeoutOrNull(3000) {
                            tcpConnection?.receiveMessage()
                        }
                        
                        if (response is Message.Heartbeat) {
                            missedHeartbeats = 0
                        } else {
                            missedHeartbeats++
                        }
                    } else {
                        missedHeartbeats++
                    }
                    
                    // 如果连续3次心跳失败，认为连接断开
                    if (missedHeartbeats >= 3) {
                        handleDisconnection()
                        break
                    }
                } catch (e: Exception) {
                    missedHeartbeats++
                    if (missedHeartbeats >= 3) {
                        handleDisconnection()
                        break
                    }
                }
            }
        }
    }

    private fun handleDisconnection() {
        _isConnected.value = false
        _connectionError.value = "连接已断开"
        tcpConnection?.disconnect()
        
        // 清除加密密钥
        encryptionKey = null
        
        // 如果需要重连，启动重连机制
        if (shouldReconnect && lastConnectedDevice != null && lastDeviceName != null) {
            startReconnect()
        }
    }

    private fun startReconnect() {
        reconnectJob?.cancel()
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            var retryCount = 0
            
            while (isActive && shouldReconnect && !_isConnected.value) {
                retryCount++
                _connectionError.value = "正在重连... (尝试 $retryCount)"
                
                delay(3000) // 每3秒尝试一次
                
                val device = lastConnectedDevice
                val deviceName = lastDeviceName
                
                if (device != null && deviceName != null) {
                    val success = connectToDevice(device, deviceName)
                    if (success) {
                        _connectionError.value = null
                        break
                    }
                }
            }
        }
    }

    suspend fun sendText(text: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!_isConnected.value || tcpConnection == null) {
                    _connectionError.value = "未连接到设备"
                    return@withContext false
                }

                // 使用加密消息发送
                val message = Message.TextInput(
                    text = text,
                    timestamp = System.currentTimeMillis()
                )
                
                encryptionKey?.let { key ->
                    val messageJson = com.google.gson.Gson().toJson(message)
                    val (ciphertext, nonce) = com.voiceinput.util.EncryptionUtil.encryptString(messageJson, key)
                    
                    val encryptedMessage = Message.EncryptedMessage(
                        fromDeviceId = UUID.randomUUID().toString(),
                        toDeviceId = lastConnectedDevice?.deviceId ?: "",
                        ciphertext = ciphertext,
                        nonce = nonce
                    )
                    
                    val sent = tcpConnection!!.sendMessage(encryptedMessage)
                    
                    if (!sent) {
                        _connectionError.value = "发送失败"
                        handleDisconnection()
                        return@withContext false
                    }
                    
                    // 等待确认（超时5秒）
                    val ack = withTimeoutOrNull(5000) {
                        tcpConnection?.receiveMessage()
                    }
                    
                    if (ack is Message.InputAck && ack.success) {
                        return@withContext true
                    }
                    
                    _connectionError.value = "未收到确认"
                    false
                } ?: run {
                    // 如果没有加密密钥，发送明文消息（向后兼容）
                    val sent = tcpConnection!!.sendMessage(message)
                    
                    if (!sent) {
                        _connectionError.value = "发送失败"
                        handleDisconnection()
                        return@withContext false
                    }
                    
                    // 等待确认（超时5秒）
                    val ack = withTimeoutOrNull(5000) {
                        tcpConnection?.receiveMessage()
                    }
                    
                    if (ack is Message.InputAck && ack.success) {
                        return@withContext true
                    }
                    
                    _connectionError.value = "未收到确认"
                    false
                }
            } catch (e: Exception) {
                _connectionError.value = "发送错误: ${e.message}"
                handleDisconnection()
                false
            }
        }
    }

    fun disconnect() {
        shouldReconnect = false
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        tcpConnection?.disconnect()
        _isConnected.value = false
        _connectedDevice.value = null
        _connectionError.value = null
    }

    fun clearError() {
        _connectionError.value = null
    }

    // 加密相关方法
    fun generateEncryptionKey() {
        encryptionKey = EncryptionUtil.generateKey()
    }

    fun getEncryptionKeyBase64(): String? {
        return encryptionKey?.let { EncryptionUtil.keyToBase64(it) }
    }

    fun setEncryptionKeyFromBase64(base64Key: String) {
        encryptionKey = EncryptionUtil.base64ToKey(base64Key)
    }

    fun sendEncryptedMessage(message: Message, toDeviceId: String) {
        encryptionKey?.let {key ->
            val messageJson = com.google.gson.Gson().toJson(message)
            val (ciphertext, nonce) = EncryptionUtil.encryptString(messageJson, key)
            
            val encryptedMessage = Message.EncryptedMessage(
                fromDeviceId = UUID.randomUUID().toString(), // 实际应该使用设备的真实ID
                toDeviceId = toDeviceId,
                ciphertext = ciphertext,
                nonce = nonce
            )
            
            CoroutineScope(Dispatchers.IO).launch {
                tcpConnection?.sendMessage(encryptedMessage)
            }
        }
    }

    fun decryptMessage(ciphertext: String, nonce: String): Message? {
        return encryptionKey?.let { key ->
            try {
                val decryptedJson = EncryptionUtil.decryptString(ciphertext, nonce, key)
                com.google.gson.Gson().fromJson(decryptedJson, Message::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun generatePin(): String {
        return (100000..999999).random().toString()
    }
}

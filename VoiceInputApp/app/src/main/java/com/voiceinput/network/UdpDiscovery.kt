package com.voiceinput.network

import com.google.gson.Gson
import com.voiceinput.data.model.Device
import com.voiceinput.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

class UdpDiscovery {
    private val gson = Gson()
    private val udpPort = 58888
    private val deviceId = UUID.randomUUID().toString()
    
    fun startDiscovery(deviceName: String): Flow<Device> = flow {
        withContext(Dispatchers.IO) {
            val socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 5000

            try {
                while (true) {
                    // 发送广播
                    val message = Message.Discover(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        version = "1.0.0"
                    )
                    val json = gson.toJson(message)
                    val sendData = json.toByteArray()
                    val sendPacket = DatagramPacket(
                        sendData,
                        sendData.size,
                        InetAddress.getByName("255.255.255.255"),
                        udpPort
                    )
                    socket.send(sendPacket)

                    // 接收响应
                    val receiveData = ByteArray(1024)
                    val receivePacket = DatagramPacket(receiveData, receiveData.size)
                    
                    try {
                        socket.receive(receivePacket)
                        val response = String(receivePacket.data, 0, receivePacket.length)
                        val responseMessage = gson.fromJson(response, Message.DiscoverResponse::class.java)
                        
                        val device = Device(
                            deviceId = responseMessage.deviceId,
                            deviceName = responseMessage.deviceName,
                            ip = responseMessage.ip,
                            port = responseMessage.port,
                            version = responseMessage.version
                        )
                        emit(device)
                    } catch (e: Exception) {
                        // 超时或其他错误，继续下一轮
                    }

                    delay(2000) // 每2秒广播一次
                }
            } finally {
                socket.close()
            }
        }
    }
}

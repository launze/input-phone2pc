package com.voiceinput.network

import com.google.gson.Gson
import com.voiceinput.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.net.SocketTimeoutException

class TcpConnection(
    private val host: String,
    private val port: Int
) {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val gson = Gson()
    private var encryptionKey: javax.crypto.SecretKey? = null

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            socket = Socket(host, port)
            socket?.soTimeout = 10000 // 10秒超时
            writer = PrintWriter(socket!!.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun sendMessage(message: Message): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConnected()) {
                return@withContext false
            }
            
            val json = gson.toJson(message)
            writer?.println(json)
            writer?.flush()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun receiveMessage(): Message? = withContext(Dispatchers.IO) {
        try {
            if (!isConnected()) {
                return@withContext null
            }
            
            val line = reader?.readLine() ?: return@withContext null
            val message = gson.fromJson(line, Message::class.java)
            
            // 如果是加密消息，需要解密
            if (message is Message.EncryptedMessage) {
                val encryptedMsg = message as Message.EncryptedMessage
                return@withContext try {
                    val key = encryptionKey
                    if (key != null) {
                        val decryptedJson = com.voiceinput.util.EncryptionUtil.decryptString(
                            encryptedMsg.ciphertext,
                            encryptedMsg.nonce,
                            key
                        )
                        gson.fromJson(decryptedJson, Message::class.java)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            
            return@withContext message
        } catch (e: SocketTimeoutException) {
            // 超时不算错误，返回null
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun disconnect() {
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket = null
            writer = null
            reader = null
        }
    }

    fun isConnected(): Boolean {
        return socket?.isConnected == true && 
               socket?.isClosed == false &&
               writer != null &&
               reader != null
    }
    
    fun setEncryptionKey(key: javax.crypto.SecretKey) {
        this.encryptionKey = key
    }
    
    fun getEncryptionKey(): javax.crypto.SecretKey? {
        return encryptionKey
    }
}

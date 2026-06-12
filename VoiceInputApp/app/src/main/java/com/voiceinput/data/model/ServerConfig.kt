package com.voiceinput.data.model

data class ServerConfig(
    val enabled: Boolean = true,
    val serverUrl: String = "wss://8.153.163.104:16908",
    val deviceId: String = "",
    val sessionId: String = ""
)

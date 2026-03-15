package com.voiceinput.data.model

data class ServerConfig(
    val enabled: Boolean = false,
    val serverUrl: String = "wss://nas.smarthome2020.top:7070",
    val deviceId: String = "",
    val sessionId: String = ""
)

package com.voiceinput.data.model

data class ServerConfig(
    val enabled: Boolean = false,
    val serverUrl: String = "wss://ha.wwszxc.tax:16908",
    val deviceId: String = "",
    val sessionId: String = ""
)

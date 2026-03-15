package com.voiceinput.data.model

data class Device(
    val deviceId: String,
    val deviceName: String,
    val ip: String,
    val port: Int,
    val version: String
)

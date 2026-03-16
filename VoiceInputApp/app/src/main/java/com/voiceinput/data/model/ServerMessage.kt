package com.voiceinput.data.model

import com.google.gson.annotations.SerializedName

/**
 * Server WebSocket protocol messages matching voice-input-server/src/protocol.rs
 */
data class ServerMsg(
    val type: String,
    // SERVER_REGISTER
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("device_name") val deviceName: String? = null,
    @SerializedName("device_type") val deviceType: String? = null,
    val version: String? = null,
    // SERVER_REGISTER_RESPONSE
    val success: Boolean? = null,
    @SerializedName("session_id") val sessionId: String? = null,
    val message: String? = null,
    // DEVICE_LIST_RESPONSE
    val devices: List<ServerDeviceInfo>? = null,
    // RELAY_MESSAGE / SERVER_PAIR_RESPONSE
    @SerializedName("from_device_id") val fromDeviceId: String? = null,
    @SerializedName("from_device_name") val fromDeviceName: String? = null,
    @SerializedName("to_device_id") val toDeviceId: String? = null,
    @SerializedName("to_device_name") val toDeviceName: String? = null,
    val payload: com.google.gson.JsonObject? = null,
    // HEARTBEAT
    val timestamp: Long? = null,
    // ERROR
    val code: String? = null,
    val pin: String? = null,
)

data class ServerDeviceInfo(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("device_type") val deviceType: String,
    val online: Boolean
)

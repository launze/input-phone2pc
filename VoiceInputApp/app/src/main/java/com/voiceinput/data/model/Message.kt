package com.voiceinput.data.model

import com.google.gson.annotations.SerializedName

sealed class Message {
    data class Discover(
        @SerializedName("device_id") val deviceId: String,
        @SerializedName("device_name") val deviceName: String,
        val version: String
    ) : Message() {
        @SerializedName("type")
        val type = "DISCOVER"
    }

    data class DiscoverResponse(
        @SerializedName("device_id") val deviceId: String,
        @SerializedName("device_name") val deviceName: String,
        val ip: String,
        val port: Int,
        val version: String
    ) : Message() {
        @SerializedName("type")
        val type = "DISCOVER_RESPONSE"
    }

    data class PairRequest(
        @SerializedName("device_id") val deviceId: String,
        @SerializedName("device_name") val deviceName: String,
        val pin: String
    ) : Message() {
        @SerializedName("type")
        val type = "PAIR_REQUEST"
    }

    data class PairResponse(
        val success: Boolean,
        val message: String
    ) : Message() {
        @SerializedName("type")
        val type = "PAIR_RESPONSE"
    }

    data class TextInput(
        val text: String,
        val timestamp: Long
    ) : Message() {
        @SerializedName("type")
        val type = "TEXT_INPUT"
    }

    data class InputAck(
        val success: Boolean,
        val timestamp: Long
    ) : Message() {
        @SerializedName("type")
        val type = "INPUT_ACK"
    }

    data class Heartbeat(
        val timestamp: Long
    ) : Message() {
        @SerializedName("type")
        val type = "HEARTBEAT"
    }

    data class EncryptionKeyExchange(
        @SerializedName("device_id") val deviceId: String,
        @SerializedName("public_key") val publicKey: String
    ) : Message() {
        @SerializedName("type")
        val type = "ENCRYPTION_KEY_EXCHANGE"
    }

    data class EncryptedMessage(
        @SerializedName("from_device_id") val fromDeviceId: String,
        @SerializedName("to_device_id") val toDeviceId: String,
        val ciphertext: String,
        val nonce: String
    ) : Message() {
        @SerializedName("type")
        val type = "ENCRYPTED_MESSAGE"
    }
}

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
        val timestamp: Long,
        @SerializedName("press_enter") val pressEnter: Boolean = false
    ) : Message() {
        @SerializedName("type")
        val type = "TEXT_INPUT"
    }

    data class NotificationInput(
        @SerializedName("app_name") val appName: String,
        @SerializedName("app_package") val appPackage: String,
        val title: String,
        val text: String,
        val timestamp: Long,
        @SerializedName("notification_key") val notificationKey: String = "",
        @SerializedName("channel_id") val channelId: String = "",
        @SerializedName("group_key") val groupKey: String = "",
        val category: String = "",
        @SerializedName("sub_text") val subText: String = "",
        @SerializedName("big_text") val bigText: String = "",
        @SerializedName("conversation_title") val conversationTitle: String = "",
        @SerializedName("post_time") val postTime: Long = timestamp,
        @SerializedName("is_ongoing") val isOngoing: Boolean = false,
        @SerializedName("is_clearable") val isClearable: Boolean = true,
        val importance: Int = 3,
        @SerializedName("forward_mode") val forwardMode: String = "pc_center",
        val silent: Boolean = false,
        @SerializedName("copy_to_clipboard") val copyToClipboard: Boolean = false,
        val icon: String? = null
    ) : Message() {
        @SerializedName("type")
        val type = "NOTIFICATION_INPUT"
    }

    data class ImageInput(
        @SerializedName("mime_type") val mimeType: String,
        @SerializedName("file_name") val fileName: String,
        @SerializedName("image_data") val imageData: String,
        val width: Int,
        val height: Int,
        val size: Int,
        val timestamp: Long
    ) : Message() {
        @SerializedName("type")
        val type = "IMAGE_INPUT"
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

package com.voiceinput.data.model

data class NotificationData(
    val appName: String,
    val appPackage: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val notificationKey: String = "",
    val channelId: String = "",
    val groupKey: String = "",
    val category: String = "",
    val subText: String = "",
    val bigText: String = "",
    val conversationTitle: String = "",
    val postTime: Long = timestamp,
    val isOngoing: Boolean = false,
    val isClearable: Boolean = true,
    val importance: Int = 3,
    val forwardMode: String = "pc_center",
    val icon: String? = null
)

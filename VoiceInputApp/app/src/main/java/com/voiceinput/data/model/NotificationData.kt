package com.voiceinput.data.model

data class NotificationData(
    val appName: String,
    val appPackage: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val icon: String? = null
)

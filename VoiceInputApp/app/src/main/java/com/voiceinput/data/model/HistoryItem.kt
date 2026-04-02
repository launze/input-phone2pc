package com.voiceinput.data.model

data class HistoryItem(
    val id: String,
    val text: String,
    val timestamp: Long,
    val targetDeviceId: String = "",
    val targetDeviceName: String = "",
    val contentType: String = "text",
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    val channel: String = "server",
    val serverMessageId: String? = null,
    val storedAt: Long? = null,
    val syncedAt: Long? = null,
    val errorMessage: String? = null,
) {
    val isPending: Boolean
        get() = syncStatus == SyncStatus.PENDING || syncStatus == SyncStatus.STORED
}

enum class SyncStatus {
    PENDING,
    STORED,
    SYNCED,
    FAILED,
    DIRECT
}

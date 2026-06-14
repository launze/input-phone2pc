package com.voiceinput.data

import com.voiceinput.data.model.HistoryItem
import com.voiceinput.data.model.SyncStatus

object RelayAckState {
    fun applyStoredAck(
        item: HistoryItem,
        messageId: String,
        toDeviceId: String,
        storedAt: Long,
        queued: Boolean
    ): HistoryItem {
        return item.copy(
            serverMessageId = messageId,
            storedAt = if (queued) storedAt else null,
            syncStatus = if (queued) SyncStatus.STORED else SyncStatus.PENDING,
            errorMessage = if (queued) "电脑离线，消息已暂存到服务器" else null,
            targetDeviceId = item.targetDeviceId.ifBlank { toDeviceId }
        )
    }

    fun applyInputAck(
        item: HistoryItem,
        serverMessageId: String?,
        fromDeviceId: String,
        success: Boolean,
        now: Long
    ): HistoryItem {
        return item.copy(
            serverMessageId = item.serverMessageId ?: serverMessageId,
            syncStatus = if (success) SyncStatus.SYNCED else SyncStatus.FAILED,
            syncedAt = if (success) now else item.syncedAt,
            storedAt = if (success) null else item.storedAt,
            errorMessage = if (success) null else "电脑处理失败 from $fromDeviceId"
        )
    }
}

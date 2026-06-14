package com.voiceinput.data

import com.voiceinput.data.model.HistoryItem
import com.voiceinput.data.model.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayAckStateTest {
    @Test
    fun storedAckMarksQueuedMessageAsStoredAndFillsTargetDevice() {
        val updated = RelayAckState.applyStoredAck(
            item = historyItem(targetDeviceId = ""),
            messageId = "server-1",
            toDeviceId = "pc-1",
            storedAt = 2000L,
            queued = true
        )

        assertEquals("server-1", updated.serverMessageId)
        assertEquals("pc-1", updated.targetDeviceId)
        assertEquals(2000L, updated.storedAt)
        assertEquals(SyncStatus.STORED, updated.syncStatus)
        assertEquals("电脑离线，消息已暂存到服务器", updated.errorMessage)
    }

    @Test
    fun storedAckForOnlineTargetKeepsMessagePendingWithoutStoredReason() {
        val updated = RelayAckState.applyStoredAck(
            item = historyItem(targetDeviceId = "pc-existing"),
            messageId = "server-1",
            toDeviceId = "pc-1",
            storedAt = 2000L,
            queued = false
        )

        assertEquals("server-1", updated.serverMessageId)
        assertEquals("pc-existing", updated.targetDeviceId)
        assertNull(updated.storedAt)
        assertEquals(SyncStatus.PENDING, updated.syncStatus)
        assertNull(updated.errorMessage)
    }

    @Test
    fun successfulInputAckMarksSyncedAndClearsStoredState() {
        val updated = RelayAckState.applyInputAck(
            item = historyItem(
                serverMessageId = "server-1",
                storedAt = 2000L,
                syncStatus = SyncStatus.STORED,
                errorMessage = "电脑离线，消息已暂存到服务器"
            ),
            serverMessageId = "server-1",
            fromDeviceId = "pc-1",
            success = true,
            now = 3000L
        )

        assertEquals("server-1", updated.serverMessageId)
        assertEquals(SyncStatus.SYNCED, updated.syncStatus)
        assertEquals(3000L, updated.syncedAt)
        assertNull(updated.storedAt)
        assertNull(updated.errorMessage)
    }

    @Test
    fun failedInputAckMarksFailedAndKeepsStoredAtForDebugging() {
        val updated = RelayAckState.applyInputAck(
            item = historyItem(
                serverMessageId = "server-1",
                storedAt = 2000L,
                syncStatus = SyncStatus.STORED
            ),
            serverMessageId = "server-1",
            fromDeviceId = "pc-1",
            success = false,
            now = 3000L
        )

        assertEquals("server-1", updated.serverMessageId)
        assertEquals(SyncStatus.FAILED, updated.syncStatus)
        assertNull(updated.syncedAt)
        assertEquals(2000L, updated.storedAt)
        assertEquals("电脑处理失败 from pc-1", updated.errorMessage)
    }

    @Test
    fun inputAckDoesNotOverwriteExistingServerMessageId() {
        val updated = RelayAckState.applyInputAck(
            item = historyItem(serverMessageId = "server-existing"),
            serverMessageId = "server-new",
            fromDeviceId = "pc-1",
            success = true,
            now = 3000L
        )

        assertEquals("server-existing", updated.serverMessageId)
    }

    @Test
    fun inputAckWithMissingServerMessageIdDoesNotWriteBlankId() {
        val updated = RelayAckState.applyInputAck(
            item = historyItem(serverMessageId = null),
            serverMessageId = null,
            fromDeviceId = "pc-1",
            success = true,
            now = 3000L
        )

        assertNull(updated.serverMessageId)
        assertEquals(SyncStatus.SYNCED, updated.syncStatus)
    }

    private fun historyItem(
        targetDeviceId: String = "pc-1",
        serverMessageId: String? = null,
        storedAt: Long? = null,
        syncStatus: SyncStatus = SyncStatus.PENDING,
        errorMessage: String? = null
    ): HistoryItem {
        return HistoryItem(
            id = "local-1",
            text = "通知内容",
            timestamp = 1000L,
            targetDeviceId = targetDeviceId,
            contentType = "notification",
            syncStatus = syncStatus,
            serverMessageId = serverMessageId,
            storedAt = storedAt,
            errorMessage = errorMessage
        )
    }
}

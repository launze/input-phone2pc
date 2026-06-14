package com.voiceinput.data

import com.google.gson.JsonParser
import com.voiceinput.data.model.NotificationData
import com.voiceinput.data.model.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationHistoryFactoryTest {
    @Test
    fun createsNotificationHistoryItemForPcForwarding() {
        val item = NotificationHistoryFactory.createHistoryItem(
            notification = notification(),
            target = NotificationTargetDevice("pc-1", "办公室电脑"),
            forwardMode = "pc_center",
            now = 123L
        )
        val metadata = JsonParser.parseString(item.metadata).asJsonObject

        assertEquals("notification", item.contentType)
        assertEquals(SyncStatus.PENDING, item.syncStatus)
        assertEquals("server", item.channel)
        assertEquals("pc-1", item.targetDeviceId)
        assertEquals("办公室电脑", item.targetDeviceName)
        assertEquals("微信", item.sourceApp)
        assertEquals("com.tencent.mm", item.sourcePackage)
        assertEquals(456L, item.timestamp)
        assertTrue(item.text.contains("[通知] 微信 - 项目群"))
        assertTrue(item.text.contains("下午三点同步"))
        assertEquals("pc_center", metadata.get("forward_mode").asString)
        assertEquals("项目群", metadata.get("title").asString)
        assertEquals(3, metadata.get("importance").asInt)
    }

    @Test
    fun historyOnlyModeUsesNotificationChannelAndNoTarget() {
        val item = NotificationHistoryFactory.createHistoryItem(
            notification = notification(timestamp = 0),
            target = null,
            forwardMode = "history_only",
            now = 999L
        )
        val metadata = JsonParser.parseString(item.metadata).asJsonObject

        assertEquals("notification", item.channel)
        assertEquals("", item.targetDeviceId)
        assertEquals("", item.targetDeviceName)
        assertEquals(999L, item.timestamp)
        assertEquals("history_only", metadata.get("forward_mode").asString)
    }

    @Test
    fun clipboardAndAiSilentModesUseServerChannel() {
        assertEquals("server", NotificationHistoryFactory.channelForForwardMode("clipboard"))
        assertEquals("server", NotificationHistoryFactory.channelForForwardMode("ai_silent"))
        assertEquals("server", NotificationHistoryFactory.channelForForwardMode("unknown"))
    }

    @Test
    fun historyIdPrefersNotificationKeyAndFallsBackToContentSignature() {
        val keyedA = NotificationHistoryFactory.buildHistoryId(notification(notificationKey = "key-1", text = "A"))
        val keyedB = NotificationHistoryFactory.buildHistoryId(notification(notificationKey = "key-1", text = "B"))
        val fallbackA = NotificationHistoryFactory.buildHistoryId(notification(notificationKey = "", text = "A"))
        val fallbackB = NotificationHistoryFactory.buildHistoryId(notification(notificationKey = "", text = "B"))

        assertEquals(keyedA, keyedB)
        assertNotEquals(fallbackA, fallbackB)
        assertTrue(keyedA.startsWith("notification-"))
    }

    private fun notification(
        timestamp: Long = 456L,
        notificationKey: String = "key-1",
        text: String = "下午三点同步"
    ): NotificationData {
        return NotificationData(
            appName = "微信",
            appPackage = "com.tencent.mm",
            title = "项目群",
            text = text,
            timestamp = timestamp,
            notificationKey = notificationKey,
            channelId = "message",
            importance = 3
        )
    }
}

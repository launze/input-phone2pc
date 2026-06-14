package com.voiceinput.data

import com.voiceinput.data.model.HistoryItem
import com.voiceinput.data.model.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryExportFormatterTest {
    @Test
    fun textExportUsesNotificationTitleAndIncludesProductivityFields() {
        val content = HistoryExportFormatter.buildText(
            listOf(
                notificationItem(
                    id = "n1",
                    text = "项目群\n下午三点同步",
                    sourceApp = "微信",
                    sourcePackage = "com.tencent.mm",
                    metadata = """{"importance":4,"channel_id":"message"}"""
                )
            )
        )

        assertTrue(content.startsWith("语传 - 通知记录"))
        assertTrue(content.contains("类型: notification"))
        assertTrue(content.contains("来源 App: 微信"))
        assertTrue(content.contains("来源包名: com.tencent.mm"))
        assertTrue(content.contains("metadata: {\"importance\":4,\"channel_id\":\"message\"}"))
        assertTrue(content.contains("服务器记录 ID: pc-n1"))
    }

    @Test
    fun markdownExportUsesHistoryTitleForMixedRecordsAndKeepsMetadata() {
        val content = HistoryExportFormatter.buildMarkdown(
            listOf(
                historyItem(id = "h1", contentType = "text", text = "hello"),
                notificationItem(id = "n1", text = "notify", metadata = """{"conversation_title":"项目群"}""")
            )
        )

        assertTrue(content.startsWith("# 语传历史记录"))
        assertTrue(content.contains("- 类型: text"))
        assertTrue(content.contains("- 类型: notification"))
        assertTrue(content.contains("- metadata: `{\"conversation_title\":\"项目群\"}`"))
    }

    @Test
    fun csvExportContainsAllProductivityColumnsAndEscapesValues() {
        val content = HistoryExportFormatter.buildCsv(
            listOf(
                notificationItem(
                    id = "n1",
                    text = "第一行\n\"第二行\"",
                    sourceApp = "微信,工作",
                    metadata = """{"title":"项目"}""",
                    errorMessage = "失败,重试"
                )
            )
        )
        val lines = content.lines()

        assertEquals(
            "id,timestamp,target_device_id,target_device_name,status,channel,content_type,source_app,source_package,server_message_id,stored_at,synced_at,favorite,pinned,tags,metadata,error_message,text",
            lines.first()
        )
        assertTrue(lines[1].contains("\"微信,工作\""))
        assertTrue(lines[1].contains("\"{\"\"title\"\":\"\"项目\"\"}\""))
        assertTrue(lines[1].contains("\"失败,重试\""))
        assertTrue(lines[1].contains("\"第一行"))
        assertTrue(lines[2].contains("\"\"第二行\"\"\""))
    }

    @Test
    fun sortedForExportPrioritizesPinnedThenFavoriteThenTimestamp() {
        val sorted = HistoryExportFormatter.sortedForExport(
            listOf(
                historyItem(id = "old", timestamp = 1),
                historyItem(id = "favorite", timestamp = 3, favorite = true),
                historyItem(id = "pinned", timestamp = 2, pinned = true),
                historyItem(id = "normal", timestamp = 4)
            )
        )

        assertEquals(listOf("pinned", "favorite", "old", "normal"), sorted.map { it.id })
    }

    @Test
    fun statusLabelsCoverAllSyncStates() {
        assertEquals("等待电脑确认", HistoryExportFormatter.syncStatusLabel(SyncStatus.PENDING))
        assertEquals("已暂存", HistoryExportFormatter.syncStatusLabel(SyncStatus.STORED))
        assertEquals("已发送", HistoryExportFormatter.syncStatusLabel(SyncStatus.SYNCED))
        assertEquals("失败", HistoryExportFormatter.syncStatusLabel(SyncStatus.FAILED))
        assertEquals("局域网直连", HistoryExportFormatter.syncStatusLabel(SyncStatus.DIRECT))
    }

    @Test
    fun unknownFormatFallsBackToTextExport() {
        val content = HistoryExportFormatter.buildContent(
            listOf(historyItem(id = "h1", contentType = "text", text = "hello")),
            "docx"
        )

        assertTrue(content.startsWith("语传 - 历史记录"))
        assertFalse(content.startsWith("# "))
    }

    private fun historyItem(
        id: String,
        contentType: String = "text",
        text: String = "content",
        timestamp: Long = 1,
        favorite: Boolean = false,
        pinned: Boolean = false
    ): HistoryItem {
        return HistoryItem(
            id = id,
            text = text,
            timestamp = timestamp,
            targetDeviceId = "pc-1",
            targetDeviceName = "办公室电脑",
            contentType = contentType,
            syncStatus = SyncStatus.SYNCED,
            channel = "server",
            isFavorite = favorite,
            isPinned = pinned,
            tags = "工作",
            serverMessageId = "pc-$id"
        )
    }

    private fun notificationItem(
        id: String,
        text: String,
        sourceApp: String = "微信",
        sourcePackage: String = "com.tencent.mm",
        metadata: String = "",
        errorMessage: String? = null
    ): HistoryItem {
        return historyItem(id = id, contentType = "notification", text = text).copy(
            sourceApp = sourceApp,
            sourcePackage = sourcePackage,
            metadata = metadata,
            errorMessage = errorMessage
        )
    }
}

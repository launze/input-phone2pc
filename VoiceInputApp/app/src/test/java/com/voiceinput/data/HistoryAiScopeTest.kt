package com.voiceinput.data

import com.voiceinput.data.model.HistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAiScopeTest {
    @Test
    fun historyAllTabScopesContentTypesToNonNotifications() {
        val filters = HistoryAiScope.buildFilters(
            notificationMode = false,
            selectedTabLabel = "全部",
            selectedChannel = "all",
            selectedSourceApp = "all",
            selectedStatus = "all",
            tagQuery = "",
            items = emptyList()
        )

        assertEquals("text,image,file", filters.get("content_type").asString)
        assertEquals(120, filters.get("limit").asInt)
        assertEquals(0, filters.getAsJsonArray("record_ids").size())
        assertFalse(filters.has("source_app"))
    }

    @Test
    fun notificationModeAlwaysScopesContentTypeToNotification() {
        val filters = HistoryAiScope.buildFilters(
            notificationMode = true,
            selectedTabLabel = "收藏",
            selectedChannel = "server",
            selectedSourceApp = "微信",
            selectedStatus = "manual",
            tagQuery = " 工作 ",
            items = listOf(item("local-1", "pc-1"), item("local-2", "pc-1"), item("local-3", null))
        )

        assertEquals("notification", filters.get("content_type").asString)
        assertTrue(filters.get("favorite").asBoolean)
        assertEquals("server", filters.get("via").asString)
        assertEquals("微信", filters.get("source_app").asString)
        assertEquals("manual", filters.get("delivery_status").asString)
        assertEquals("工作", filters.get("tag").asString)
        assertEquals(listOf("pc-1"), filters.getAsJsonArray("record_ids").map { it.asString })
    }

    @Test
    fun statusTabsMapToFavoriteAndPinnedToolFilters() {
        val favoriteFilters = HistoryAiScope.buildFilters(
            notificationMode = false,
            selectedTabLabel = "收藏",
            selectedChannel = "all",
            selectedSourceApp = "all",
            selectedStatus = "favorite",
            tagQuery = "",
            items = emptyList()
        )
        val pinnedFilters = HistoryAiScope.buildFilters(
            notificationMode = false,
            selectedTabLabel = "置顶",
            selectedChannel = "all",
            selectedSourceApp = "all",
            selectedStatus = "pinned",
            tagQuery = "",
            items = emptyList()
        )

        assertTrue(favoriteFilters.get("favorite").asBoolean)
        assertTrue(pinnedFilters.get("pinned").asBoolean)
        assertFalse(favoriteFilters.has("delivery_status"))
        assertFalse(pinnedFilters.has("delivery_status"))
    }

    @Test
    fun recordIdsUseServerMessageIdsOnlyDistinctAndLimited() {
        val items = (1..5).map { index ->
            item("local-$index", if (index % 2 == 0) "pc-even" else "pc-$index")
        } + item("local-empty", "") + item("local-null", null)

        val filters = HistoryAiScope.buildFilters(
            notificationMode = false,
            selectedTabLabel = "文本",
            selectedChannel = "all",
            selectedSourceApp = "all",
            selectedStatus = "all",
            tagQuery = "",
            items = items,
            recordIdLimit = 3,
            queryLimit = 40
        )

        assertEquals("text", filters.get("content_type").asString)
        assertEquals(40, filters.get("limit").asInt)
        assertEquals(
            listOf("pc-1", "pc-even", "pc-3"),
            filters.getAsJsonArray("record_ids").map { it.asString }
        )
    }

    @Test
    fun questionKeepsPcRecordIdsAndExplainsLocalIdsAreNotToolIds() {
        val question = HistoryAiScope.buildQuestion(
            recordMode = "通知记录",
            selectedTabLabel = "置顶",
            selectedDevice = "办公室电脑",
            selectedChannel = "server",
            selectedSourceApp = "微信",
            selectedStatus = "pinned",
            tagQuery = " 项目 ",
            items = listOf(
                item(
                    id = "local-1",
                    serverMessageId = "pc-1",
                    text = "下午三点同步项目进展",
                    contentType = "notification",
                    sourceApp = "微信",
                    metadata = "importance=4"
                ),
                item(
                    id = "local-2",
                    serverMessageId = null,
                    text = "只在手机本地的通知",
                    contentType = "notification",
                    targetDeviceName = "办公室电脑"
                )
            )
        )

        assertTrue(question.contains("请基于 App 通知记录 当前筛选结果做一次生产力总结"))
        assertTrue(question.contains("当前筛选：类型=置顶，设备=办公室电脑，通道=server，来源App=微信，状态=pinned，标签= 项目 "))
        assertTrue(question.contains("当前记录数：2"))
        assertTrue(question.contains("可直接给 PC 工具使用的记录 ID：pc-1"))
        assertTrue(question.contains("只有 PC记录ID 能用于 get_record_detail，本地ID 仅用于理解 App 侧记录，不能直接当作 PC 工具参数"))
        assertTrue(question.contains("缺少 PC记录ID 的记录数：1"))
        assertTrue(question.contains("PC记录ID=pc-1，本地ID=local-1，类型=notification，来源=微信"))
        assertTrue(question.contains("PC记录ID=无，本地ID=local-2，类型=notification，来源=办公室电脑"))
    }

    @Test
    fun questionLimitsRecordIdsPreviewMetadataAndContent() {
        val items = (1..4).map { index ->
            item(
                id = "local-$index",
                serverMessageId = "pc-$index",
                text = "正文".repeat(20),
                metadata = "m".repeat(20)
            )
        }

        val question = HistoryAiScope.buildQuestion(
            recordMode = "历史记录",
            selectedTabLabel = "全部",
            selectedDevice = "all",
            selectedChannel = "all",
            selectedSourceApp = "all",
            selectedStatus = "all",
            tagQuery = "",
            items = items,
            recordIdLimit = 2,
            previewLimit = 2,
            metadataPreviewChars = 5,
            contentPreviewChars = 6
        )

        assertTrue(question.contains("可直接给 PC 工具使用的记录 ID：pc-1, pc-2"))
        assertFalse(question.contains("pc-3, pc-4"))
        assertTrue(question.contains("metadata=mmmmm"))
        assertFalse(question.contains("metadata=mmmmmm"))
        assertTrue(question.contains("摘要=正文正文正文"))
        assertFalse(question.contains("本地ID=local-3"))
    }

    @Test
    fun questionStatesWhenNoPcRecordIdsAreAvailable() {
        val question = HistoryAiScope.buildQuestion(
            recordMode = "历史记录",
            selectedTabLabel = "全部",
            selectedDevice = "all",
            selectedChannel = "all",
            selectedSourceApp = "all",
            selectedStatus = "all",
            tagQuery = "",
            items = listOf(item("local-only", null))
        )

        assertTrue(question.contains("可直接给 PC 工具使用的记录 ID：暂无，说明这些记录可能还未被 PC 接收或来自局域网直连"))
        assertTrue(question.contains("缺少 PC记录ID 的记录数：1"))
    }

    private fun item(
        id: String,
        serverMessageId: String?,
        text: String = "content",
        contentType: String = "text",
        sourceApp: String = "",
        sourcePackage: String = "",
        targetDeviceName: String = "",
        metadata: String = ""
    ): HistoryItem {
        return HistoryItem(
            id = id,
            text = text,
            timestamp = 1,
            targetDeviceName = targetDeviceName,
            contentType = contentType,
            serverMessageId = serverMessageId,
            sourceApp = sourceApp,
            sourcePackage = sourcePackage,
            metadata = metadata
        )
    }
}

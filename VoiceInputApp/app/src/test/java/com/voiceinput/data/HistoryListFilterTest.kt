package com.voiceinput.data

import com.voiceinput.data.model.HistoryItem
import com.voiceinput.data.model.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryListFilterTest {
    @Test
    fun historyModeExcludesNotificationsAndBuildsOptions() {
        val result = HistoryListFilter.evaluate(
            items = sampleItems(),
            state = HistoryFilterState(notificationMode = false, selectedTab = 0)
        )

        assertEquals(listOf("全部", "文本", "图片", "文件", "待同步", "收藏", "置顶"), result.tabs)
        assertEquals(4, result.pendingTabIndex)
        assertEquals(listOf("phone", "项目电脑"), result.deviceOptions)
        assertEquals(listOf("lan", "server"), result.channelOptions)
        assertEquals(emptyList<String>(), result.sourceAppOptions)
        assertEquals(
            listOf("pinned-file", "favorite-text", "latest-text", "pending-image"),
            result.filteredItems.map { it.id }
        )
        assertEquals(listOf(4, 2, 1, 1, 1, 1, 1), result.tabCounts)
    }

    @Test
    fun notificationModeOnlyShowsNotificationsAndSearchesMetadata() {
        val result = HistoryListFilter.evaluate(
            items = sampleItems(),
            state = HistoryFilterState(
                notificationMode = true,
                selectedTab = 0,
                searchQuery = "三点",
                selectedSourceApp = "微信"
            )
        )

        assertEquals(listOf("全部", "待同步", "收藏", "置顶"), result.tabs)
        assertEquals(1, result.pendingTabIndex)
        assertEquals(listOf("com.tencent.mm", "微信"), result.sourceAppOptions)
        assertEquals(listOf("notification-1"), result.filteredItems.map { it.id })
        assertEquals(listOf(2, 1, 1, 1), result.tabCounts)
    }

    @Test
    fun filtersByDeviceChannelStatusTagAndSourceApp() {
        val historyResult = HistoryListFilter.evaluate(
            items = sampleItems(),
            state = HistoryFilterState(
                notificationMode = false,
                selectedTab = 0,
                selectedDevice = "项目电脑",
                selectedChannel = "server",
                selectedStatus = "synced",
                tagQuery = "工作"
            )
        )
        val notificationResult = HistoryListFilter.evaluate(
            items = sampleItems(),
            state = HistoryFilterState(
                notificationMode = true,
                selectedTab = 3,
                selectedChannel = "server",
                selectedSourceApp = "com.tencent.mm"
            )
        )

        assertEquals(listOf("favorite-text"), historyResult.filteredItems.map { it.id })
        assertEquals(listOf("notification-2"), notificationResult.filteredItems.map { it.id })
    }

    @Test
    fun tabFiltersCoverTypePendingFavoriteAndPinned() {
        val items = sampleItems()
        assertEquals(
            listOf("favorite-text", "latest-text"),
            HistoryListFilter.evaluate(items, HistoryFilterState(false, selectedTab = 1)).filteredItems.map { it.id }
        )
        assertEquals(
            listOf("pending-image"),
            HistoryListFilter.evaluate(items, HistoryFilterState(false, selectedTab = 2)).filteredItems.map { it.id }
        )
        assertEquals(
            listOf("pinned-file"),
            HistoryListFilter.evaluate(items, HistoryFilterState(false, selectedTab = 3)).filteredItems.map { it.id }
        )
        assertEquals(
            listOf("pending-image"),
            HistoryListFilter.evaluate(items, HistoryFilterState(false, selectedTab = 4)).filteredItems.map { it.id }
        )
        assertEquals(
            listOf("favorite-text"),
            HistoryListFilter.evaluate(items, HistoryFilterState(false, selectedTab = 5)).filteredItems.map { it.id }
        )
        assertEquals(
            listOf("pinned-file"),
            HistoryListFilter.evaluate(items, HistoryFilterState(false, selectedTab = 6)).filteredItems.map { it.id }
        )
    }

    @Test
    fun destructiveTargetIdsKeepHistoryAndNotificationsSeparated() {
        val items = sampleItems()
        val historyResult = HistoryListFilter.evaluate(
            items,
            HistoryFilterState(notificationMode = false, selectedTab = 0, tagQuery = "工作")
        )
        val notificationResult = HistoryListFilter.evaluate(
            items,
            HistoryFilterState(notificationMode = true, selectedTab = 0, selectedSourceApp = "微信")
        )

        assertEquals(
            setOf("latest-text", "favorite-text", "pending-image", "pinned-file"),
            HistoryListFilter.clearTargetIds(items, notificationMode = false)
        )
        assertEquals(
            setOf("notification-1", "notification-2"),
            HistoryListFilter.clearTargetIds(items, notificationMode = true)
        )
        assertEquals(
            setOf("favorite-text"),
            HistoryListFilter.deleteFilteredTargetIds(historyResult.filteredItems)
        )
        assertEquals(
            setOf("notification-1"),
            HistoryListFilter.deleteFilteredTargetIds(notificationResult.filteredItems)
        )
    }

    private fun sampleItems(): List<HistoryItem> {
        return listOf(
            item(
                id = "latest-text",
                text = "普通文本",
                timestamp = 50,
                contentType = "text",
                targetDeviceId = "phone",
                channel = "lan"
            ),
            item(
                id = "favorite-text",
                text = "项目总结",
                timestamp = 40,
                contentType = "text",
                targetDeviceName = "项目电脑",
                channel = "server",
                syncStatus = SyncStatus.SYNCED,
                isFavorite = true,
                tags = "工作"
            ),
            item(
                id = "pending-image",
                text = "截图",
                timestamp = 30,
                contentType = "image",
                targetDeviceName = "项目电脑",
                channel = "",
                syncStatus = SyncStatus.STORED
            ),
            item(
                id = "pinned-file",
                text = "文件",
                timestamp = 20,
                contentType = "file",
                targetDeviceId = "phone",
                channel = "server",
                isPinned = true
            ),
            item(
                id = "notification-1",
                text = "通知正文",
                timestamp = 60,
                contentType = "notification",
                channel = "server",
                syncStatus = SyncStatus.FAILED,
                isFavorite = true,
                sourceApp = "微信",
                sourcePackage = "com.tencent.mm",
                metadata = """{"title":"项目群","text":"下午三点同步"}"""
            ),
            item(
                id = "notification-2",
                text = "置顶通知",
                timestamp = 10,
                contentType = "notification",
                channel = "server",
                isPinned = true,
                sourcePackage = "com.tencent.mm"
            )
        )
    }

    private fun item(
        id: String,
        text: String,
        timestamp: Long,
        contentType: String,
        targetDeviceId: String = "",
        targetDeviceName: String = "",
        syncStatus: SyncStatus = SyncStatus.SYNCED,
        channel: String = "server",
        isFavorite: Boolean = false,
        isPinned: Boolean = false,
        tags: String = "",
        sourceApp: String = "",
        sourcePackage: String = "",
        metadata: String = ""
    ): HistoryItem {
        return HistoryItem(
            id = id,
            text = text,
            timestamp = timestamp,
            targetDeviceId = targetDeviceId,
            targetDeviceName = targetDeviceName,
            contentType = contentType,
            syncStatus = syncStatus,
            channel = channel,
            isFavorite = isFavorite,
            isPinned = isPinned,
            tags = tags,
            sourceApp = sourceApp,
            sourcePackage = sourcePackage,
            metadata = metadata
        )
    }
}

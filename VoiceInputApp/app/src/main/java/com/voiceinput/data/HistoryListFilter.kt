package com.voiceinput.data

import com.voiceinput.data.model.HistoryItem
import com.voiceinput.data.model.SyncStatus

data class HistoryFilterState(
    val notificationMode: Boolean,
    val selectedTab: Int,
    val searchQuery: String = "",
    val selectedDevice: String = "all",
    val selectedChannel: String = "all",
    val selectedSourceApp: String = "all",
    val selectedStatus: String = "all",
    val tagQuery: String = ""
)

data class HistoryFilterResult(
    val scopedItems: List<HistoryItem>,
    val filteredItems: List<HistoryItem>,
    val tabs: List<String>,
    val tabCounts: List<Int>,
    val pendingTabIndex: Int,
    val deviceOptions: List<String>,
    val channelOptions: List<String>,
    val sourceAppOptions: List<String>,
    val statusOptions: List<Pair<String, String>>
)

object HistoryListFilter {
    private val notificationTabs = listOf("全部", "待同步", "收藏", "置顶")
    private val historyTabs = listOf("全部", "文本", "图片", "文件", "待同步", "收藏", "置顶")
    val statusOptions = listOf(
        "pending" to "待发送",
        "stored" to "已暂存",
        "synced" to "已同步",
        "failed" to "失败",
        "direct" to "直连"
    )

    fun evaluate(items: List<HistoryItem>, state: HistoryFilterState): HistoryFilterResult {
        val scopedItems = scopedItems(items, state.notificationMode)
        val tabs = tabs(state.notificationMode)
        val filteredItems = filterScopedItems(scopedItems, state)
        return HistoryFilterResult(
            scopedItems = scopedItems,
            filteredItems = filteredItems,
            tabs = tabs,
            tabCounts = tabs.indices.map { tabCount(scopedItems, state.notificationMode, it) },
            pendingTabIndex = pendingTabIndex(state.notificationMode),
            deviceOptions = deviceOptions(scopedItems),
            channelOptions = channelOptions(scopedItems),
            sourceAppOptions = sourceAppOptions(scopedItems),
            statusOptions = statusOptions
        )
    }

    fun tabs(notificationMode: Boolean): List<String> {
        return if (notificationMode) notificationTabs else historyTabs
    }

    fun pendingTabIndex(notificationMode: Boolean): Int {
        return if (notificationMode) 1 else 4
    }

    fun scopedItems(items: List<HistoryItem>, notificationMode: Boolean): List<HistoryItem> {
        return items.filter { item ->
            if (notificationMode) item.contentType == "notification" else item.contentType != "notification"
        }
    }

    fun clearTargetIds(items: List<HistoryItem>, notificationMode: Boolean): Set<String> {
        return scopedItems(items, notificationMode).map { it.id }.toSet()
    }

    fun deleteFilteredTargetIds(filteredItems: List<HistoryItem>): Set<String> {
        return filteredItems.map { it.id }.toSet()
    }

    fun deviceOptions(items: List<HistoryItem>): List<String> {
        return items
            .mapNotNull { it.targetDeviceName.ifBlank { it.targetDeviceId }.takeIf(String::isNotBlank) }
            .distinct()
            .sorted()
    }

    fun channelOptions(items: List<HistoryItem>): List<String> {
        return items.map { it.channel.ifBlank { "server" } }.distinct().sorted()
    }

    fun sourceAppOptions(items: List<HistoryItem>): List<String> {
        return items
            .mapNotNull { it.sourceApp.ifBlank { it.sourcePackage }.takeIf(String::isNotBlank) }
            .distinct()
            .sorted()
    }

    fun tabCount(items: List<HistoryItem>, notificationMode: Boolean, tabIndex: Int): Int {
        return when {
            notificationMode && tabIndex == 1 -> items.count { it.isPending || it.syncStatus == SyncStatus.FAILED }
            notificationMode && tabIndex == 2 -> items.count { it.isFavorite }
            notificationMode && tabIndex == 3 -> items.count { it.isPinned }
            !notificationMode && tabIndex == 1 -> items.count { it.contentType == "text" }
            !notificationMode && tabIndex == 2 -> items.count { it.contentType == "image" }
            !notificationMode && tabIndex == 3 -> items.count { it.contentType == "file" }
            !notificationMode && tabIndex == 4 -> items.count { it.isPending || it.syncStatus == SyncStatus.FAILED }
            !notificationMode && tabIndex == 5 -> items.count { it.isFavorite }
            !notificationMode && tabIndex == 6 -> items.count { it.isPinned }
            else -> items.size
        }
    }

    fun filterScopedItems(scopedItems: List<HistoryItem>, state: HistoryFilterState): List<HistoryItem> {
        val normalizedSearch = state.searchQuery.trim()
        val tabFiltered = when {
            state.notificationMode && state.selectedTab == 1 -> scopedItems.filter { it.isPending || it.syncStatus == SyncStatus.FAILED }
            state.notificationMode && state.selectedTab == 2 -> scopedItems.filter { it.isFavorite }
            state.notificationMode && state.selectedTab == 3 -> scopedItems.filter { it.isPinned }
            !state.notificationMode && state.selectedTab == 1 -> scopedItems.filter { it.contentType == "text" }
            !state.notificationMode && state.selectedTab == 2 -> scopedItems.filter { it.contentType == "image" }
            !state.notificationMode && state.selectedTab == 3 -> scopedItems.filter { it.contentType == "file" }
            !state.notificationMode && state.selectedTab == 4 -> scopedItems.filter { it.isPending || it.syncStatus == SyncStatus.FAILED }
            !state.notificationMode && state.selectedTab == 5 -> scopedItems.filter { it.isFavorite }
            !state.notificationMode && state.selectedTab == 6 -> scopedItems.filter { it.isPinned }
            else -> scopedItems
        }
        return tabFiltered
            .filter { item ->
                state.selectedDevice == "all" ||
                    item.targetDeviceName == state.selectedDevice ||
                    item.targetDeviceId == state.selectedDevice
            }
            .filter { item ->
                state.selectedChannel == "all" || item.channel.ifBlank { "server" } == state.selectedChannel
            }
            .filter { item ->
                state.selectedSourceApp == "all" ||
                    item.sourceApp == state.selectedSourceApp ||
                    item.sourcePackage == state.selectedSourceApp
            }
            .filter { item ->
                state.selectedStatus == "all" || item.syncStatus.name.equals(state.selectedStatus, ignoreCase = true)
            }
            .filter { item ->
                state.tagQuery.isBlank() || item.tags.contains(state.tagQuery.trim(), ignoreCase = true)
            }
            .filter { item ->
                normalizedSearch.isBlank() ||
                    item.text.contains(normalizedSearch, ignoreCase = true) ||
                    item.targetDeviceName.contains(normalizedSearch, ignoreCase = true) ||
                    item.targetDeviceId.contains(normalizedSearch, ignoreCase = true) ||
                    item.channel.contains(normalizedSearch, ignoreCase = true) ||
                    item.syncStatus.name.contains(normalizedSearch, ignoreCase = true) ||
                    item.contentType.contains(normalizedSearch, ignoreCase = true) ||
                    item.sourceApp.contains(normalizedSearch, ignoreCase = true) ||
                    item.sourcePackage.contains(normalizedSearch, ignoreCase = true) ||
                    item.tags.contains(normalizedSearch, ignoreCase = true) ||
                    item.metadata.contains(normalizedSearch, ignoreCase = true) ||
                    item.errorMessage.orEmpty().contains(normalizedSearch, ignoreCase = true)
            }
            .sortedWith(historyItemComparator)
    }

    val historyItemComparator: Comparator<HistoryItem> =
        compareByDescending<HistoryItem> { it.isPinned }
            .thenByDescending { it.isFavorite }
            .thenByDescending { it.timestamp }
            .thenByDescending { it.id }
}

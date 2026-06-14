package com.voiceinput.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.voiceinput.data.model.HistoryItem

object HistoryAiScope {
    fun buildQuestion(
        recordMode: String,
        selectedTabLabel: String,
        selectedDevice: String,
        selectedChannel: String,
        selectedSourceApp: String,
        selectedStatus: String,
        tagQuery: String,
        items: List<HistoryItem>,
        recordIdLimit: Int = 80,
        previewLimit: Int = 12,
        metadataPreviewChars: Int = 240,
        contentPreviewChars: Int = 120
    ): String {
        val pcRecordIds = items
            .mapNotNull { it.serverMessageId?.takeIf(String::isNotBlank) }
            .distinct()
            .take(recordIdLimit)
            .joinToString(", ")
        val localOnlyCount = items.count { it.serverMessageId.isNullOrBlank() }
        val recordPreview = items.take(previewLimit).joinToString("\n") { item ->
            val pcId = item.serverMessageId?.takeIf(String::isNotBlank) ?: "无"
            val source = item.sourceApp.ifBlank { item.sourcePackage }.ifBlank { item.targetDeviceName }
            val metadata = item.metadata
                .takeIf(String::isNotBlank)
                ?.let { "，metadata=${it.take(metadataPreviewChars)}" }
                .orEmpty()
            "- PC记录ID=$pcId，本地ID=${item.id}，类型=${item.contentType}，来源=${source.ifBlank { "未知" }}，时间=${item.timestamp}${metadata}，摘要=${item.text.take(contentPreviewChars)}"
        }
        val filters = buildFilterDescription(
            selectedTabLabel = selectedTabLabel,
            selectedDevice = selectedDevice,
            selectedChannel = selectedChannel,
            selectedSourceApp = selectedSourceApp,
            selectedStatus = selectedStatus,
            tagQuery = tagQuery
        )
        return """
            请基于 App $recordMode 当前筛选结果做一次生产力总结。
            当前筛选：$filters。
            当前记录数：${items.size}。
            可直接给 PC 工具使用的记录 ID：${pcRecordIds.ifBlank { "暂无，说明这些记录可能还未被 PC 接收或来自局域网直连" }}。
            只有 PC记录ID 能用于 get_record_detail，本地ID 仅用于理解 App 侧记录，不能直接当作 PC 工具参数。
            缺少 PC记录ID 的记录数：$localOnlyCount。
            当前筛选记录摘要：
            $recordPreview
            请由你自主选择合适的 Skill 和工具，输出待办、重点进展、风险和下一步建议。
        """.trimIndent()
    }

    fun buildFilters(
        notificationMode: Boolean,
        selectedTabLabel: String,
        selectedChannel: String,
        selectedSourceApp: String,
        selectedStatus: String,
        tagQuery: String,
        items: List<HistoryItem>,
        recordIdLimit: Int = 80,
        queryLimit: Int = 120
    ): JsonObject {
        val filters = JsonObject()
        contentTypeFromTab(notificationMode, selectedTabLabel)?.let {
            filters.addProperty("content_type", it)
        }
        when (selectedTabLabel) {
            "收藏" -> filters.addProperty("favorite", true)
            "置顶" -> filters.addProperty("pinned", true)
        }
        if (selectedChannel != "all") {
            filters.addProperty("via", selectedChannel)
        }
        if (selectedSourceApp != "all") {
            filters.addProperty("source_app", selectedSourceApp)
        }
        if (selectedStatus != "all") {
            when (selectedStatus) {
                "favorite" -> filters.addProperty("favorite", true)
                "pinned" -> filters.addProperty("pinned", true)
                "received", "manual", "offline_sync" -> filters.addProperty("delivery_status", selectedStatus)
            }
        }
        if (tagQuery.isNotBlank()) {
            filters.addProperty("tag", tagQuery.trim())
        }

        val recordIds = JsonArray()
        items
            .mapNotNull { it.serverMessageId?.takeIf(String::isNotBlank) }
            .distinct()
            .take(recordIdLimit)
            .forEach { recordIds.add(it) }
        filters.add("record_ids", recordIds)
        filters.addProperty("limit", queryLimit)
        return filters
    }

    fun contentTypeFromTab(notificationMode: Boolean, tabLabel: String): String? {
        if (notificationMode) return "notification"
        return when (tabLabel) {
            "文本" -> "text"
            "图片" -> "image"
            "文件" -> "file"
            else -> "text,image,file"
        }
    }

    private fun buildFilterDescription(
        selectedTabLabel: String,
        selectedDevice: String,
        selectedChannel: String,
        selectedSourceApp: String,
        selectedStatus: String,
        tagQuery: String
    ): String {
        return buildList {
            add("类型=$selectedTabLabel")
            if (selectedDevice != "all") add("设备=$selectedDevice")
            if (selectedChannel != "all") add("通道=$selectedChannel")
            if (selectedSourceApp != "all") add("来源App=$selectedSourceApp")
            if (selectedStatus != "all") add("状态=$selectedStatus")
            if (tagQuery.isNotBlank()) add("标签=$tagQuery")
        }.joinToString("，")
    }
}

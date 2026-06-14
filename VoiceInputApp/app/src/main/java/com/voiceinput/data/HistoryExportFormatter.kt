package com.voiceinput.data

import com.voiceinput.data.model.HistoryItem
import com.voiceinput.data.model.SyncStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HistoryExportFormatter {
    fun buildContent(items: List<HistoryItem>, format: String): String {
        return when (format.lowercase(Locale.ROOT)) {
            "md" -> buildMarkdown(items)
            "csv" -> buildCsv(items)
            else -> buildText(items)
        }
    }

    fun buildText(items: List<HistoryItem>): String {
        val dateFormat = exportDateFormat()
        val title = exportTitleForItems(items)
        val builder = StringBuilder()
        builder.appendLine("语传 - $title")
        builder.appendLine("导出时间: ${dateFormat.format(Date())}")
        builder.appendLine("记录数: ${items.size}")
        builder.appendLine()

        sortedForExport(items).forEachIndexed { index, item ->
            builder.appendLine("${index + 1}. ${dateFormat.format(Date(item.timestamp))}")
            builder.appendLine("类型: ${item.contentType}")
            builder.appendLine("状态: ${syncStatusLabel(item.syncStatus)}")
            builder.appendLine("通道: ${item.channel}")
            if (item.isPinned) {
                builder.appendLine("置顶: 是")
            }
            if (item.isFavorite) {
                builder.appendLine("收藏: 是")
            }
            if (item.tags.isNotBlank()) {
                builder.appendLine("标签: ${item.tags}")
            }
            if (item.targetDeviceName.isNotBlank()) {
                builder.appendLine("设备: ${item.targetDeviceName}")
            }
            if (item.sourceApp.isNotBlank()) {
                builder.appendLine("来源 App: ${item.sourceApp}")
            }
            if (item.sourcePackage.isNotBlank()) {
                builder.appendLine("来源包名: ${item.sourcePackage}")
            }
            if (item.metadata.isNotBlank()) {
                builder.appendLine("metadata: ${item.metadata}")
            }
            item.serverMessageId?.takeIf { it.isNotBlank() }?.let {
                builder.appendLine("服务器记录 ID: $it")
            }
            item.errorMessage?.takeIf { it.isNotBlank() }?.let {
                builder.appendLine("状态说明: $it")
            }
            builder.appendLine(item.text)
            builder.appendLine()
        }

        return builder.toString().trimEnd()
    }

    fun buildMarkdown(items: List<HistoryItem>): String {
        val dateFormat = exportDateFormat()
        val title = exportTitleForItems(items)
        val builder = StringBuilder()
        builder.appendLine("# 语传$title")
        builder.appendLine()
        builder.appendLine("- 导出时间: ${dateFormat.format(Date())}")
        builder.appendLine("- 记录数: ${items.size}")
        builder.appendLine()

        sortedForExport(items).forEachIndexed { index, item ->
            builder.appendLine("## ${index + 1}. ${dateFormat.format(Date(item.timestamp))}")
            builder.appendLine()
            builder.appendLine("- 类型: ${item.contentType}")
            builder.appendLine("- 状态: ${syncStatusLabel(item.syncStatus)}")
            builder.appendLine("- 通道: ${item.channel}")
            if (item.isPinned) {
                builder.appendLine("- 置顶: 是")
            }
            if (item.isFavorite) {
                builder.appendLine("- 收藏: 是")
            }
            if (item.tags.isNotBlank()) {
                builder.appendLine("- 标签: ${item.tags}")
            }
            if (item.targetDeviceName.isNotBlank()) {
                builder.appendLine("- 设备: ${item.targetDeviceName}")
            }
            if (item.sourceApp.isNotBlank()) {
                builder.appendLine("- 来源 App: ${item.sourceApp}")
            }
            if (item.sourcePackage.isNotBlank()) {
                builder.appendLine("- 来源包名: ${item.sourcePackage}")
            }
            if (item.metadata.isNotBlank()) {
                builder.appendLine("- metadata: `${item.metadata}`")
            }
            item.serverMessageId?.takeIf { it.isNotBlank() }?.let {
                builder.appendLine("- 服务器记录 ID: $it")
            }
            item.errorMessage?.takeIf { it.isNotBlank() }?.let {
                builder.appendLine("- 状态说明: $it")
            }
            builder.appendLine()
            builder.appendLine(item.text)
            builder.appendLine()
        }

        return builder.toString().trimEnd()
    }

    fun buildCsv(items: List<HistoryItem>): String {
        val dateFormat = exportDateFormat()
        val builder = StringBuilder()
        builder.appendLine("id,timestamp,target_device_id,target_device_name,status,channel,content_type,source_app,source_package,server_message_id,stored_at,synced_at,favorite,pinned,tags,metadata,error_message,text")
        sortedForExport(items).forEach { item ->
            builder.appendLine(
                listOf(
                    item.id,
                    dateFormat.format(Date(item.timestamp)),
                    item.targetDeviceId,
                    item.targetDeviceName,
                    syncStatusLabel(item.syncStatus),
                    item.channel,
                    item.contentType,
                    item.sourceApp,
                    item.sourcePackage,
                    item.serverMessageId.orEmpty(),
                    item.storedAt?.let { dateFormat.format(Date(it)) }.orEmpty(),
                    item.syncedAt?.let { dateFormat.format(Date(it)) }.orEmpty(),
                    if (item.isFavorite) "1" else "0",
                    if (item.isPinned) "1" else "0",
                    item.tags,
                    item.metadata,
                    item.errorMessage.orEmpty(),
                    item.text
                ).joinToString(",") { escapeCsv(it) }
            )
        }
        return builder.toString().trimEnd()
    }

    fun exportTitleForItems(items: List<HistoryItem>): String {
        return if (items.isNotEmpty() && items.all { it.contentType == "notification" }) {
            "通知记录"
        } else {
            "历史记录"
        }
    }

    fun sortedForExport(items: List<HistoryItem>): List<HistoryItem> {
        return items.sortedWith(
            compareByDescending<HistoryItem> { it.isPinned }
                .thenByDescending { it.isFavorite }
                .thenBy { it.timestamp }
        )
    }

    fun syncStatusLabel(status: SyncStatus): String {
        return when (status) {
            SyncStatus.PENDING -> "等待电脑确认"
            SyncStatus.STORED -> "已暂存"
            SyncStatus.SYNCED -> "已发送"
            SyncStatus.FAILED -> "失败"
            SyncStatus.DIRECT -> "局域网直连"
        }
    }

    fun escapeCsv(value: String): String {
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun exportDateFormat(): SimpleDateFormat {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }
}

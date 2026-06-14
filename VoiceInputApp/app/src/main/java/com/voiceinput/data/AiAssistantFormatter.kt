package com.voiceinput.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AiAssistantFormatter {
    private val gson = Gson()

    data class ExportState(
        val sessionId: String? = null,
        val recordCount: Int = 0,
        val exportedFilePath: String? = null,
        val status: String = "",
        val toolEvents: List<ToolEvent> = emptyList(),
        val messages: List<Message> = emptyList()
    )

    data class Message(
        val role: String,
        val content: String,
        val recordCount: Int = 0,
        val exportedFilePath: String? = null
    )

    data class ToolEvent(
        val event: String,
        val toolName: String = "",
        val message: String = "",
        val detail: String = "",
        val timestamp: Long
    )

    fun buildToolEventDetail(payload: JsonObject): String {
        val data = payload.getAsJsonObject("data") ?: return ""
        val parts = mutableListOf<String>()
        data.getAsJsonObject("arguments")?.let { arguments ->
            parts += "参数 ${gson.toJson(arguments)}"
        }
        data.get("record_count")?.takeIf { !it.isJsonNull }?.asInt?.let {
            parts += "记录 $it 条"
        }
        data.get("app_count")?.takeIf { !it.isJsonNull }?.asInt?.let {
            parts += "App $it 个"
        }
        data.get("record_id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let {
            parts += "记录 $it"
        }
        data.getAsJsonArray("record_ids")?.let { ids ->
            val preview = ids
                .mapNotNull { item -> item.takeIf { !it.isJsonNull }?.asString?.takeIf { value -> value.isNotBlank() } }
                .take(5)
                .joinToString(", ")
            if (preview.isNotBlank()) {
                parts += "记录ID $preview${if (ids.size() > 5) " 等${ids.size()}条" else ""}"
            }
        }
        data.getAsJsonArray("record_samples")?.let { samples ->
            val preview = samples
                .mapNotNull { item ->
                    item.takeIf { it.isJsonObject }?.asJsonObject?.let { record ->
                        val id = record.get("id")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                        val type = record.get("content_type")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                        val content = record.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                        listOf(id, type, content.take(24)).filter { it.isNotBlank() }.joinToString(" ")
                    }
                }
                .take(3)
                .joinToString("；")
            if (preview.isNotBlank()) {
                parts += "样例 $preview"
            }
        }
        val exportedFile = data.getAsJsonObject("exported_file")
        val savedPath = exportedFile
            ?.get("saved_path")
            ?.takeIf { !it.isJsonNull }
            ?.asString
            ?.takeIf { it.isNotBlank() }
        if (!savedPath.isNullOrBlank()) {
            parts += "已导出 $savedPath"
        }
        if (data.get("scheduled")?.takeIf { !it.isJsonNull }?.asBoolean == true) {
            parts += "已安排导出"
        }
        if (data.get("saved")?.takeIf { !it.isJsonNull }?.asBoolean == true) {
            parts += "会话已保存"
        }
        data.get("selected_skill")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let {
            parts += "Skill $it"
        }
        data.get("source_filters")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let {
            parts += "范围 $it"
        }
        return parts.joinToString(" · ")
    }

    fun buildExportMarkdown(state: ExportState): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val builder = StringBuilder()
        builder.appendLine("# 语传 AI 助手")
        builder.appendLine()
        builder.appendLine("- 导出时间: ${dateFormat.format(Date())}")
        state.sessionId?.takeIf { it.isNotBlank() }?.let {
            builder.appendLine("- 会话: $it")
        }
        if (state.recordCount > 0) {
            builder.appendLine("- 引用记录: ${state.recordCount} 条")
        }
        state.exportedFilePath?.takeIf { it.isNotBlank() }?.let {
            builder.appendLine("- PC Word 导出: $it")
        }
        state.status.takeIf { it.isNotBlank() }?.let {
            builder.appendLine("- 状态: $it")
        }
        builder.appendLine()

        if (state.toolEvents.isNotEmpty()) {
            builder.appendLine("## 工具调用轨迹")
            builder.appendLine()
            state.toolEvents.forEachIndexed { index, event ->
                builder.appendLine("### ${index + 1}. ${event.event.ifBlank { "tool_event" }}")
                builder.appendLine()
                builder.appendLine("- 时间: ${dateFormat.format(Date(event.timestamp))}")
                if (event.toolName.isNotBlank()) {
                    builder.appendLine("- 工具: ${event.toolName}")
                }
                if (event.message.isNotBlank()) {
                    builder.appendLine("- 消息: ${event.message}")
                }
                if (event.detail.isNotBlank()) {
                    builder.appendLine("- 结果: ${event.detail}")
                }
                builder.appendLine()
            }
        }

        state.messages
            .filter { it.content.isNotBlank() }
            .forEach { message ->
                val role = if (message.role == "user") "我" else "PC AI 助手"
                builder.appendLine("## $role")
                builder.appendLine()
                builder.appendLine(message.content.trim())
                if (message.recordCount > 0) {
                    builder.appendLine()
                    builder.appendLine("> 引用 ${message.recordCount} 条 PC 记录")
                }
                message.exportedFilePath?.takeIf { it.isNotBlank() }?.let {
                    builder.appendLine()
                    builder.appendLine("> PC Word 导出: $it")
                }
                builder.appendLine()
            }

        return builder.toString().trimEnd()
    }

    fun buildWordExportRequest(): String {
        return buildString {
            appendLine("请把当前 AI 助手会话的最新回答导出为 Word 文件。")
            appendLine("必须由你自主选择 export_answer_word 工具完成，format 必须为 word。")
            appendLine("导出标题建议：语传 AI 助手回答。")
            appendLine("如果需要保存会话，也可以同时选择 save_ai_session 工具。")
        }.trim()
    }
}

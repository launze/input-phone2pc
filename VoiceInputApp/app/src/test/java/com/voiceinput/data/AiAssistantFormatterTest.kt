package com.voiceinput.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAssistantFormatterTest {
    @Test
    fun toolEventDetailSummarizesArgumentsRecordsSamplesAndExportState() {
        val payload = JsonParser.parseString(
            """
            {
              "data": {
                "arguments": {"content_type":"notification","favorite":true},
                "record_count": 6,
                "app_count": 2,
                "record_id": "pc-1",
                "record_ids": ["pc-1","pc-2","pc-3","pc-4","pc-5","pc-6"],
                "record_samples": [
                  {"id":"pc-1","content_type":"notification","content":"项目群下午三点同步"},
                  {"id":"pc-2","content_type":"text","content":"会议纪要很长很长很长很长很长"},
                  {"id":"pc-3","content_type":"file","content":"roadmap.md"},
                  {"id":"pc-4","content_type":"image","content":"shot.png"}
                ],
                "exported_file": {"saved_path":"C:/tmp/report.docx"},
                "scheduled": true,
                "saved": true,
                "selected_skill": "daily_notification_digest",
                "source_filters": "{\"content_type\":\"notification\"}"
              }
            }
            """.trimIndent()
        ).asJsonObject

        val detail = AiAssistantFormatter.buildToolEventDetail(payload)

        assertTrue(detail.contains("参数 {\"content_type\":\"notification\",\"favorite\":true}"))
        assertTrue(detail.contains("记录 6 条"))
        assertTrue(detail.contains("App 2 个"))
        assertTrue(detail.contains("记录 pc-1"))
        assertTrue(detail.contains("记录ID pc-1, pc-2, pc-3, pc-4, pc-5 等6条"))
        assertTrue(detail.contains("样例 pc-1 notification 项目群下午三点同步"))
        assertTrue(detail.contains("pc-3 file roadmap.md"))
        assertFalse(detail.contains("pc-4 image"))
        assertTrue(detail.contains("已导出 C:/tmp/report.docx"))
        assertTrue(detail.contains("已安排导出"))
        assertTrue(detail.contains("会话已保存"))
        assertTrue(detail.contains("Skill daily_notification_digest"))
        assertTrue(detail.contains("范围 {\"content_type\":\"notification\"}"))
    }

    @Test
    fun exportMarkdownIncludesToolEventsMessagesReferencesAndWordPath() {
        val markdown = AiAssistantFormatter.buildExportMarkdown(
            AiAssistantFormatter.ExportState(
                sessionId = "session-1",
                recordCount = 3,
                exportedFilePath = "C:/tmp/session.docx",
                status = "PC AI 助手已完成",
                toolEvents = listOf(
                    AiAssistantFormatter.ToolEvent(
                        event = "tool_call_result",
                        toolName = "search_notification_records",
                        message = "工具调用完成",
                        detail = "记录 3 条",
                        timestamp = 1_700_000_000_000
                    )
                ),
                messages = listOf(
                    AiAssistantFormatter.Message(
                        role = "user",
                        content = "总结今天通知"
                    ),
                    AiAssistantFormatter.Message(
                        role = "assistant",
                        content = "今天有三条工作通知。",
                        recordCount = 3,
                        exportedFilePath = "C:/tmp/answer.docx"
                    ),
                    AiAssistantFormatter.Message(
                        role = "assistant",
                        content = "   "
                    )
                )
            )
        )

        assertTrue(markdown.startsWith("# 语传 AI 助手"))
        assertTrue(markdown.contains("- 会话: session-1"))
        assertTrue(markdown.contains("- 引用记录: 3 条"))
        assertTrue(markdown.contains("- PC Word 导出: C:/tmp/session.docx"))
        assertTrue(markdown.contains("## 工具调用轨迹"))
        assertTrue(markdown.contains("### 1. tool_call_result"))
        assertTrue(markdown.contains("- 工具: search_notification_records"))
        assertTrue(markdown.contains("- 结果: 记录 3 条"))
        assertTrue(markdown.contains("## 我"))
        assertTrue(markdown.contains("总结今天通知"))
        assertTrue(markdown.contains("## PC AI 助手"))
        assertTrue(markdown.contains("今天有三条工作通知。"))
        assertTrue(markdown.contains("> 引用 3 条 PC 记录"))
        assertTrue(markdown.contains("> PC Word 导出: C:/tmp/answer.docx"))
        assertFalse(markdown.contains("   "))
    }

    @Test
    fun toolEventDetailReturnsBlankWhenDataMissing() {
        val detail = AiAssistantFormatter.buildToolEventDetail(JsonParser.parseString("{}").asJsonObject)

        assertTrue(detail.isBlank())
    }

    @Test
    fun wordExportRequestKeepsToolSelectionWithLlm() {
        val request = AiAssistantFormatter.buildWordExportRequest()

        assertTrue(request.contains("export_answer_word"))
        assertTrue(request.contains("format 必须为 word"))
        assertTrue(request.contains("自主选择"))
        assertTrue(request.contains("save_ai_session"))
        assertFalse(request.contains("App 直接导出"))
        assertEquals(request.trim(), request)
    }
}

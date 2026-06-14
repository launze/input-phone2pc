package com.voiceinput.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAssistantReducerTest {
    @Test
    fun shouldAcceptOnlyPendingRequestWhenGenerationIsActive() {
        val state = stateWithStreamingRequest("req-1")

        assertTrue(AiAssistantReducer.shouldAcceptPayload(state, "req-1", "req-1"))
        assertFalse(AiAssistantReducer.shouldAcceptPayload(state, "req-1", "req-2"))
        assertFalse(AiAssistantReducer.shouldAcceptPayload(state, "req-1", null))
    }

    @Test
    fun shouldAcceptLatePayloadForKnownFinishedRequestWhenNoPendingRequest() {
        val state = stateWithStreamingRequest("req-1")

        assertTrue(AiAssistantReducer.shouldAcceptPayload(state, null, "req-1"))
        assertFalse(AiAssistantReducer.shouldAcceptPayload(state, null, "req-2"))
        assertTrue(AiAssistantReducer.shouldAcceptPayload(state, null, null))
    }

    @Test
    fun deltaAppendsToAssistantPlaceholderAndUpdatesAnswer() {
        val state = stateWithStreamingRequest("req-1")

        val updated = AiAssistantReducer.applyDelta(state, "req-1", "第一段")
            .let { AiAssistantReducer.applyDelta(it, "req-1", "第二段") }

        val assistant = updated.messages.single { it.id == "req-1-assistant" }
        assertEquals("第一段第二段", assistant.content)
        assertTrue(assistant.streaming)
        assertEquals("第一段第二段", updated.answer)
        assertEquals("PC AI 助手正在生成...", updated.status)
    }

    @Test
    fun successfulResponseReplacesPlaceholderAndClearsPending() {
        val state = stateWithStreamingRequest("req-1")

        val reduction = AiAssistantReducer.applyResponseSuccess(
            state = state,
            requestId = "req-1",
            answer = "最终回答",
            sessionId = "session-1",
            recordCount = 3,
            exportedFilePath = "C:/tmp/answer.docx",
            timestamp = 2000L
        )

        val assistant = reduction.state.messages.single { it.id == "req-1-assistant" }
        assertEquals("最终回答", assistant.content)
        assertFalse(assistant.streaming)
        assertEquals(3, assistant.recordCount)
        assertEquals("C:/tmp/answer.docx", assistant.exportedFilePath)
        assertEquals("session-1", reduction.state.sessionId)
        assertFalse(reduction.state.loading)
        assertNull(reduction.state.error)
        assertTrue(reduction.clearPendingRequest)
        assertTrue(reduction.state.status.contains("已导出 Word"))
    }

    @Test
    fun failedResponseStopsAssistantPlaceholder() {
        val state = stateWithStreamingRequest("req-1")

        val reduction = AiAssistantReducer.applyResponseFailure(
            state = state,
            requestId = "req-1",
            error = "规划失败"
        )

        val assistant = reduction.state.messages.single { it.id == "req-1-assistant" }
        assertFalse(assistant.streaming)
        assertFalse(reduction.state.loading)
        assertEquals("规划失败", reduction.state.error)
        assertTrue(reduction.clearPendingRequest)
    }

    @Test
    fun terminalToolEventStopsStreamingAndKeepsLastAnswer() {
        val state = AiAssistantReducer.applyDelta(stateWithStreamingRequest("req-1"), "req-1", "流式回答")

        val reduction = AiAssistantReducer.applyToolEvent(
            state = state,
            requestId = "req-1",
            eventItem = AiAssistantToolEvent(
                id = "tool-1",
                event = "assistant_done",
                toolName = "",
                message = "",
                timestamp = 3000L
            ),
            sessionId = "session-1",
            recordCount = 5,
            exportedFilePath = "C:/tmp/final.docx",
            status = "PC AI 助手已完成，已导出 Word"
        )

        val assistant = reduction.state.messages.single { it.id == "req-1-assistant" }
        assertFalse(assistant.streaming)
        assertEquals(5, assistant.recordCount)
        assertEquals("C:/tmp/final.docx", assistant.exportedFilePath)
        assertEquals("流式回答", reduction.state.answer)
        assertEquals("session-1", reduction.state.sessionId)
        assertFalse(reduction.state.loading)
        assertTrue(reduction.clearPendingRequest)
    }

    @Test
    fun toolEventsKeepOnlyLatestThirty() {
        val state = (1..30).fold(stateWithStreamingRequest("req-1")) { current, index ->
            AiAssistantReducer.applyToolEvent(
                state = current,
                requestId = "req-1",
                eventItem = AiAssistantToolEvent(id = "tool-$index", event = "tool_call_result", timestamp = index.toLong()),
                sessionId = null,
                recordCount = null,
                exportedFilePath = null,
                status = "事件 $index"
            ).state
        }

        val updated = AiAssistantReducer.applyToolEvent(
            state = state,
            requestId = "req-1",
            eventItem = AiAssistantToolEvent(id = "tool-31", event = "tool_call_result", timestamp = 31L),
            sessionId = null,
            recordCount = null,
            exportedFilePath = null,
            status = "事件 31"
        ).state

        assertEquals(30, updated.toolEvents.size)
        assertEquals("tool-2", updated.toolEvents.first().id)
        assertEquals("tool-31", updated.toolEvents.last().id)
    }

    private fun stateWithStreamingRequest(requestId: String): AiAssistantState {
        return AiAssistantState(
            question = "问题",
            messages = listOf(
                AiAssistantMessage(
                    id = "$requestId-user",
                    role = "user",
                    content = "问题",
                    timestamp = 1000L
                ),
                AiAssistantMessage(
                    id = "$requestId-assistant",
                    role = "assistant",
                    content = "",
                    timestamp = 1001L,
                    streaming = true
                )
            ),
            loading = true,
            status = "等待 PC 返回"
        )
    }
}

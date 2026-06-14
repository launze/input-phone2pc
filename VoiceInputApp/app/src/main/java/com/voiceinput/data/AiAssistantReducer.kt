package com.voiceinput.data

object AiAssistantReducer {
    data class Reduction(
        val state: AiAssistantState,
        val clearPendingRequest: Boolean = false
    )

    fun shouldAcceptPayload(
        state: AiAssistantState,
        pendingRequestId: String?,
        requestId: String?
    ): Boolean {
        if (requestId.isNullOrBlank()) {
            return pendingRequestId == null
        }
        val pending = pendingRequestId
        if (pending != null) {
            return requestId == pending
        }
        val messageIdPrefix = "$requestId-"
        return state.messages.any { message ->
            message.id == "$requestId-assistant" ||
                message.id == "$requestId-user" ||
                message.id.startsWith(messageIdPrefix)
        }
    }

    fun applyDelta(
        state: AiAssistantState,
        requestId: String,
        delta: String
    ): AiAssistantState {
        if (delta.isBlank()) return state
        val messageId = "$requestId-assistant"
        val updatedMessages = state.messages.map { message ->
            if (message.id == messageId) {
                message.copy(
                    content = message.content + delta,
                    streaming = true
                )
            } else {
                message
            }
        }
        return state.copy(
            messages = updatedMessages,
            answer = updatedMessages.lastOrNull { it.id == messageId }?.content.orEmpty(),
            status = "PC AI 助手正在生成..."
        )
    }

    fun applyResponseSuccess(
        state: AiAssistantState,
        requestId: String?,
        answer: String,
        sessionId: String?,
        recordCount: Int,
        exportedFilePath: String?,
        timestamp: Long
    ): Reduction {
        val assistantMessage = AiAssistantMessage(
            id = "${requestId ?: "app-ai"}-assistant",
            role = "assistant",
            content = answer,
            timestamp = timestamp,
            recordCount = recordCount,
            exportedFilePath = exportedFilePath,
            streaming = false
        )
        val messages = replaceMessage(state.messages, assistantMessage.id, assistantMessage)
        return Reduction(
            state = state.copy(
                answer = answer,
                sessionId = sessionId,
                recordCount = recordCount,
                exportedFilePath = exportedFilePath,
                messages = messages,
                loading = false,
                error = null,
                status = if (exportedFilePath.isNullOrBlank()) {
                    "PC AI 助手已返回，引用 $recordCount 条记录"
                } else {
                    "PC AI 助手已返回，引用 $recordCount 条记录，已导出 Word"
                }
            ),
            clearPendingRequest = true
        )
    }

    fun applyResponseFailure(
        state: AiAssistantState,
        requestId: String?,
        error: String
    ): Reduction {
        return Reduction(
            state = state.copy(
                messages = stopStreamingMessage(state.messages, requestId),
                loading = false,
                error = error,
                status = "AI 助手执行失败"
            ),
            clearPendingRequest = true
        )
    }

    fun applyToolEvent(
        state: AiAssistantState,
        requestId: String,
        eventItem: AiAssistantToolEvent,
        sessionId: String?,
        recordCount: Int?,
        exportedFilePath: String?,
        status: String
    ): Reduction {
        val terminalError = eventItem.event == "assistant_error"
        val terminalDone = eventItem.event == "assistant_done"
        val terminal = terminalError || terminalDone
        val updatedMessages = if (terminal) {
            state.messages.map { message ->
                if (message.id == "$requestId-assistant") {
                    message.copy(
                        streaming = false,
                        recordCount = recordCount ?: message.recordCount,
                        exportedFilePath = exportedFilePath ?: message.exportedFilePath
                    )
                } else {
                    message
                }
            }
        } else {
            state.messages
        }
        val assistantAnswer = updatedMessages
            .lastOrNull { it.id == "$requestId-assistant" }
            ?.content
            .orEmpty()
        return Reduction(
            state = state.copy(
                messages = updatedMessages,
                toolEvents = (state.toolEvents + eventItem).takeLast(30),
                answer = if (terminalDone && assistantAnswer.isNotBlank()) assistantAnswer else state.answer,
                sessionId = sessionId ?: state.sessionId,
                recordCount = recordCount ?: state.recordCount,
                exportedFilePath = exportedFilePath ?: state.exportedFilePath,
                loading = if (terminal) false else state.loading,
                error = if (terminalError) eventItem.message.ifBlank { "PC AI 助手执行失败" } else state.error,
                status = status
            ),
            clearPendingRequest = terminal
        )
    }

    private fun replaceMessage(
        messages: List<AiAssistantMessage>,
        messageId: String,
        replacement: AiAssistantMessage
    ): List<AiAssistantMessage> {
        var replaced = false
        val updated = messages.map { message ->
            if (message.id == messageId) {
                replaced = true
                replacement
            } else {
                message
            }
        }
        return if (replaced) updated else updated + replacement
    }

    private fun stopStreamingMessage(
        messages: List<AiAssistantMessage>,
        requestId: String?
    ): List<AiAssistantMessage> {
        val messageId = "${requestId ?: "app-ai"}-assistant"
        return messages.map { message ->
            if (message.id == messageId) {
                message.copy(streaming = false)
            } else {
                message
            }
        }
    }
}

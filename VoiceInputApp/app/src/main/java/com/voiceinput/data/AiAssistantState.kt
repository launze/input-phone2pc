package com.voiceinput.data

data class AiAssistantState(
    val question: String = "",
    val answer: String = "",
    val messages: List<AiAssistantMessage> = emptyList(),
    val sessionId: String? = null,
    val recordCount: Int = 0,
    val exportedFilePath: String? = null,
    val toolEvents: List<AiAssistantToolEvent> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val status: String = "输入问题后会发送给 PC AI 助手"
)

data class AiAssistantMessage(
    val id: String = "",
    val role: String,
    val content: String,
    val timestamp: Long,
    val recordCount: Int = 0,
    val exportedFilePath: String? = null,
    val streaming: Boolean = false
)

data class AiAssistantToolEvent(
    val id: String = "",
    val event: String,
    val toolName: String = "",
    val message: String = "",
    val detail: String = "",
    val timestamp: Long
)

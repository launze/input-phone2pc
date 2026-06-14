package com.voiceinput.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voiceinput.viewmodel.InputViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantScreen(
    viewModel: InputViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.aiAssistantState.collectAsState()
    val context = LocalContext.current
    var question by remember(state.question) { mutableStateOf(state.question) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) {
            viewModel.exportAiAssistantAnswer(uri) { _, message ->
                scope.launch {
                    snackbarHostState.showSnackbar(message)
                }
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("已取消导出")
            }
        }
    }
    val canExport = state.messages.any { it.role != "user" && it.content.isNotBlank() }
    val latestAssistantAnswer = state.messages
        .lastOrNull { it.role != "user" && it.content.isNotBlank() }
        ?.content
        .orEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("AI 助手") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("询问 PC AI 助手") },
                placeholder = { Text("例如：总结今天微信通知里的待办") },
                minLines = 3,
                maxLines = 8,
                enabled = !state.loading
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        viewModel.askAiAssistant(question)
                        question = ""
                    },
                    enabled = !state.loading && question.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (state.loading) "等待 PC 返回" else "发送给 PC AI")
                }
                if (state.loading) {
                    Button(
                        onClick = { viewModel.cancelAiAssistant() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("停止")
                    }
                }
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        viewModel.startNewAiAssistantSession()
                        question = ""
                    },
                    enabled = !state.loading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("新会话")
                }
                Button(
                    onClick = {
                        copyTextToClipboard(context, latestAssistantAnswer)
                        scope.launch {
                            snackbarHostState.showSnackbar("AI 回答已复制")
                        }
                    },
                    enabled = latestAssistantAnswer.isNotBlank() && !state.loading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("复制回答")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        exportLauncher.launch(viewModel.suggestedAiAssistantExportFileName())
                    },
                    enabled = canExport && !state.loading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导出 MD")
                }
            }
            Button(
                onClick = { viewModel.requestAiAssistantWordExport() },
                enabled = canExport && !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("PC 导出 Word")
            }

            Text(
                text = "问题会发送到 PC；PC AI 会基于历史记录、通知记录和 Skills 自主选择工具查询。历史页发起总结时，会把当前筛选或选中记录写入问题范围。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = state.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            state.error?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (state.messages.isEmpty() && !state.loading) {
                    Text(
                        text = "答案会使用 PC 端同一套 Skills、历史记录和通知工具生成。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.toolEvents.isNotEmpty()) {
                            Text(
                                text = "工具调用（${state.toolEvents.size}）",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            state.toolEvents.takeLast(12).forEach { event ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = aiToolEventLabel(event.event, event.toolName),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        event.message.takeIf { it.isNotBlank() }?.let { message ->
                                            Text(
                                                text = message,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        event.detail.takeIf { it.isNotBlank() }?.let { detail ->
                                            Text(
                                                text = detail,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        state.messages.forEach { message ->
                            val isUser = message.role == "user"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                            ) {
                                Card(
                                    modifier = Modifier.widthIn(max = 320.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isUser) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = if (isUser) "我" else "PC AI 助手",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = if (message.content.isBlank() && message.streaming) {
                                                "正在生成..."
                                            } else {
                                                message.content
                                            },
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        if (message.streaming && message.content.isNotBlank()) {
                                            Text(
                                                text = "生成中",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (!isUser && message.recordCount > 0) {
                                            Text(
                                                text = "引用 ${message.recordCount} 条 PC 记录",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (!isUser && !message.exportedFilePath.isNullOrBlank()) {
                                            Text(
                                                text = "PC Word: ${message.exportedFilePath}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copyTextToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("语传 AI 回答", text))
}

private fun aiToolEventLabel(event: String, toolName: String): String {
    val resolvedTool = toolName.ifBlank { "工具" }
    return when (event) {
        "tool_call_start" -> "开始调用 $resolvedTool"
        "tool_call_result" -> "$resolvedTool 调用完成"
        "assistant_done" -> "回答完成"
        "assistant_error" -> "执行失败"
        else -> event.ifBlank { "工具事件" }
    }
}

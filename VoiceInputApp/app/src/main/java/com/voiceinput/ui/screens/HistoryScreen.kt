package com.voiceinput.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voiceinput.data.model.HistoryItem
import com.voiceinput.data.model.SyncStatus
import com.voiceinput.ui.components.HistoryItemView
import com.voiceinput.viewmodel.InputViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: InputViewModel,
    onBack: () -> Unit
) {
    val historyItems by viewModel.historyItems.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }
    var showClearDialog by remember { mutableStateOf(false) }
    var pendingExportFormat by remember { mutableStateOf("txt") }
    var pendingExportFileName by remember { mutableStateOf<String?>(null) }
    var pendingExportItems by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    val retryableTextCount = remember(historyItems) {
        historyItems.count { it.syncStatus == SyncStatus.FAILED && it.contentType == "text" }
    }

    val filteredItems = remember(historyItems, selectedTab) {
        if (selectedTab == 1) {
            historyItems.filter { it.isPending || it.syncStatus == SyncStatus.FAILED }
        } else {
            historyItems
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val fileName = pendingExportFileName
        val format = pendingExportFormat
        val items = pendingExportItems
        pendingExportFileName = null
        pendingExportItems = emptyList()
        if (uri != null) {
            viewModel.exportHistory(uri, items, format) { _, message ->
                scope.launch {
                    snackbarHostState.showSnackbar(message)
                }
            }
        } else if (fileName != null) {
            scope.launch {
                snackbarHostState.showSnackbar("已取消导出")
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.reloadHistory()
    }

    LaunchedEffect(Unit) {
        viewModel.uiMessages.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.reloadHistory()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("历史记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    if (it.isBlank()) {
                        viewModel.reloadHistory()
                    } else {
                        viewModel.searchHistory(it)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索历史") },
                placeholder = { Text("输入关键词或设备名") },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(
                            onClick = {
                                searchQuery = ""
                                viewModel.reloadHistory()
                            }
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "清空搜索")
                        }
                    }
                },
                singleLine = true
            )

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("全部 (${historyItems.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            "待同步 (${
                                historyItems.count { it.isPending || it.syncStatus == SyncStatus.FAILED }
                            })"
                        )
                    }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.retryFailedTextItems() },
                    enabled = retryableTextCount > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("重试失败文本 ($retryableTextCount)")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExportButton(
                        label = "TXT",
                        enabled = filteredItems.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        pendingExportFormat = "txt"
                        pendingExportItems = filteredItems
                        pendingExportFileName = viewModel.suggestedHistoryExportFileName("txt")
                        exportLauncher.launch(pendingExportFileName)
                    }
                    ExportButton(
                        label = "MD",
                        enabled = filteredItems.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        pendingExportFormat = "md"
                        pendingExportItems = filteredItems
                        pendingExportFileName = viewModel.suggestedHistoryExportFileName("md")
                        exportLauncher.launch(pendingExportFileName)
                    }
                    ExportButton(
                        label = "CSV",
                        enabled = filteredItems.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        pendingExportFormat = "csv"
                        pendingExportItems = filteredItems
                        pendingExportFileName = viewModel.suggestedHistoryExportFileName("csv")
                        exportLauncher.launch(pendingExportFileName)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedTab == 1) {
                        "待同步/异常 ${filteredItems.size} 条"
                    } else {
                        "共 ${filteredItems.size} 条记录"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TextButton(
                    onClick = { showClearDialog = true },
                    enabled = filteredItems.isNotEmpty()
                ) {
                    Text("清空历史")
                }
            }

            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            searchQuery.isNotBlank() -> "没有匹配的记录"
                            selectedTab == 1 -> "当前没有待同步记录"
                            else -> "暂无历史记录"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = filteredItems.sortedByDescending { it.timestamp },
                        key = { it.id }
                    ) { item ->
                        HistoryItemView(
                            item = item,
                            onClick = {
                                viewModel.onHistoryItemClick(item)
                                onBack()
                            },
                            onRetry = if (
                                item.syncStatus == SyncStatus.FAILED &&
                                item.contentType == "text"
                            ) {
                                { viewModel.retryHistoryItem(item.id) }
                            } else {
                                null
                            },
                            onDelete = { viewModel.deleteHistoryItem(item.id) }
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空历史记录") },
            text = { Text("确定要清空当前历史记录吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearDialog = false
                        scope.launch {
                            snackbarHostState.showSnackbar("历史记录已清空")
                        }
                    }
                ) {
                    Text("确定", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun ExportButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
    ) {
        Text(label)
    }
}

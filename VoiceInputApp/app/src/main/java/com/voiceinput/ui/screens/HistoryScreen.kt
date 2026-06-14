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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
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
import com.google.gson.JsonObject
import com.voiceinput.data.HistoryAiScope
import com.voiceinput.data.HistoryFilterState
import com.voiceinput.data.HistoryListFilter
import com.voiceinput.data.model.HistoryItem
import com.voiceinput.ui.components.HistoryItemView
import com.voiceinput.viewmodel.InputViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: InputViewModel,
    recordMode: String = "history",
    onBack: () -> Unit,
    onScanPair: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSwitchDevice: () -> Unit = {},
    onCheckServer: () -> Unit = {},
    onOpenAiAssistant: () -> Unit = {}
) {
    val historyItems by viewModel.historyItems.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }
    var showClearDialog by remember { mutableStateOf(false) }
    var pendingExportFormat by remember { mutableStateOf("txt") }
    var pendingExportFileName by remember { mutableStateOf<String?>(null) }
    var pendingExportItems by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf("all") }
    var selectedChannel by remember { mutableStateOf("all") }
    var selectedSourceApp by remember { mutableStateOf("all") }
    var selectedStatus by remember { mutableStateOf("all") }
    var tagQuery by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var showDeleteFilteredDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var initialScrollCompleted by remember { mutableStateOf(false) }
    val notificationMode = recordMode == "notifications"
    val filterState = HistoryFilterState(
        notificationMode = notificationMode,
        selectedTab = selectedTab,
        searchQuery = searchQuery,
        selectedDevice = selectedDevice,
        selectedChannel = selectedChannel,
        selectedSourceApp = selectedSourceApp,
        selectedStatus = selectedStatus,
        tagQuery = tagQuery
    )
    val filterResult = remember(historyItems, filterState) {
        HistoryListFilter.evaluate(historyItems, filterState)
    }
    val scopedHistoryItems = filterResult.scopedItems
    val historyTabs = filterResult.tabs
    val pendingTabIndex = filterResult.pendingTabIndex
    val deviceOptions = filterResult.deviceOptions
    val channelOptions = filterResult.channelOptions
    val sourceAppOptions = filterResult.sourceAppOptions
    val statusOptions = filterResult.statusOptions
    val filteredItems = filterResult.filteredItems
    val selectedItems = remember(filteredItems, selectedIds) {
        filteredItems.filter { it.id in selectedIds }
    }
    val latestHistorySignature = remember(filteredItems) {
        filteredItems.lastOrNull()?.let { item ->
            listOf(
                item.id,
                item.timestamp,
                item.contentType,
                item.syncStatus.name,
                item.text,
                item.errorMessage.orEmpty(),
                item.syncedAt ?: 0L,
                item.storedAt ?: 0L
            ).joinToString("|")
        }.orEmpty()
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

    DisposableEffect(Unit) {
        onDispose {
            viewModel.reloadHistory()
        }
    }

    LaunchedEffect(selectedTab, searchQuery, selectedDevice, selectedChannel, selectedSourceApp, selectedStatus, tagQuery) {
        initialScrollCompleted = false
        selectedIds = emptySet()
    }

    LaunchedEffect(latestHistorySignature) {
        if (filteredItems.isEmpty()) {
            initialScrollCompleted = false
            return@LaunchedEffect
        }

        if (!initialScrollCompleted) {
            listState.scrollToItem(filteredItems.lastIndex)
            initialScrollCompleted = true
        } else {
            listState.animateScrollToItem(filteredItems.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (notificationMode) "通知记录" else "历史记录") },
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
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (notificationMode) "搜索通知" else "搜索历史") },
                placeholder = { Text(if (notificationMode) "输入关键词、App 或设备名" else "输入关键词或设备名") },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(
                            onClick = {
                                searchQuery = ""
                            }
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "清空搜索")
                        }
                    }
                },
                singleLine = true
            )

            ScrollableTabRow(selectedTabIndex = selectedTab) {
                historyTabs.forEachIndexed { index, label ->
                    val count = filterResult.tabCounts.getOrElse(index) { scopedHistoryItems.size }
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text("$label ($count)") }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HistoryFilterMenu(
                    label = "设备",
                    selected = selectedDevice,
                    options = deviceOptions,
                    allLabel = "全部设备",
                    modifier = Modifier.weight(1f)
                ) { selectedDevice = it }
                HistoryFilterMenu(
                    label = "通道",
                    selected = selectedChannel,
                    options = channelOptions,
                    allLabel = "全部通道",
                    modifier = Modifier.weight(1f)
                ) { selectedChannel = it }
            }

            if (notificationMode && sourceAppOptions.isNotEmpty()) {
                HistoryFilterMenu(
                    label = "来源 App",
                    selected = selectedSourceApp,
                    options = sourceAppOptions,
                    allLabel = "全部 App",
                    modifier = Modifier.fillMaxWidth()
                ) { selectedSourceApp = it }
            }

            HistoryFilterMenu(
                label = "状态",
                selected = selectedStatus,
                options = statusOptions.map { it.first },
                allLabel = "全部状态",
                optionLabels = statusOptions.toMap(),
                modifier = Modifier.fillMaxWidth()
            ) { selectedStatus = it }

            OutlinedTextField(
                value = tagQuery,
                onValueChange = { tagQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("标签筛选") },
                placeholder = { Text("输入标签关键词") },
                singleLine = true,
                trailingIcon = {
                    if (tagQuery.isNotBlank()) {
                        IconButton(onClick = { tagQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "清空标签筛选")
                        }
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExportButton(
                    label = "TXT",
                    enabled = (if (selectionMode) selectedItems else filteredItems).isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    pendingExportFormat = "txt"
                    pendingExportItems = if (selectionMode && selectedItems.isNotEmpty()) selectedItems else filteredItems
                    pendingExportFileName = viewModel.suggestedHistoryExportFileName("txt", recordMode)
                    exportLauncher.launch(pendingExportFileName)
                }
                ExportButton(
                    label = "MD",
                    enabled = (if (selectionMode) selectedItems else filteredItems).isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    pendingExportFormat = "md"
                    pendingExportItems = if (selectionMode && selectedItems.isNotEmpty()) selectedItems else filteredItems
                    pendingExportFileName = viewModel.suggestedHistoryExportFileName("md", recordMode)
                    exportLauncher.launch(pendingExportFileName)
                }
                ExportButton(
                    label = "CSV",
                    enabled = (if (selectionMode) selectedItems else filteredItems).isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    pendingExportFormat = "csv"
                    pendingExportItems = if (selectionMode && selectedItems.isNotEmpty()) selectedItems else filteredItems
                    pendingExportFileName = viewModel.suggestedHistoryExportFileName("csv", recordMode)
                    exportLauncher.launch(pendingExportFileName)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val targetItems = if (selectionMode && selectedItems.isNotEmpty()) selectedItems else filteredItems
                        val selectedTabLabel = historyTabs.getOrElse(selectedTab) { "全部" }
                        viewModel.askAiAssistant(
                            HistoryAiScope.buildQuestion(
                                recordMode = if (notificationMode) "通知记录" else "历史记录",
                                selectedTabLabel = selectedTabLabel,
                                selectedDevice = selectedDevice,
                                selectedChannel = selectedChannel,
                                selectedSourceApp = selectedSourceApp,
                                selectedStatus = selectedStatus,
                                tagQuery = tagQuery,
                                items = targetItems
                            ),
                            buildHistoryAiFilters(
                                notificationMode = notificationMode,
                                selectedTabLabel = selectedTabLabel,
                                selectedChannel = selectedChannel,
                                selectedSourceApp = selectedSourceApp,
                                selectedStatus = selectedStatus,
                                tagQuery = tagQuery,
                                items = targetItems
                            ),
                            continueCurrentSession = false
                        )
                        onOpenAiAssistant()
                    },
                    enabled = (if (selectionMode) selectedItems else filteredItems).isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("AI 总结")
                }
                OutlinedButton(
                    onClick = {
                        selectionMode = !selectionMode
                        selectedIds = emptySet()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.DoneAll, contentDescription = null)
                    Text(if (selectionMode) "退出多选" else "多选")
                }
            }

            if (selectionMode) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                selectedIds = if (selectedIds.size == filteredItems.size) {
                                emptySet()
                            } else {
                                filteredItems.map { it.id }.toSet()
                            }
                        },
                        enabled = filteredItems.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (selectedIds.size == filteredItems.size) "取消全选" else "全选")
                    }
                    Button(
                        onClick = { showDeleteSelectedDialog = true },
                        enabled = selectedIds.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text("删除选中")
                    }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.setHistoryItemsFavorite(selectedIds, true)
                                scope.launch {
                                    snackbarHostState.showSnackbar("已收藏选中${if (notificationMode) "通知" else "记录"}")
                                }
                            },
                            enabled = selectedIds.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null)
                            Text("收藏")
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.setHistoryItemsFavorite(selectedIds, false)
                                scope.launch {
                                    snackbarHostState.showSnackbar("已取消收藏选中${if (notificationMode) "通知" else "记录"}")
                                }
                            },
                            enabled = selectedIds.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.StarBorder, contentDescription = null)
                            Text("取消收藏")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.setHistoryItemsPinned(selectedIds, true)
                                scope.launch {
                                    snackbarHostState.showSnackbar("已置顶选中${if (notificationMode) "通知" else "记录"}")
                                }
                            },
                            enabled = selectedIds.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                            Text("置顶")
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.setHistoryItemsPinned(selectedIds, false)
                                scope.launch {
                                    snackbarHostState.showSnackbar("已取消置顶选中${if (notificationMode) "通知" else "记录"}")
                                }
                            },
                            enabled = selectedIds.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("取消置顶")
                        }
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { showDeleteFilteredDialog = true },
                    enabled = filteredItems.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Text("删除筛选")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedTab == pendingTabIndex) {
                        "待同步/异常 ${filteredItems.size} 条"
                    } else {
                        "共 ${filteredItems.size} 条${if (notificationMode) "通知" else "记录"}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TextButton(
                    onClick = { showClearDialog = true },
                    enabled = filteredItems.isNotEmpty()
                ) {
                    Text(if (notificationMode) "清空全部通知" else "清空全部历史")
                }
            }

            if (filteredItems.isEmpty()) {
                HistoryEmptyState(
                    hasScopedHistory = scopedHistoryItems.isNotEmpty(),
                    hasPairedDevice = pairedDevices.isNotEmpty(),
                    isConnected = connectionStatus.connected,
                    searchQuery = searchQuery,
                    selectedTab = selectedTab,
                    notificationMode = notificationMode,
                    onScanPair = onScanPair,
                    onOpenSettings = onOpenSettings,
                    onSwitchDevice = onSwitchDevice,
                    onCheckServer = onCheckServer,
                    onBackToInput = onBack
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = filteredItems,
                        key = { it.id }
                    ) { item ->
                        HistoryItemView(
                            item = item,
                            onClick = {
                                if (selectionMode) {
                                    selectedIds = toggleSelection(selectedIds, item.id)
                                } else {
                                    viewModel.onHistoryItemClick(item)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("已放入输入框，可编辑后再次发送")
                                    }
                                    onBack()
                                }
                            },
                            onDelete = { viewModel.deleteHistoryItem(item.id) },
                            selectionMode = selectionMode,
                            selected = item.id in selectedIds,
                            onSelectedChange = { checked ->
                                selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id
                            }
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(if (notificationMode) "清空全部通知" else "清空全部历史") },
            text = {
                Text(
                    if (notificationMode) {
                        "确定要清空全部通知记录吗？文本、图片和文件历史会保留。"
                    } else {
                        "确定要清空全部历史记录吗？通知记录会保留。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targetIds = HistoryListFilter.clearTargetIds(historyItems, notificationMode)
                        if (targetIds.isNotEmpty()) {
                            viewModel.deleteHistoryItems(targetIds)
                        }
                        showClearDialog = false
                        scope.launch {
                            snackbarHostState.showSnackbar(if (notificationMode) "通知记录已清空" else "历史记录已清空")
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

    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text("删除选中${if (notificationMode) "通知" else "记录"}") },
            text = { Text("确定删除选中的 ${selectedIds.size} 条${if (notificationMode) "通知" else "历史记录"}吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteHistoryItems(selectedIds)
                        selectedIds = emptySet()
                        selectionMode = false
                        showDeleteSelectedDialog = false
                        scope.launch {
                            snackbarHostState.showSnackbar("已删除选中${if (notificationMode) "通知" else "记录"}")
                        }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showDeleteFilteredDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteFilteredDialog = false },
            title = { Text("删除当前筛选出的${if (notificationMode) "通知" else "记录"}") },
            text = { Text("确定删除当前筛选出的 ${filteredItems.size} 条${if (notificationMode) "通知" else "历史记录"}吗？未命中当前筛选的内容会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteHistoryItems(HistoryListFilter.deleteFilteredTargetIds(filteredItems))
                        selectedIds = emptySet()
                        selectionMode = false
                        showDeleteFilteredDialog = false
                        scope.launch {
                            snackbarHostState.showSnackbar("已删除当前筛选出的${if (notificationMode) "通知" else "记录"}")
                        }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteFilteredDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun HistoryEmptyState(
    hasScopedHistory: Boolean,
    hasPairedDevice: Boolean,
    isConnected: Boolean,
    searchQuery: String,
    selectedTab: Int,
    notificationMode: Boolean,
    onScanPair: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchDevice: () -> Unit,
    onCheckServer: () -> Unit,
    onBackToInput: () -> Unit
) {
    val title: String
    val subtitle: String
    val primaryText: String?
    val primaryAction: (() -> Unit)?
    val secondaryText: String?
    val secondaryAction: (() -> Unit)?
    val pendingTabIndex = if (notificationMode) 1 else 4
    val favoriteTabIndex = if (notificationMode) 2 else 5
    val pinnedTabIndex = if (notificationMode) 3 else 6

    when {
        searchQuery.isNotBlank() -> {
            title = "没有匹配的记录"
            subtitle = if (notificationMode) "换个关键词，或清空筛选后再看全部通知。" else "换个关键词，或清空筛选后再看全部历史。"
            primaryText = null
            primaryAction = null
            secondaryText = null
            secondaryAction = null
        }
        !hasScopedHistory && !hasPairedDevice -> {
            title = if (notificationMode) "暂无通知记录" else "暂无历史记录"
            subtitle = if (notificationMode) {
                "先扫码配对电脑，并在设置里开启通知监听和转发。"
            } else {
                "先扫码配对电脑，发送文字、图片或文件后会出现在这里。"
            }
            primaryText = "扫码配对"
            primaryAction = onScanPair
            secondaryText = "打开设置"
            secondaryAction = onOpenSettings
        }
        !hasScopedHistory && hasPairedDevice && !isConnected -> {
            title = "电脑离线"
            subtitle = if (notificationMode) {
                "可以继续记录通知；服务器或局域网恢复后再同步到电脑。"
            } else {
                "可以继续输入；服务器或局域网恢复后，内容会暂存或同步。"
            }
            primaryText = "切换电脑"
            primaryAction = onSwitchDevice
            secondaryText = "检查服务器"
            secondaryAction = onCheckServer
        }
        selectedTab == pendingTabIndex -> {
            title = "当前没有待同步记录"
            subtitle = "待发送、暂存或失败的内容会集中出现在这里。"
            primaryText = "返回输入"
            primaryAction = onBackToInput
            secondaryText = null
            secondaryAction = null
        }
        notificationMode && !hasScopedHistory -> {
            title = "暂无通知记录"
            subtitle = "在设置里开启通知监听和转发后，微信等通知会进入这里。"
            primaryText = "打开设置"
            primaryAction = onOpenSettings
            secondaryText = null
            secondaryAction = null
        }
        selectedTab == favoriteTabIndex -> {
            title = "暂无收藏记录"
            subtitle = "在历史项上点收藏，后续可快速筛选、导出或让 AI 总结。"
            primaryText = null
            primaryAction = null
            secondaryText = null
            secondaryAction = null
        }
        selectedTab == pinnedTabIndex -> {
            title = "暂无置顶记录"
            subtitle = "把常用记录置顶后，它们会优先显示。"
            primaryText = null
            primaryAction = null
            secondaryText = null
            secondaryAction = null
        }
        !hasScopedHistory -> {
            title = if (notificationMode) "暂无通知记录" else "暂无历史记录"
            subtitle = if (notificationMode) {
                "开启通知监听和转发后，微信等通知会出现在这里。"
            } else {
                "发送文字、图片或文件后会出现在这里。"
            }
            primaryText = "返回输入"
            primaryAction = onBackToInput
            secondaryText = "打开设置"
            secondaryAction = onOpenSettings
        }
        else -> {
            title = "当前筛选没有结果"
            subtitle = "调整类型、设备、通道、状态或标签筛选后再查看。"
            primaryText = null
            primaryAction = null
            secondaryText = null
            secondaryAction = null
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (primaryText != null && primaryAction != null) {
                Button(onClick = primaryAction) {
                    Text(primaryText)
                }
            }
            if (secondaryText != null && secondaryAction != null) {
                OutlinedButton(onClick = secondaryAction) {
                    Text(secondaryText)
                }
            }
        }
    }
}

private fun buildHistoryAiFilters(
    notificationMode: Boolean,
    selectedTabLabel: String,
    selectedChannel: String,
    selectedSourceApp: String,
    selectedStatus: String,
    tagQuery: String,
    items: List<HistoryItem>
): JsonObject {
    // App 侧设备筛选表示目标电脑；PC AI 工具的 from_device 表示来源手机。
    // 目标电脑会保留在自然语言范围说明里，不作为 PC 后端工具参数传入。
    return HistoryAiScope.buildFilters(
        notificationMode = notificationMode,
        selectedTabLabel = selectedTabLabel,
        selectedChannel = selectedChannel,
        selectedSourceApp = selectedSourceApp,
        selectedStatus = selectedStatus,
        tagQuery = tagQuery,
        items = items
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryFilterMenu(
    label: String,
    selected: String,
    options: List<String>,
    allLabel: String,
    optionLabels: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = if (selected == "all") allLabel else optionLabels[selected] ?: selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(allLabel) },
                onClick = {
                    onSelected("all")
                    expanded = false
                }
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabels[option] ?: option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun toggleSelection(current: Set<String>, id: String): Set<String> {
    return if (id in current) current - id else current + id
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

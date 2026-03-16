package com.voiceinput.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voiceinput.data.model.ServerDeviceInfo
import com.voiceinput.network.ServerConnection
import com.voiceinput.ui.components.ConnectionStatusIndicator
import com.voiceinput.ui.components.HistoryItemView
import com.voiceinput.ui.components.InputField
import com.voiceinput.viewmodel.InputViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputScreen(
    viewModel: InputViewModel = viewModel(),
    onNavigateToSettings: () -> Unit = {},
    onNavigateToScanner: () -> Unit = {}
) {
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val historyItems by viewModel.historyItems.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val serverDevices by viewModel.serverDevices.collectAsState()
    val serverConnectionState by viewModel.serverConnectionState.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    var showDeviceDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.startDiscovery()
    }

    val isServerConnected = serverConnectionState is ServerConnection.ConnectionState.Connected

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("语音输入助手")
                        ConnectionStatusIndicator(
                            connected = connectionStatus.connected,
                            deviceName = connectionStatus.deviceName
                        )
                    }
                },
                actions = {
                    // 搜索历史按钮（有历史记录时显示）
                    AnimatedVisibility(
                        visible = historyItems.isNotEmpty(),
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally()
                    ) {
                        IconButton(onClick = { showSearchDialog = true }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "搜索",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    // 清空历史按钮（有历史记录时显示）
                    AnimatedVisibility(
                        visible = historyItems.isNotEmpty(),
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally()
                    ) {
                        IconButton(onClick = { showClearHistoryDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "清空历史",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    // 连接设备按钮（未连接时显示）
                    AnimatedVisibility(
                        visible = !connectionStatus.connected,
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally()
                    ) {
                        IconButton(onClick = { showDeviceDialog = true }) {
                            Icon(
                                Icons.Default.Link,
                                contentDescription = "连接",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    // 设置按钮
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // History list or empty state with scan button
            AnimatedContent(
                targetState = historyItems.isEmpty(),
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith
                            fadeOut(animationSpec = tween(300))
                },
                label = "history",
                modifier = Modifier.weight(1f)
            ) { isEmpty ->
                if (isEmpty) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (connectionStatus.connected) {
                                Text(
                                    text = "已连接",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "在手机上输入文字发送到电脑",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                // 未连接 + 无配对设备 → 显示扫码配对引导
                                Text(
                                    text = if (pairedDevices.isEmpty()) "未配对任何设备" else "未连接设备",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "扫描电脑端二维码完成配对",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = onNavigateToScanner,
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.QrCodeScanner,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("扫码配对")
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = historyItems,
                            key = { it.id }
                        ) { item ->
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                HistoryItemView(
                                    item = item,
                                    onClick = { viewModel.onHistoryItemClick(item) },
                                    onDelete = { viewModel.deleteHistoryItem(item.id) }
                                )
                            }
                        }
                    }
                }
            }

            // Input area - always visible above IME
            Surface(
                shadowElevation = 8.dp,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                InputField(
                    text = inputText,
                    onTextChange = { viewModel.onInputTextChange(it) },
                    onSend = { viewModel.sendText() },
                    enabled = connectionStatus.connected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        }
    }

    // Device selection dialog - only show paired devices
    if (showDeviceDialog) {
        PairedDeviceSelectionDialog(
            pairedDevices = pairedDevices,
            serverDevices = serverDevices,
            onDeviceSelected = { deviceId, deviceName ->
                val sd = serverDevices.firstOrNull { it.deviceId == deviceId }
                    ?: com.voiceinput.data.model.ServerDeviceInfo(
                        deviceId = deviceId, deviceName = deviceName,
                        deviceType = "desktop", online = false
                    )
                viewModel.connectToServerDevice(sd)
                showDeviceDialog = false
            },
            onDismiss = { showDeviceDialog = false }
        )
    }
    
    // Search history dialog
    if (showSearchDialog) {
        SearchHistoryDialog(
            onSearch = { query ->
                if (query.isBlank()) {
                    viewModel.reloadHistory()
                } else {
                    viewModel.searchHistory(query)
                }
            },
            onDismiss = {
                viewModel.reloadHistory()
                showSearchDialog = false
            }
        )
    }
    
    // Clear history confirmation dialog
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("清空历史记录") },
            text = { Text("确定要清空所有历史记录吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearHistoryDialog = false
                    }
                ) {
                    Text("确定", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectionDialog(
    localDevices: List<com.voiceinput.data.model.Device>,
    serverDevices: List<ServerDeviceInfo>,
    isServerConnected: Boolean,
    onLocalDeviceSelected: (com.voiceinput.data.model.Device) -> Unit,
    onServerDeviceSelected: (ServerDeviceInfo) -> Unit,
    onRefreshServerDevices: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择设备") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Server devices section
                if (isServerConnected) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Cloud,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "服务器设备",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = onRefreshServerDevices,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "刷新",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    if (serverDevices.isEmpty()) {
                        item {
                            Text(
                                "暂无在线设备，请确保电脑端已连接服务器",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 24.dp)
                            )
                        }
                    } else {
                        items(serverDevices) { device ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onServerDeviceSelected(device) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Computer,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            device.deviceName,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            "${device.deviceType} - 服务器中转",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Badge(
                                        containerColor = if (device.online)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.error
                                    ) {
                                        Text(if (device.online) "在线" else "离线")
                                    }
                                }
                            }
                        }
                    }

                    // Divider between sections
                    if (localDevices.isNotEmpty()) {
                        item {
                            Divider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }

                // Local devices section
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "局域网设备",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                if (localDevices.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(
                                "正在搜索局域网设备...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(localDevices) { device ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onLocalDeviceSelected(device) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Wifi,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        device.deviceName,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        "${device.ip}:${device.port}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Hint if server not connected
                if (!isServerConnected) {
                    item {
                        Text(
                            "提示: 连接服务器可发现远程设备",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairedDeviceSelectionDialog(
    pairedDevices: Map<String, com.voiceinput.data.ConfigManager.PairedDevice>,
    serverDevices: List<ServerDeviceInfo>,
    onDeviceSelected: (deviceId: String, deviceName: String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择已配对设备") },
        text = {
            if (pairedDevices.isEmpty()) {
                Text(
                    text = "暂无已配对设备，请先扫码配对",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pairedDevices.values.toList(), key = { it.deviceId }) { paired ->
                        val online = serverDevices.any { it.deviceId == paired.deviceId && it.online }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onDeviceSelected(paired.deviceId, paired.deviceName) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Computer,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(paired.deviceName, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (paired.localIp.isNotEmpty()) "局域网: :" else "服务器中转",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Badge(
                                    containerColor = if (online)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(if (online) "在线" else "离线")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchHistoryDialog(
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索历史记录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { 
                        searchQuery = it
                        onSearch(it)
                    },
                    label = { Text("输入关键词") },
                    placeholder = { Text("搜索...") },
                    singleLine = true,
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { 
                                searchQuery = ""
                                onSearch("")
                            }) {
                                Icon(Icons.Default.Clear, "清除")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "实时搜索历史记录中的文本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

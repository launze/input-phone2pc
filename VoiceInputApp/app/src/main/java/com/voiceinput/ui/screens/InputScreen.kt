package com.voiceinput.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                label = "history"
            ) { isEmpty ->
                if (isEmpty) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
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
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
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
                                    onClick = { viewModel.onHistoryItemClick(item) }
                                )
                            }
                        }
                    }
                }
            }

            // Input area
            Surface(
                shadowElevation = 8.dp,
                tonalElevation = 2.dp
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

    // Device selection dialog
    if (showDeviceDialog) {
        DeviceSelectionDialog(
            localDevices = discoveredDevices,
            serverDevices = serverDevices,
            isServerConnected = isServerConnected,
            onLocalDeviceSelected = { device ->
                viewModel.connectToDevice(device)
                showDeviceDialog = false
            },
            onServerDeviceSelected = { device ->
                viewModel.connectToServerDevice(device)
                showDeviceDialog = false
            },
            onRefreshServerDevices = { viewModel.refreshServerDevices() },
            onDismiss = { showDeviceDialog = false }
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

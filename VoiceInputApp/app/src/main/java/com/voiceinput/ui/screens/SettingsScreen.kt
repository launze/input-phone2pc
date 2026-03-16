package com.voiceinput.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voiceinput.data.ConfigManager
import com.voiceinput.network.ServerConnection
import com.voiceinput.viewmodel.InputViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: InputViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val configManager = remember { ConfigManager(context) }

    var serverModeEnabled by remember { mutableStateOf(configManager.isServerModeEnabled()) }
    var serverUrl by remember { mutableStateOf(configManager.getServerUrl()) }
    var showCustomUrl by remember { mutableStateOf(false) }

    val connectionState by viewModel.serverConnectionState.collectAsState()
    val connectionLog by viewModel.connectionLog.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()

    // 开启服务器模式时自动连接
    LaunchedEffect(serverModeEnabled) {
        if (serverModeEnabled &&
            connectionState is ServerConnection.ConnectionState.Disconnected) {
            val url = configManager.getServerUrl()
            if (url.isNotBlank()) {
                viewModel.connectToServer(url)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 服务器设置卡片
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "服务端中转设置",
                        style = MaterialTheme.typography.titleMedium
                    )



                    // 服务器模式开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用服务端中转",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "通过中转服务器连接设备",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 自定义服务器地址按钮
                        IconButton(
                            onClick = { showCustomUrl = !showCustomUrl },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "自定义服务器",
                                modifier = Modifier.size(18.dp),
                                tint = if (showCustomUrl) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = serverModeEnabled,
                            onCheckedChange = {
                                serverModeEnabled = it
                                configManager.saveServerModeEnabled(it)
                                if (!it) {
                                    viewModel.disconnectFromServer()
                                    showCustomUrl = false
                                }
                            }
                        )
                    }

                    // 自定义服务器地址输入（默认隐藏）
                    if (showCustomUrl) {
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = {
                                serverUrl = it
                                configManager.saveServerUrl(it)
                            },
                            label = { Text("服务器地址") },
                            placeholder = { Text("wss://your-server:7070") },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = {
                                Icon(Icons.Default.Cloud, "服务器")
                            },
                            singleLine = true
                        )
                    }

                    // 连接状态和按钮
                    if (serverModeEnabled) {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = when (connectionState) {
                                    is ServerConnection.ConnectionState.Connected ->
                                        MaterialTheme.colorScheme.primaryContainer
                                    is ServerConnection.ConnectionState.Error ->
                                        MaterialTheme.colorScheme.errorContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    when (connectionState) {
                                        is ServerConnection.ConnectionState.Connected -> Icons.Default.CheckCircle
                                        is ServerConnection.ConnectionState.Connecting -> Icons.Default.Refresh
                                        is ServerConnection.ConnectionState.Reconnecting -> Icons.Default.Refresh
                                        is ServerConnection.ConnectionState.Error -> Icons.Default.Error
                                        else -> Icons.Default.Info
                                    },
                                    contentDescription = null
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = when (val state = connectionState) {
                                            is ServerConnection.ConnectionState.Disconnected -> "未连接"
                                            is ServerConnection.ConnectionState.Connecting -> "连接中..."
                                            is ServerConnection.ConnectionState.Connected -> "已连接服务器"
                                            is ServerConnection.ConnectionState.Reconnecting -> "重连中（第${state.attempt}次，${state.delayMs / 1000}秒后）"
                                            is ServerConnection.ConnectionState.Error -> "错误: ${state.message}"
                                        },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (connectionState is ServerConnection.ConnectionState.Connected) {
                                Button(
                                    onClick = { viewModel.disconnectFromServer() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("断开连接")
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.connectToServer(serverUrl) },
                                    modifier = Modifier.weight(1f),
                                    enabled = serverUrl.isNotBlank()
                                ) {
                                    Text("连接服务器")
                                }
                            }
                        }
                    }
                }
            }

            // 已配对设备卡片
            if (pairedDevices.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "已配对设备",
                            style = MaterialTheme.typography.titleMedium
                        )
    
                        pairedDevices.values.forEach { device ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = device.deviceName,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = if (device.localIp.isNotEmpty()) "局域网: ${device.localIp}:${device.localPort}" else "服务器中转",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(
                                    onClick = { viewModel.unpairDevice(device.deviceId) },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("取消配对")
                                }
                            }
                        }
                    }
                }
            }

            // 连接日志卡片
            if (connectionLog.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "连接日志",
                                style = MaterialTheme.typography.titleMedium
                            )
                            TextButton(onClick = { viewModel.clearLog() }) {
                                Text("清除")
                            }
                        }
    
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            connectionLog.forEach { log ->
                                Text(
                                    text = log,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when {
                                        log.contains("失败") || log.contains("错误") || log.contains("异常") || log.contains("原因") ->
                                            MaterialTheme.colorScheme.error
                                        log.contains("成功") ->
                                            MaterialTheme.colorScheme.primary
                                        else ->
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 关于卡片
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "关于",
                        style = MaterialTheme.typography.titleMedium
                    )

                    InfoRow("版本", "v1.2.0")
                    InfoRow("UDP 端口", "58888")
                    InfoRow("TCP 端口", "58889")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

package com.voiceinput.ui.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.voiceinput.data.ConfigManager
import com.voiceinput.network.ServerConnection
import com.voiceinput.viewmodel.InputViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: InputViewModel,
    onBack: () -> Unit,
    onNavigateToScanner: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configManager = remember { ConfigManager(context) }

    var serverModeEnabled by remember { mutableStateOf(configManager.isServerModeEnabled()) }
    var serverUrl by remember { mutableStateOf(configManager.getServerUrl()) }
    var notificationEnabled by remember { mutableStateOf(configManager.isNotificationEnabled()) }
    val connectionState by viewModel.serverConnectionState.collectAsState()
    val connectionLog by viewModel.connectionLog.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    var notificationAccessGranted by remember {
        mutableStateOf(isNotificationListenerEnabled(context))
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccessGranted = isNotificationListenerEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(serverModeEnabled) {
        if (
            serverModeEnabled &&
            connectionState is ServerConnection.ConnectionState.Disconnected &&
            serverUrl.isNotBlank()
        ) {
            viewModel.connectToServer(serverUrl)
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("远程连接", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "同一 Wi‑Fi 下通常不需要远程连接。只有跨网络或异地使用时再打开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    SettingSwitchRow(
                        title = "启用远程连接",
                        subtitle = "让手机通过服务器连接电脑",
                        checked = serverModeEnabled,
                        onCheckedChange = {
                            serverModeEnabled = it
                            configManager.saveServerModeEnabled(it)
                            if (!it) {
                                viewModel.disconnectFromServer()
                            }
                        }
                    )

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = {
                            serverUrl = it
                            configManager.saveServerUrl(it)
                        },
                        label = { Text("服务器地址") },
                        placeholder = { Text("wss://your-server:7070") },
                        leadingIcon = { Icon(Icons.Default.Cloud, "服务器") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

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
                                imageVector = when (connectionState) {
                                    is ServerConnection.ConnectionState.Connected ->
                                        Icons.Default.CheckCircle
                                    is ServerConnection.ConnectionState.Connecting,
                                    is ServerConnection.ConnectionState.Reconnecting ->
                                        Icons.Default.Refresh
                                    is ServerConnection.ConnectionState.Error ->
                                        Icons.Default.Error
                                    else -> Icons.Default.Info
                                },
                                contentDescription = null
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when (val state = connectionState) {
                                        is ServerConnection.ConnectionState.Disconnected -> "远程连接未建立"
                                        is ServerConnection.ConnectionState.Connecting -> "正在连接远程服务器..."
                                        is ServerConnection.ConnectionState.Connected -> "远程服务器已连接"
                                        is ServerConnection.ConnectionState.Reconnecting ->
                                            "重连中（第${state.attempt}次，${state.delayMs / 1000} 秒后）"
                                        is ServerConnection.ConnectionState.Error ->
                                            "连接失败：${state.message}"
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
                        Button(
                            onClick = {
                                configManager.saveServerModeEnabled(true)
                                serverModeEnabled = true
                                viewModel.connectToServer(serverUrl)
                            },
                            enabled = serverUrl.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("测试连接")
                        }
                        OutlinedButton(
                            onClick = { viewModel.disconnectFromServer() },
                            enabled = connectionState !is ServerConnection.ConnectionState.Disconnected,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("断开")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("配对入口", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "即使没有任何已配对设备，也可以从这里直接重新扫码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onNavigateToScanner,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Text("立即扫码配对", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("通知转发", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "仅在远程连接已建立、并且已选择目标电脑时，才会把手机通知转发到电脑端。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SettingSwitchRow(
                        title = "启用通知转发",
                        subtitle = if (notificationAccessGranted) {
                            "系统通知权限已授予"
                        } else {
                            "还需要去系统设置授予通知读取权限"
                        },
                        checked = notificationEnabled,
                        onCheckedChange = {
                            notificationEnabled = it
                            viewModel.setNotificationForwardingEnabled(it)
                        }
                    )
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(context, intent, null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Text("前往系统授权", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("当前目标设备", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (connectionStatus.deviceName.isNotBlank()) {
                            connectionStatus.deviceName
                        } else {
                            "还没有选中的设备"
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (connectionStatus.connected) {
                            "当前可以直接发送文本和图片"
                        } else {
                            "可以回到首页切换设备，或等待电脑重新上线"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (pairedDevices.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("已配对设备", style = MaterialTheme.typography.titleMedium)

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
                                        text = if (device.localIp.isNotEmpty()) {
                                            "局域网 ${device.localIp}:${device.localPort}"
                                        } else {
                                            "远程设备"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        val shouldOpenScanner = pairedDevices.size == 1
                                        viewModel.unpairDevice(device.deviceId)
                                        if (shouldOpenScanner) {
                                            onNavigateToScanner()
                                        }
                                    },
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

            if (connectionLog.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("连接日志", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { viewModel.clearLog() }) {
                                Text("清除")
                            }
                        }

                        connectionLog.forEach { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    log.contains("失败") || log.contains("错误") || log.contains("异常") ->
                                        MaterialTheme.colorScheme.error
                                    log.contains("成功") || log.contains("已连接") ->
                                        MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("关于", style = MaterialTheme.typography.titleMedium)
                    InfoRow("版本", "v1.2.0")
                    InfoRow("局域网发现端口", "58888")
                    InfoRow("局域网传输端口", "58889")
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val listeners = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ).orEmpty()
    val componentName = ComponentName(context, com.voiceinput.service.NotificationListenerService::class.java)
    return listeners.contains(componentName.flattenToString())
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

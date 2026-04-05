package com.voiceinput.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voiceinput.data.model.ServerDeviceInfo
import com.voiceinput.network.ServerConnection
import com.voiceinput.ui.components.ConnectionStatusIndicator
import com.voiceinput.ui.components.HistoryItemView
import com.voiceinput.ui.components.InputField
import com.voiceinput.viewmodel.InputViewModel
import java.io.File
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun InputScreen(
    viewModel: InputViewModel = viewModel(),
    onNavigateToSettings: () -> Unit = {},
    onNavigateToScanner: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {}
) {
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val sendAvailable by viewModel.sendAvailable.collectAsState()
    val historyItems by viewModel.visibleHistoryItems.collectAsState()
    val historyHasMore by viewModel.historyHasMore.collectAsState()
    val historyLoadingMore by viewModel.historyLoadingMore.collectAsState()
    val historyInitialLoaded by viewModel.historyInitialLoaded.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val serverDevices by viewModel.serverDevices.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    var showDeviceDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val listState = rememberLazyListState()
    var initialScrollCompleted by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.sendImage(uri)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val capturedUri = pendingCameraUri
        pendingCameraUri = null
        if (success && capturedUri != null) {
            viewModel.sendImage(capturedUri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadInitialVisibleHistory()
        viewModel.startDiscovery()
    }

    LaunchedEffect(Unit) {
        viewModel.uiMessages.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(historyInitialLoaded, historyItems.size) {
        if (!historyInitialLoaded) {
            return@LaunchedEffect
        }

        if (historyItems.isEmpty()) {
            initialScrollCompleted = false
            return@LaunchedEffect
        }

        if (!initialScrollCompleted) {
            listState.scrollToItem(historyItems.lastIndex)
            initialScrollCompleted = true
        }
    }

    LaunchedEffect(
        listState,
        historyInitialLoaded,
        historyItems.size,
        historyHasMore,
        historyLoadingMore
    ) {
        if (!historyInitialLoaded) {
            return@LaunchedEffect
        }

        snapshotFlow {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
        }.collectLatest { reachedTop ->
            if (
                reachedTop &&
                historyItems.isNotEmpty() &&
                historyHasMore &&
                !historyLoadingMore
            ) {
                val insertedCount = viewModel.loadOlderVisibleHistory()
                if (insertedCount > 0) {
                    listState.scrollToItem(insertedCount)
                }
            }
        }
    }

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
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = "历史记录",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
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
                            if (sendAvailable) {
                                Text(
                                    text = if (connectionStatus.connected) "已连接" else "设备离线",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (connectionStatus.connected) {
                                        "在手机上输入文字或发送图片到电脑"
                                    } else {
                                        "消息会先暂存到服务器，待电脑上线后自动同步"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = if (pairedDevices.isEmpty()) {
                                        "未配对任何设备"
                                    } else if (connectionStatus.deviceName.isNotBlank()) {
                                        "已选择设备（离线）"
                                    } else {
                                        "已配对设备"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (pairedDevices.isEmpty()) {
                                        "扫描电脑端二维码完成配对"
                                    } else {
                                        "等待服务器连接或电脑上线，无需重新扫码"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        if (pairedDevices.isEmpty()) {
                                            onNavigateToScanner()
                                        } else {
                                            showDeviceDialog = true
                                        }
                                    },
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Icon(
                                        if (pairedDevices.isEmpty()) Icons.Default.QrCodeScanner else Icons.Default.Link,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (pairedDevices.isEmpty()) "扫码配对" else "选择设备")
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (historyLoadingMore) {
                            item(key = "history-loading") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                }
                            }
                        }

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
                    onPickImage = {
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onTakePhoto = {
                        val outputUri = createCameraOutputUri(context)
                        pendingCameraUri = outputUri
                        cameraLauncher.launch(outputUri)
                    },
                    enabled = sendAvailable,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        }
    }

    if (showDeviceDialog) {
        PairedDeviceSelectionDialog(
            pairedDevices = pairedDevices,
            serverDevices = serverDevices,
            onDeviceSelected = { deviceId, deviceName ->
                val sd = serverDevices.firstOrNull { it.deviceId == deviceId }
                    ?: ServerDeviceInfo(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        deviceType = "desktop",
                        online = false
                    )
                viewModel.connectToServerDevice(sd)
                showDeviceDialog = false
            },
            onDismiss = { showDeviceDialog = false }
        )
    }

}

private fun createCameraOutputUri(context: Context): Uri {
    val cameraDir = File(context.cacheDir, "camera").apply {
        mkdirs()
    }
    val imageFile = File(cameraDir, "capture_${System.currentTimeMillis()}.jpg")
    if (!imageFile.exists()) {
        imageFile.createNewFile()
    }
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        imageFile
    )
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
                                        containerColor = if (device.online) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        }
                                    ) {
                                        Text(if (device.online) "在线" else "离线")
                                    }
                                }
                            }
                        }
                    }

                    if (localDevices.isNotEmpty()) {
                        item {
                            Divider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }

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
                                    Text(paired.deviceName, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (paired.localIp.isNotEmpty()) "局域网" else "服务器中转",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Badge(
                                    containerColor = if (online) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
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
                            IconButton(
                                onClick = {
                                    searchQuery = ""
                                    onSearch("")
                                }
                            ) {
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

package com.voiceinput.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.OutlinedButton
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
import com.voiceinput.data.ConfigManager
import com.voiceinput.data.model.Device
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
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    var showDeviceDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val listState = rememberLazyListState()
    var initialScrollCompleted by remember { mutableStateOf(false) }
    val retryableVisibleTextCount = remember(historyItems) {
        historyItems.count { it.contentType == "text" && it.syncStatus == com.voiceinput.data.model.SyncStatus.FAILED }
    }

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
                            deviceName = connectionStatus.deviceName,
                            transport = connectionStatus.transport,
                            pairedDeviceCount = pairedDevices.size
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
                    IconButton(
                        onClick = {
                            if (pairedDevices.isEmpty()) {
                                onNavigateToScanner()
                            } else {
                                showDeviceDialog = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (pairedDevices.isEmpty()) {
                                Icons.Default.QrCodeScanner
                            } else {
                                Icons.Default.Computer
                            },
                            contentDescription = if (pairedDevices.isEmpty()) "扫码配对" else "选择电脑",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
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
            StatusActionCard(
                connectionStatus = connectionStatus,
                pairedDeviceCount = pairedDevices.size,
                localDeviceCount = discoveredDevices.size,
                retryableTextCount = retryableVisibleTextCount,
                onSelectDevice = { showDeviceDialog = true },
                onRetryFailed = { viewModel.retryFailedTextItems() },
                onStartPairing = onNavigateToScanner,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )

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
                            Text(
                                text = when {
                                    connectionStatus.connected -> "连接已就绪"
                                    connectionStatus.deviceName.isNotBlank() -> "这台电脑暂时离线"
                                    pairedDevices.isNotEmpty() -> "先选一台电脑"
                                    else -> "还没有连接任何电脑"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = when {
                                    connectionStatus.connected ->
                                        "发送后的文字、图片和通知状态会显示在这里。"
                                    connectionStatus.deviceName.isNotBlank() ->
                                        "下一步：点上方状态卡里的“切换电脑”，或等待这台电脑重新上线。"
                                    pairedDevices.isNotEmpty() ->
                                        "下一步：点上方状态卡里的“选择电脑”，或扫码新增一台。"
                                    else ->
                                        "下一步：打开电脑端二维码，点击上方“扫码配对”。"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                                    onRetry = if (
                                        item.contentType == "text" &&
                                        item.syncStatus == com.voiceinput.data.model.SyncStatus.FAILED
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

            Surface(
                shadowElevation = 8.dp,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (retryableVisibleTextCount > 0) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "有 $retryableVisibleTextCount 条失败文本可重试",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "适合在设备重新在线后快速补发",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                OutlinedButton(onClick = { viewModel.retryFailedTextItems() }) {
                                    Text("一键重试")
                                }
                            }
                        }
                    }

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
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!sendAvailable && inputText.isBlank()) {
                        Text(
                            text = if (pairedDevices.isEmpty()) {
                                "先扫码配对，或从上方切换到局域网设备。"
                            } else {
                                "当前设备未在线，可切换设备或等待电脑上线。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showDeviceDialog) {
        DeviceSelectionDialog(
            localDevices = discoveredDevices,
            serverDevices = serverDevices,
            pairedDevices = pairedDevices,
            selectedDeviceId = connectionStatus.deviceId,
            isServerConnected = viewModel.isConnectedToServer(),
            onLocalDeviceSelected = { device ->
                viewModel.connectToDevice(device)
                showDeviceDialog = false
            },
            onServerDeviceSelected = { device ->
                viewModel.connectToServerDevice(device)
                showDeviceDialog = false
            },
            onScanPair = {
                showDeviceDialog = false
                onNavigateToScanner()
            },
            onRefreshServerDevices = { viewModel.refreshServerDevices() },
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
    localDevices: List<Device>,
    serverDevices: List<ServerDeviceInfo>,
    pairedDevices: Map<String, ConfigManager.PairedDevice>,
    selectedDeviceId: String?,
    isServerConnected: Boolean,
    onLocalDeviceSelected: (Device) -> Unit,
    onServerDeviceSelected: (ServerDeviceInfo) -> Unit,
    onScanPair: () -> Unit,
    onRefreshServerDevices: () -> Unit,
    onDismiss: () -> Unit
) {
    val savedLocalDevices = remember(pairedDevices, localDevices) {
        pairedDevices.values
            .filter { it.localIp.isNotBlank() && localDevices.none { device -> device.deviceId == it.deviceId } }
            .map {
                Device(
                    deviceId = it.deviceId,
                    deviceName = it.deviceName,
                    ip = it.localIp,
                    port = it.localPort,
                    version = "saved"
                )
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换设备") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onScanPair
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("新增电脑", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "扫码配对，或从剪贴板导入配对信息",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (pairedDevices.isNotEmpty()) {
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
                                    "已配对远程设备",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (isServerConnected) {
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
                    }

                    if (!isServerConnected) {
                        item {
                            Text(
                                "远程连接未建立，已配对设备会在电脑上线后显示在线状态",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 24.dp)
                            )
                        }
                    } else {
                        items(pairedDevices.values.toList(), key = { it.deviceId }) { paired ->
                            val online = serverDevices.any { it.deviceId == paired.deviceId && it.online }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    onServerDeviceSelected(
                                        ServerDeviceInfo(
                                            deviceId = paired.deviceId,
                                            deviceName = paired.deviceName,
                                            deviceType = paired.deviceType,
                                            online = online
                                        )
                                    )
                                }
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
                                            paired.deviceName,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            if (paired.deviceId == selectedDeviceId) {
                                                "当前选择的远程设备"
                                            } else {
                                                "点击切换为当前远程设备"
                                            },
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

                    if (localDevices.isNotEmpty() || pairedDevices.isNotEmpty()) {
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

                if (savedLocalDevices.isNotEmpty()) {
                    items(savedLocalDevices, key = { it.deviceId }) { device ->
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
                                        "已保存地址 ${device.ip}:${device.port}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (device.deviceId == selectedDeviceId) {
                                    Badge {
                                        Text("当前")
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
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
                                if (device.deviceId == selectedDeviceId) {
                                    Badge {
                                        Text("当前")
                                    }
                                }
                            }
                        }
                    }
                }

                if (!isServerConnected) {
                    item {
                        Text(
                            "提示: 连接远程服务器后，可以看到远程设备在线状态与通知转发状态",
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

@Composable
private fun StatusActionCard(
    connectionStatus: InputViewModel.ConnectionStatus,
    pairedDeviceCount: Int,
    localDeviceCount: Int,
    retryableTextCount: Int,
    onSelectDevice: () -> Unit,
    onRetryFailed: () -> Unit,
    onStartPairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasCurrentTarget = connectionStatus.deviceName.isNotBlank()
    val primaryActionLabel = when {
        pairedDeviceCount == 0 -> "立即扫码连接"
        !hasCurrentTarget -> "选择电脑"
        else -> "切换电脑"
    }
    val primaryActionDescription = when {
        connectionStatus.connected ->
            "当前电脑：${connectionStatus.deviceName.ifBlank { "已连接" }}"
        hasCurrentTarget ->
            "当前目标：${connectionStatus.deviceName}"
        pairedDeviceCount > 0 ->
            "已保存 $pairedDeviceCount 台电脑，下一步先选一台"
        localDeviceCount > 0 ->
            "发现 $localDeviceCount 台附近电脑，仍需先扫码建立配对"
        else ->
            "第一次使用时，请先打开电脑端二维码并扫码"
    }
    val nextStep = when {
        connectionStatus.connected -> "下一步：可直接发送，也可以切换到其他电脑。"
        hasCurrentTarget -> "下一步：切换其他电脑，或等待这台重新上线。"
        pairedDeviceCount > 0 -> "下一步：点“选择电脑”，从已保存设备里选一台。"
        localDeviceCount > 0 -> "下一步：先扫码配对，之后就能快速切换附近电脑。"
        else -> "下一步：点击“立即扫码连接”，完成第一次配对。"
    }
    val secondaryActionLabel = when {
        pairedDeviceCount == 0 && localDeviceCount > 0 -> "查看附近电脑"
        else -> "扫码新电脑"
    }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = when {
                    connectionStatus.connected -> "已连接，可直接发送"
                    hasCurrentTarget -> "这台电脑暂时离线"
                    pairedDeviceCount > 0 -> "已保存电脑，先选一台"
                    else -> "还没有连接任何电脑"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = primaryActionDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = nextStep,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (pairedDeviceCount == 0) {
                            onStartPairing()
                        } else {
                            onSelectDevice()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (pairedDeviceCount == 0) Icons.Default.QrCodeScanner else Icons.Default.Computer,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(primaryActionLabel)
                }
                OutlinedButton(
                    onClick = {
                        if (pairedDeviceCount == 0 && localDeviceCount > 0) {
                            onSelectDevice()
                        } else {
                            onStartPairing()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (pairedDeviceCount == 0 && localDeviceCount > 0) Icons.Default.Wifi else Icons.Default.QrCodeScanner,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(secondaryActionLabel)
                }
            }

            if (retryableTextCount > 0) {
                OutlinedButton(
                    onClick = onRetryFailed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("重试 $retryableTextCount 条失败文本")
                }
            }
        }
    }
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

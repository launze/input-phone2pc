package com.voiceinput.ui.screens

import android.Manifest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voiceinput.BuildConfig
import com.voiceinput.data.ConfigManager
import com.voiceinput.data.NotificationAppOption
import com.voiceinput.data.NotificationAppSelectionState
import com.voiceinput.data.PairedDeviceUiState
import com.voiceinput.data.model.AppUpdateInfo
import com.voiceinput.data.model.ServerDeviceInfo
import com.voiceinput.network.ServerConnection
import com.voiceinput.service.VoiceInputForegroundService
import com.voiceinput.update.AppUpdateManager
import com.voiceinput.viewmodel.InputViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: InputViewModel,
    initialSection: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val configManager = remember { ConfigManager(context) }
    val updateManager = remember { AppUpdateManager(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var serverModeEnabled by remember { mutableStateOf(configManager.isServerModeEnabled()) }
    var serverUrl by remember { mutableStateOf(configManager.getServerUrl()) }
    var showCustomUrl by remember { mutableStateOf(false) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateInstalling by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var updateMessage by remember { mutableStateOf("") }
    var notificationForwardEnabled by remember { mutableStateOf(configManager.isNotificationEnabled()) }
    var notificationIncludePackages by remember {
        mutableStateOf(configManager.getNotificationIncludePackages())
    }
    var notificationExcludePackages by remember {
        mutableStateOf(configManager.getNotificationExcludePackages())
    }
    var notificationExcludeKeywords by remember {
        mutableStateOf(configManager.getNotificationExcludeKeywords())
    }
    var notificationPermissionEnabled by remember {
        mutableStateOf(isNotificationListenerEnabled(context))
    }
    var postNotificationPermissionEnabled by remember {
        mutableStateOf(isPostNotificationPermissionGranted(context))
    }
    var filterEmptyNotifications by remember {
        mutableStateOf(configManager.shouldFilterEmptyNotifications())
    }
    var filterOngoingNotifications by remember {
        mutableStateOf(configManager.shouldFilterOngoingNotifications())
    }
    var filterLowImportanceNotifications by remember {
        mutableStateOf(configManager.shouldFilterLowImportanceNotifications())
    }
    var allowSensitiveNotificationApps by remember {
        mutableStateOf(configManager.allowSensitiveNotificationApps())
    }
    var redactSensitiveNotificationContent by remember {
        mutableStateOf(configManager.shouldRedactSensitiveNotificationContent())
    }
    var notificationRedactKeywords by remember {
        mutableStateOf(configManager.getNotificationRedactKeywords())
    }
    var notificationForwardMode by remember {
        mutableStateOf(configManager.getNotificationForwardMode())
    }
    var notificationAppFilterAll by remember {
        mutableStateOf(notificationIncludePackages.trim().isBlank())
    }
    var notificationSelectedPackages by remember {
        mutableStateOf(NotificationAppSelectionState.selectedPackagesFromRules(notificationIncludePackages))
    }
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var showAllInstalledApps by remember { mutableStateOf(false) }
    var selectedSection by remember {
        mutableStateOf(SettingsSection.fromRouteValue(initialSection))
    }
    var showConnectionLog by remember { mutableStateOf(false) }
    var cacheSizeText by remember { mutableStateOf(formatBytes(calculateAppCacheSize(context))) }
    var cacheActionMessage by remember { mutableStateOf("") }
    var pendingEnableForegroundNotification by remember { mutableStateOf(false) }

    val connectionState by viewModel.serverConnectionState.collectAsState()
    val connectionLog by viewModel.connectionLog.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val serverDevices by viewModel.serverDevices.collectAsState()
    val lastTargetDevice by viewModel.lastTargetDevice.collectAsState()

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

    LaunchedEffect(Unit) {
        installedApps = loadInstalledLaunchableApps(context.packageManager)
        cacheSizeText = formatBytes(calculateAppCacheSize(context))
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiveContext: Context?, intent: Intent?) {
                if (intent?.action == VoiceInputForegroundService.ACTION_NOTIFICATION_FORWARDING_CHANGED) {
                    notificationForwardEnabled = configManager.isNotificationEnabled()
                    notificationPermissionEnabled = isNotificationListenerEnabled(context)
                    postNotificationPermissionEnabled = isPostNotificationPermissionGranted(context)
                }
            }
        }
        val filter = IntentFilter(VoiceInputForegroundService.ACTION_NOTIFICATION_FORWARDING_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    val postNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val shouldEnableAfterGrant = pendingEnableForegroundNotification || notificationForwardEnabled
        pendingEnableForegroundNotification = false
        postNotificationPermissionEnabled = granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        if (postNotificationPermissionEnabled && shouldEnableAfterGrant) {
            notificationForwardEnabled = true
            configManager.saveNotificationEnabled(true)
            VoiceInputForegroundService.start(context)
        } else if (shouldEnableAfterGrant) {
            notificationForwardEnabled = false
            configManager.saveNotificationEnabled(false)
            scope.launch {
                snackbarHostState.showSnackbar("未获得系统通知权限，常驻同步通知未开启")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            ScrollableTabRow(
                selectedTabIndex = SettingsSection.values().indexOf(selectedSection),
                edgePadding = 0.dp
            ) {
                SettingsSection.values().forEach { section ->
                    Tab(
                        selected = selectedSection == section,
                        onClick = { selectedSection = section },
                        text = { Text(section.title) }
                    )
                }
            }

            // 服务器设置卡片
            if (selectedSection == SettingsSection.Connection) Card(
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
                            placeholder = { Text("wss://8.153.163.104:16908") },
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
            if (selectedSection == SettingsSection.Notification) Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "通知转发",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("常驻同步通知", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "在手机下拉栏保持语传运行，用于同步输入和通知",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = notificationForwardEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    if (isPostNotificationPermissionGranted(context)) {
                                        notificationForwardEnabled = true
                                        postNotificationPermissionEnabled = true
                                        configManager.saveNotificationEnabled(true)
                                        VoiceInputForegroundService.start(context)
                                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        pendingEnableForegroundNotification = true
                                        postNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        notificationForwardEnabled = true
                                        configManager.saveNotificationEnabled(true)
                                        VoiceInputForegroundService.start(context)
                                    }
                                } else {
                                    notificationForwardEnabled = false
                                    configManager.saveNotificationEnabled(false)
                                    VoiceInputForegroundService.stop(context)
                                }
                            }
                        )
                    }

                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (postNotificationPermissionEnabled) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                if (postNotificationPermissionEnabled) Icons.Default.CheckCircle else Icons.Default.NotificationsActive,
                                contentDescription = null
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (postNotificationPermissionEnabled) {
                                        "系统通知权限已开启"
                                    } else {
                                        "需要允许显示常驻通知"
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Android 13 及以上需要此权限，手机下拉栏才能显示语传常驻同步通知",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (!postNotificationPermissionEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Button(
                            onClick = {
                                postNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("允许常驻通知")
                        }
                    }

                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (notificationPermissionEnabled) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                if (notificationPermissionEnabled) Icons.Default.CheckCircle else Icons.Default.Notifications,
                                contentDescription = null
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (notificationPermissionEnabled) {
                                        "通知监听权限已开启"
                                    } else {
                                        "需要开启通知监听权限"
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "开启后可读取微信等通知，后续用于转发和 AI 总结",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("打开授权设置")
                        }
                        Button(
                            onClick = {
                                notificationPermissionEnabled = isNotificationListenerEnabled(context)
                                postNotificationPermissionEnabled = isPostNotificationPermissionGranted(context)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("刷新状态")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val appSelectionState = NotificationAppSelectionState.from(
                            filterAll = notificationAppFilterAll,
                            selectedPackages = notificationSelectedPackages,
                            installedApps = installedApps.map {
                                NotificationAppOption(label = it.label, packageName = it.packageName)
                            },
                            showAllInstalledApps = showAllInstalledApps
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("转发全部 App", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = appSelectionState.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = notificationAppFilterAll,
                            onCheckedChange = { enabled ->
                                notificationAppFilterAll = enabled
                                if (enabled) {
                                    notificationSelectedPackages = emptySet()
                                    notificationIncludePackages = ""
                                    configManager.saveNotificationIncludePackages("")
                                }
                            }
                        )
                    }

                    if (!notificationAppFilterAll) {
                        val appSelectionState = NotificationAppSelectionState.from(
                            filterAll = notificationAppFilterAll,
                            selectedPackages = notificationSelectedPackages,
                            installedApps = installedApps.map {
                                NotificationAppOption(label = it.label, packageName = it.packageName)
                            },
                            showAllInstalledApps = showAllInstalledApps
                        )
                        appSelectionState.emptyText?.let { emptyText ->
                            Text(
                                text = emptyText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } ?: run {
                            appSelectionState.visibleApps.forEach { app ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = app.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Checkbox(
                                        checked = app.packageName in appSelectionState.selectedPackages,
                                        onCheckedChange = { checked ->
                                            notificationSelectedPackages = NotificationAppSelectionState.updateSelectedPackage(
                                                selectedPackages = notificationSelectedPackages,
                                                packageName = app.packageName,
                                                checked = checked
                                            )
                                            notificationIncludePackages = NotificationAppSelectionState
                                                .includeRulesFromSelection(notificationSelectedPackages)
                                            configManager.saveNotificationIncludePackages(notificationIncludePackages)
                                        }
                                    )
                                }
                            }
                            appSelectionState.listToggleText?.let { toggleText ->
                                TextButton(onClick = { showAllInstalledApps = !showAllInstalledApps }) {
                                    Text(toggleText)
                                }
                            }
                        }
                    }

                    NotificationFilterSwitch(
                        title = "过滤空通知",
                        subtitle = "标题和正文都为空时不保存、不转发",
                        checked = filterEmptyNotifications,
                        onCheckedChange = {
                            filterEmptyNotifications = it
                            configManager.saveNotificationFilterEmpty(it)
                        }
                    )

                    NotificationFilterSwitch(
                        title = "过滤系统常驻通知",
                        subtitle = "排除正在运行、播放、同步等不易清除的常驻通知",
                        checked = filterOngoingNotifications,
                        onCheckedChange = {
                            filterOngoingNotifications = it
                            configManager.saveNotificationFilterOngoing(it)
                        }
                    )

                    NotificationFilterSwitch(
                        title = "过滤低重要性通知",
                        subtitle = "排除低优先级和静默通知，减少噪音",
                        checked = filterLowImportanceNotifications,
                        onCheckedChange = {
                            filterLowImportanceNotifications = it
                            configManager.saveNotificationFilterLowImportance(it)
                        }
                    )

                    NotificationFilterSwitch(
                        title = "允许敏感 App",
                        subtitle = "关闭时默认排除金融、支付、健康等包名特征",
                        checked = allowSensitiveNotificationApps,
                        onCheckedChange = {
                            allowSensitiveNotificationApps = it
                            configManager.saveNotificationAllowSensitiveApps(it)
                        }
                    )

                    NotificationFilterSwitch(
                        title = "通知内容脱敏",
                        subtitle = "入库和转发前隐藏手机号、邮箱、证件号、验证码和自定义词",
                        checked = redactSensitiveNotificationContent,
                        onCheckedChange = {
                            redactSensitiveNotificationContent = it
                            configManager.saveNotificationRedactSensitiveContent(it)
                        }
                    )

                    NotificationModeMenu(
                        selected = notificationForwardMode,
                        onSelected = { mode ->
                            notificationForwardMode = mode
                            configManager.saveNotificationForwardMode(mode)
                        }
                    )

                    OutlinedTextField(
                        value = notificationIncludePackages,
                        onValueChange = {
                            notificationIncludePackages = it
                            notificationSelectedPackages = NotificationAppSelectionState.selectedPackagesFromRules(it)
                            notificationAppFilterAll = NotificationAppSelectionState.shouldFilterAllForManualRules(it)
                            configManager.saveNotificationIncludePackages(it)
                        },
                        label = { Text("允许转发包名") },
                        placeholder = { Text("留空表示转发全部 App；每行一个包名，支持 com.tencent.*") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )

                    OutlinedTextField(
                        value = notificationExcludePackages,
                        onValueChange = {
                            notificationExcludePackages = it
                            configManager.saveNotificationExcludePackages(it)
                        },
                        label = { Text("排除包名") },
                        placeholder = { Text("例如 com.android.systemui 或 com.tencent.*") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )

                    OutlinedTextField(
                        value = notificationExcludeKeywords,
                        onValueChange = {
                            notificationExcludeKeywords = it
                            configManager.saveNotificationExcludeKeywords(it)
                        },
                        label = { Text("排除关键词") },
                        placeholder = { Text("例如 广告, 已登录") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )

                    OutlinedTextField(
                        value = notificationRedactKeywords,
                        onValueChange = {
                            notificationRedactKeywords = it
                            configManager.saveNotificationRedactKeywords(it)
                        },
                        label = { Text("脱敏关键词") },
                        placeholder = { Text("例如 客户姓名、项目代号；每行一个") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        enabled = redactSensitiveNotificationContent
                    )
                }
            }

            // 已配对设备卡片
            if (selectedSection == SettingsSection.Devices) {
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

                        if (pairedDevices.isEmpty()) {
                            Text(
                                text = "暂无已配对设备，请回到首页扫码配对。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            pairedDevices.values.forEach { device ->
                                val serverDevice = serverDevices.firstOrNull { it.deviceId == device.deviceId }
                                val deviceState = PairedDeviceUiState.from(
                                    device = device,
                                    serverDevice = serverDevice,
                                    defaultDeviceId = lastTargetDevice?.deviceId
                                )
                                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = deviceState.deviceName,
                                                        style = MaterialTheme.typography.bodyLarge
                                                    )
                                                    if (deviceState.isDefault) {
                                                        AssistChip(
                                                            onClick = {},
                                                            label = { Text("默认") },
                                                            leadingIcon = {
                                                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                            }
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = deviceState.routeText,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = deviceState.statusText,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (deviceState.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = "上次连接: ${deviceState.lastConnectedText}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Badge(
                                                containerColor = if (deviceState.online) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                }
                                            ) {
                                                Text(deviceState.badgeText)
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    viewModel.selectPairedDeviceAsDefault(device.deviceId, device.deviceName)
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar("已设为默认电脑：${device.deviceName}")
                                                    }
                                                },
                                                enabled = deviceState.defaultActionEnabled,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(deviceState.defaultActionText)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    val selected = serverDevice ?: ServerDeviceInfo(
                                                        deviceId = device.deviceId,
                                                        deviceName = device.deviceName,
                                                        deviceType = device.deviceType,
                                                        online = deviceState.online
                                                    )
                                                    viewModel.connectToServerDevice(selected)
                                                    viewModel.refreshServerDevices()
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(deviceState.reconnectFeedbackText)
                                                    }
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(deviceState.reconnectActionText)
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
                    }
                }
            }

            // 连接日志卡片
            if (selectedSection == SettingsSection.Advanced) {
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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { showConnectionLog = !showConnectionLog }) {
                                    Text(if (showConnectionLog) "收起" else "展开")
                                }
                                TextButton(onClick = { viewModel.clearLog() }) {
                                    Text("清除")
                                }
                            }
                        }
                        if (!showConnectionLog) {
                            Text(
                                text = if (connectionLog.isEmpty()) "暂无连接日志" else "最近 ${connectionLog.size} 条日志，点击展开查看。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
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
                                            log.contains("成功") -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "缓存与调试信息",
                            style = MaterialTheme.typography.titleMedium
                        )
                        InfoRow("缓存占用", cacheSizeText)
                        InfoRow("设备 ID", configManager.getDeviceId().take(8) + "...")
                        InfoRow("设备名称", configManager.getDeviceName())
                        InfoRow("配对设备", "${pairedDevices.size} 台")
                        InfoRow("连接状态", viewModel.getConnectionStatus())
                        if (cacheActionMessage.isNotBlank()) {
                            Text(
                                text = cacheActionMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (cacheActionMessage.contains("失败")) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    cacheSizeText = formatBytes(calculateAppCacheSize(context))
                                    cacheActionMessage = "调试信息已刷新。"
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("刷新信息")
                            }
                            Button(
                                onClick = {
                                    val cleared = clearAppCache(context)
                                    cacheSizeText = formatBytes(calculateAppCacheSize(context))
                                    cacheActionMessage = "已清理临时缓存 ${formatBytes(cleared)}。"
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("清理缓存")
                            }
                        }
                    }
                }
            }

            // 关于卡片
            if (selectedSection == SettingsSection.About) Card(
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

                    InfoRow("软件", "语传")
                    InfoRow("版本", "v${BuildConfig.VERSION_NAME}")
                    InfoRow("UDP 端口", "58888")
                    InfoRow("TCP 端口", "58889")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "GitHub", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "launze/input-phone2pc",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textDecoration = TextDecoration.Underline,
                                color = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.clickable {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/launze/input-phone2pc"))
                                context.startActivity(intent)
                            }
                        )
                    }

                    if (updateMessage.isNotBlank()) {
                        Text(
                            text = updateMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (updateMessage.contains("失败") || updateMessage.contains("错误")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    updateInfo?.takeIf { it.hasUpdate }?.let { info ->
                        Text(
                            text = "发现新版本 v${info.latestVersion}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (info.releaseNotes.isNotBlank()) {
                            Text(
                                text = info.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    updateChecking = true
                                    updateMessage = "正在检查更新..."
                                    try {
                                        val info = updateManager.checkUpdate()
                                        updateInfo = info
                                        updateMessage = if (info.hasUpdate) {
                                            "可以更新到 v${info.latestVersion}"
                                        } else {
                                            "当前已是最新版本 v${BuildConfig.VERSION_NAME}"
                                        }
                                    } catch (error: Exception) {
                                        updateMessage = "检查更新失败: ${error.message ?: "未知错误"}"
                                    } finally {
                                        updateChecking = false
                                    }
                                }
                            },
                            enabled = !updateChecking && !updateInstalling,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (updateChecking) "检查中..." else "检查更新")
                        }

                        Button(
                            onClick = {
                                val info = updateInfo ?: return@Button
                                scope.launch {
                                    updateInstalling = true
                                    updateMessage = "正在下载安装包..."
                                    try {
                                        val file = updateManager.downloadApk(info)
                                        updateMessage = "下载完成，正在打开安装程序。"
                                        updateManager.installApk(file)
                                    } catch (error: Exception) {
                                        updateMessage = "更新失败: ${error.message ?: "未知错误"}"
                                    } finally {
                                        updateInstalling = false
                                    }
                                }
                            },
                            enabled = updateInfo?.hasUpdate == true && !updateChecking && !updateInstalling,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (updateInstalling) "下载中..." else "下载更新")
                        }
                    }
                }
            }
        }
    }
}

private enum class SettingsSection(val title: String) {
    Connection("连接"),
    Devices("设备"),
    Notification("通知"),
    Advanced("高级"),
    About("关于");

    companion object {
        fun fromRouteValue(value: String?): SettingsSection {
            return values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Connection
        }
    }
}

private data class InstalledAppInfo(
    val label: String,
    val packageName: String
)

private fun loadInstalledLaunchableApps(packageManager: PackageManager): List<InstalledAppInfo> {
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packageManager.queryIntentActivities(launcherIntent, 0)
        .mapNotNull { resolveInfo ->
            val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
            val label = resolveInfo.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() }
                ?: packageName
            InstalledAppInfo(label = label, packageName = packageName)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

private fun calculateAppCacheSize(context: android.content.Context): Long {
    return listOfNotNull(context.cacheDir, context.externalCacheDir)
        .sumOf { directorySize(it) }
}

private fun clearAppCache(context: android.content.Context): Long {
    return listOfNotNull(context.cacheDir, context.externalCacheDir)
        .sumOf { clearDirectoryContents(it) }
}

private fun directorySize(file: java.io.File): Long {
    if (!file.exists()) return 0L
    if (file.isFile) return file.length()
    return file.listFiles()?.sumOf { directorySize(it) } ?: 0L
}

private fun clearDirectoryContents(directory: java.io.File): Long {
    if (!directory.exists() || !directory.isDirectory) return 0L
    return directory.listFiles()?.sumOf { child ->
        val size = directorySize(child)
        if (child.deleteRecursively()) size else 0L
    } ?: 0L
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(java.util.Locale.getDefault(), "%.1f MB", mb)
    return String.format(java.util.Locale.getDefault(), "%.1f GB", mb / 1024.0)
}

@Composable
private fun NotificationFilterSwitch(
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
            Text(title, style = MaterialTheme.typography.bodyMedium)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationModeMenu(
    selected: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        "pc_center" to "转发到电脑通知记录",
        "clipboard" to "转发并复制到电脑剪贴板",
        "ai_silent" to "转发给 AI 总结，不弹出",
        "history_only" to "仅保存到手机历史"
    )
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: options.first().second
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("通知转发模式") },
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
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    }
                )
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

private fun isNotificationListenerEnabled(context: android.content.Context): Boolean {
    val enabledListeners = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    val packageName = context.packageName
    return enabledListeners.split(":").any { component ->
        component.contains(packageName, ignoreCase = true)
    }
}

private fun isPostNotificationPermissionGranted(context: android.content.Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
}

package com.voiceinput.ui.screens

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onScanResult: (serverUrl: String, deviceId: String, deviceName: String, localIp: String, localPort: Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val gson = remember { Gson() }
    var scanStatus by remember { mutableStateOf("正在打开摄像头...") }
    var hasError by remember { mutableStateOf(false) }

    val launchScan: () -> Unit = remember {
        {
            scanStatus = "正在打开摄像头..."
            hasError = false
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            if (!handlePairingPayload(result.contents, gson, onScanResult)) {
                scanStatus = "不是有效的配对信息"
                hasError = true
            } else {
                scanStatus = "扫描成功，正在开始连接..."
                hasError = false
            }
        } else {
            scanStatus = "扫描已取消，可重新扫码或从剪贴板导入"
            hasError = false
        }
    }

    fun startScan() {
        launchScan()
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("将二维码放入取景框中")
            setBeepEnabled(false)
            setOrientationLocked(true)
            captureActivity = com.journeyapps.barcodescanner.CaptureActivity::class.java
        }
        scanLauncher.launch(options)
    }

    fun importFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val hasText = clipboard.hasPrimaryClip() &&
            clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) != false
        if (!hasText) {
            scanStatus = "剪贴板里没有可用的配对信息"
            hasError = true
            return
        }

        val text = clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            .orEmpty()

        if (text.isBlank()) {
            scanStatus = "剪贴板内容为空"
            hasError = true
            return
        }

        val parsed = handlePairingPayload(text, gson, onScanResult)
        if (parsed) {
            scanStatus = "已从剪贴板导入配对信息"
            hasError = false
        } else {
            scanStatus = "剪贴板内容不是有效的配对信息"
            hasError = true
        }
    }

    LaunchedEffect(Unit) {
        startScan()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扫码配对") },
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.height(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "扫描电脑端二维码",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "如果相机权限异常，也可以先在电脑端复制配对信息，再从剪贴板导入。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = scanStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = ::startScan,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.height(0.dp))
                Text("重新扫码", modifier = Modifier.padding(start = 8.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = ::importFromClipboard,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null)
                Text("从剪贴板导入", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private fun handlePairingPayload(
    raw: String,
    gson: Gson,
    onScanResult: (serverUrl: String, deviceId: String, deviceName: String, localIp: String, localPort: Int) -> Unit
): Boolean {
    return try {
        val json = gson.fromJson(raw, JsonObject::class.java)
        val type = json.get("type")?.asString
        if (type != "VOICEINPUT_PAIR") {
            return false
        }

        val serverUrl = json.get("server_url")?.asString ?: ""
        val deviceId = json.get("device_id")?.asString ?: ""
        val deviceName = json.get("device_name")?.asString ?: ""
        val localIp = json.get("local_ip")?.asString ?: ""
        val localPort = json.get("local_port")?.asInt ?: 58889

        if (deviceId.isBlank()) {
            return false
        }

        onScanResult(serverUrl, deviceId, deviceName, localIp, localPort)
        true
    } catch (_: Exception) {
        false
    }
}

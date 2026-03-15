package com.voiceinput.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val gson = remember { Gson() }
    var scanStatus by remember { mutableStateOf("点击下方按钮开始扫描") }
    var hasError by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            try {
                val json = gson.fromJson(result.contents, JsonObject::class.java)
                val type = json.get("type")?.asString
                if (type == "VOICEINPUT_PAIR") {
                    val serverUrl = json.get("server_url")?.asString ?: ""
                    val deviceId = json.get("device_id")?.asString ?: ""
                    val deviceName = json.get("device_name")?.asString ?: ""
                    val localIp = json.get("local_ip")?.asString ?: ""
                    val localPort = json.get("local_port")?.asInt ?: 58889

                    if (deviceId.isNotEmpty()) {
                        scanStatus = "扫描成功! 正在配对 $deviceName ..."
                        hasError = false
                        onScanResult(serverUrl, deviceId, deviceName, localIp, localPort)
                    } else {
                        scanStatus = "二维码内容不完整"
                        hasError = true
                    }
                } else {
                    scanStatus = "不是有效的配对二维码"
                    hasError = true
                }
            } catch (e: Exception) {
                scanStatus = "无法解析二维码: ${e.message}"
                hasError = true
            }
        } else {
            scanStatus = "扫描已取消"
            hasError = false
        }
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
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "扫描电脑端二维码",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "打开电脑端应用，连接服务器后会显示配对二维码",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    val options = ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("将二维码放入取景框中")
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                    }
                    scanLauncher.launch(options)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("开始扫描", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = scanStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

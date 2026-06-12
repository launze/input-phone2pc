package com.voiceinput.ui.screens

import android.app.Activity
import android.content.Intent
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.voiceinput.cimbar.FileTransferScanActivity
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileQrScannerScreen(
    onFileScanned: (fileName: String, mimeType: String, base64Data: String, size: Long) -> Unit,
    onBack: () -> Unit
) {
    val gson = remember { Gson() }
    val context = LocalContext.current
    var scanStatus by remember { mutableStateOf("正在打开摄像头...") }
    var hasError by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            scanStatus = "扫描已取消"
            onBack()
            return@rememberLauncherForActivityResult
        }

        try {
            val parsed = parseActivityResultFile(gson, result.data)
            scanStatus = "扫描成功，正在发送 ${parsed.fileName} ..."
            hasError = false
            onFileScanned(parsed.fileName, parsed.mimeType, parsed.base64Data, parsed.size)
            onBack()
        } catch (e: Exception) {
            scanStatus = "无法解析文件二维码: ${e.message ?: "未知错误"}"
            hasError = true
        }
    }

    LaunchedEffect(Unit) {
        scanLauncher.launch(Intent(context, FileTransferScanActivity::class.java))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扫码接收文件") },
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
                text = "扫描文件二维码",
                style = MaterialTheme.typography.headlineSmall
            )

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

private data class ScannedFilePayload(
    val fileName: String,
    val mimeType: String,
    val base64Data: String,
    val size: Long
)

private fun parseScannedFile(gson: Gson, contents: String): ScannedFilePayload {
    val trimmed = contents.trim()
    if (trimmed.startsWith("{")) {
        val json = gson.fromJson(trimmed, JsonObject::class.java)
        val type = json.get("type")?.asString.orEmpty()
        if (type != "VOICEINPUT_FILE") {
            throw IllegalArgumentException("不是有效的文件二维码")
        }

        val fileName = json.get("file_name")?.asString.orEmpty().ifBlank {
            "scanned-file-${System.currentTimeMillis()}"
        }
        val mimeType = json.get("mime_type")?.asString.orEmpty().ifBlank {
            "application/octet-stream"
        }
        val data = json.get("data")?.asString.orEmpty()
        if (data.isBlank()) {
            throw IllegalArgumentException("文件数据为空")
        }

        return ScannedFilePayload(
            fileName = fileName,
            mimeType = mimeType,
            base64Data = data,
            size = json.get("size")?.asLong ?: Base64.decode(data, Base64.DEFAULT).size.toLong()
        )
    }

    val bytes = trimmed.toByteArray(Charsets.UTF_8)
    return ScannedFilePayload(
        fileName = "scanned-text-${System.currentTimeMillis()}.txt",
        mimeType = "text/plain; charset=utf-8",
        base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
        size = bytes.size.toLong()
    )
}

private fun parseActivityResultFile(gson: Gson, data: android.content.Intent?): ScannedFilePayload {
    val path = data?.getStringExtra(FileTransferScanActivity.EXTRA_FILE_PATH).orEmpty()
    if (path.isBlank()) {
        throw IllegalArgumentException("没有收到文件路径")
    }

    val file = File(path)
    if (!file.exists()) {
        throw IllegalArgumentException("接收文件不存在")
    }

    return try {
        val fileName = data?.getStringExtra(FileTransferScanActivity.EXTRA_FILE_NAME)
            .orEmpty()
            .ifBlank { file.name }
        val bytes = file.readBytes()
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (text.startsWith("{")) {
            runCatching { parseScannedFile(gson, text) }.getOrElse {
                ScannedFilePayload(
                    fileName = fileName,
                    mimeType = guessMimeType(fileName),
                    base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    size = bytes.size.toLong()
                )
            }
        } else {
            ScannedFilePayload(
                fileName = fileName,
                mimeType = guessMimeType(fileName),
                base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                size = data?.getLongExtra(FileTransferScanActivity.EXTRA_FILE_SIZE, bytes.size.toLong())
                    ?: bytes.size.toLong()
            )
        }
    } finally {
        file.delete()
    }
}

private fun guessMimeType(fileName: String): String {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (extension) {
        "txt", "log", "md", "csv", "json", "xml" -> "text/plain; charset=utf-8"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}

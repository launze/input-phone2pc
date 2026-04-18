package com.voiceinput.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.voiceinput.data.ConfigManager
import com.voiceinput.data.model.AppUpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class AppUpdateManager(private val context: Context) {
    private val gson = Gson()
    private val client = OkHttpClient()
    private val configManager = ConfigManager(context)

    suspend fun checkUpdate(): AppUpdateInfo = withContext(Dispatchers.IO) {
        val base = normalizeBase(configManager.getServerUrl())
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        val url = "$base/api/updates/check?device_type=android&platform=android&arch=universal&version=$version&channel=stable"
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: error("empty response")
            if (!resp.isSuccessful) error(body)
            gson.fromJson(body, AppUpdateInfo::class.java)
        }
    }

    suspend fun downloadApk(info: AppUpdateInfo): File = withContext(Dispatchers.IO) {
        val asset = info.asset ?: error("missing update asset")
        val base = normalizeBase(configManager.getServerUrl())
        val downloadUrl = if (asset.downloadUrl.startsWith("http://") || asset.downloadUrl.startsWith("https://")) {
            asset.downloadUrl
        } else {
            base + asset.downloadUrl
        }
        val targetDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val targetFile = File(targetDir, asset.fileName)
        val req = Request.Builder().url(downloadUrl).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error(resp.body?.string() ?: "download failed")
            val body = resp.body ?: error("empty body")
            FileOutputStream(targetFile).use { out ->
                body.byteStream().copyTo(out)
            }
        }
        targetFile
    }

    fun installApk(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun normalizeBase(serverUrl: String): String {
        val normalized = when {
            serverUrl.startsWith("wss://") -> "https://" + serverUrl.removePrefix("wss://").trimEnd('/')
            serverUrl.startsWith("ws://") -> "http://" + serverUrl.removePrefix("ws://").trimEnd('/')
            else -> serverUrl.trimEnd('/')
        }
        val uri = Uri.parse(normalized)
        val scheme = uri.scheme ?: "https"
        val host = uri.host ?: error("missing host")
        val nextPort = when (val port = uri.port) {
            7070 -> 7071
            -1 -> 7071
            else -> port
        }
        return "$scheme://$host:$nextPort"
    }
}

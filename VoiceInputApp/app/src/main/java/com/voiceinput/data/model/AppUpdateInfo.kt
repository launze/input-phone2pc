package com.voiceinput.data.model

data class AppUpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val minimumSupportedVersion: String? = null,
    val forceUpdate: Boolean = false,
    val releaseNotes: String = "",
    val asset: AppUpdateAsset? = null
)

data class AppUpdateAsset(
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val size: Long,
    val mimeType: String
)

sealed class AppUpdateState {
    data object Idle : AppUpdateState()
    data object Checking : AppUpdateState()
    data class Available(val info: AppUpdateInfo) : AppUpdateState()
    data class UpToDate(val version: String) : AppUpdateState()
    data class Downloading(val fileName: String) : AppUpdateState()
    data class ReadyToInstall(val fileName: String) : AppUpdateState()
    data class Error(val message: String) : AppUpdateState()
}

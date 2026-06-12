package com.voiceinput.data.model

import com.google.gson.annotations.SerializedName

data class AppUpdateInfo(
    @SerializedName("has_update") val hasUpdate: Boolean,
    @SerializedName("latest_version") val latestVersion: String,
    @SerializedName("minimum_supported_version") val minimumSupportedVersion: String? = null,
    @SerializedName("force_update") val forceUpdate: Boolean = false,
    @SerializedName("release_notes") val releaseNotes: String = "",
    val asset: AppUpdateAsset? = null
)

data class AppUpdateAsset(
    @SerializedName("file_name") val fileName: String,
    @SerializedName("download_url") val downloadUrl: String,
    val sha256: String,
    val size: Long,
    @SerializedName("mime_type") val mimeType: String
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

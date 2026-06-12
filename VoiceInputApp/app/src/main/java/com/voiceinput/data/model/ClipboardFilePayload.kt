package com.voiceinput.data.model

data class ClipboardFilePayload(
    val mimeType: String,
    val fileName: String,
    val data: String,
    val size: Long
)

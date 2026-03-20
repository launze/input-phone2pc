package com.voiceinput.data.model

data class ClipboardImagePayload(
    val mimeType: String,
    val fileName: String,
    val imageData: String,
    val width: Int,
    val height: Int,
    val size: Int
)

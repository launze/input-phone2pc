package com.voiceinput.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.voiceinput.data.model.ClipboardFilePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FileTransferCodec {
    private const val DEFAULT_MIME_TYPE = "application/octet-stream"
    private const val MAX_FILE_BYTES = 4_500_000L

    suspend fun encodeFromUri(context: Context, uri: Uri): ClipboardFilePayload? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val fileName = queryDisplayName(context, uri).ifBlank {
                "voiceinput-file-${System.currentTimeMillis()}"
            }
            val size = querySize(context, uri)
            if (size > MAX_FILE_BYTES) {
                return@withContext null
            }

            val bytes = resolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            } ?: return@withContext null

            if (bytes.isEmpty() || bytes.size.toLong() > MAX_FILE_BYTES) {
                return@withContext null
            }

            ClipboardFilePayload(
                mimeType = resolver.getType(uri) ?: DEFAULT_MIME_TYPE,
                fileName = sanitizeFileName(fileName),
                data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                size = bytes.size.toLong()
            )
        }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        return queryOpenableColumn(context, uri, OpenableColumns.DISPLAY_NAME) as? String ?: ""
    }

    private fun querySize(context: Context, uri: Uri): Long {
        return when (val value = queryOpenableColumn(context, uri, OpenableColumns.SIZE)) {
            is Long -> value
            is Int -> value.toLong()
            else -> -1L
        }
    }

    private fun queryOpenableColumn(context: Context, uri: Uri, column: String): Any? {
        return try {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return null
                }
                val index = cursor.getColumnIndex(column)
                if (index < 0 || cursor.isNull(index)) {
                    return null
                }
                when (cursor.getType(index)) {
                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                    android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeFileName(name: String): String {
        val sanitized = name
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .trim('.')
        return sanitized.ifBlank { "voiceinput-file-${System.currentTimeMillis()}" }
    }
}

package com.voiceinput.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.voiceinput.data.model.HistoryItem
import com.voiceinput.data.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HistoryRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "voice_input_history",
        Context.MODE_PRIVATE
    )
    private val gson = Gson()

    companion object {
        private const val KEY_HISTORY_ITEMS = "history_items"
        private const val MAX_HISTORY_SIZE = 1000
    }

    suspend fun saveHistory(items: List<HistoryItem>) = withContext(Dispatchers.IO) {
        val limitedItems = if (items.size > MAX_HISTORY_SIZE) {
            items.takeLast(MAX_HISTORY_SIZE)
        } else {
            items
        }
        val json = gson.toJson(limitedItems)
        prefs.edit().putString(KEY_HISTORY_ITEMS, json).apply()
    }

    suspend fun loadHistory(): List<HistoryItem> = withContext(Dispatchers.IO) {
        readHistoryItems()
    }

    suspend fun loadRecentHistoryPage(limit: Int): List<HistoryItem> = withContext(Dispatchers.IO) {
        if (limit <= 0) {
            emptyList()
        } else {
            readHistoryItems().takeLast(limit)
        }
    }

    suspend fun loadHistoryBefore(
        beforeTimestamp: Long,
        beforeId: String,
        limit: Int
    ): List<HistoryItem> = withContext(Dispatchers.IO) {
        if (limit <= 0) {
            return@withContext emptyList()
        }

        val allHistory = readHistoryItems()
        val boundaryIndex = allHistory.indexOfFirst { item ->
            item.timestamp == beforeTimestamp && item.id == beforeId
        }

        if (boundaryIndex <= 0) {
            emptyList()
        } else {
            allHistory.subList(maxOf(0, boundaryIndex - limit), boundaryIndex)
        }
    }

    suspend fun hasHistoryBefore(beforeTimestamp: Long, beforeId: String): Boolean =
        withContext(Dispatchers.IO) {
            readHistoryItems().indexOfFirst { item ->
                item.timestamp == beforeTimestamp && item.id == beforeId
            } > 0
        }
    
    private fun readHistoryItems(): List<HistoryItem> {
        val json = prefs.getString(KEY_HISTORY_ITEMS, null) ?: return emptyList()
        return try {
            val array = JsonParser.parseString(json).asJsonArray
            array.mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.toHistoryItem()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun addHistoryItem(item: HistoryItem) = withContext(Dispatchers.IO) {
        val currentHistory = readHistoryItems().toMutableList()
        currentHistory.add(item)
        saveHistory(currentHistory)
    }

    suspend fun updateHistoryItem(itemId: String, transform: (HistoryItem) -> HistoryItem) =
        withContext(Dispatchers.IO) {
            val updated = readHistoryItems().map { item ->
                if (item.id == itemId) transform(item) else item
            }
            saveHistory(updated)
        }

    suspend fun updateHistoryByServerMessageId(
        serverMessageId: String,
        transform: (HistoryItem) -> HistoryItem
    ): HistoryItem? = withContext(Dispatchers.IO) {
        var changedItem: HistoryItem? = null
        val updated = readHistoryItems().map { item ->
            if (item.serverMessageId == serverMessageId) {
                transform(item).also { changedItem = it }
            } else {
                item
            }
        }
        if (changedItem != null) {
            saveHistory(updated)
        }
        changedItem
    }

    suspend fun deleteHistoryItem(itemId: String) = withContext(Dispatchers.IO) {
        val currentHistory = readHistoryItems().toMutableList()
        currentHistory.removeAll { it.id == itemId }
        saveHistory(currentHistory)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_HISTORY_ITEMS).apply()
    }

    suspend fun searchHistory(query: String): List<HistoryItem> = withContext(Dispatchers.IO) {
        val allHistory = readHistoryItems()
        if (query.isBlank()) return@withContext allHistory
        allHistory.filter {
            it.text.contains(query, ignoreCase = true) ||
                it.targetDeviceName.contains(query, ignoreCase = true)
        }
    }

    suspend fun getRecentHistory(count: Int): List<HistoryItem> = withContext(Dispatchers.IO) {
        val allHistory = readHistoryItems()
        allHistory.takeLast(count)
    }

    private fun JsonObject.toHistoryItem(): HistoryItem {
        val timestamp = getLong("timestamp")
        return HistoryItem(
            id = getString("id"),
            text = getString("text"),
            timestamp = timestamp,
            targetDeviceId = getString("targetDeviceId"),
            targetDeviceName = getString("targetDeviceName"),
            contentType = getString("contentType").ifBlank { "text" },
            syncStatus = getSyncStatus("syncStatus"),
            channel = getString("channel").ifBlank { "server" },
            serverMessageId = getNullableString("serverMessageId"),
            storedAt = getNullableLong("storedAt"),
            syncedAt = getNullableLong("syncedAt"),
            errorMessage = getNullableString("errorMessage"),
        )
    }

    private fun JsonObject.getString(name: String): String {
        return if (has(name) && !get(name).isJsonNull) get(name).asString else ""
    }

    private fun JsonObject.getNullableString(name: String): String? {
        return if (has(name) && !get(name).isJsonNull) get(name).asString else null
    }

    private fun JsonObject.getLong(name: String): Long {
        return if (has(name) && !get(name).isJsonNull) get(name).asLong else 0L
    }

    private fun JsonObject.getNullableLong(name: String): Long? {
        return if (has(name) && !get(name).isJsonNull) get(name).asLong else null
    }

    private fun JsonObject.getSyncStatus(name: String): SyncStatus {
        val raw = getNullableString(name) ?: return SyncStatus.SYNCED
        return SyncStatus.values().firstOrNull { it.name == raw } ?: SyncStatus.SYNCED
    }
}

package com.voiceinput.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.voiceinput.data.model.HistoryItem
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
        private const val MAX_HISTORY_SIZE = 1000 // 最多保存1000条记录
    }

    // 保存历史记录
    suspend fun saveHistory(items: List<HistoryItem>) = withContext(Dispatchers.IO) {
        val limitedItems = if (items.size > MAX_HISTORY_SIZE) {
            items.takeLast(MAX_HISTORY_SIZE)
        } else {
            items
        }
        val json = gson.toJson(limitedItems)
        prefs.edit().putString(KEY_HISTORY_ITEMS, json).apply()
    }

    // 加载历史记录
    suspend fun loadHistory(): List<HistoryItem> = withContext(Dispatchers.IO) {
        val json = prefs.getString(KEY_HISTORY_ITEMS, null) ?: return@withContext emptyList()
        try {
            val type = object : TypeToken<List<HistoryItem>>() {}.type
            gson.fromJson<List<HistoryItem>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 添加单条记录
    suspend fun addHistoryItem(item: HistoryItem) = withContext(Dispatchers.IO) {
        val currentHistory = loadHistory().toMutableList()
        currentHistory.add(item)
        saveHistory(currentHistory)
    }

    // 删除单条记录
    suspend fun deleteHistoryItem(itemId: String) = withContext(Dispatchers.IO) {
        val currentHistory = loadHistory().toMutableList()
        currentHistory.removeAll { it.id == itemId }
        saveHistory(currentHistory)
    }

    // 清空所有历史记录
    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_HISTORY_ITEMS).apply()
    }

    // 搜索历史记录
    suspend fun searchHistory(query: String): List<HistoryItem> = withContext(Dispatchers.IO) {
        val allHistory = loadHistory()
        if (query.isBlank()) return@withContext allHistory
        allHistory.filter { it.text.contains(query, ignoreCase = true) }
    }

    // 获取最近N条记录
    suspend fun getRecentHistory(count: Int): List<HistoryItem> = withContext(Dispatchers.IO) {
        val allHistory = loadHistory()
        allHistory.takeLast(count)
    }
}

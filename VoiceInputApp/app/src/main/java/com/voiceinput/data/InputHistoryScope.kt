package com.voiceinput.data

import com.voiceinput.data.model.HistoryItem

object InputHistoryScope {
    fun isInputRecord(item: HistoryItem): Boolean {
        return item.contentType != "notification"
    }

    fun filterInputRecords(items: List<HistoryItem>): List<HistoryItem> {
        return items.filter(::isInputRecord)
    }
}

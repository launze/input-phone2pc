package com.voiceinput.data

import com.voiceinput.data.model.HistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputHistoryScopeTest {
    @Test
    fun inputRecordsExcludeNotifications() {
        assertTrue(InputHistoryScope.isInputRecord(item("text")))
        assertTrue(InputHistoryScope.isInputRecord(item("image")))
        assertTrue(InputHistoryScope.isInputRecord(item("file")))
        assertFalse(InputHistoryScope.isInputRecord(item("notification")))
    }

    @Test
    fun filterInputRecordsKeepsInputHistoryOrderOnly() {
        val items = listOf(
            item("text", "text-1"),
            item("notification", "notification-1"),
            item("image", "image-1"),
            item("file", "file-1"),
            item("notification", "notification-2")
        )

        assertEquals(
            listOf("text-1", "image-1", "file-1"),
            InputHistoryScope.filterInputRecords(items).map { it.id }
        )
    }

    private fun item(contentType: String, id: String = contentType): HistoryItem {
        return HistoryItem(
            id = id,
            text = id,
            timestamp = 1000L,
            contentType = contentType
        )
    }
}

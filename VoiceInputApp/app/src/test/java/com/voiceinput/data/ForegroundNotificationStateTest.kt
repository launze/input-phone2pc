package com.voiceinput.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundNotificationStateTest {
    @Test
    fun enabledAndConnectedShowsConnectedDevice() {
        val state = ForegroundNotificationState.from(
            forwardingEnabled = true,
            connected = true,
            connectedDeviceName = "项目电脑",
            targetDeviceName = "备用电脑"
        )

        assertEquals("已连接：项目电脑", state.statusText)
        assertEquals("暂停通知转发", state.toggleActionLabel)
        assertTrue(state.forwardingEnabled)
    }

    @Test
    fun enabledWithDisconnectedTargetShowsOfflineBuffering() {
        val state = ForegroundNotificationState.from(
            forwardingEnabled = true,
            connected = false,
            connectedDeviceName = "",
            targetDeviceName = "办公室电脑"
        )

        assertEquals("电脑离线，通知和输入会暂存：办公室电脑", state.statusText)
        assertEquals("暂停通知转发", state.toggleActionLabel)
        assertTrue(state.forwardingEnabled)
    }

    @Test
    fun enabledWithoutTargetShowsNoTargetMessage() {
        val state = ForegroundNotificationState.from(
            forwardingEnabled = true,
            connected = false,
            connectedDeviceName = "",
            targetDeviceName = null
        )

        assertEquals("未选择电脑，输入和通知会先保留", state.statusText)
        assertEquals("暂停通知转发", state.toggleActionLabel)
        assertTrue(state.forwardingEnabled)
    }

    @Test
    fun disabledShowsPausedMessageAndResumeAction() {
        val state = ForegroundNotificationState.from(
            forwardingEnabled = false,
            connected = true,
            connectedDeviceName = "项目电脑",
            targetDeviceName = "办公室电脑"
        )

        assertEquals("通知转发已暂停，点击继续", state.statusText)
        assertEquals("继续通知转发", state.toggleActionLabel)
        assertFalse(state.forwardingEnabled)
    }

    @Test
    fun blankConnectedDeviceNameFallsBackToTarget() {
        val state = ForegroundNotificationState.from(
            forwardingEnabled = true,
            connected = true,
            connectedDeviceName = "   ",
            targetDeviceName = "默认电脑"
        )

        assertEquals("电脑离线，通知和输入会暂存：默认电脑", state.statusText)
        assertEquals("暂停通知转发", state.toggleActionLabel)
    }

    @Test
    fun blankConnectedDeviceNameFallsBackToNoTarget() {
        val state = ForegroundNotificationState.from(
            forwardingEnabled = true,
            connected = true,
            connectedDeviceName = "   ",
            targetDeviceName = "   "
        )

        assertEquals("未选择电脑，输入和通知会先保留", state.statusText)
        assertEquals("暂停通知转发", state.toggleActionLabel)
    }
}

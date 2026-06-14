package com.voiceinput.data

import com.voiceinput.data.model.ServerDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedDeviceUiStateTest {
    @Test
    fun onlineLanDefaultDeviceShowsRouteAndDisabledDefaultAction() {
        val state = PairedDeviceUiState.from(
            device = pairedDevice(
                localIp = "192.168.1.20",
                localPort = 58889,
                lastConnectedAt = 1_700_000_000_000L
            ),
            serverDevice = serverDevice(online = true),
            defaultDeviceId = "pc-1",
            nowMillis = 1_700_000_020_000L
        )

        assertEquals("局域网 192.168.1.20:58889 · 最近在线", state.routeText)
        assertEquals("服务器在线", state.statusText)
        assertEquals("刚刚", state.lastConnectedText)
        assertEquals("在线", state.badgeText)
        assertEquals("当前默认", state.defaultActionText)
        assertFalse(state.defaultActionEnabled)
        assertEquals("一键重连", state.reconnectActionText)
        assertEquals("已重新选择：办公室电脑", state.reconnectFeedbackText)
        assertTrue(state.online)
        assertTrue(state.isDefault)
    }

    @Test
    fun offlineServerRelayDeviceShowsBufferedStatusAndSetDefaultAction() {
        val state = PairedDeviceUiState.from(
            device = pairedDevice(
                localIp = "",
                localPort = 0,
                lastConnectedAt = 1_700_000_000_000L
            ),
            serverDevice = serverDevice(online = false),
            defaultDeviceId = "other-pc",
            nowMillis = 1_700_000_120_000L
        )

        assertEquals("服务器中转", state.routeText)
        assertEquals("当前未在线，可继续离线暂存或稍后重连", state.statusText)
        assertEquals("2 分钟前", state.lastConnectedText)
        assertEquals("离线", state.badgeText)
        assertEquals("设为默认", state.defaultActionText)
        assertTrue(state.defaultActionEnabled)
        assertEquals("已设为目标电脑，正在刷新在线状态", state.reconnectFeedbackText)
        assertFalse(state.online)
        assertFalse(state.isDefault)
    }

    @Test
    fun unknownServerDeviceIsOfflineButKeepsLanRoute() {
        val state = PairedDeviceUiState.from(
            device = pairedDevice(localIp = "10.0.0.8", localPort = 58889),
            serverDevice = null,
            defaultDeviceId = null
        )

        assertEquals("局域网 10.0.0.8:58889", state.routeText)
        assertEquals("离线", state.badgeText)
        assertFalse(state.online)
    }

    @Test
    fun lastConnectedTextHandlesEmptyRelativeAndAbsoluteTimes() {
        assertEquals(
            "暂无记录",
            PairedDeviceUiState.formatLastConnectedAt(timestamp = 0L, nowMillis = 10_000L)
        )
        assertEquals(
            "3 小时前",
            PairedDeviceUiState.formatLastConnectedAt(
                timestamp = 1_700_000_000_000L,
                nowMillis = 1_700_010_800_000L
            )
        )
        assertEquals(
            "11-14 22:13",
            PairedDeviceUiState.formatLastConnectedAt(
                timestamp = 1_700_000_000_000L,
                nowMillis = 1_700_100_000_000L,
                absoluteTimeFormatter = { "11-14 22:13" }
            )
        )
    }

    private fun pairedDevice(
        localIp: String = "",
        localPort: Int = 0,
        lastConnectedAt: Long = 0L
    ): ConfigManager.PairedDevice {
        return ConfigManager.PairedDevice(
            deviceId = "pc-1",
            deviceName = "办公室电脑",
            deviceType = "desktop",
            localIp = localIp,
            localPort = localPort,
            lastConnectedAt = lastConnectedAt
        )
    }

    private fun serverDevice(online: Boolean): ServerDeviceInfo {
        return ServerDeviceInfo(
            deviceId = "pc-1",
            deviceName = "办公室电脑",
            deviceType = "desktop",
            online = online
        )
    }
}

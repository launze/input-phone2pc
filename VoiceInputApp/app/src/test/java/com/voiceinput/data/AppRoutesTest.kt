package com.voiceinput.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppRoutesTest {
    @Test
    fun defaultIntentRouteOpensInput() {
        assertEquals(AppRoutes.INPUT, AppRoutes.routeForIntentAction(null, null))
        assertEquals(AppRoutes.INPUT, AppRoutes.routeForIntentAction("unknown", null))
    }

    @Test
    fun openSettingsActionRoutesToSettings() {
        assertEquals(
            AppRoutes.SETTINGS,
            AppRoutes.routeForIntentAction(AppRoutes.ACTION_OPEN_SETTINGS, null)
        )
        assertEquals(
            AppRoutes.SETTINGS,
            AppRoutes.routeForIntentAction(AppRoutes.ACTION_OPEN_SETTINGS, "Advanced")
        )
    }

    @Test
    fun openSettingsDevicesActionRoutesToDeviceSettings() {
        assertEquals(
            AppRoutes.SETTINGS_DEVICES,
            AppRoutes.routeForIntentAction(AppRoutes.ACTION_OPEN_SETTINGS, AppRoutes.SETTINGS_SECTION_DEVICES)
        )
        assertEquals(
            AppRoutes.SETTINGS_DEVICES,
            AppRoutes.routeForIntentAction(AppRoutes.ACTION_OPEN_SETTINGS, "devices")
        )
    }
}

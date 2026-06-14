package com.voiceinput.data

object AppRoutes {
    const val ACTION_OPEN_SETTINGS = "com.voiceinput.action.OPEN_SETTINGS"
    const val EXTRA_SETTINGS_SECTION = "settings_section"
    const val SETTINGS_SECTION_DEVICES = "Devices"

    const val INPUT = "input"
    const val SETTINGS = "settings"
    const val SETTINGS_DEVICES = "settings_devices"
    const val HISTORY = "history"
    const val NOTIFICATIONS = "notifications"
    const val AI_ASSISTANT = "ai_assistant"

    fun routeForIntentAction(action: String?, settingsSection: String?): String {
        return when (action) {
            ACTION_OPEN_SETTINGS -> {
                if (settingsSection.equals(SETTINGS_SECTION_DEVICES, ignoreCase = true)) {
                    SETTINGS_DEVICES
                } else {
                    SETTINGS
                }
            }
            else -> INPUT
        }
    }
}

package com.voiceinput.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterRulesTest {
    @Test
    fun splitRuleListSupportsCommonSeparators() {
        assertEquals(
            listOf("com.tencent.mm", "com.alibaba.android.rimet", "com.ss.android.ugc.aweme"),
            NotificationFilterRules.splitRuleList(" com.tencent.mm,\ncom.alibaba.android.rimet； com.ss.android.ugc.aweme ")
        )
    }

    @Test
    fun packageRuleMatchesExactPackageAndSubPackages() {
        assertTrue(NotificationFilterRules.matchesPackageRule("com.tencent.mm", "com.tencent.mm"))
        assertTrue(NotificationFilterRules.matchesPackageRule("com.tencent.mm.work", "com.tencent.mm"))
        assertFalse(NotificationFilterRules.matchesPackageRule("com.tencent.mmwork", "com.tencent.mm"))
    }

    @Test
    fun packageRuleMatchesExplicitWildcardsOnly() {
        assertTrue(NotificationFilterRules.matchesPackageRule("com.tencent.mm", "com.tencent.*"))
        assertTrue(NotificationFilterRules.matchesPackageRule("com.tencent.wework", "com.tencent.*"))
        assertFalse(NotificationFilterRules.matchesPackageRule("com.tencenter.mail", "com.tencent.*"))
    }

    @Test
    fun packageRuleDoesNotUseContainsMatching() {
        assertFalse(NotificationFilterRules.matchesPackageRule("x.com.tencent.mm.clone", "com.tencent.mm"))
        assertFalse(NotificationFilterRules.matchesPackageRule("com.example.tencent.mm", "tencent.mm"))
    }

    @Test
    fun shouldForwardNotificationRejectsEmptyOngoingLowImportanceAndSensitiveApps() {
        assertFalse(
            NotificationFilterRules.shouldForwardNotification(
                appPackage = "com.tencent.mm",
                title = "",
                text = "",
                options = NotificationFilterRules.ForwardOptions(filterEmpty = true)
            )
        )
        assertFalse(
            NotificationFilterRules.shouldForwardNotification(
                appPackage = "com.tencent.mm",
                title = "同步中",
                text = "常驻通知",
                isOngoing = true,
                options = NotificationFilterRules.ForwardOptions(filterOngoing = true)
            )
        )
        assertFalse(
            NotificationFilterRules.shouldForwardNotification(
                appPackage = "com.tencent.mm",
                title = "低优先级",
                text = "消息",
                importance = 1,
                options = NotificationFilterRules.ForwardOptions(
                    filterLowImportance = true,
                    lowImportanceThreshold = 2
                )
            )
        )
        assertFalse(
            NotificationFilterRules.shouldForwardNotification(
                appPackage = "com.example.bank",
                title = "余额提醒",
                text = "新通知",
                options = NotificationFilterRules.ForwardOptions(allowSensitiveApps = false)
            )
        )
    }

    @Test
    fun shouldForwardNotificationHonorsIncludeExcludeAndKeywordRules() {
        val options = NotificationFilterRules.ForwardOptions(
            includePackages = "com.tencent.*",
            excludePackages = "com.tencent.ads",
            excludeKeywords = "广告,促销",
            allowSensitiveApps = true
        )

        assertTrue(
            NotificationFilterRules.shouldForwardNotification(
                appPackage = "com.tencent.mm",
                title = "项目消息",
                text = "今天开会",
                options = options
            )
        )
        assertFalse(
            NotificationFilterRules.shouldForwardNotification(
                appPackage = "com.alibaba.android.rimet",
                title = "项目消息",
                text = "今天开会",
                options = options
            )
        )
        assertFalse(
            NotificationFilterRules.shouldForwardNotification(
                appPackage = "com.tencent.ads",
                title = "项目消息",
                text = "今天开会",
                options = options
            )
        )
        assertFalse(
            NotificationFilterRules.shouldForwardNotification(
                appPackage = "com.tencent.mm",
                title = "促销提醒",
                text = "今天开会",
                options = options
            )
        )
    }

    @Test
    fun redactSensitiveContentMasksBuiltInPatterns() {
        val redacted = NotificationFilterRules.redactSensitiveContent(
            "张三 13812345678 test@example.com 11010519491231002X 验证码：123456"
        )

        assertFalse(redacted.contains("13812345678"))
        assertFalse(redacted.contains("test@example.com"))
        assertFalse(redacted.contains("11010519491231002X"))
        assertFalse(redacted.contains("123456"))
        assertTrue(redacted.contains("[手机号已脱敏]"))
        assertTrue(redacted.contains("[邮箱已脱敏]"))
        assertTrue(redacted.contains("[证件号已脱敏]"))
        assertTrue(redacted.contains("[验证码已脱敏]"))
    }

    @Test
    fun redactSensitiveContentMasksCustomKeywordsCaseInsensitive() {
        val redacted = NotificationFilterRules.redactSensitiveContent(
            "Project Phoenix 今天有新进展",
            "phoenix"
        )

        assertEquals("Project [已脱敏] 今天有新进展", redacted)
    }

    @Test
    fun redactSensitiveContentKeepsBlankValues() {
        assertEquals("   ", NotificationFilterRules.redactSensitiveContent("   ", "secret"))
    }
}

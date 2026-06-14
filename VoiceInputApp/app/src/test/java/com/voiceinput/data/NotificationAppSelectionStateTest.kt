package com.voiceinput.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAppSelectionStateTest {
    @Test
    fun filterAllHidesAppListAndShowsAllSubtitle() {
        val state = NotificationAppSelectionState.from(
            filterAll = true,
            selectedPackages = setOf("com.tencent.mm"),
            installedApps = apps(12),
            showAllInstalledApps = false
        )

        assertEquals("当前不过滤 App，仅按关键词和排除规则处理", state.subtitle)
        assertTrue(state.visibleApps.isEmpty())
        assertNull(state.emptyText)
        assertNull(state.listToggleText)
    }

    @Test
    fun filteredModeShowsLimitedAppsAndExpandText() {
        val state = NotificationAppSelectionState.from(
            filterAll = false,
            selectedPackages = setOf("com.example.app02"),
            installedApps = apps(10),
            showAllInstalledApps = false
        )

        assertEquals("仅转发已勾选的 App", state.subtitle)
        assertEquals(8, state.visibleApps.size)
        assertEquals("显示全部 10 个 App", state.listToggleText)
        assertEquals(setOf("com.example.app02"), state.selectedPackages)
    }

    @Test
    fun expandedFilteredModeShowsAllAppsAndCollapseText() {
        val state = NotificationAppSelectionState.from(
            filterAll = false,
            selectedPackages = emptySet(),
            installedApps = apps(10),
            showAllInstalledApps = true
        )

        assertEquals(10, state.visibleApps.size)
        assertEquals("收起 App 列表", state.listToggleText)
    }

    @Test
    fun filteredModeWithNoInstalledAppsShowsManualEntryHint() {
        val state = NotificationAppSelectionState.from(
            filterAll = false,
            selectedPackages = emptySet(),
            installedApps = emptyList(),
            showAllInstalledApps = false
        )

        assertEquals("未读取到可选择的 App，可在下方直接填写包名。", state.emptyText)
        assertTrue(state.visibleApps.isEmpty())
    }

    @Test
    fun manualRulesAndSelectionUseSamePackageFormat() {
        val selected = NotificationAppSelectionState.selectedPackagesFromRules(
            " com.tencent.mm,\ncom.alibaba.android.rimet； com.tencent.wework "
        )

        assertEquals(
            setOf("com.tencent.mm", "com.alibaba.android.rimet", "com.tencent.wework"),
            selected
        )
        assertEquals(
            "com.alibaba.android.rimet\ncom.tencent.mm\ncom.tencent.wework",
            NotificationAppSelectionState.includeRulesFromSelection(selected)
        )
    }

    @Test
    fun checkboxSelectionAddsAndRemovesTrimmedPackageNames() {
        val selected = NotificationAppSelectionState.updateSelectedPackage(
            selectedPackages = setOf("com.tencent.mm"),
            packageName = " com.alibaba.android.rimet ",
            checked = true
        )
        val removed = NotificationAppSelectionState.updateSelectedPackage(
            selectedPackages = selected,
            packageName = "com.tencent.mm",
            checked = false
        )

        assertEquals(setOf("com.tencent.mm", "com.alibaba.android.rimet"), selected)
        assertEquals(setOf("com.alibaba.android.rimet"), removed)
    }

    @Test
    fun blankManualRulesMeanForwardAllApps() {
        assertTrue(NotificationAppSelectionState.shouldFilterAllForManualRules(" \n "))
    }

    private fun apps(count: Int): List<NotificationAppOption> {
        return (1..count).map { index ->
            NotificationAppOption(
                label = "App $index",
                packageName = "com.example.app${index.toString().padStart(2, '0')}"
            )
        }
    }
}

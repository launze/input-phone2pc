package com.voiceinput.data

data class NotificationAppOption(
    val label: String,
    val packageName: String
)

data class NotificationAppSelectionState(
    val filterAll: Boolean,
    val selectedPackages: Set<String>,
    val visibleApps: List<NotificationAppOption>,
    val subtitle: String,
    val emptyText: String?,
    val listToggleText: String?
) {
    companion object {
        fun from(
            filterAll: Boolean,
            selectedPackages: Set<String>,
            installedApps: List<NotificationAppOption>,
            showAllInstalledApps: Boolean,
            visibleLimit: Int = 8
        ): NotificationAppSelectionState {
            val visibleApps = if (filterAll) {
                emptyList()
            } else if (showAllInstalledApps) {
                installedApps
            } else {
                installedApps.take(visibleLimit)
            }
            return NotificationAppSelectionState(
                filterAll = filterAll,
                selectedPackages = selectedPackages,
                visibleApps = visibleApps,
                subtitle = if (filterAll) {
                    "当前不过滤 App，仅按关键词和排除规则处理"
                } else {
                    "仅转发已勾选的 App"
                },
                emptyText = if (!filterAll && installedApps.isEmpty()) {
                    "未读取到可选择的 App，可在下方直接填写包名。"
                } else {
                    null
                },
                listToggleText = if (!filterAll && installedApps.size > visibleLimit) {
                    if (showAllInstalledApps) {
                        "收起 App 列表"
                    } else {
                        "显示全部 ${installedApps.size} 个 App"
                    }
                } else {
                    null
                }
            )
        }

        fun selectedPackagesFromRules(value: String): Set<String> {
            return NotificationFilterRules.splitRuleList(value).toSet()
        }

        fun includeRulesFromSelection(selectedPackages: Set<String>): String {
            return selectedPackages.sorted().joinToString("\n")
        }

        fun updateSelectedPackage(
            selectedPackages: Set<String>,
            packageName: String,
            checked: Boolean
        ): Set<String> {
            val normalized = packageName.trim()
            if (normalized.isBlank()) return selectedPackages
            return if (checked) {
                selectedPackages + normalized
            } else {
                selectedPackages - normalized
            }
        }

        fun shouldFilterAllForManualRules(value: String): Boolean {
            return value.trim().isBlank()
        }
    }
}

package com.voiceinput.data

object NotificationFilterRules {
    data class ForwardOptions(
        val includePackages: String = "",
        val excludePackages: String = "",
        val excludeKeywords: String = "",
        val filterEmpty: Boolean = true,
        val filterOngoing: Boolean = true,
        val filterLowImportance: Boolean = false,
        val lowImportanceThreshold: Int = 2,
        val allowSensitiveApps: Boolean = false
    )

    fun splitRuleList(value: String): List<String> {
        return value
            .split(',', '\n', ';', '，', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun matchesPackageRule(appPackage: String, rule: String): Boolean {
        val normalizedPackage = appPackage.trim().lowercase()
        val normalizedRule = rule.trim().lowercase()
        if (normalizedPackage.isBlank() || normalizedRule.isBlank()) return false
        if ('*' in normalizedRule) {
            val pattern = normalizedRule
                .split('*')
                .joinToString(".*") { Regex.escape(it) }
            return Regex("^$pattern$").matches(normalizedPackage)
        }
        return normalizedPackage == normalizedRule ||
            normalizedPackage.startsWith("$normalizedRule.")
    }

    fun isSensitivePackage(appPackage: String): Boolean {
        val sensitiveHints = listOf(
            "bank",
            "pay",
            "wallet",
            "alipay",
            "finance",
            "securities",
            "broker",
            "health",
            "medical",
            "hospital"
        )
        return sensitiveHints.any { appPackage.contains(it, ignoreCase = true) }
    }

    fun shouldForwardNotification(
        appPackage: String,
        title: String,
        text: String,
        isOngoing: Boolean = false,
        importance: Int = 3,
        options: ForwardOptions = ForwardOptions()
    ): Boolean {
        val normalizedPackage = appPackage.trim()
        val includePackages = splitRuleList(options.includePackages)
        val excludePackages = splitRuleList(options.excludePackages)
        val excludeKeywords = splitRuleList(options.excludeKeywords)
        val searchableText = "$title\n$text"

        if (options.filterEmpty && searchableText.isBlank()) {
            return false
        }

        if (options.filterOngoing && isOngoing) {
            return false
        }

        if (options.filterLowImportance && importance <= options.lowImportanceThreshold) {
            return false
        }

        if (!options.allowSensitiveApps && isSensitivePackage(normalizedPackage)) {
            return false
        }

        if (includePackages.isNotEmpty() && includePackages.none { matchesPackageRule(normalizedPackage, it) }) {
            return false
        }

        if (excludePackages.any { matchesPackageRule(normalizedPackage, it) }) {
            return false
        }

        if (excludeKeywords.any { searchableText.contains(it, ignoreCase = true) }) {
            return false
        }

        return true
    }

    fun redactSensitiveContent(value: String, customKeywords: String = ""): String {
        if (value.isBlank()) return value

        var redacted = value
            .replace(Regex("""(?<!\d)1[3-9]\d{9}(?!\d)"""), "[手机号已脱敏]")
            .replace(Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""), "[邮箱已脱敏]")
            .replace(Regex("""(?<![0-9A-Za-z])\d{17}[\dXx](?![0-9A-Za-z])"""), "[证件号已脱敏]")
            .replace(Regex("""(?i)(验证码|校验码|动态码|code)[:：\s-]*\d{4,8}"""), "$1：[验证码已脱敏]")

        splitRuleList(customKeywords).forEach { keyword ->
            redacted = redacted.replace(keyword, "[已脱敏]", ignoreCase = true)
        }
        return redacted
    }
}

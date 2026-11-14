package com.jscyril.ebb.guard

/** Decides whether a Settings-like window lets the user disable or uninstall Ebb. Pure; matching is English-only (best-effort). */
object GuardMatcher {
    val GUARD_PACKAGES = listOf(
        "com.android.settings", "com.google.android.settings.intelligence",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.samsung.android.lool", "com.miui.securitycenter", "com.coloros.safecenter",
    )

    private val SENSITIVE_TEXT = listOf(
        "uninstall", "force stop", "deactivate", "disable", "clear storage", "clear data", "clear cache",
        "turn off", "stop", "remove", "permissions", "device admin", "accessibility", "usage access",
        "battery", "unrestricted", "optimi", "display over other apps", "allow restricted settings",
    )
    private val SENSITIVE_CLS = listOf(
        "InstalledAppDetails", "AppInfo", "DeviceAdminAdd", "DeviceAdminSettings", "Accessibility", "UsageAccess",
        "HighPowerApplications", "UninstallerActivity", "UninstallAlertActivity", "ManageApplications",
    )
    private val PLEDGE_TEXT = listOf(
        "date & time", "date and time", "automatic date", "set time", "time zone", "developer options",
        "usb debugging", "wireless debugging", "factory reset", "erase all data", "reset options",
    )
    private val PLEDGE_CLS = listOf("DateTime", "Development", "MasterClear", "FactoryReset")

    /** Allocation-free, so the service can call it on every click event. */
    fun isGuardPackage(pkg: CharSequence) = GUARD_PACKAGES.any { it.contentEquals(pkg) }

    fun shouldEject(pkg: String, cls: String?, texts: List<CharSequence>, appName: String, pledgeActive: Boolean): Boolean {
        if (!isGuardPackage(pkg)) return false
        val lower = texts.map { it.toString().lowercase() }
        fun textHas(keys: List<String>) = lower.any { t -> keys.any { it in t } }
        fun clsHas(keys: List<String>) = cls != null && keys.any { cls.contains(it, ignoreCase = true) }

        val name = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(appName.lowercase())}(?![\\p{L}\\p{N}])")
        if (lower.any { name.containsMatchIn(it) }) {
            // "Use Ebb" is the accessibility toggle label; that page has no other keyword or distinctive class.
            val toggle = "use ${appName.lowercase()}"
            if (textHas(SENSITIVE_TEXT) || clsHas(SENSITIVE_CLS) || lower.any { it.startsWith(toggle) }) return true
        }
        return pledgeActive && (textHas(PLEDGE_TEXT) || clsHas(PLEDGE_CLS))
    }
}

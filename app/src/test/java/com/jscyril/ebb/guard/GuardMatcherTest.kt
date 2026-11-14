package com.jscyril.ebb.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardMatcherTest {
    private fun eject(pkg: String, texts: List<String>, cls: String? = null, pledge: Boolean = false) =
        GuardMatcher.shouldEject(pkg, cls, texts, "Ebb", pledge)

    private val settings = "com.android.settings"

    @Test fun settingsAppInfo() = assertTrue(eject(settings, listOf("Ebb", "Uninstall", "Force stop")))

    @Test fun packageInstallerUninstallDialog() =
        assertTrue(eject("com.google.android.packageinstaller", listOf("Ebb", "Do you want to uninstall this app?")))

    @Test fun accessibilityDetailPage() = assertTrue(eject(settings, listOf("Ebb", "Use Ebb")))

    @Test fun deviceAdminPageByClass() =
        assertTrue(eject(settings, listOf("Ebb"), cls = "com.android.settings.applications.specialaccess.deviceadmin.DeviceAdminAdd"))

    @Test fun appNameMustBeWholeWord() = assertFalse(eject(settings, listOf("Ebbing tide", "Uninstall")))

    @Test fun appNameCaseInsensitive() = assertTrue(eject(settings, listOf("EBB", "Battery")))

    @Test fun unrelatedSettingsPage() = assertFalse(eject(settings, listOf("Wi-Fi", "Network")))

    @Test fun nonGuardPackage() = assertFalse(eject("com.example.notes", listOf("Ebb", "Uninstall")))

    @Test fun ownPackageNeverGuarded() = assertFalse(eject("com.jscyril.ebb", listOf("Ebb", "Disable protection")))

    @Test fun dateTimeOnlyDuringPledge() {
        val t = listOf("Date & time", "Set time automatically")
        assertTrue(eject(settings, t, pledge = true))
        assertFalse(eject(settings, t, pledge = false))
    }

    @Test fun developerOptionsOnlyDuringPledge() {
        val t = listOf("Developer options", "USB debugging")
        assertTrue(eject(settings, t, pledge = true))
        assertFalse(eject(settings, t, pledge = false))
    }

    @Test fun pledgeClassMatch() {
        assertTrue(eject(settings, emptyList(), cls = "com.android.settings.Settings\$DateTimeSettingsActivity", pledge = true))
        assertFalse(eject(settings, emptyList(), cls = "com.android.settings.Settings\$DateTimeSettingsActivity"))
    }
}

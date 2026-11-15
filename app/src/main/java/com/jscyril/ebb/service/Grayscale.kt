package com.jscyril.ebb.service

import android.content.Context
import android.provider.Settings

/**
 * Turns the screen grey through Android's colour correction (needs WRITE_SECURE_SETTINGS). The user's own colour
 * correction is saved first and put back after, and the saved copy survives a crash so [set] false can still undo it.
 */
object Grayscale {
    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"
    private const val MONOCHROMACY = 0

    fun set(ctx: Context, on: Boolean) {
        if (!SelfToggle.canWrite(ctx)) return
        val p = ctx.getSharedPreferences("grayscale", Context.MODE_PRIVATE)
        val cr = ctx.contentResolver
        runCatching {
            if (on) {
                if (p.contains(ENABLED)) return // already grey
                p.edit().putInt(ENABLED, Settings.Secure.getInt(cr, ENABLED, 0)).putInt(MODE, Settings.Secure.getInt(cr, MODE, -1)).commit()
                Settings.Secure.putInt(cr, MODE, MONOCHROMACY)
                Settings.Secure.putInt(cr, ENABLED, 1)
            } else {
                if (!p.contains(ENABLED)) return
                Settings.Secure.putInt(cr, ENABLED, p.getInt(ENABLED, 0))
                p.getInt(MODE, -1).takeIf { it >= 0 }?.let { Settings.Secure.putInt(cr, MODE, it) }
                p.edit().clear().commit()
            }
        }
    }
}

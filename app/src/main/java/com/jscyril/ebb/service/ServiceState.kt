package com.jscyril.ebb.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** Whether the user has Ebb's accessibility service switched on (the service cannot restart itself). */
object ServiceState {
    fun isEnabled(ctx: Context): Boolean {
        val me = ComponentName(ctx, BlockerService::class.java)
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

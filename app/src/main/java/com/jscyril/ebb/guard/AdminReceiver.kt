package com.jscyril.ebb.guard

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.jscyril.ebb.R

/** Device Admin with no policies: while active, Android refuses a plain uninstall. */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)
}

object Admin {
    private fun component(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun isActive(ctx: Context): Boolean = dpm(ctx)?.isAdminActive(component(ctx)) == true

    fun enableIntent(ctx: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx))
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, ctx.getString(R.string.admin_explanation))

    /** Only the in-app flow calls this, after DisableRequest.canDisable allows it. */
    fun deactivate(ctx: Context) { if (isActive(ctx)) dpm(ctx)?.removeActiveAdmin(component(ctx)) }
}

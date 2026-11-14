package com.jscyril.ebb.ui.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jscyril.ebb.R
import com.jscyril.ebb.service.BlockerService
import com.jscyril.ebb.ui.theme.EbbThemeFromRepo

/**
 * Hosts the emergency flow in a normal window, where the soft keyboard is reliable (an accessibility overlay
 * may not get one). noHistory + finish on stop: leaving starts the 60 s wait over; nothing is restored.
 */
class EmergencyActivity : ComponentActivity() {
    private var confirmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return finish()
        val name = intent.getStringExtra(EXTRA_NAME) ?: pkg
        val passes = intent.getIntExtra(EXTRA_PASSES, 0)
        setContent {
            EbbThemeFromRepo { EmergencyScreen(name, passes, onConfirm = { confirm(pkg) }, onCancel = ::finish) }
        }
    }

    private fun confirm(pkg: String) {
        val service = BlockerService.instance
        if (service == null) {
            Toast.makeText(this, R.string.emergency_no_service, Toast.LENGTH_LONG).show()
            return
        }
        confirmed = true
        service.confirmEmergency(pkg)
        finish()
    }

    override fun onStop() {
        super.onStop()
        finish() // the user left (Home, recents, screen off): the flow resets
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !confirmed) BlockerService.instance?.emergencyClosed() // re-cover a PiP or the app
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_NAME = "appName"
        private const val EXTRA_PASSES = "passes"

        fun intent(ctx: Context, pkg: String, appName: String, passesLeft: Int): Intent =
            Intent(ctx, EmergencyActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_NAME, appName).putExtra(EXTRA_PASSES, passesLeft)
    }
}

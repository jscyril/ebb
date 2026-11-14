package com.jscyril.ebb

import android.os.Bundle
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.data.ThemePrefs
import com.jscyril.ebb.ui.app.EbbRoot
import com.jscyril.ebb.ui.app.maturePending
import com.jscyril.ebb.ui.theme.EbbTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Theme prefs + onboarding flag; null until DataStore has answered, while the splash stays up. */
    private val boot = mutableStateOf<Pair<ThemePrefs, Boolean>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { boot.value == null } // no default-theme first frame
        splash.setOnExitAnimationListener { s ->
            // Hand-off: the mark swells slightly while the splash fades. iconView can be missing on some OEM splashes.
            runCatching { s.iconView.animate().scaleX(1.15f).scaleY(1.15f).setDuration(300).setInterpolator(OvershootInterpolator()).start() }
            s.view.animate().alpha(0f).setDuration(300).setInterpolator(AccelerateInterpolator())
                .withEndAction { s.remove() }.start()
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = Repo.get(this)
        lifecycleScope.launch { combine(repo.themeFlow, repo.onboardingDoneFlow, ::Pair).collect { boot.value = it } }
        setContent {
            val b = boot.value ?: return@setContent
            EbbTheme(b.first) { EbbRoot(b.second) }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { runCatching { maturePending(this@MainActivity) } }
    }
}

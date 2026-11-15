package com.jscyril.ebb

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
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
        val shownAt = SystemClock.uptimeMillis()
        // Hold the splash for a beat (and until theme prefs load, so there's no default-theme first frame).
        splash.setKeepOnScreenCondition { boot.value == null || SystemClock.uptimeMillis() - shownAt < SPLASH_MS }
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
            // The session timer chip needs notification permission (Android 13+); ask once onboarding is done.
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(b.second) {
                if (b.second && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private companion object { const val SPLASH_MS = 2_000L }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { runCatching { maturePending(this@MainActivity) } }
    }
}

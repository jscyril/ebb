package com.jscyril.ebb

import android.os.Bundle
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.jscyril.ebb.ui.theme.EbbThemeFromRepo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setOnExitAnimationListener { splash ->
            // Hand-off: the mark swells slightly while the splash fades, then the splash view is removed.
            splash.iconView.animate().scaleX(1.15f).scaleY(1.15f).setDuration(300).setInterpolator(OvershootInterpolator()).start()
            splash.view.animate().alpha(0f).setDuration(300).setInterpolator(AccelerateInterpolator())
                .withEndAction { splash.remove() }.start()
        }
        super.onCreate(savedInstanceState)
        // Placeholder until Task 9 builds the real screens.
        setContent {
            EbbThemeFromRepo {
                Surface(Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayLarge)
                    }
                }
            }
        }
    }
}

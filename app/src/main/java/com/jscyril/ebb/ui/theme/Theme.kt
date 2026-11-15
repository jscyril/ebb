package com.jscyril.ebb.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jscyril.ebb.data.Accent
import com.jscyril.ebb.data.DarkMode
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.data.ThemePrefs

/** Ebb's theme: the chosen accent + dark mode, system font, rounded expressive shapes, expressive motion. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EbbTheme(prefs: ThemePrefs, content: @Composable () -> Unit) {
    val dark = when (prefs.dark) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK, DarkMode.AMOLED -> true
    }
    val amoled = prefs.dark == DarkMode.AMOLED
    val ctx = LocalContext.current
    val scheme = remember(prefs.accent, dark, amoled) {
        if (prefs.accent == Accent.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val d = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            if (amoled) d.amoled() else d
        } else Palette.scheme(prefs.accent, dark, amoled).toColorScheme(dark)
    }
    // enableEdgeToEdge picks bar icon colours from the system mode; follow the app's mode instead. Overlays have no Activity.
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        shapes = EbbShapes,
        typography = EbbTypography,
        content = content,
    )
}

/** [EbbTheme] driven by the saved theme prefs; used by the app and the overlays. */
@Composable
fun EbbThemeFromRepo(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // shortcut: first frame uses default prefs (system/dynamic) until DataStore emits, usually within one frame.
    val prefs by remember(ctx) { Repo.get(ctx).themeFlow }.collectAsState(initial = ThemePrefs())
    EbbTheme(prefs, content)
}

/** Big tabular numbers for the countdown and dashboard totals: digits don't jiggle as they change. */
object EbbType {
    val Countdown = TextStyle(
        fontWeight = FontWeight.Light, fontSize = 96.sp, lineHeight = 100.sp,
        letterSpacing = (-2).sp, fontFeatureSettings = "tnum",
    )
}

private val EbbTypography = Typography().run {
    fun TextStyle.numeric(spacing: Float) = copy(fontFeatureSettings = "tnum", letterSpacing = spacing.sp)
    copy(
        displayLarge = displayLarge.numeric(-1.5f),
        displayMedium = displayMedium.numeric(-1f),
        displaySmall = displaySmall.numeric(-0.5f),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Medium),
    )
}

private val EbbShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

internal fun ColorScheme.amoled(): ColorScheme {
    val a = Palette.AMOLED.map { Color(it) }
    return copy(
        background = a[0], surface = a[0], surfaceDim = a[0], surfaceContainerLowest = a[1],
        surfaceContainerLow = a[2], surfaceContainer = a[3], surfaceContainerHigh = a[4], surfaceContainerHighest = a[5],
    )
}

internal fun Palette.Scheme.toColorScheme(dark: Boolean): ColorScheme {
    val c = { v: Int -> Color(v) }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(primary.c), onPrimary = c(primary.on),
        primaryContainer = c(primary.container), onPrimaryContainer = c(primary.onContainer),
        inversePrimary = c(inversePrimary), surfaceTint = c(primary.c),
        secondary = c(secondary.c), onSecondary = c(secondary.on),
        secondaryContainer = c(secondary.container), onSecondaryContainer = c(secondary.onContainer),
        tertiary = c(tertiary.c), onTertiary = c(tertiary.on),
        tertiaryContainer = c(tertiary.container), onTertiaryContainer = c(tertiary.onContainer),
        background = c(n.surface), onBackground = c(n.onSurface),
        surface = c(n.surface), onSurface = c(n.onSurface),
        surfaceVariant = c(n.surfaceVariant), onSurfaceVariant = c(n.onSurfaceVariant),
        surfaceContainerLowest = c(n.containerLowest), surfaceContainerLow = c(n.containerLow),
        surfaceContainer = c(n.container), surfaceContainerHigh = c(n.containerHigh),
        surfaceContainerHighest = c(n.containerHighest),
        surfaceDim = c(n.surfaceDim), surfaceBright = c(n.surfaceBright),
        outline = c(n.outline), outlineVariant = c(n.outlineVariant),
        inverseSurface = c(n.inverseSurface), inverseOnSurface = c(n.inverseOnSurface),
    )
}

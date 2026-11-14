package com.jscyril.ebb.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProviders
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import com.jscyril.ebb.MainActivity
import com.jscyril.ebb.R
import com.jscyril.ebb.data.Accent
import com.jscyril.ebb.data.DarkMode
import com.jscyril.ebb.data.ThemePrefs
import com.jscyril.ebb.ui.app.formatDayTime
import com.jscyril.ebb.ui.app.hoursMinutes
import com.jscyril.ebb.ui.theme.Palette
import com.jscyril.ebb.ui.theme.amoled
import com.jscyril.ebb.ui.theme.toColorScheme
import java.util.Date

/** The app's accent + dark mode as Glance colours; null means the system's dynamic widget colours. */
fun widgetColors(ctx: Context, prefs: ThemePrefs): ColorProviders? {
    val dynamic = prefs.accent == Accent.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    if (dynamic && prefs.dark == DarkMode.SYSTEM) return null
    fun scheme(dark: Boolean) = if (dynamic) {
        (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)).let { if (prefs.dark == DarkMode.AMOLED) it.amoled() else it }
    } else Palette.scheme(prefs.accent, dark, prefs.dark == DarkMode.AMOLED).toColorScheme(dark)
    return when (prefs.dark) {
        DarkMode.SYSTEM -> ColorProviders(light = scheme(false), dark = scheme(true))
        DarkMode.LIGHT -> ColorProviders(scheme(false))
        DarkMode.DARK, DarkMode.AMOLED -> ColorProviders(scheme(true))
    }
}

@Composable
fun EbbGlanceTheme(colors: ColorProviders?, content: @Composable () -> Unit) =
    if (colors == null) GlanceTheme(content = content) else GlanceTheme(colors, content)

/** Rounded, themed, tap-to-open root. cornerRadius is a no-op before API 31, so older hosts get a rounded drawable. */
@Composable
fun widgetRoot(): GlanceModifier {
    val bg = GlanceTheme.colors.widgetBackground
    val base = GlanceModifier.fillMaxSize().appWidgetBackground()
    val shaped = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) base.background(bg).cornerRadius(24.dp)
    else base.background(ImageProvider(R.drawable.widget_bg), colorFilter = ColorFilter.tint(bg))
    return shaped.clickable(actionStartActivity<MainActivity>()).padding(12.dp)
}

/**
 * A progress ring: full track, then the arc from 12 o'clock with round caps. The widget draws it white
 * (track translucent) and tints it with the theme's primary, so it follows day/night without a re-render.
 */
fun ringBitmap(sizePx: Int, fraction: Float, trackArgb: Int, progressArgb: Int, strokePx: Float): Bitmap {
    val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = strokePx; strokeCap = Paint.Cap.ROUND }
    val h = strokePx / 2
    val r = RectF(h, h, sizePx - h, sizePx - h)
    p.color = trackArgb
    c.drawOval(r, p)
    val sweep = sweepDegrees(fraction)
    if (sweep > 0.5f) { p.color = progressArgb; c.drawArc(r, -90f, sweep, false, p) }
    return b
}

private const val RING_MAX_PX = 300
private const val TRACK = 0x3DFFFFFF // 24 % white: primary at low alpha once tinted

/** [ringBitmap] for a [size] dp ring on this display, capped at 300 px to keep the widget's parcel small. */
fun ringFor(ctx: Context, size: Dp, fraction: Float): Bitmap {
    val px = (size.value * ctx.resources.displayMetrics.density).toInt().coerceIn(1, RING_MAX_PX)
    return ringBitmap(px, fraction, TRACK, Palette.WHITE, px * 0.11f)
}

/** "42m", "1h", "1h 12m". */
fun shortDuration(ctx: Context, seconds: Long): String {
    val (h, m) = hoursMinutes(seconds)
    return when {
        h == 0L -> ctx.getString(R.string.dur_short_m, m)
        m == 0 -> ctx.getString(R.string.dur_short_h, h.toInt())
        else -> ctx.getString(R.string.dur_short_hm, h.toInt(), m)
    }
}

/** "1 hour 5 minutes" / "1 hour" / "42 minutes" for TalkBack. */
fun spokenDuration(ctx: Context, seconds: Long): String {
    val (h, m) = hoursMinutes(seconds)
    val r = ctx.resources
    val mins = r.getQuantityString(R.plurals.a11y_minutes, m, m)
    return when {
        h == 0L -> mins
        m == 0 -> r.getQuantityString(R.plurals.a11y_hours, h.toInt(), h.toInt())
        else -> r.getQuantityString(R.plurals.a11y_hours, h.toInt(), h.toInt()) + " " + mins
    }
}

/** "4:30 PM" today, "Tue 4:30 PM" otherwise: absolute only, the widget never counts down. */
fun clockText(ctx: Context, at: Long): String =
    if (DateUtils.isToday(at)) DateFormat.getTimeFormat(ctx).format(Date(at)) else formatDayTime(ctx, at)

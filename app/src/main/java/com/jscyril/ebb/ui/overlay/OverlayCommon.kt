package com.jscyril.ebb.ui.overlay

import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jscyril.ebb.R
import kotlinx.coroutines.delay
import java.util.Date

/** PiP / split-screen windows get the compact variant. */
internal fun BoxWithConstraintsScope.isCompact() = maxHeight < 320.dp || maxWidth < 280.dp

/** Time of day in the user's 12/24 h setting. */
@Composable
internal fun timeOfDay(at: Long): String {
    val ctx = LocalContext.current
    return remember(at) { DateFormat.getTimeFormat(ctx).format(Date(at)) }
}

/**
 * [start] (trusted time when shown) advanced by elapsed realtime, updated on each whole second.
 * Elapsed realtime, not the wall clock, so a changed system clock cannot make the countdown lie.
 */
@Composable
internal fun tickingNow(start: Long): State<Long> {
    val base = remember(start) { SystemClock.elapsedRealtime() }
    return produceState(start, start) {
        while (true) {
            val e = SystemClock.elapsedRealtime() - base
            value = start + e
            delay(1_000 - e % 1_000)
        }
    }
}

/** A round progress ring; [progress] 0..1 fills clockwise from the top. Read only while drawing, so it never recomposes. */
@Composable
internal fun Ring(progress: () -> Float, modifier: Modifier = Modifier, stroke: Dp = 10.dp, content: @Composable BoxScope.() -> Unit = {}) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val fill = MaterialTheme.colorScheme.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val tl = Offset(w / 2, w / 2)
            val sz = Size(size.width - w, size.height - w)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(w))
            val p = progress().coerceIn(0f, 1f)
            if (p > 0f) drawArc(fill, -90f, 360f * p, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** The gated app's icon (decorative: the name is always in text nearby), with an optional lock badge. */
@Composable
internal fun AppIcon(name: String, icon: ImageBitmap?, size: Dp, locked: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(size)) {
        val shape = RoundedCornerShape(size * 0.28f)
        if (icon != null) Image(icon, null, Modifier.fillMaxSize().clip(shape))
        else Box(Modifier.fillMaxSize().clip(shape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = cs.onSecondaryContainer)
        }
        if (locked) {
            val b = size * 0.42f
            Box(
                Modifier.align(Alignment.BottomEnd).offset(b * 0.2f, b * 0.2f).size(b)
                    .clip(CircleShape).background(cs.surface).padding(3.dp).clip(CircleShape).background(cs.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(b * 0.5f), tint = cs.onPrimary) }
        }
    }
}

/** Small-window variant: icon, one status line, Go home. */
@Composable
internal fun CompactOverlay(name: String, icon: ImageBitmap?, status: String, onGoHome: () -> Unit, locked: Boolean = true) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(contentAlignment = Alignment.Center) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(name, icon, 40.dp, locked)
                Text(status, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FilledTonalIconButton(onGoHome, Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_home), stringResource(R.string.overlay_go_home))
                }
            }
        }
    }
}

/** Full screen background that fills behind the system bars. */
@Composable
internal fun FullBleed(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) =
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), content = content)

@Composable
internal fun Overlay(compact: @Composable () -> Unit, full: @Composable BoxWithConstraintsScope.() -> Unit) =
    BoxWithConstraints(Modifier.fillMaxSize()) { if (isCompact()) compact() else full() }

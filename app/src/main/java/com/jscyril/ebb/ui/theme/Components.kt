package com.jscyril.ebb.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.contentColorFor
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jscyril.ebb.R

private val CardShape = RoundedCornerShape(28.dp)

/** Springs the element down to [pressed] scale while [source] reports a press. */
@Composable
private fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.96f, rest: Float = 1f): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (isPressed) pressed else rest, EbbMotion.Fast(), label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}

/** Primary call to action: a tall pill with a tactile spring press. */
@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp).pressScale(source),
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
        interactionSource = source,
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

/** Elevated, generously rounded card. Clickable (with press spring) when [onClick] is given. */
@Composable
fun SpringCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, container: Color = Color.Unspecified, content: @Composable ColumnScope.() -> Unit) {
    val colors = if (container == Color.Unspecified) CardDefaults.elevatedCardColors()
    else CardDefaults.elevatedCardColors(containerColor = container, contentColor = contentColorFor(container))
    if (onClick == null) {
        ElevatedCard(modifier, shape = CardShape, colors = colors) { Column(Modifier.fillMaxWidth().padding(20.dp), content = content) }
    } else {
        val source = remember { MutableInteractionSource() }
        ElevatedCard(onClick, modifier.heightIn(min = 48.dp).pressScale(source, pressed = 0.97f), shape = CardShape, colors = colors, interactionSource = source) {
            Column(Modifier.fillMaxWidth().padding(20.dp), content = content)
        }
    }
}

/** One session tier in the prompt: "N min" and "then locked X h". Selected state springs colour + scale. */
@Composable
fun TierChip(minutes: Int, lockMinutes: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.primaryContainer else cs.surfaceContainerHigh, EbbMotion.Standard(), label = "tierBg")
    val fg by animateColorAsState(if (selected) cs.onPrimaryContainer else cs.onSurface, EbbMotion.Standard(), label = "tierFg")
    val dot by animateFloatAsState(if (selected) 1f else 0f, EbbMotion.Fast(), label = "tierDot")
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = 72.dp).pressScale(source, rest = if (selected) 1f else 0.97f)
            .semantics { role = Role.RadioButton },
        shape = MaterialTheme.shapes.large,
        color = bg,
        contentColor = fg,
        interactionSource = source,
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.tier_minutes, minutes), style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"))
                Text(lockText(lockMinutes), style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.78f))
            }
            // Decorative expressive mark that blooms in when selected; state is announced by the selectable semantics.
            Box(Modifier.size(20.dp).graphicsLayer { scaleX = dot; scaleY = dot; rotationZ = 90f * (1 - dot) }
                .clip(MaterialShapes.Cookie9Sided.toShape()).background(cs.primary))
        }
    }
}

@Composable
private fun lockText(lockMinutes: Int): String =
    if (lockMinutes % 60 == 0) stringResource(R.string.tier_locked_hours, lockMinutes / 60)
    else stringResource(R.string.tier_locked_minutes, lockMinutes)

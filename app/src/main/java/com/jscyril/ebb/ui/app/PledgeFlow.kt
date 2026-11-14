package com.jscyril.ebb.ui.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jscyril.ebb.R
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.guard.Pledge
import com.jscyril.ebb.guard.PledgeRecord
import com.jscyril.ebb.service.TrustedNow
import com.jscyril.ebb.ui.theme.BigButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val PLEDGE_DAYS = listOf(7, 30, 90)

/**
 * Choose a pledge length, generate, show the passcode once, confirm it was handed over, then save the hash.
 * The plaintext lives only in this composable's `remember`: leaving the flow drops it; it is never persisted or logged.
 * [allowNone] adds "No pledge" (onboarding). [onDone] runs after saving, or with false when "No pledge" was chosen.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PledgeFlow(allowNone: Boolean, onDone: (pledged: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var days by remember { mutableStateOf<Int?>(30) }
    var generating by remember { mutableStateOf(false) }
    var made by remember { mutableStateOf<Pair<PledgeRecord, String>?>(null) }
    var handedOver by remember { mutableStateOf(false) }

    AnimatedContent(made != null, modifier, label = "pledge") { showing ->
        val m = made
        if (!showing || m == null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.selectableGroup()) {
                PLEDGE_DAYS.forEach { d ->
                    ChoiceRow(pluralStringResource(R.plurals.pledge_days, d, d), days == d) { days = d }
                }
                if (allowNone) ChoiceRow(stringResource(R.string.pledge_none), days == null) { days = null }
            }
            Text(
                stringResource(if (days == null) R.string.pledge_none_warning else R.string.pledge_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = if (days == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            if (generating) LoadingIndicator(Modifier.align(Alignment.CenterHorizontally))
            else BigButton(
                stringResource(if (days == null) R.string.pledge_continue_without else R.string.pledge_create),
                {
                    val d = days
                    if (d == null) onDone(false)
                    else scope.launch {
                        generating = true
                        val now = TrustedNow.peek(ctx)
                        made = withContext(Dispatchers.Default) { Pledge.create(d, now) } // PBKDF2 is slow
                        generating = false
                    }
                },
                Modifier.fillMaxWidth(),
            )
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val (record, code) = m
            Text(stringResource(R.string.pledge_code_title), style = MaterialTheme.typography.titleLarge)
            Body(stringResource(R.string.pledge_code_body))
            val grouped = formatGroupedPasscode(code)
            val shareText = stringResource(R.string.pledge_share_text, grouped)
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                Text(
                    grouped,
                    Modifier.fillMaxWidth().padding(20.dp).semantics {
                        // Read it out group by group, letter by letter.
                        contentDescription = grouped.toCharArray().joinToString(" ")
                    },
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp),
                    textAlign = TextAlign.Center,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ copySensitive(ctx, grouped) }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_copy), null); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.pledge_copy))
                }
                FilledTonalButton({
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, shareText)
                    ctx.startActivity(Intent.createChooser(send, null))
                }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_share), null); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.pledge_share))
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(handedOver, role = Role.Checkbox) { handedOver = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(handedOver, null)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.pledge_handed_over), style = MaterialTheme.typography.bodyLarge)
            }
            BigButton(
                stringResource(R.string.pledge_lock_in),
                {
                    scope.launch {
                        // A pledge starts clean: no queued loosening and no disable request survive into it,
                        // or an early end by passcode could skip the 48 h wait.
                        val repo = Repo.get(ctx)
                        repo.savePledge(record); repo.savePending(null); repo.saveDisableRequestedAt(null)
                        made = null; onDone(true)
                    }
                },
                Modifier.fillMaxWidth(), enabled = handedOver,
            )
        }
    }
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) =
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null)
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }

/** Copies with the "sensitive" flag, so Android 13+ hides it from the clipboard preview. */
private fun copySensitive(ctx: android.content.Context, text: String) {
    val clip = ClipData.newPlainText(ctx.getString(R.string.pledge_clip_label), text)
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

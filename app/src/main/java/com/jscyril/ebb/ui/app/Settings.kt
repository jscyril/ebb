package com.jscyril.ebb.ui.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jscyril.ebb.R
import com.jscyril.ebb.data.Accent
import com.jscyril.ebb.data.DarkMode
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.data.ThemePrefs
import com.jscyril.ebb.guard.Admin
import com.jscyril.ebb.guard.DisableRequest
import com.jscyril.ebb.guard.Pledge
import com.jscyril.ebb.service.ServiceState
import com.jscyril.ebb.service.TrustedNow
import com.jscyril.ebb.widget.WidgetRefresher
import com.jscyril.ebb.ui.theme.BigButton
import com.jscyril.ebb.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsVm(private val app: Application) : AndroidViewModel(app) {
    private val repo = Repo.get(app)
    private val sub = SharingStarted.WhileSubscribed(5_000)
    val theme = repo.themeFlow.stateIn(viewModelScope, sub, ThemePrefs())
    val rules = repo.rulesFlow.stateIn(viewModelScope, sub, null)
    val pending = repo.pendingFlow.stateIn(viewModelScope, sub, null)
    val pledge = repo.pledgeFlow.stateIn(viewModelScope, sub, null)
    val disableReq = repo.disableRequestedAtFlow.stateIn(viewModelScope, sub, null)
    val protectionOn = repo.protectionEnabledFlow.stateIn(viewModelScope, sub, true)
    val now = trustedTicker(app, 15_000).stateIn(viewModelScope, sub, null)

    /** Last failed passcode try, elapsed realtime; memory only (ruling), enough to slow guessing by hand. */
    private val _lastFail = MutableStateFlow<Long?>(null)
    val lastFail = _lastFail.asStateFlow()
    private val _checking = MutableStateFlow(false)
    val checking = _checking.asStateFlow()

    fun setTheme(t: ThemePrefs) = viewModelScope.launch { repo.saveTheme(t); WidgetRefresher.requestUpdate(app) }

    fun submitPasses(n: Int, onMessage: (String) -> Unit) = viewModelScope.launch {
        val r = repo.rules()
        onMessage(submitMessage(app, submitRules(app, r.copy(passesPerMonth = n))))
    }

    /** The passcode ends the pledge only; the 48 h disable wait still applies afterwards (ruling). */
    fun endPledge(code: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        if (_checking.value || !passcodeAttemptAllowed(_lastFail.value, SystemClock.elapsedRealtime())) return@launch
        val p = repo.pledge() ?: return@launch onResult(true)
        _checking.value = true
        val ok = withContext(Dispatchers.Default) { Pledge.verify(p, code) } // PBKDF2 is slow
        _checking.value = false
        if (ok) { repo.savePledge(null); repo.saveDisableRequestedAt(null); _lastFail.value = null } else _lastFail.value = SystemClock.elapsedRealtime()
        onResult(ok)
    }

    fun requestDisable() = viewModelScope.launch {
        val now = TrustedNow.peek(app)
        if (!Pledge.isActive(repo.pledge(), now)) repo.saveDisableRequestedAt(now)
    }

    fun cancelDisable() = viewModelScope.launch { repo.saveDisableRequestedAt(null) }

    /** Final step; re-checks the wait with fresh trusted time. The request is cleared so a later disable waits again. */
    fun turnOff(phrase: String) = viewModelScope.launch {
        if (!disablePhraseMatches(phrase)) return@launch
        if (!DisableRequest.canDisable(repo.pledge(), repo.disableRequestedAt(), TrustedNow.peek(app))) return@launch
        repo.setProtectionEnabled(false)
        repo.saveDisableRequestedAt(null)
        Admin.deactivate(app)
    }

    /** Turning protection on is tightening: immediate. */
    fun turnOn() = viewModelScope.launch { repo.setProtectionEnabled(true) }
}

@Composable
fun SettingsTab(contentPadding: PaddingValues, onMessage: (String) -> Unit, vm: SettingsVm = viewModel()) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val pledge by vm.pledge.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val pledgeActive = now?.let { Pledge.isActive(pledge, it) } ?: (pledge != null)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_settings), Modifier.padding(vertical = 8.dp).semantics { heading() }, style = MaterialTheme.typography.displaySmall)
        Card { AppearanceSection(theme, vm::setTheme) }
        rules?.let { r -> Card { PassesSection(r.passesPerMonth, pledgeActive, vm, onMessage) } }
        Card { PledgeSection(vm, pledgeActive) }
        Card { ProtectionSection(vm, pledgeActive) }
        Card { AboutSection() }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) =
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(20.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }

@Composable
private fun AppearanceSection(theme: ThemePrefs, onChange: (ThemePrefs) -> Unit) {
    SectionTitle(stringResource(R.string.set_appearance), Modifier.semantics { heading() })
    val modes = listOf(DarkMode.SYSTEM to R.string.dark_system, DarkMode.LIGHT to R.string.dark_light, DarkMode.DARK to R.string.dark_dark, DarkMode.AMOLED to R.string.dark_amoled)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { i, (mode, label) ->
            SegmentedButton(
                theme.dark == mode, { onChange(theme.copy(dark = mode)) },
                SegmentedButtonDefaults.itemShape(i, modes.size), Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(label), maxLines = 1) }
        }
    }
    Text(stringResource(R.string.set_accent), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
    val dark = when (theme.dark) { DarkMode.SYSTEM -> isSystemInDarkTheme(); DarkMode.LIGHT -> false; else -> true }
    val ctx = LocalContext.current
    val accents = Accent.entries.filter { it != Accent.DYNAMIC || Build.VERSION.SDK_INT >= 31 }
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceBetween) {
        accents.forEach { a ->
            val color = remember(a, dark) {
                if (a == Accent.DYNAMIC && Build.VERSION.SDK_INT >= 31) dynamicLightColorScheme(ctx).primary
                else Color(Palette.scheme(a, dark, false).primary.c)
            }
            Swatch(color, theme.accent == a, accentName(a)) { onChange(theme.copy(accent = a)) }
        }
    }
}

@Composable
private fun accentName(a: Accent) = stringResource(
    when (a) {
        Accent.DYNAMIC -> R.string.accent_dynamic; Accent.TIDE -> R.string.accent_tide; Accent.CORAL -> R.string.accent_coral
        Accent.MOSS -> R.string.accent_moss; Accent.DUSK -> R.string.accent_dusk; Accent.SAND -> R.string.accent_sand
    },
)

/** Round swatch that turns into a scalloped cookie when picked. */
@Composable
private fun Swatch(color: Color, selected: Boolean, name: String, onClick: () -> Unit) {
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    val shape: Shape = if (selected) cookie else CircleShape
    Box(
        Modifier.size(48.dp).clip(shape).background(color)
            .selectable(selected, role = Role.RadioButton, onClick = onClick).semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(painterResource(R.drawable.ic_check), null, tint = Color.White)
    }
}

@Composable
private fun PassesSection(current: Int, pledgeActive: Boolean, vm: SettingsVm, onMessage: (String) -> Unit) {
    SectionTitle(stringResource(R.string.set_passes), Modifier.semantics { heading() })
    var draft by remember(current) { mutableStateOf(current) }
    Stepper(stringResource(R.string.passes_label), draft, { draft = it }, 0, 4, 1, { it.toString() }, upEnabled = !pledgeActive || draft < current)
    Body(stringResource(if (pledgeActive) R.string.passes_pledge_note else R.string.passes_note))
    if (draft != current) {
        BigButton(
            stringResource(if (draft > current) R.string.rules_save_wait else R.string.rules_save),
            { vm.submitPasses(draft, onMessage) }, Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PledgeSection(vm: SettingsVm, pledgeActive: Boolean) {
    val pledge by vm.pledge.collectAsStateWithLifecycle()
    var starting by rememberSaveable { mutableStateOf(false) }
    var ending by rememberSaveable { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.set_pledge), Modifier.semantics { heading() })
    val p = pledge
    if (pledgeActive && p != null) {
        Text(stringResource(R.string.pledge_locked_until, dateText(p.endsAt)), style = MaterialTheme.typography.titleMedium)
        Body(stringResource(R.string.pledge_active_note))
        OutlinedButton({ ending = true }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pledge_end_early)) }
    } else {
        Body(stringResource(R.string.pledge_none_active))
        val req by vm.disableReq.collectAsStateWithLifecycle()
        if (req != null) Body(stringResource(R.string.pledge_cancels_request))
        OutlinedButton({ starting = true }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pledge_start)) }
    }
    if (starting) {
        Dialog({ starting = false }, DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.pledge_start), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                        IconButton({ starting = false }, Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cd_close)) }
                    }
                    Body(stringResource(R.string.onb_pledge_body))
                    PledgeFlow(allowNone = false, onDone = { starting = false })
                }
            }
        }
    }
    if (ending) EndPledgeDialog(vm) { ending = false }
}

@Composable
private fun EndPledgeDialog(vm: SettingsVm, onClose: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val lastFail by vm.lastFail.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    // Seconds until the next try is allowed; ticks only while a lockout is running.
    val wait by produceState(0, lastFail) {
        while (true) {
            val left = lastFail?.let { (it + PASSCODE_RETRY_MS - SystemClock.elapsedRealtime() + 999) / 1000 } ?: 0
            value = left.toInt().coerceAtLeast(0)
            if (value == 0) break
            delay(1_000)
        }
    }
    AlertDialog(
        onClose,
        confirmButton = {
            TextButton(
                { vm.endPledge(code) { ok -> if (ok) onClose() else { failed = true; code = "" } } },
                Modifier.heightIn(min = 48.dp), enabled = wait == 0 && !checking && code.isNotBlank(),
            ) { Text(stringResource(R.string.pledge_end_confirm)) }
        },
        dismissButton = { TextButton(onClose, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.emergency_cancel)) } },
        title = { Text(stringResource(R.string.pledge_end_early)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Body(stringResource(R.string.pledge_end_body))
                OutlinedTextField(
                    code, { code = it.uppercase() }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.pledge_passcode)) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    isError = failed, enabled = wait == 0 && !checking,
                    supportingText = {
                        when {
                            wait > 0 -> Text(pluralStringResource(R.plurals.pledge_wrong_wait, wait, wait))
                            failed -> Text(stringResource(R.string.pledge_wrong))
                        }
                    },
                )
            }
        },
    )
}

@Composable
private fun ProtectionSection(vm: SettingsVm, pledgeActive: Boolean) {
    val ctx = LocalContext.current
    val protectionOn by vm.protectionOn.collectAsStateWithLifecycle()
    val req by vm.disableReq.collectAsStateWithLifecycle()
    val pledge by vm.pledge.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    var serviceOn by remember { mutableStateOf(ServiceState.isEnabled(ctx)) }
    var adminOn by remember { mutableStateOf(Admin.isActive(ctx)) }
    LifecycleResumeEffect(Unit) {
        serviceOn = ServiceState.isEnabled(ctx); adminOn = Admin.isActive(ctx)
        onPauseOrDispose {}
    }
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { adminOn = Admin.isActive(ctx) }
    SectionTitle(stringResource(R.string.set_protection), Modifier.semantics { heading() })
    val t = now ?: return
    val r = req
    val p = pledge
    val stage = when {
        !protectionOn -> 0
        pledgeActive -> 1
        r == null || (p != null && r < p.endsAt) -> 2 // a request filed during a pledge never counts
        DisableRequest.isExpired(r, t) -> 5
        !DisableRequest.canDisable(p, r, t) -> 3
        else -> 4
    }
    AnimatedContent(stage, label = "protection") { s ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (s) {
                0 -> {
                    Text(stringResource(R.string.prot_off), style = MaterialTheme.typography.titleMedium)
                    if (serviceOn) {
                        Body(stringResource(R.string.prot_off_service_still_on))
                        OutlinedButton({ ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.onb_a11y_action))
                        }
                    }
                    BigButton(stringResource(R.string.prot_turn_on), {
                        vm.turnOn()
                        if (!adminOn) adminLauncher.launch(Admin.enableIntent(ctx)) // Home's banner covers a switched-off service
                    }, Modifier.fillMaxWidth())
                }
                1 -> {
                    Body(stringResource(R.string.prot_pledge_blocks))
                    OutlinedButton({}, Modifier.heightIn(min = 48.dp), enabled = false) { Text(stringResource(R.string.prot_turn_off)) }
                }
                2 -> {
                    Body(stringResource(R.string.prot_explain))
                    OutlinedButton(vm::requestDisable, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.prot_request)) }
                }
                3 -> {
                    val at = (r ?: t) + DisableRequest.WAIT_MS
                    Text(stringResource(R.string.prot_waiting, durationText(at - t)), style = MaterialTheme.typography.titleMedium)
                    Body(stringResource(R.string.prot_waiting_at, dayAndTime(at)))
                    TextButton(vm::cancelDisable, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.prot_cancel_request)) }
                }
                5 -> {
                    Text(stringResource(R.string.prot_expired), style = MaterialTheme.typography.titleMedium)
                    Body(stringResource(R.string.prot_expired_body))
                    OutlinedButton(vm::requestDisable, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.prot_request_again)) }
                }
                else -> {
                    var phrase by remember { mutableStateOf("") }
                    Body(stringResource(R.string.prot_type, DISABLE_PHRASE))
                    OutlinedTextField(
                        phrase, { phrase = it }, Modifier.fillMaxWidth().imePadding(),
                        label = { Text(stringResource(R.string.emergency_field)) },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done), singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(vm::cancelDisable, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.prot_cancel_request)) }
                        Spacer(Modifier.weight(1f))
                        Button(
                            { vm.turnOff(phrase) }, Modifier.heightIn(min = 48.dp), enabled = disablePhraseMatches(phrase),
                            colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError),
                        ) { Text(stringResource(R.string.prot_turn_off)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSection() {
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
    SectionTitle(stringResource(R.string.set_about), Modifier.semantics { heading() })
    Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyLarge)
    TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))) }, Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.about_source))
    }
}

private const val GITHUB_URL = "https://github.com/jscyril/ebb"

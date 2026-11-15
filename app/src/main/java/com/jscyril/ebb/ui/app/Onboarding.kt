package com.jscyril.ebb.ui.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.graphics.shapes.RoundedPolygon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jscyril.ebb.R
import com.jscyril.ebb.data.Repo
import com.jscyril.ebb.engine.AppRule
import com.jscyril.ebb.engine.Defaults
import com.jscyril.ebb.engine.Tier
import com.jscyril.ebb.guard.Admin
import com.jscyril.ebb.guard.Rules
import com.jscyril.ebb.service.ServiceState
import com.jscyril.ebb.ui.theme.BigButton
import com.jscyril.ebb.ui.theme.rememberReducedMotion
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Ruling: before onboarding is done nothing is guarded, so rules are saved directly (no cooldown), and survive process death. */
class OnboardingVm(private val app: Application) : AndroidViewModel(app) {
    private val repo = Repo.get(app)
    val rules = repo.rulesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val pledge = repo.pledgeFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Atomic in DataStore, so quick toggles never overwrite each other. */
    private fun edit(f: (Rules) -> Rules) = viewModelScope.launch { repo.updateRules(f); rulesChanged(app) }

    /** One tier list + cap for every app picked during onboarding; per-app tweaks happen on the Rules tab. */
    fun template(r: Rules?) = r?.apps?.values?.firstOrNull() ?: AppRule()

    fun toggle(pkg: String) = edit { r ->
        if (pkg in r.apps) r.copy(apps = r.apps - pkg)
        else r.copy(apps = r.apps + (pkg to template(r).copy(pkg = pkg, name = Apps.label(app, pkg), domains = null)))
    }

    fun setTemplate(tiers: List<Tier>, cap: Int) = edit { r -> r.copy(apps = r.apps.mapValues { it.value.copy(tiers = tiers, dailyCapMin = cap) }) }
    fun setPasses(n: Int) = edit { it.copy(passesPerMonth = n) }

    fun finish() = viewModelScope.launch {
        // Sorted so the prompt reads shortest first.
        repo.updateRules { r -> r.copy(apps = r.apps.mapValues { it.value.copy(tiers = it.value.tiers.sortedBy { t -> t.sessionMin }) }) }
        repo.savePending(null)
        repo.setOnboardingDone()
        rulesChanged(app)
    }
}

private enum class Step { WELCOME, APPS, TIERS, PASSES, ADMIN, RESTRICTED, ACCESSIBILITY, PLEDGE, DONE }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Onboarding(vm: OnboardingVm = viewModel()) {
    val ctx = LocalContext.current
    // "Allow restricted settings" only exists on Android 13+; accessibility stays the last permission step.
    val steps = remember { Step.entries.filter { it != Step.RESTRICTED || Build.VERSION.SDK_INT >= 33 } }
    val pager = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val pledge by vm.pledge.collectAsStateWithLifecycle()
    var adminOn by remember { mutableStateOf(Admin.isActive(ctx)) }
    var serviceOn by remember { mutableStateOf(ServiceState.isEnabled(ctx)) }
    LifecycleResumeEffect(Unit) {
        adminOn = Admin.isActive(ctx); serviceOn = ServiceState.isEnabled(ctx)
        onPauseOrDispose {}
    }
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { adminOn = Admin.isActive(ctx) }

    fun go(delta: Int) = scope.launch {
        val to = (pager.currentPage + delta).coerceIn(0, steps.lastIndex)
        if (reduced) pager.scrollToPage(to) else pager.animateScrollToPage(to)
    }
    BackHandler(pager.currentPage > 0) { go(-1) }

    val step = steps[pager.currentPage]
    val r = rules
    val template = vm.template(r)
    val canContinue = when (step) {
        Step.APPS -> !r?.apps.isNullOrEmpty()
        Step.TIERS -> validateTiers(template.tiers).isEmpty()
        Step.ADMIN -> adminOn
        Step.ACCESSIBILITY -> serviceOn
        Step.DONE -> adminOn && serviceOn // either may have been switched off since its step
        else -> true
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            val progress by animateFloatAsState((pager.currentPage + 1f) / steps.size, label = "progress")
            val stepDesc = stringResource(R.string.onb_step_of, pager.currentPage + 1, steps.size)
            LinearWavyProgressIndicator(
                { progress },
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).semantics { this.contentDescription = stepDesc },
            )
            HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = false, key = { steps[it] }) { page ->
                when (steps[page]) {
                    Step.WELCOME -> Page(MaterialShapes.Cookie9Sided, R.string.onb_welcome_title, R.string.onb_welcome_body) {
                        Text(stringResource(R.string.onb_welcome_honest), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Step.APPS -> Page(null, R.string.onb_apps_title, R.string.onb_apps_body, scroll = false) {
                        AppPicker(r?.apps?.keys.orEmpty(), vm::toggle, Modifier.weight(1f))
                    }
                    Step.TIERS -> Page(MaterialShapes.Clover4Leaf, R.string.onb_tiers_title, R.string.onb_tiers_body, art = 120.dp) {
                        RuleEditor(template.tiers, template.dailyCapMin, vm::setTemplate)
                    }
                    Step.PASSES -> Page(MaterialShapes.Heart, R.string.onb_passes_title, R.string.onb_passes_body) {
                        Stepper(
                            stringResource(R.string.passes_label), r?.passesPerMonth ?: Defaults.PASSES_PER_MONTH, vm::setPasses, 0, 4, 1,
                            { it.toString() },
                        )
                    }
                    Step.ADMIN -> Page(MaterialShapes.Gem, R.string.onb_admin_title, R.string.onb_admin_body) {
                        PermissionState(adminOn, R.string.onb_admin_on, R.string.onb_admin_off)
                        if (!adminOn) OutlinedButton({ adminLauncher.launch(Admin.enableIntent(ctx)) }, Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.onb_admin_action))
                        }
                    }
                    Step.RESTRICTED -> Page(MaterialShapes.Pentagon, R.string.onb_restricted_title, R.string.onb_restricted_body) {
                        listOf(R.string.onb_restricted_1, R.string.onb_restricted_2, R.string.onb_restricted_3, R.string.onb_restricted_4)
                            .forEachIndexed { i, s -> Text(stringResource(R.string.numbered, i + 1, stringResource(s)), style = MaterialTheme.typography.bodyLarge) }
                        OutlinedButton({
                            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                        }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.onb_restricted_action)) }
                        Body(stringResource(R.string.onb_restricted_optional))
                    }
                    Step.ACCESSIBILITY -> Page(MaterialShapes.Sunny, R.string.onb_a11y_title, R.string.onb_a11y_body) {
                        PermissionState(serviceOn, R.string.onb_a11y_on, R.string.onb_a11y_off)
                        if (!serviceOn) OutlinedButton({ ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.onb_a11y_action))
                        }
                    }
                    Step.PLEDGE -> Page(MaterialShapes.Burst, R.string.onb_pledge_title, R.string.onb_pledge_body, art = 120.dp) {
                        val p = pledge
                        if (p != null) Text(stringResource(R.string.pledge_locked_until, dateText(p.endsAt)), style = MaterialTheme.typography.titleMedium)
                        else PledgeFlow(allowNone = true, onDone = { go(1) })
                    }
                    Step.DONE -> Page(MaterialShapes.SoftBurst, R.string.onb_done_title, R.string.onb_done_body) {
                        if (!adminOn || !serviceOn) Text(stringResource(R.string.onb_done_missing), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (pager.currentPage > 0) TextButton({ go(-1) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.onb_back)) }
                else Spacer(Modifier.size(1.dp))
                // The pledge page has its own actions until a pledge exists.
                AnimatedContent(step, label = "cta") { s ->
                    when {
                        s == Step.PLEDGE && pledge == null -> Spacer(Modifier.size(1.dp))
                        s == Step.DONE -> BigButton(stringResource(R.string.onb_start), { vm.finish() }, enabled = canContinue)
                        else -> BigButton(stringResource(R.string.onb_continue), { go(1) }, enabled = canContinue)
                    }
                }
            }
        }
    }
}

/** One onboarding page: illustration, heading, body, then [content]. */
@Composable
private fun Page(
    shape: RoundedPolygon?, title: Int, body: Int, art: Dp = 180.dp, scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = Modifier.fillMaxSize().padding(horizontal = 24.dp)
    Column(
        if (scroll) m.verticalScroll(rememberScrollState()) else m,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (shape != null) Illustration(shape, art, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp))
        Text(stringResource(title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(4.dp))
        content()
        Spacer(Modifier.size(16.dp))
    }
}

/** Decorative: a slowly turning expressive shape, a small companion shape and the Ebb mark. No bitmaps. */
@Composable
fun Illustration(shape: RoundedPolygon, size: Dp, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    val turn = if (reduced) null else rememberInfiniteTransition(label = "art")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(40_000, easing = LinearEasing)), label = "turn")
    val big = shape.toShape()
    val small = MaterialShapes.Cookie4Sided.toShape()
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = turn?.value ?: 0f }.clip(big).background(cs.primaryContainer))
        Box(
            Modifier.size(size * 0.28f).align(Alignment.TopEnd).offset(x = size * 0.04f)
                .graphicsLayer { rotationZ = -(turn?.value ?: 0f) * 2 }.clip(small).background(cs.tertiaryContainer),
        )
        Image(
            painterResource(R.drawable.splash_icon_static), null, Modifier.size(size * 0.62f),
            colorFilter = ColorFilter.tint(cs.onPrimaryContainer),
        )
    }
}

@Composable
private fun PermissionState(on: Boolean, onText: Int, offText: Int) {
    val cs = MaterialTheme.colorScheme
    AnimatedContent(on, label = "perm") { isOn ->
        Surface(color = if (isOn) cs.primaryContainer else cs.errorContainer, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(if (isOn) R.drawable.ic_check else R.drawable.ic_warning), null, tint = if (isOn) cs.onPrimaryContainer else cs.onErrorContainer)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(if (isOn) onText else offText), color = if (isOn) cs.onPrimaryContainer else cs.onErrorContainer, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}


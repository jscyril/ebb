package com.jscyril.ebb.data

import com.jscyril.ebb.BuildConfig

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import com.jscyril.ebb.engine.AppState
import com.jscyril.ebb.engine.Defaults
import com.jscyril.ebb.guard.MatureAction
import com.jscyril.ebb.guard.Pending
import com.jscyril.ebb.guard.Pledge
import com.jscyril.ebb.guard.PledgeRecord
import com.jscyril.ebb.guard.Rules
import com.jscyril.ebb.guard.matureAction
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.ZoneId
import com.jscyril.ebb.engine.AppNames

class Repo private constructor(ctx: Context) {
    private val ds = ctx.applicationContext.ds
    private val dao = Room.databaseBuilder(ctx.applicationContext, EbbDb::class.java, "ebb.db").addMigrations(MIGRATION_1_2).build().usage()

    companion object {
        @Volatile private var inst: Repo? = null
        fun get(ctx: Context): Repo = inst ?: synchronized(this) { inst ?: Repo(ctx).also { inst = it } }
        private const val DAY_MS = 86_400_000L
        private val EMPTY_RULES = Rules(emptyMap(), Defaults.PASSES_PER_MONTH)
        private val STATES_SER = MapSerializer(String.serializer(), AppState.serializer())
        private val LONGS_SER = ListSerializer(Long.serializer())
        fun emptyState() = AppState(0, 0, null, null, null, null)
    }

    // ---- DataStore helpers
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Decodes a stored blob; on corruption keeps a copy under `<key>_corrupt` and returns [default]. */
    private fun <T> Preferences.dec(key: Preferences.Key<String>, ser: KSerializer<T>, default: T): T =
        decodeOr(this[key], ser, default) { raw ->
            scope.launch { ds.edit { it[stringPreferencesKey(key.name + "_corrupt")] = raw } }
        }

    private suspend fun <T> put(key: Preferences.Key<String>, v: T?, enc: (T) -> String) {
        ds.edit { if (v == null) it.remove(key) else it[key] = enc(v) }
    }

    // ---- zone (pinned on first access so a device zone change can't mint extra passes)
    @Volatile private var zoneCache: ZoneId? = null
    suspend fun zone(): ZoneId = zoneCache ?: ds.edit {
        if (it[Keys.ZONE] == null) it[Keys.ZONE] = ZoneId.systemDefault().id
    }[Keys.ZONE]!!.let(ZoneId::of).also { zoneCache = it }

    // ---- rules
    val rulesFlow: Flow<Rules> = ds.data.map { it.dec(Keys.RULES, Rules.serializer(), EMPTY_RULES).also { r -> AppNames.remember(r.apps.values) } }
    suspend fun rules() = rulesFlow.first()
    suspend fun saveRules(r: Rules) = put(Keys.RULES, r) { json.encodeToString(Rules.serializer(), it) }

    /** Atomic read-modify-write of the rules, so rapid edits never overwrite each other. */
    suspend fun updateRules(f: (Rules) -> Rules) {
        ds.edit { p -> p[Keys.RULES] = json.encodeToString(Rules.serializer(), f(p.dec(Keys.RULES, Rules.serializer(), EMPTY_RULES))) }
    }

    // ---- per-app state
    val statesFlow: Flow<Map<String, AppState>> = ds.data.map { it.dec(Keys.STATES, STATES_SER, emptyMap()) }
    suspend fun state(pkg: String) = statesFlow.first()[pkg] ?: emptyState()
    suspend fun saveState(pkg: String, s: AppState) { updateState(pkg) { s } }

    /** Atomic read-modify-write of one app's state. */
    suspend fun updateState(pkg: String, f: (AppState) -> AppState): AppState {
        var out = emptyState()
        ds.edit { p ->
            val m = p.dec(Keys.STATES, STATES_SER, emptyMap())
            out = f(m[pkg] ?: emptyState())
            p[Keys.STATES] = json.encodeToString(STATES_SER, m + (pkg to out))
        }
        return out
    }

    // ---- pledge / pending / disable request
    val pledgeFlow: Flow<PledgeRecord?> = ds.data.map { p -> p[Keys.PLEDGE]?.let { p.dec(Keys.PLEDGE, PledgeDto.serializer().nullable, null) }?.record }
    suspend fun pledge(): PledgeRecord? = pledgeFlow.first()
    suspend fun savePledge(r: PledgeRecord?) =
        put(Keys.PLEDGE, r) { json.encodeToString(PledgeDto.serializer(), PledgeDto.of(it)) }

    val pendingFlow: Flow<Pending?> = ds.data.map { it[Keys.PENDING]?.let { _ -> it.dec(Keys.PENDING, Pending.serializer().nullable, null) } }
    suspend fun pending() = pendingFlow.first()
    suspend fun savePending(p: Pending?) = put(Keys.PENDING, p) { json.encodeToString(Pending.serializer(), it) }

    /**
     * Applies or drops a due pending change ([matureAction]) in one atomic edit, so it can't overwrite a rules write
     * made in between. Returns the rules afterwards and what was done.
     */
    suspend fun maturePending(now: Long): Pair<Rules, MatureAction> {
        if (pending() == null) return rules() to MatureAction.Keep // fast path: the service calls this on every event
        var out = EMPTY_RULES
        var action = MatureAction.Keep
        ds.edit { p ->
            out = p.dec(Keys.RULES, Rules.serializer(), EMPTY_RULES)
            val pend = p[Keys.PENDING]?.let { p.dec(Keys.PENDING, Pending.serializer().nullable, null) } ?: return@edit
            val pledge = p[Keys.PLEDGE]?.let { p.dec(Keys.PLEDGE, PledgeDto.serializer().nullable, null) }?.record
            action = matureAction(Pledge.isActive(pledge, now), now >= pend.effectiveAt)
            if (action == MatureAction.Apply) { out = pend.newRules; p[Keys.RULES] = json.encodeToString(Rules.serializer(), out) }
            if (action != MatureAction.Keep) p.remove(Keys.PENDING)
        }
        return out to action
    }

    /** Writes [rules] (if not null) and [pending] only if the stored rules still equal [expected]; false means re-run. */
    suspend fun saveIfRulesUnchanged(expected: Rules, rules: Rules?, pending: Pending?): Boolean {
        var ok = false
        ds.edit { p ->
            if (p.dec(Keys.RULES, Rules.serializer(), EMPTY_RULES) != expected) return@edit
            ok = true
            if (rules != null) p[Keys.RULES] = json.encodeToString(Rules.serializer(), rules)
            if (pending == null) p.remove(Keys.PENDING) else p[Keys.PENDING] = json.encodeToString(Pending.serializer(), pending)
        }
        return ok
    }

    val disableRequestedAtFlow: Flow<Long?> = ds.data.map { it[Keys.DISABLE_REQ]?.toLongOrNull() }
    suspend fun disableRequestedAt(): Long? = disableRequestedAtFlow.first()
    suspend fun saveDisableRequestedAt(t: Long?) = put(Keys.DISABLE_REQ, t) { it.toString() }

    // ---- emergency passes
    val emergencyUsesFlow: Flow<List<Long>> = ds.data.map { it.dec(Keys.EMERGENCY, LONGS_SER, emptyList()) }
    suspend fun emergencyUses(): List<Long> = emergencyUsesFlow.first()
    suspend fun addEmergencyUse(at: Long) {
        ds.edit { p ->
            val l = p.dec(Keys.EMERGENCY, LONGS_SER, emptyList())
            p[Keys.EMERGENCY] = json.encodeToString(LONGS_SER, (l + at).filter { it >= at - 62 * DAY_MS })
        }
    }

    // ---- payment pauses
    val pausedUntilFlow: Flow<Long?> = ds.data.map { it[Keys.PAUSED_UNTIL]?.toLongOrNull() }
    suspend fun pausedUntil(): Long? = pausedUntilFlow.first()
    val pauseUsesFlow: Flow<List<Long>> = ds.data.map { it.dec(Keys.PAUSES, LONGS_SER, emptyList()) }
    suspend fun pauseUses(): List<Long> = pauseUsesFlow.first()
    suspend fun startPause(at: Long, until: Long) {
        ds.edit { p ->
            val l = p.dec(Keys.PAUSES, LONGS_SER, emptyList())
            p[Keys.PAUSES] = json.encodeToString(LONGS_SER, (l + at).filter { it >= at - 2 * DAY_MS })
            p[Keys.PAUSED_UNTIL] = until.toString()
        }
    }
    suspend fun endPause() = put<Long>(Keys.PAUSED_UNTIL, null) { it.toString() }

    // ---- trusted clock anchor
    suspend fun clockAnchor(): ClockAnchor? = ds.data.first().let { p -> p[Keys.CLOCK]?.let { p.dec(Keys.CLOCK, ClockAnchor.serializer().nullable, null) } }
    suspend fun saveClockAnchor(a: ClockAnchor) = put(Keys.CLOCK, a) { json.encodeToString(ClockAnchor.serializer(), it) }

    // ---- theme / onboarding / protection
    val themeFlow: Flow<ThemePrefs> = ds.data.map { p ->
        ThemePrefs(
            runCatching { DarkMode.valueOf(p[Keys.DARK]!!) }.getOrDefault(DarkMode.SYSTEM),
            runCatching { Accent.valueOf(p[Keys.ACCENT]!!) }.getOrDefault(Accent.TIDE),
        )
    }
    suspend fun saveTheme(t: ThemePrefs) = ds.edit { it[Keys.DARK] = t.dark.name; it[Keys.ACCENT] = t.accent.name }.let {}

    val onboardingDoneFlow: Flow<Boolean> = ds.data.map { it[Keys.ONBOARDED] == "true" }
    suspend fun setOnboardingDone() = put(Keys.ONBOARDED, "true") { it }

    val protectionEnabledFlow: Flow<Boolean> = ds.data.map { it[Keys.PROTECTION] != "false" }
    suspend fun setProtectionEnabled(on: Boolean) = put(Keys.PROTECTION, on.toString()) { it }

    /** Debug builds only: rule changes skip the 24 h wait. Always false in release, whatever is stored. */
    val testModeFlow: Flow<Boolean> = ds.data.map { BuildConfig.DEBUG && it[Keys.TEST_MODE] == "true" }
    suspend fun setTestMode(on: Boolean) = put(Keys.TEST_MODE, on.toString()) { it }

    // ---- usage analytics
    suspend fun logSession(pkg: String, start: Long, end: Long, plannedMin: Int, kind: Kind) {
        val z = zone()
        val bumps = bumpsFor(kind, start, end, z)
        dao.log(UsageEvent(pkg = pkg, start = start, end = end, plannedMin = plannedMin, kind = kind), bumps)
    }
    suspend fun logForeground(pkg: String, start: Long, end: Long) = logSession(pkg, start, end, 0, Kind.FOREGROUND)

    /** Trusted now for [dayKeyTicker]; set by EbbApp (TrustedNow lives in service/, which depends on data/). */
    @Volatile var clock: suspend () -> Long = { System.currentTimeMillis() }

    /** Emits the current local dayKey, then again at each local midnight. */
    fun dayKeyTicker(z: ZoneId): Flow<Int> = flow {
        while (true) {
            val now = Instant.ofEpochMilli(clock()).atZone(z)
            emit(dayKeyOf(now.toLocalDate()))
            delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(z)).toMillis().coerceAtLeast(1))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun dailyTotals(days: Int): Flow<List<DayTotal>> = flow {
        val z = zone()
        emitAll(dayKeyTicker(z).flatMapLatest { todayKey ->
            dao.totals(dayKeyOf(dateOfKey(todayKey).minusDays(days - 1L))).map { fillDays(it, todayKey, days) }
        })
    }

    fun perApp(dayKey: Int): Flow<List<AppTotal>> = dao.perApp(dayKey)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun hourly(days: Int): Flow<IntArray> = flow {
        val z = zone()
        emitAll(dayKeyTicker(z).flatMapLatest { todayKey ->
            val since = dateOfKey(todayKey).minusDays(days - 1L).atStartOfDay(z).toInstant().toEpochMilli()
            dao.foreground(since).map { ev -> hourlyBuckets(ev.map { maxOf(it.start, since) to it.end }, z) }
        })
    }

    /** Day keys with an emergency pass in the last [days] days. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun emergencyDays(days: Int): Flow<Set<Int>> = flow {
        val z = zone()
        emitAll(dayKeyTicker(z).flatMapLatest { todayKey ->
            val since = dateOfKey(todayKey).minusDays(days - 1L).atStartOfDay(z).toInstant().toEpochMilli()
            dao.emergencyStarts(since).map { l -> l.mapTo(HashSet()) { dayKeyOf(it, z) } }
        })
    }

    /** Planned minutes of the sessions started since [fromMs]. */
    fun sessionsSince(fromMs: Long): Flow<List<Int>> = dao.sessionPlans(fromMs)

    /** The earliest day with any recorded usage, or null before the first. */
    fun firstUseDayKey(): Flow<Int?> = dao.firstDayKey()

    suspend fun pruneOldEvents(now: Long) = dao.deleteEventsBefore(now - 90 * DAY_MS)
}

// ---- rules backup: a file the user keeps; imports go through submitRules like any edit.
private val backupJson = kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true }

fun encodeRulesBackup(r: Rules): String = backupJson.encodeToString(Rules.serializer(), r)

/** Parses a backup from an untrusted file: null unless it holds at least one app; values clamped to sane ranges. */
fun decodeRulesBackup(text: String): Rules? = runCatching { json.decodeFromString(Rules.serializer(), text) }.getOrNull()
    ?.let { r ->
        r.copy(
            apps = r.apps.values.filter { it.pkg.isNotBlank() }.associateBy { it.pkg }
                .mapValues { (_, a) -> a.copy(dailyCapMin = a.dailyCapMin.coerceIn(0, 24 * 60)) },
            passesPerMonth = r.passesPerMonth.coerceIn(0, 4),
            sharedCapMin = r.sharedCapMin?.coerceIn(1, 24 * 60),
        )
    }?.takeIf { it.apps.isNotEmpty() }

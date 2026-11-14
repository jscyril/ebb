package com.jscyril.ebb.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import com.jscyril.ebb.engine.AppState
import com.jscyril.ebb.engine.Defaults
import com.jscyril.ebb.guard.Pending
import com.jscyril.ebb.guard.PledgeRecord
import com.jscyril.ebb.guard.Rules
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
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.ZoneId

class Repo private constructor(ctx: Context) {
    private val ds = ctx.applicationContext.ds
    private val dao = Room.databaseBuilder(ctx.applicationContext, EbbDb::class.java, "ebb.db").build().usage()

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
    val rulesFlow: Flow<Rules> = ds.data.map { it.dec(Keys.RULES, Rules.serializer(), EMPTY_RULES) }
    suspend fun rules() = rulesFlow.first()
    suspend fun saveRules(r: Rules) = put(Keys.RULES, r) { json.encodeToString(Rules.serializer(), it) }

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
    suspend fun pledge(): PledgeRecord? = ds.data.first().let { p -> p[Keys.PLEDGE]?.let { p.dec(Keys.PLEDGE, PledgeDto.serializer().nullable, null) } }?.toRecord()
    suspend fun savePledge(r: PledgeRecord?) =
        put(Keys.PLEDGE, r) { json.encodeToString(PledgeDto.serializer(), PledgeDto.of(it)) }

    val pendingFlow: Flow<Pending?> = ds.data.map { it[Keys.PENDING]?.let { _ -> it.dec(Keys.PENDING, Pending.serializer().nullable, null) } }
    suspend fun pending() = pendingFlow.first()
    suspend fun savePending(p: Pending?) = put(Keys.PENDING, p) { json.encodeToString(Pending.serializer(), it) }

    suspend fun disableRequestedAt(): Long? = ds.data.first()[Keys.DISABLE_REQ]?.toLongOrNull()
    suspend fun saveDisableRequestedAt(t: Long?) = put(Keys.DISABLE_REQ, t) { it.toString() }

    // ---- emergency passes
    suspend fun emergencyUses(): List<Long> = ds.data.first().dec(Keys.EMERGENCY, LONGS_SER, emptyList())
    suspend fun addEmergencyUse(at: Long) {
        ds.edit { p ->
            val l = p.dec(Keys.EMERGENCY, LONGS_SER, emptyList())
            p[Keys.EMERGENCY] = json.encodeToString(LONGS_SER, (l + at).filter { it >= at - 62 * DAY_MS })
        }
    }

    // ---- trusted clock anchor
    suspend fun clockAnchor(): ClockAnchor? = ds.data.first().let { p -> p[Keys.CLOCK]?.let { p.dec(Keys.CLOCK, ClockAnchor.serializer().nullable, null) } }
    suspend fun saveClockAnchor(a: ClockAnchor) = put(Keys.CLOCK, a) { json.encodeToString(ClockAnchor.serializer(), it) }

    // ---- theme / onboarding / protection
    val themeFlow: Flow<ThemePrefs> = ds.data.map { p ->
        ThemePrefs(
            runCatching { DarkMode.valueOf(p[Keys.DARK]!!) }.getOrDefault(DarkMode.SYSTEM),
            runCatching { Accent.valueOf(p[Keys.ACCENT]!!) }.getOrDefault(Accent.DYNAMIC),
        )
    }
    suspend fun saveTheme(t: ThemePrefs) = ds.edit { it[Keys.DARK] = t.dark.name; it[Keys.ACCENT] = t.accent.name }.let {}

    val onboardingDoneFlow: Flow<Boolean> = ds.data.map { it[Keys.ONBOARDED] == "true" }
    suspend fun setOnboardingDone() = put(Keys.ONBOARDED, "true") { it }

    val protectionEnabledFlow: Flow<Boolean> = ds.data.map { it[Keys.PROTECTION] != "false" }
    suspend fun setProtectionEnabled(on: Boolean) = put(Keys.PROTECTION, on.toString()) { it }

    // ---- usage analytics
    suspend fun logSession(pkg: String, start: Long, end: Long, plannedMin: Int, kind: Kind) {
        val z = zone()
        val bumps = bumpsFor(kind, start, end, z)
        dao.log(UsageEvent(pkg = pkg, start = start, end = end, plannedMin = plannedMin, kind = kind), bumps)
    }
    suspend fun logForeground(pkg: String, start: Long, end: Long) = logSession(pkg, start, end, 0, Kind.FOREGROUND)

    /** Emits the current local dayKey, then again at each local midnight. */
    fun dayKeyTicker(z: ZoneId): Flow<Int> = flow {
        while (true) {
            val now = ZonedDateTime.now(z)
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

    suspend fun pruneOldEvents(now: Long) = dao.deleteEventsBefore(now - 90 * DAY_MS)
}

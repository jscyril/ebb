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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
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
    private inline fun <reified T> Preferences.read(key: Preferences.Key<String>): T? =
        this[key]?.let { json.decodeFromString<T>(it) }

    private suspend fun <T> put(key: Preferences.Key<String>, v: T?, enc: (T) -> String) {
        ds.edit { if (v == null) it.remove(key) else it[key] = enc(v) }
    }

    // ---- zone (pinned on first access so a device zone change can't mint extra passes)
    @Volatile private var zoneCache: ZoneId? = null
    suspend fun zone(): ZoneId = zoneCache ?: ds.edit {
        if (it[Keys.ZONE] == null) it[Keys.ZONE] = ZoneId.systemDefault().id
    }[Keys.ZONE]!!.let(ZoneId::of).also { zoneCache = it }

    // ---- rules
    val rulesFlow: Flow<Rules> = ds.data.map { it.read<Rules>(Keys.RULES) ?: EMPTY_RULES }
    suspend fun rules() = rulesFlow.first()
    suspend fun saveRules(r: Rules) = put(Keys.RULES, r) { json.encodeToString(Rules.serializer(), it) }

    // ---- per-app state
    val statesFlow: Flow<Map<String, AppState>> =
        ds.data.map { p -> p[Keys.STATES]?.let { json.decodeFromString(STATES_SER, it) } ?: emptyMap() }
    suspend fun state(pkg: String) = statesFlow.first()[pkg] ?: emptyState()
    suspend fun saveState(pkg: String, s: AppState) {
        ds.edit { p ->
            val m = p[Keys.STATES]?.let { json.decodeFromString(STATES_SER, it) } ?: emptyMap()
            p[Keys.STATES] = json.encodeToString(STATES_SER, m + (pkg to s))
        }
    }

    // ---- pledge / pending / disable request
    suspend fun pledge(): PledgeRecord? = ds.data.first().read<PledgeDto>(Keys.PLEDGE)?.toRecord()
    suspend fun savePledge(r: PledgeRecord?) =
        put(Keys.PLEDGE, r) { json.encodeToString(PledgeDto.serializer(), PledgeDto.of(it)) }

    val pendingFlow: Flow<Pending?> = ds.data.map { it.read<Pending>(Keys.PENDING) }
    suspend fun pending() = pendingFlow.first()
    suspend fun savePending(p: Pending?) = put(Keys.PENDING, p) { json.encodeToString(Pending.serializer(), it) }

    suspend fun disableRequestedAt(): Long? = ds.data.first()[Keys.DISABLE_REQ]?.toLong()
    suspend fun saveDisableRequestedAt(t: Long?) = put(Keys.DISABLE_REQ, t) { it.toString() }

    // ---- emergency passes
    suspend fun emergencyUses(): List<Long> =
        ds.data.first()[Keys.EMERGENCY]?.let { json.decodeFromString(LONGS_SER, it) } ?: emptyList()
    suspend fun addEmergencyUse(at: Long) {
        ds.edit { p ->
            val l = p[Keys.EMERGENCY]?.let { json.decodeFromString(LONGS_SER, it) } ?: emptyList()
            p[Keys.EMERGENCY] = json.encodeToString(LONGS_SER, (l + at).filter { it >= at - 62 * DAY_MS })
        }
    }

    // ---- trusted clock anchor
    suspend fun clockAnchor(): ClockAnchor? = ds.data.first().read<ClockAnchor>(Keys.CLOCK)
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
        val bumps = when (kind) {
            Kind.FOREGROUND -> splitByDay(start, end, z).map { (d, s) -> Triple(d, s, 0 to 0) }
            Kind.SESSION -> listOf(Triple(dayKeyOf(start, z), 0L, 1 to 0))
            Kind.BLOCKED_ATTEMPT -> listOf(Triple(dayKeyOf(start, z), 0L, 0 to 1))
            Kind.EMERGENCY -> emptyList()
        }
        dao.log(UsageEvent(pkg = pkg, start = start, end = end, plannedMin = plannedMin, kind = kind), bumps)
    }
    suspend fun logForeground(pkg: String, start: Long, end: Long) = logSession(pkg, start, end, 0, Kind.FOREGROUND)

    private fun today(z: ZoneId) = java.time.LocalDate.now(z)

    /** Today is fixed when collection starts; re-collect after midnight to roll the window. */
    fun dailyTotals(days: Int): Flow<List<DayTotal>> = flow {
        val z = zone()
        val t = today(z)
        val todayKey = dayKeyOf(t)
        dao.totals(dayKeyOf(t.minusDays(days - 1L))).collect { emit(fillDays(it, todayKey, days, z)) }
    }

    fun perApp(dayKey: Int): Flow<List<AppTotal>> = dao.perApp(dayKey)

    fun hourly(days: Int): Flow<IntArray> = flow {
        val z = zone()
        val since = today(z).minusDays(days - 1L).atStartOfDay(z).toInstant().toEpochMilli()
        dao.foreground(since).collect { ev -> emit(hourlyBuckets(ev.map { maxOf(it.start, since) to it.end }, z)) }
    }

    suspend fun pruneOldEvents(now: Long) = dao.deleteEventsBefore(now - 90 * DAY_MS)
}

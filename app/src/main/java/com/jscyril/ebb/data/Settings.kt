package com.jscyril.ebb.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jscyril.ebb.guard.PledgeRecord
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.util.Base64

internal val Context.ds by preferencesDataStore("ebb", corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() })

internal val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

internal object Keys {
    val RULES = stringPreferencesKey("rules")
    val STATES = stringPreferencesKey("states")
    val PLEDGE = stringPreferencesKey("pledge")
    val PENDING = stringPreferencesKey("pending")
    val DISABLE_REQ = stringPreferencesKey("disable_requested_at")
    val EMERGENCY = stringPreferencesKey("emergency_uses")
    val CLOCK = stringPreferencesKey("clock_anchor")
    val DARK = stringPreferencesKey("theme_dark")
    val ACCENT = stringPreferencesKey("theme_accent")
    val ONBOARDED = stringPreferencesKey("onboarding_done")
    val PROTECTION = stringPreferencesKey("protection_enabled")
    val ZONE = stringPreferencesKey("zone_id")
    val TEST_MODE = stringPreferencesKey("debug_test_mode")
    val PAUSED_UNTIL = stringPreferencesKey("paused_until")
    val PAUSES = stringPreferencesKey("pause_uses")
}

/** [skew] is wall clock minus trusted time when saved; after a reboot it undoes a clock change made before it. */
@Serializable
data class ClockAnchor(val wall: Long = 0, val elapsed: Long = 0, val lastTrusted: Long = 0, val bootCount: Int = 0, val skew: Long = 0)

enum class DarkMode { SYSTEM, LIGHT, DARK, AMOLED }
enum class Accent { DYNAMIC, TIDE, CORAL, MOSS, DUSK, SAND }
data class ThemePrefs(val dark: DarkMode = DarkMode.SYSTEM, val accent: Accent = Accent.TIDE)

/** ByteArrays as Base64 so the record fits a JSON string. */
@Serializable
internal class PledgeDto(val endsAt: Long = 0, val salt: String = "", val hash: String = "") {
    /** Decoded while deserializing, so bad Base64 fails inside [decodeOr] (null + `_corrupt` copy), not in the caller. */
    @Transient val record = PledgeRecord(endsAt, Base64.getDecoder().decode(salt), Base64.getDecoder().decode(hash))
    companion object {
        fun of(r: PledgeRecord) = PledgeDto(
            r.endsAt, Base64.getEncoder().encodeToString(r.salt), Base64.getEncoder().encodeToString(r.hash),
        )
    }
}

/** Decodes [raw] or returns [default]; a present-but-undecodable blob is reported to [onCorrupt] first. */
internal fun <T> decodeOr(raw: String?, ser: KSerializer<T>, default: T, onCorrupt: (String) -> Unit = {}): T {
    if (raw == null) return default
    return try { json.decodeFromString(ser, raw) } catch (e: Exception) { onCorrupt(raw); default }
}

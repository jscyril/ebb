package com.jscyril.ebb.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jscyril.ebb.guard.PledgeRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

internal val Context.ds by preferencesDataStore("ebb")

internal val json = Json { ignoreUnknownKeys = true }

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
}

@Serializable
data class ClockAnchor(val wall: Long, val elapsed: Long, val lastTrusted: Long, val bootCount: Int)

enum class DarkMode { SYSTEM, LIGHT, DARK, AMOLED }
enum class Accent { DYNAMIC, TIDE, CORAL, MOSS, DUSK, SAND }
data class ThemePrefs(val dark: DarkMode = DarkMode.SYSTEM, val accent: Accent = Accent.DYNAMIC)

/** ByteArrays as Base64 so the record fits a JSON string. */
@Serializable
internal class PledgeDto(val endsAt: Long, val salt: String, val hash: String) {
    fun toRecord() = PledgeRecord(endsAt, Base64.getDecoder().decode(salt), Base64.getDecoder().decode(hash))
    companion object {
        fun of(r: PledgeRecord) = PledgeDto(
            r.endsAt, Base64.getEncoder().encodeToString(r.salt), Base64.getEncoder().encodeToString(r.hash),
        )
    }
}

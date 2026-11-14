package com.jscyril.ebb.ui.theme

import com.jscyril.ebb.data.Accent
import kotlin.math.pow

/**
 * Pure ARGB colour math and the hand-built accent presets (no Compose, so it is unit-tested).
 * Compose has no seed-to-scheme API, so each preset is a few seed tokens and [scheme] derives
 * every role by mixing toward white/black/ink, then nudges any pair below 4.5:1 until it passes.
 */
object Palette {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val TIDE = 0xFF0E5E66.toInt()
    const val SEA_GLASS = 0xFFBFE3DA.toInt()
    const val INK = 0xFF0B1F24.toInt()
    const val SAND = 0xFFE9D8B4.toInt()
    private const val GREY = 0xFF6B7476.toInt()
    private const val MIN = 4.5

    /** Seed tokens; null containers/ink are derived from the primary. */
    private class Seed(val primary: Int, val tertiary: Int, val primaryContainer: Int? = null, val tertiaryContainer: Int? = null, val ink: Int? = null)

    private val seeds = mapOf(
        Accent.TIDE to Seed(TIDE, 0xFF8A6A2F.toInt(), SEA_GLASS, SAND, INK),
        Accent.CORAL to Seed(0xFFC8553D.toInt(), TIDE),
        Accent.MOSS to Seed(0xFF4F6F3A.toInt(), 0xFF8A6A2F.toInt()),
        Accent.DUSK to Seed(0xFF4C5196.toInt(), 0xFF9C4A6B.toInt()),
        Accent.SAND to Seed(0xFF8A6A2F.toInt(), TIDE),
    )

    /** AMOLED surface ladder: pure black base, near-black containers so cards still separate. */
    val AMOLED = intArrayOf(BLACK, BLACK, 0xFF0A0B0B.toInt(), 0xFF111313.toInt(), 0xFF181A1B.toInt(), 0xFF202324.toInt())

    /** DYNAMIC has no seed; callers fall back to TIDE where dynamic colour is unavailable. */
    fun scheme(accent: Accent, dark: Boolean, amoled: Boolean): Scheme {
        val s = seeds[accent] ?: seeds.getValue(Accent.TIDE)
        val ink = s.ink ?: mix(0xFF0E1112.toInt(), s.primary, 0.12)
        val secondarySeed = mix(s.primary, GREY, 0.55)
        val primary = role(s.primary, dark, s.primaryContainer)
        val n = neutral(s.primary, ink, dark, amoled)
        return Scheme(
            primary = primary.copy(c = readable(primary.c, n.surface)),
            secondary = role(secondarySeed, dark, null),
            tertiary = role(s.tertiary, dark, s.tertiaryContainer),
            n = n,
            inversePrimary = role(s.primary, !dark, null).c,
        )
    }

    /** Builds the four colours of one role from a seed. */
    private fun role(seed: Int, dark: Boolean, lightContainer: Int?): Role = if (!dark) {
        val on = WHITE
        val container = lightContainer ?: mix(seed, WHITE, 0.78)
        Role(readable(seed, on), on, container, readable(mix(seed, BLACK, 0.6), container))
    } else {
        val c = mix(seed, WHITE, 0.6)
        val container = mix(seed, BLACK, 0.45)
        Role(c, readable(mix(seed, BLACK, 0.7), c), container, readable(mix(seed, WHITE, 0.82), container))
    }

    private fun neutral(seed: Int, ink: Int, dark: Boolean, amoled: Boolean): Neutral {
        val tint = mix(seed, GREY, 0.6)
        return if (!dark) {
            val surface = mix(WHITE, tint, 0.04)
            val variant = mix(WHITE, tint, 0.2)
            Neutral(
                surface, readable(ink, surface), variant, readable(mix(ink, WHITE, 0.25), variant),
                WHITE, mix(WHITE, tint, 0.06), mix(WHITE, tint, 0.09), mix(WHITE, tint, 0.12), mix(WHITE, tint, 0.15),
                mix(WHITE, tint, 0.18), surface, mix(ink, WHITE, 0.45), mix(ink, WHITE, 0.78),
                ink, mix(WHITE, tint, 0.06),
            )
        } else {
            val base = mix(ink, BLACK, 0.35)
            val l = if (amoled) AMOLED else intArrayOf(
                base, mix(ink, BLACK, 0.6), mix(ink, BLACK, 0.2), ink, mix(ink, WHITE, 0.05), mix(ink, WHITE, 0.1),
            )
            val variant = if (amoled) AMOLED[4] else mix(ink, WHITE, 0.14)
            val onSurface = mix(WHITE, seed, 0.08)
            Neutral(
                l[0], readable(onSurface, l[0]), variant, readable(mix(WHITE, ink, 0.25), variant),
                l[1], l[2], l[3], l[4], l[5],
                l[0], if (amoled) AMOLED[5] else mix(ink, WHITE, 0.14), mix(WHITE, ink, 0.55), mix(WHITE, ink, 0.82),
                mix(WHITE, seed, 0.1), ink,
            )
        }
    }

    /** Moves [fg] toward black or white (whichever is further from [bg]) until it reads at 4.5:1. */
    fun readable(fg: Int, bg: Int): Int {
        val target = if (luminance(bg) > 0.18) BLACK else WHITE
        var c = fg
        var t = 0.0
        while (contrast(c, bg) < MIN && t < 1.0) { t += 0.04; c = mix(fg, target, t) }
        return c
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** WCAG relative luminance of an opaque ARGB colour. */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    /** Linear RGB mix: t = 0 gives [a], t = 1 gives [b]. Result is opaque. */
    fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(shift: Int) = ((a shr shift and 0xFF) * (1 - t) + (b shr shift and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    data class Role(val c: Int, val on: Int, val container: Int, val onContainer: Int)
    data class Neutral(
        val surface: Int, val onSurface: Int, val surfaceVariant: Int, val onSurfaceVariant: Int,
        val containerLowest: Int, val containerLow: Int, val container: Int, val containerHigh: Int,
        val containerHighest: Int, val surfaceDim: Int, val surfaceBright: Int,
        val outline: Int, val outlineVariant: Int, val inverseSurface: Int, val inverseOnSurface: Int,
    )
    data class Scheme(val primary: Role, val secondary: Role, val tertiary: Role, val n: Neutral, val inversePrimary: Int)
}

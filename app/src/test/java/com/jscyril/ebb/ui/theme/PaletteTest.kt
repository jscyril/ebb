package com.jscyril.ebb.ui.theme

import com.jscyril.ebb.data.Accent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    @Test fun contrastMath() {
        assertEquals(21.0, Palette.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, Palette.contrast(0xFF0E5E66.toInt(), 0xFF0E5E66.toInt()), 0.001)
    }

    @Test fun everyPresetTextPairReaches4_5() {
        val presets = Accent.entries.filter { it != Accent.DYNAMIC }
        for (a in presets) for (dark in listOf(false, true)) for (amoled in listOf(false, true)) {
            if (amoled && !dark) continue
            val s = Palette.scheme(a, dark, amoled)
            val pairs = listOf(
                "primary" to (s.primary.c to s.primary.on),
                "primaryContainer" to (s.primary.container to s.primary.onContainer),
                "secondary" to (s.secondary.c to s.secondary.on),
                "secondaryContainer" to (s.secondary.container to s.secondary.onContainer),
                "tertiary" to (s.tertiary.c to s.tertiary.on),
                "tertiaryContainer" to (s.tertiary.container to s.tertiary.onContainer),
                "surface" to (s.n.surface to s.n.onSurface),
                "surfaceContainerHighest" to (s.n.containerHighest to s.n.onSurface),
                "surfaceVariant" to (s.n.surfaceVariant to s.n.onSurfaceVariant),
                "primaryOnSurface" to (s.n.surface to s.primary.c),
                "inverse" to (s.n.inverseSurface to s.n.inverseOnSurface),
            )
            for ((name, p) in pairs) {
                val r = Palette.contrast(p.first, p.second)
                assertTrue("$a dark=$dark amoled=$amoled $name = %.2f".format(r), r >= 4.5)
            }
        }
    }

    @Test fun tideKeepsBrandColours() {
        val s = Palette.scheme(Accent.TIDE, dark = false, amoled = false)
        assertEquals(0xFF0E5E66.toInt(), s.primary.c)
        assertEquals(0xFFBFE3DA.toInt(), s.primary.container)
    }

    @Test fun amoledSurfaceIsBlack() =
        assertEquals(0xFF000000.toInt(), Palette.scheme(Accent.MOSS, dark = true, amoled = true).n.surface)
}

package com.jt.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.jt.notes.ui.Themes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    private fun contrast(a: Color, b: Color): Float {
        val l1 = maxOf(a.luminance(), b.luminance())
        val l2 = minOf(a.luminance(), b.luminance())
        return (l1 + 0.05f) / (l2 + 0.05f)
    }

    @Test fun elevenChoicesWithUniqueIds() {
        assertEquals(11, Themes.all.size)
        assertEquals(11, Themes.all.map { it.id }.toSet().size)
        assertEquals("classic", Themes.find("nonsense").id)
    }

    @Test fun textIsReadableOnEveryTheme() {
        for (spec in Themes.all) {
            val p = spec.palette ?: continue
            assertTrue("${spec.id} ink on ground", contrast(p.ink, p.ground) >= 4.5f)
            assertTrue("${spec.id} ink on cards", contrast(p.ink, p.surface) >= 4.5f)
            assertTrue("${spec.id} muted on ground", contrast(p.muted, p.ground) >= 3.0f)
            assertTrue("${spec.id} text on accent", contrast(p.onAccent, p.accent) >= 3.0f)
            assertTrue("${spec.id} text on bar", contrast(p.onBar, p.bar) >= 4.5f)
        }
    }
}

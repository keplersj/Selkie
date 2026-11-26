package io.github.keplersj.selkie.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb).toDouble() / minOf(la, lb).toDouble()
    }

    private fun pairs(cs: ColorScheme) = mapOf(
        "primary" to (cs.onPrimary to cs.primary),
        "primaryContainer" to (cs.onPrimaryContainer to cs.primaryContainer),
        "secondaryContainer" to (cs.onSecondaryContainer to cs.secondaryContainer),
        "tertiaryContainer" to (cs.onTertiaryContainer to cs.tertiaryContainer),
        "errorContainer" to (cs.onErrorContainer to cs.errorContainer),
        "surface" to (cs.onSurface to cs.surface),
        "surfaceVariantText" to (cs.onSurfaceVariant to cs.surfaceContainer),
        "planned" to (cs.tertiary to cs.surface),
        "error" to (cs.error to cs.surface),
    )

    @Test fun fallbackSchemesMeetTextContrast() {
        for ((name, cs) in listOf("light" to SeaTealLight, "dark" to SeaTealDark)) {
            for ((pair, colors) in pairs(cs)) {
                val r = contrast(colors.first, colors.second)
                assertTrue("$name $pair contrast $r is under 4.5:1", r >= 4.5)
            }
        }
    }

    @Test fun terminalTextMeetsContrast() {
        val t = DefaultTerminalColors
        assertTrue(contrast(t.foreground, t.background) >= 4.5)
        assertTrue(contrast(t.dim, t.background) >= 4.5)
        assertTrue(contrast(t.accent, t.background) >= 4.5)
    }

    @Test fun everyIconParses() {
        val icons = SelkieIcons::class.java.declaredMethods
            .filter { it.name.startsWith("get") && it.returnType == ImageVector::class.java && it.parameterCount == 0 }
        assertTrue("found ${icons.size} icons", icons.size >= 30)
        for (m in icons) {
            val v = m.invoke(SelkieIcons) as ImageVector
            assertTrue("${v.name} has no path", v.root.size > 0)
        }
    }
}

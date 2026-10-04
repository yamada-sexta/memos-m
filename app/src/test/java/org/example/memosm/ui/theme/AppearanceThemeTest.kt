package org.example.memosm.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import org.example.memosm.model.AppFont
import org.example.memosm.model.AppearancePreferences
import org.example.memosm.model.ColorTheme
import org.example.memosm.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class AppearanceThemeTest {
    @Test fun `device theme is followed only when system mode is selected`() {
        assertTrue(ThemeMode.SYSTEM.isDark(true))
        assertEquals(false, ThemeMode.SYSTEM.isDark(false))
        assertEquals(false, ThemeMode.LIGHT.isDark(true))
        assertTrue(ThemeMode.DARK.isDark(false))
    }

    @Test fun `wallpaper choice retains every system color role`() {
        val colors = lightColorScheme()
        assertSame(colors, appearanceColorScheme(AppearancePreferences(), colors, false))
    }

    @Test fun `custom palettes use different accents and readable foreground pairs in both modes`() {
        for (dark in listOf(false, true)) {
            val system = if (dark) darkColorScheme() else lightColorScheme()
            val primaryColors = mutableSetOf<Color>()
            for (hue in listOf(20f, 140f, 260f)) {
                val colors = appearanceColorScheme(AppearancePreferences(colorTheme = ColorTheme.CUSTOM, customHue = hue), system, dark)
                primaryColors += colors.primary
                for ((background, foreground) in listOf(
                    colors.primary to colors.onPrimary,
                    colors.primaryContainer to colors.onPrimaryContainer,
                    colors.surface to colors.onSurface,
                    colors.surfaceContainerHigh to colors.onSurface
                )) {
                    assertTrue("Unreadable pair at hue $hue, dark=$dark", contrast(background, foreground) >= 4.5)
                }
            }
            assertEquals(3, primaryColors.size)
        }
    }

    @Test fun `font choice reaches body heading labels and expressive styles`() {
        val typography = typographyFor(AppFont.SERIF)
        for (style in listOf(typography.bodyMedium, typography.headlineLarge, typography.titleLarge, typography.labelSmall, typography.titleLargeEmphasized)) {
            assertEquals(FontFamily.Serif, style.fontFamily)
        }
        assertNotEquals(typography.bodyMedium.fontFamily, typographyFor(AppFont.MONOSPACE).bodyMedium.fontFamily)
    }

    @Test fun `monochrome and Memos ignore wallpaper changes in light and dark modes`() {
        for (dark in listOf(false, true)) {
            val firstWallpaper = lightColorScheme(primary = Color.Red)
            val secondWallpaper = lightColorScheme(primary = Color.Blue)
            for (theme in listOf(ColorTheme.MONOCHROME, ColorTheme.MEMOS)) {
                val preferences = AppearancePreferences(colorTheme = theme)
                val first = appearanceColorScheme(preferences, firstWallpaper, dark)
                val second = appearanceColorScheme(preferences, secondWallpaper, dark)
                assertEquals(first.primary, second.primary)
                assertEquals(first.surface, second.surface)
                assertEquals(first.surfaceContainerHigh, second.surfaceContainerHigh)
                if (theme == ColorTheme.MONOCHROME) {
                    for (color in listOf(first.primary, first.primaryContainer, first.secondary, first.tertiary, first.surface)) {
                        assertEquals(color.red, color.green, 0.002f)
                        assertEquals(color.green, color.blue, 0.002f)
                    }
                } else {
                    assertEquals(if (dark) Color(0xFF5B97D3) else Color(0xFF305880), first.primary)
                    assertEquals(if (dark) Color(0xFF1D1F23) else Color(0xFFFAF9F5), first.surface)
                    for ((background, foreground) in listOf(
                        first.primary to first.onPrimary,
                        first.primaryContainer to first.onPrimaryContainer,
                        first.surfaceContainerHigh to first.onSurface,
                        first.error to first.onError,
                        first.errorContainer to first.onErrorContainer
                    )) {
                        assertTrue("Unreadable Memos pair, dark=$dark", contrast(background, foreground) >= 4.5)
                    }
                }
            }
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val firstLuminance = first.luminance().toDouble()
        val secondLuminance = second.luminance().toDouble()
        return (max(firstLuminance, secondLuminance) + 0.05) / (min(firstLuminance, secondLuminance) + 0.05)
    }
}

package org.example.memosm.data

import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.example.memosm.account.MemoryPreferences
import org.example.memosm.model.AppFont
import org.example.memosm.model.AppearancePreferences
import org.example.memosm.model.ColorTheme
import org.example.memosm.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearancePreferencesTest {
    @Test fun `existing installs keep device colors mode and font`() = runTest {
        assertEquals(AppearancePreferences(), DataStoreManager(MemoryPreferences()).appearance.first())
    }

    @Test fun `saved choices survive a new reader and unrelated updates`() = runTest {
        val preferences = MemoryPreferences()
        val writer = DataStoreManager(preferences)
        writer.saveColorTheme(ColorTheme.CUSTOM, 125f)
        writer.saveThemeMode(ThemeMode.DARK)
        writer.saveFont(AppFont.SERIF)
        writer.savePageSize(25)
        val reader = DataStoreManager(preferences)
        assertEquals(AppearancePreferences(ThemeMode.DARK, ColorTheme.CUSTOM, 125f, AppFont.SERIF), reader.appearance.first())
        reader.saveColorTheme(ColorTheme.SYSTEM)
        assertEquals(125f, reader.appearance.first().customHue)
        assertEquals(ThemeMode.DARK, reader.appearance.first().themeMode)
        assertEquals(AppFont.SERIF, reader.appearance.first().font)
    }

    @Test fun `simultaneous activity changes preserve independent preferences`() = runTest {
        val storage = MemoryPreferences()
        val first = DataStoreManager(storage)
        val second = DataStoreManager(storage)
        listOf(
            async { first.saveThemeMode(ThemeMode.LIGHT) },
            async { second.saveColorTheme(ColorTheme.MONOCHROME) },
            async { second.saveFont(AppFont.MONOSPACE) }
        ).awaitAll()
        assertEquals(AppearancePreferences(ThemeMode.LIGHT, ColorTheme.MONOCHROME, null, AppFont.MONOSPACE), first.appearance.first())
    }

    @Test fun `unknown choices and corrupt hue safely use device defaults`() = runTest {
        val preferences = preferencesOf(
            DataStoreManager.APPEARANCE_THEME_MODE to "future-mode",
            DataStoreManager.APPEARANCE_FONT to "missing-font",
            DataStoreManager.APPEARANCE_COLOR_THEME to ColorTheme.CUSTOM.name,
            DataStoreManager.APPEARANCE_CUSTOM_HUE to Float.NaN
        )
        assertEquals(AppearancePreferences(), DataStoreManager(MemoryPreferences(preferences)).appearance.first())
    }

    @Test fun `invalid custom colors are rejected and hue wraps at the end of the spectrum`() = runTest {
        val manager = DataStoreManager(MemoryPreferences())
        assertTrue(runCatching { manager.saveColorTheme(ColorTheme.CUSTOM, Float.POSITIVE_INFINITY) }.isFailure)
        assertEquals(AppearancePreferences(), manager.appearance.first())
        manager.saveColorTheme(ColorTheme.CUSTOM, 360f)
        assertEquals(0f, manager.appearance.first().customHue)
    }
}

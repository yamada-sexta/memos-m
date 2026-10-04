package org.example.memosm.model

enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }
}

enum class ColorTheme { SYSTEM, MONOCHROME, CUSTOM }

enum class AppFont { SYSTEM, SANS_SERIF, SERIF, MONOSPACE }

/** Device preferences, shared by every account and activity. */
data class AppearancePreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorTheme: ColorTheme = ColorTheme.SYSTEM,
    val customHue: Float? = null,
    val font: AppFont = AppFont.SYSTEM
)

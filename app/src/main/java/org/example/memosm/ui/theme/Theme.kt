package org.example.memosm.ui.theme

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import org.example.memosm.data.DataStoreManager
import org.example.memosm.model.AppearancePreferences
import org.example.memosm.model.ColorTheme

@Composable
fun systemColorScheme(isDark: Boolean, dynamicColor: Boolean = true): ColorScheme {
    val context = LocalContext.current
    return when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> darkColorScheme()
        else -> lightColorScheme()
    }
}

/** Device colors, neutral Material palettes, source-backed Memos colors, or a user-selected hue. */
fun appearanceColorScheme(
    appearance: AppearancePreferences,
    systemColors: ColorScheme,
    isDark: Boolean
): ColorScheme = when (appearance.colorTheme) {
    ColorTheme.SYSTEM -> systemColors
    ColorTheme.MONOCHROME -> dynamicColorScheme(
        seedColor = Color.hsv(0f, saturation = 0f, value = 0.5f),
        isDark = isDark,
        style = PaletteStyle.Monochrome,
        specVersion = ColorSpec.SpecVersion.SPEC_2025
    )
    ColorTheme.MEMOS -> memosColorScheme(isDark)
    ColorTheme.CUSTOM -> appearance.customHue?.let { hue ->
        dynamicColorScheme(
            seedColor = Color.hsv(hue % 360f, saturation = 0.65f, value = 0.75f),
            isDark = isDark,
            style = PaletteStyle.TonalSpot,
            specVersion = ColorSpec.SpecVersion.SPEC_2025
        )
    } ?: systemColors
}

@Composable
fun MemosMTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean = true,
    appearance: AppearancePreferences = AppearancePreferences(),
    content: @Composable () -> Unit
) {
    val isDark = darkTheme ?: appearance.themeMode.isDark(isSystemInDarkTheme())
    val systemColors = systemColorScheme(isDark, dynamicColor)
    val colors = remember(appearance.colorTheme, appearance.customHue, systemColors, isDark) {
        appearanceColorScheme(appearance, systemColors, isDark)
    }
    val typography = remember(appearance.font) { typographyFor(appearance.font) }
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

/** Each activity observes the same saved preferences, including changes made in another activity. */
@Composable
fun SavedMemosMTheme(dataStoreManager: DataStoreManager, content: @Composable () -> Unit) {
    val appearance by dataStoreManager.appearance.collectAsStateWithLifecycle(
        initialValue = AppearancePreferences(), minActiveState = Lifecycle.State.CREATED
    )
    val isDark = appearance.themeMode.isDark(isSystemInDarkTheme())
    val activity = LocalActivity.current
    val view = LocalView.current
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }
    MemosMTheme(appearance = appearance, content = content)
}

/** Expressive styling is local to Profile and its activities. */
@Composable
fun ProfileTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val colors = base.copy(surface = base.surfaceContainerLow, background = base.surfaceContainerLow)
    MaterialExpressiveTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}

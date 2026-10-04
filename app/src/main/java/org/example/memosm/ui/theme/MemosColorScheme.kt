package org.example.memosm.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * Memos' default palettes from ../memos/web/src/themes/default.css and default-dark.css.
 * OKLCH tokens converted to sRGB. Fixed colors are deliberately confined to this optional theme;
 * Material roles map to the upstream background, card, popover, accent and text tokens.
 */
fun memosColorScheme(isDark: Boolean): ColorScheme = if (isDark) MemosDarkColors else MemosLightColors

private val MemosLightColors = lightColorScheme(
    primary = Color(0xFF305880), // --primary
    onPrimary = Color(0xFFFAF9F5), // --primary-foreground
    primaryContainer = Color(0xFFE9E6DC), // --accent
    onPrimaryContainer = Color(0xFF28261B), // --accent-foreground
    secondary = Color(0xFF535146), // --secondary-foreground
    onSecondary = Color(0xFFE9E6DC), // --secondary
    secondaryContainer = Color(0xFFE9E6DC), // --secondary
    onSecondaryContainer = Color(0xFF535146), // --secondary-foreground
    tertiary = Color(0xFF28261B), // --accent-foreground
    onTertiary = Color(0xFFE9E6DC), // --accent
    tertiaryContainer = Color(0xFFE9E6DC), // --accent
    onTertiaryContainer = Color(0xFF28261B), // --accent-foreground
    background = Color(0xFFFAF9F5), // --background
    onBackground = Color(0xFF242011), // --foreground
    surface = Color(0xFFFAF9F5), // --background
    onSurface = Color(0xFF242011), // --foreground
    surfaceVariant = Color(0xFFEDE9DE), // --muted
    onSurfaceVariant = Color(0xFF74736E), // --muted-foreground
    surfaceTint = Color(0xFF305880), // --primary
    error = Color(0xFF9C433F), // --destructive
    onError = Color(0xFFFFFFFF), // --destructive-foreground
    outline = Color(0xFFB4B2A7), // --input
    outlineVariant = Color(0xFFDAD9D4), // --border
    scrim = Color(0xFF000000), // --overlay
    surfaceBright = Color(0xFFFFFFFF), // --popover
    surfaceDim = Color(0xFFF5F4EE), // --sidebar
    surfaceContainerLowest = Color(0xFFFAF9F5), // --background
    surfaceContainerLow = Color(0xFFF5F4EE), // --sidebar
    surfaceContainer = Color(0xFFFFFFFF), // --card
    surfaceContainerHigh = Color(0xFFFFFFFF), // --popover
    surfaceContainerHighest = Color(0xFFEDE9DE), // --muted
    errorContainer = Color(0xFF9C433F).copy(alpha = 0.12f).compositeOver(Color(0xFFFAF9F5)),
    onErrorContainer = Color(0xFF9C433F),
    inverseSurface = Color(0xFF1D1F23),
    inverseOnSurface = Color(0xFFDBDEE2),
    inversePrimary = Color(0xFF5B97D3)
)

private val MemosDarkColors = darkColorScheme(
    primary = Color(0xFF5B97D3), // --primary
    onPrimary = Color(0xFF1D1F23), // --primary-foreground
    primaryContainer = Color(0xFF3E464F), // --accent
    onPrimaryContainer = Color(0xFFE8EBEF), // --accent-foreground
    secondary = Color(0xFFD5D8DB), // --secondary-foreground
    onSecondary = Color(0xFF32363B), // --secondary
    secondaryContainer = Color(0xFF32363B), // --secondary
    onSecondaryContainer = Color(0xFFD5D8DB), // --secondary-foreground
    tertiary = Color(0xFFE8EBEF), // --accent-foreground
    onTertiary = Color(0xFF3E464F), // --accent
    tertiaryContainer = Color(0xFF3E464F), // --accent
    onTertiaryContainer = Color(0xFFE8EBEF), // --accent-foreground
    background = Color(0xFF1D1F23), // --background
    onBackground = Color(0xFFDBDEE2), // --foreground
    surface = Color(0xFF1D1F23), // --background
    onSurface = Color(0xFFDBDEE2), // --foreground
    surfaceVariant = Color(0xFF373B40), // --muted
    onSurfaceVariant = Color(0xFFA2A5A9), // --muted-foreground
    surfaceTint = Color(0xFF5B97D3), // --primary
    error = Color(0xFFE55454), // --destructive
    onError = Color(0xFF1D1F23), // --background, for legible text on the dark destructive color
    outline = Color(0xFF494D53), // --input
    outlineVariant = Color(0xFF3F4348), // --border
    scrim = Color(0xFF000000), // --overlay
    surfaceBright = Color(0xFF2F3338), // --popover
    surfaceDim = Color(0xFF16191C), // --sidebar
    surfaceContainerLowest = Color(0xFF1D1F23), // --background
    surfaceContainerLow = Color(0xFF16191C), // --sidebar
    surfaceContainer = Color(0xFF25282C), // --card
    surfaceContainerHigh = Color(0xFF2F3338), // --popover
    surfaceContainerHighest = Color(0xFF373B40), // --muted
    errorContainer = Color(0xFFE55454).copy(alpha = 0.12f).compositeOver(Color(0xFF1D1F23)),
    onErrorContainer = Color(0xFFDBDEE2),
    inverseSurface = Color(0xFFFAF9F5),
    inverseOnSurface = Color(0xFF242011),
    inversePrimary = Color(0xFF305880)
)

package org.example.memosm.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.example.memosm.model.AppFont

// Set of Material typography styles to start with
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)

val AppFont.fontFamily: FontFamily
    get() = when (this) {
        AppFont.SYSTEM -> FontFamily.Default
        AppFont.SANS_SERIF -> FontFamily.SansSerif
        AppFont.SERIF -> FontFamily.Serif
        AppFont.MONOSPACE -> FontFamily.Monospace
    }

fun typographyFor(font: AppFont): Typography {
    val family = font.fontFamily
    return Typography.copy(
        displayLarge = Typography.displayLarge.copy(fontFamily = family),
        displayMedium = Typography.displayMedium.copy(fontFamily = family),
        displaySmall = Typography.displaySmall.copy(fontFamily = family),
        headlineLarge = Typography.headlineLarge.copy(fontFamily = family),
        headlineMedium = Typography.headlineMedium.copy(fontFamily = family),
        headlineSmall = Typography.headlineSmall.copy(fontFamily = family),
        titleLarge = Typography.titleLarge.copy(fontFamily = family),
        titleMedium = Typography.titleMedium.copy(fontFamily = family),
        titleSmall = Typography.titleSmall.copy(fontFamily = family),
        bodyLarge = Typography.bodyLarge.copy(fontFamily = family),
        bodyMedium = Typography.bodyMedium.copy(fontFamily = family),
        bodySmall = Typography.bodySmall.copy(fontFamily = family),
        labelLarge = Typography.labelLarge.copy(fontFamily = family),
        labelMedium = Typography.labelMedium.copy(fontFamily = family),
        labelSmall = Typography.labelSmall.copy(fontFamily = family),
        displayLargeEmphasized = Typography.displayLargeEmphasized.copy(fontFamily = family),
        displayMediumEmphasized = Typography.displayMediumEmphasized.copy(fontFamily = family),
        displaySmallEmphasized = Typography.displaySmallEmphasized.copy(fontFamily = family),
        headlineLargeEmphasized = Typography.headlineLargeEmphasized.copy(fontFamily = family),
        headlineMediumEmphasized = Typography.headlineMediumEmphasized.copy(fontFamily = family),
        headlineSmallEmphasized = Typography.headlineSmallEmphasized.copy(fontFamily = family),
        titleLargeEmphasized = Typography.titleLargeEmphasized.copy(fontFamily = family),
        titleMediumEmphasized = Typography.titleMediumEmphasized.copy(fontFamily = family),
        titleSmallEmphasized = Typography.titleSmallEmphasized.copy(fontFamily = family),
        bodyLargeEmphasized = Typography.bodyLargeEmphasized.copy(fontFamily = family),
        bodyMediumEmphasized = Typography.bodyMediumEmphasized.copy(fontFamily = family),
        bodySmallEmphasized = Typography.bodySmallEmphasized.copy(fontFamily = family),
        labelLargeEmphasized = Typography.labelLargeEmphasized.copy(fontFamily = family),
        labelMediumEmphasized = Typography.labelMediumEmphasized.copy(fontFamily = family),
        labelSmallEmphasized = Typography.labelSmallEmphasized.copy(fontFamily = family),
    )
}

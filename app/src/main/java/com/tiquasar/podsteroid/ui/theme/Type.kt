package com.tiquasar.podsteroid.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Build a Material3 Expressive Typography that uses Inter as the base UI font
 * family. Called from PodsteroidTheme — must be @Composable so we can resolve
 * the asset-backed Inter family via LocalContext (see PodsteroidTokens.ui()).
 *
 * Larger, bolder scale than the previous conservative set: big displays for
 * hero headers, generous body sizes, tracked labels.
 */
@Composable @ReadOnlyComposable
fun buildPodsteroidTypography(): Typography {
    val ui = PodsteroidTokens.ui()
    return Typography(
        displayLarge = TextStyle(fontFamily = ui, fontWeight = FontWeight.Bold,   fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.025).sp),
        displayMedium = TextStyle(fontFamily = ui, fontWeight = FontWeight.Bold,   fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = (-0.02).sp),
        displaySmall  = TextStyle(fontFamily = ui, fontWeight = FontWeight.Bold,   fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.01).sp),

        headlineLarge = TextStyle(fontFamily = ui, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.01).sp),
        headlineMedium = TextStyle(fontFamily = ui, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
        headlineSmall  = TextStyle(fontFamily = ui, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),

        titleLarge  = TextStyle(fontFamily = ui, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium,   fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.001.sp),
        titleSmall  = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium,   fontSize = 14.sp, lineHeight = 18.sp),

        bodyLarge  = TextStyle(fontFamily = ui, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = ui, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall  = TextStyle(fontFamily = ui, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),

        labelLarge  = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.01.sp),
        labelMedium = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.04.sp),
        labelSmall  = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.05.sp),
    )
}

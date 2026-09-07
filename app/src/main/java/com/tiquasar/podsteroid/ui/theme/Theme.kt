/*
 * Podsteroid Compose theme — "Aurora" Material 3 Expressive identity.
 *
 * Default identity: Material You wallpaper-derived dynamic color is ON by
 * default (Android 12+). When dynamic color is off (or below API 31) a fresh
 * violet/cyan/pink static scheme is used instead of the old blue. Rounded,
 * pill-forward shapes and a larger expressive type scale carry the new look.
 *
 * Switch themes via Settings → Appearance → Dynamic color, which flows in via
 * the dynamicColor parameter.
 */
package com.tiquasar.podsteroid.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val PodsteroidDark = darkColorScheme(
    primary             = PodsteroidPrimary,
    onPrimary           = PodsteroidOnPrimary,
    primaryContainer    = PodsteroidPrimaryCt,
    onPrimaryContainer  = PodsteroidOnPrimaryCt,

    secondary           = PodsteroidSecondary,
    onSecondary         = PodsteroidOnSecondary,
    secondaryContainer  = PodsteroidSecondaryCt,
    onSecondaryContainer= PodsteroidOnSecondaryCt,

    tertiary            = PodsteroidTertiary,
    onTertiary          = PodsteroidOnTertiary,
    tertiaryContainer   = PodsteroidTertiaryCt,
    onTertiaryContainer = PodsteroidOnTertiaryCt,

    background          = PodsteroidDarkBg,
    onBackground        = PodsteroidDarkText,
    surface             = PodsteroidDarkSurface,
    onSurface           = PodsteroidDarkText,
    surfaceVariant      = PodsteroidDarkSurface2,
    onSurfaceVariant    = PodsteroidDarkTextMute,
    surfaceContainerHighest = PodsteroidDarkSurface3,
    surfaceContainerHigh = PodsteroidDarkSurface2,
    surfaceContainer    = PodsteroidDarkSurface,

    outline             = PodsteroidDarkBorder,
    outlineVariant      = PodsteroidDarkBorder,

    error               = PodsteroidRed,
    onError             = PodsteroidOnPrimary,
    errorContainer      = PodsteroidRed.copy(alpha = 0.16f),
    onErrorContainer    = PodsteroidRed,
)

private val PodsteroidLight = lightColorScheme(
    primary             = PodsteroidPrimary,
    onPrimary           = PodsteroidOnPrimary,
    primaryContainer    = PodsteroidPrimaryCt,
    onPrimaryContainer  = PodsteroidOnPrimaryCt,

    secondary           = PodsteroidSecondary,
    onSecondary         = PodsteroidOnSecondary,
    secondaryContainer  = PodsteroidSecondaryCt,
    onSecondaryContainer= PodsteroidOnSecondaryCt,

    tertiary            = PodsteroidTertiary,
    onTertiary          = PodsteroidOnTertiary,
    tertiaryContainer   = PodsteroidTertiaryCt,
    onTertiaryContainer = PodsteroidOnTertiaryCt,

    background          = PodsteroidLightBg,
    onBackground        = PodsteroidLightText,
    surface             = PodsteroidLightSurface,
    onSurface           = PodsteroidLightText,
    surfaceVariant      = PodsteroidLightSurface2,
    onSurfaceVariant    = PodsteroidLightTextMute,
    surfaceContainerHighest = PodsteroidLightSurface3,
    surfaceContainerHigh = PodsteroidLightSurface2,
    surfaceContainer    = PodsteroidLightSurface,

    outline             = PodsteroidLightBorder,
    outlineVariant      = PodsteroidLightBorder,

    error               = PodsteroidRed,
    onError             = PodsteroidOnPrimary,
    errorContainer      = PodsteroidRed.copy(alpha = 0.12f),
    onErrorContainer    = PodsteroidRed,
)

/** Material 3 Expressive corner radii — rounder, friendlier, pill-forward. */
private val PodsteroidShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small      = RoundedCornerShape(20.dp),
    medium     = RoundedCornerShape(24.dp),
    large      = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp),
)

@Composable
fun PodsteroidTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val effectiveDark = darkTheme ?: isSystemInDarkTheme()
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (effectiveDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        effectiveDark -> PodsteroidDark
        else          -> PodsteroidLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = buildPodsteroidTypography(),
        shapes      = PodsteroidShapes,
        content     = content,
    )
}

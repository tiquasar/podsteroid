/*
 * Single source of truth for Podsteroid design tokens. Theme.kt maps these into
 * Material colorScheme; screens consume colors via MaterialTheme.colorScheme.*
 * and consume the non-color tokens (spacing, fonts, radii) directly from here.
 */
package com.tiquasar.podsteroid.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileOutputStream

object PodsteroidTokens {

    object Spacing {
        val XS  = 4.dp
        val SM  = 8.dp
        val MD  = 12.dp
        val LG  = 16.dp
        val XL  = 20.dp
        val XL2 = 24.dp
        val XL3 = 32.dp
        val XL4 = 40.dp
    }

    object Radius {
        val Chip   = 999.dp
        val Button = 999.dp
        val Card   = 20.dp
        val Sheet  = 28.dp
        val Large  = 32.dp
    }

    object TypeSize {
        val Display  = 36.sp
        val Headline = 24.sp
        val Title    = 16.sp
        val Body     = 14.sp
        val Label    = 12.sp
    }

    /** Expressive brand gradient (blue → cyan) for hero surfaces. */
    fun brandBrush(): Brush = Brush.linearGradient(
        listOf(PodsteroidPrimary, PodsteroidSecondary),
    )

    val Accent     = PodsteroidAccent
    val AccentInk  = PodsteroidAccentInk
    val Amber      = PodsteroidAmber
    val Red        = PodsteroidRed
    val Green      = PodsteroidGreen

    @Volatile private var interFamily: FontFamily? = null

    fun interFamily(context: Context): FontFamily {
        interFamily?.let { return it }
        synchronized(this) {
            interFamily?.let { return it }
            val regular  = extractAsset(context, "ui-fonts/Inter-Regular.ttf")
            val semiBold = extractAsset(context, "ui-fonts/Inter-SemiBold.ttf")
            val fam = FontFamily(
                Font(regular,  FontWeight.Normal),
                Font(semiBold, FontWeight.SemiBold),
            )
            interFamily = fam
            return fam
        }
    }

    @Volatile private var monoFamily: FontFamily? = null

    fun monoFamily(context: Context): FontFamily {
        monoFamily?.let { return it }
        synchronized(this) {
            monoFamily?.let { return it }
            val mono = extractAsset(context, "fonts/JetBrains-Mono.ttf")
            val fam = FontFamily(Font(mono, FontWeight.Normal))
            monoFamily = fam
            return fam
        }
    }

    @Composable @ReadOnlyComposable
    fun ui(): FontFamily = interFamily(LocalContext.current)

    @Composable @ReadOnlyComposable
    fun mono(): FontFamily = monoFamily(LocalContext.current)

    private fun extractAsset(context: Context, assetPath: String): File {
        val outFile = File(context.filesDir, assetPath)
        if (outFile.exists() && outFile.length() > 0) return outFile
        outFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return outFile
    }
}

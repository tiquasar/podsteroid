package com.tiquasar.podsteroid.ui.screens.servers

import androidx.compose.ui.graphics.Color
import kotlin.random.Random

/** Fixed badge palette; a server picks one index at creation and its VMs inherit it. */
object ServerBadge {
    val COLORS = listOf(
        0xFFE53935, // red
        0xFFD81B60, // pink
        0xFF8E24AA, // purple
        0xFF5E35B1, // deep purple
        0xFF1E88E5, // blue
        0xFF00ACC1, // cyan
        0xFF43A047, // green
        0xFFFDD835, // yellow
        0xFFFB8C00, // orange
        0xFF6D4C41, // brown
    )

    fun color(index: Int): Color = Color(COLORS[Math.floorMod(index, COLORS.size)])

    fun randomIndex(): Int = Random.nextInt(COLORS.size)
}

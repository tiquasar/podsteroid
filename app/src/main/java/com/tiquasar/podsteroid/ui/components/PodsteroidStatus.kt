package com.tiquasar.podsteroid.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens

/**
 * Expressive status pill: a colored dot inside a tonal chip with a tracked
 * label. The dot color is decoupled from the accent so a stopped state stays
 * grey even on a lime-themed app.
 *
 * `pulsing = true` (used for a live/running state) draws an expanding ring
 * behind the dot so active VMs read as "alive" at a glance.
 */
@Composable
fun PodsteroidStatus(
    label: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(dotColor.copy(alpha = 0.14f))
            .padding(horizontal = PodsteroidTokens.Spacing.MD, vertical = PodsteroidTokens.Spacing.XS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (pulsing) {
                val transition = rememberInfiniteTransition(label = "statusPulse")
                val scale by transition.animateFloat(
                    initialValue = 1f,
                    targetValue = 2.4f,
                    animationSpec = infiniteRepeatable(tween(durationMillis = 1100)),
                )
                val alpha by transition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(tween(durationMillis = 1100)),
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Spacer(Modifier.width(PodsteroidTokens.Spacing.SM))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

object PodsteroidStatusColors {
    val Running = PodsteroidTokens.Green
    val Starting = PodsteroidTokens.Amber
    val Stopped = Color(0xFF9098A6)
    val Error = PodsteroidTokens.Red
}

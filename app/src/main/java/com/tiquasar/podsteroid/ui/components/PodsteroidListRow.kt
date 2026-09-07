package com.tiquasar.podsteroid.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens

/**
 * Canonical horizontal row used across Home, Settings, Setup, drawer, picker.
 * label = left, value = right, 1 px bottom divider drawn unless `divider = false`.
 *
 * `mono = true` renders the value in JetBrains Mono — for IPs, ports, paths.
 * `icon` adds a leading glyph; `chevron = true` adds a trailing › (used for
 * rows that navigate to a sub-screen). Tappable rows get extra horizontal
 * padding so the ripple feels intentional.
 */
@Composable
fun PodsteroidListRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    trailing: String? = null,
    mono: Boolean = false,
    icon: ImageVector? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
    rightSlot: @Composable (() -> Unit)? = null,
) {
    val rowMod = modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(
            vertical = PodsteroidTokens.Spacing.MD,
            horizontal = if (onClick != null) PodsteroidTokens.Spacing.SM else 0.dp,
        )

    Row(
        modifier = rowMod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (rightSlot != null) {
            rightSlot()
        } else if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (mono) PodsteroidTokens.mono() else FontFamily.Default,
            )
            if (trailing != null) {
                Spacer(Modifier.width(PodsteroidTokens.Spacing.SM))
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (chevron) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (divider) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp,
        )
    }
}

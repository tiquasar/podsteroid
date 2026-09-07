package com.tiquasar.podsteroid.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens

/**
 * Canonical Podsteroid card: a rounded, slightly elevated tonal surface with a
 * hairline border for definition on both light and dark themes. Centralizes the
 * card look so every surface in the app reads as one system.
 */
@Composable
fun PodsteroidCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    border: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(PodsteroidTokens.Radius.Card)
    val borderStroke = if (border) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    } else {
        null
    }
    val inner: @Composable ColumnScope.() -> Unit = {
        Column(modifier = Modifier.padding(PodsteroidTokens.Spacing.MD), content = content)
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = borderStroke,
            content = inner,
        )
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = borderStroke,
            content = inner,
        )
    }
}

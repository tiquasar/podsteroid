package com.tiquasar.podsteroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens

/**
 * Material 3 Expressive top bar: a large, bold headline title with an optional
 * subtitle. In `gradient` mode the bar becomes a brand-gradient hero surface
 * (violet→pink) with light text — used on the Home screen for a distinctive,
 * app-like feel. Otherwise it's a surface header with a thin bottom divider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodsteroidTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    gradient: Boolean = false,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = if (gradient) {
        TopAppBarDefaults.topAppBarColors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
        )
    } else {
        TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    },
) {
    Box(modifier = modifier.shadow(2.dp)) {
        if (gradient) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(PodsteroidTokens.brandBrush()),
            )
        }
        Column {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (gradient) {
                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
                navigationIcon = navigationIcon,
                actions = actions,
                colors = colors,
            )
            if (!gradient) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(PodsteroidTokens.brandBrush()),
                )
            }
        }
    }
}

/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Container observability: quick-access to podman ps / stats / logs / inspect
 * inside the VM. Commands are generated and copied for pasting into the
 * terminal (live per-container metrics are read from the guest there).
 */
package com.tiquasar.podsteroid.ui.screens.observability

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tiquasar.podsteroid.R
import com.tiquasar.podsteroid.ui.components.AdaptiveContainer
import com.tiquasar.podsteroid.ui.components.PodsteroidGhostButton
import com.tiquasar.podsteroid.ui.components.PodsteroidSectionLabel
import com.tiquasar.podsteroid.ui.components.PodsteroidTopBar
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObservabilityScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            PodsteroidTopBar(
                title = stringResource(R.string.observability_title),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PodsteroidTokens.Spacing.XL, vertical = PodsteroidTokens.Spacing.LG),
                verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.MD),
            ) {
                Text(
                    text = stringResource(R.string.observability_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CommandBlock(
                    label = stringResource(R.string.observability_running),
                    command = "podman ps -a --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'",
                    onCopy = { copy(context, "podman ps -a") },
                )
                CommandBlock(
                    label = stringResource(R.string.observability_stats),
                    command = "podman stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}'",
                    onCopy = { copy(context, "podman stats --no-stream") },
                )
                CommandBlock(
                    label = stringResource(R.string.observability_logs),
                    command = "podman logs -f <container>",
                    onCopy = { copy(context, "podman logs -f <container>") },
                )
                CommandBlock(
                    label = stringResource(R.string.observability_images),
                    command = "podman images",
                    onCopy = { copy(context, "podman images") },
                )
            }
        }
    }
}

@Composable
private fun CommandBlock(label: String, command: String, onCopy: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            text = command,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(PodsteroidTokens.Spacing.SM),
        )
        PodsteroidGhostButton(text = stringResource(R.string.cluster_copy), onClick = onCopy, modifier = Modifier.fillMaxWidth())
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("podsteroid-observability", text))
    Toast.makeText(context, context.getString(R.string.observability_copied), Toast.LENGTH_SHORT).show()
}

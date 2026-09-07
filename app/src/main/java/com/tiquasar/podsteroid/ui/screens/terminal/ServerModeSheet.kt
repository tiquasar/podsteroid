/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Explainer card for server (headless) mode. Modeled on X11SettingsSheet: a
 * ModalBottomSheet styled from PodsteroidTokens. Describes the feature and how to
 * exit, with one primary action to enable it.
 */
package com.tiquasar.podsteroid.ui.screens.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tiquasar.podsteroid.R
import com.tiquasar.podsteroid.ui.components.PodsteroidPrimaryButton
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerModeSheet(onDismiss: () -> Unit, onEnable: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PodsteroidTokens.Spacing.XL)
                .padding(bottom = PodsteroidTokens.Spacing.XL),
        ) {
            Text(
                text = stringResource(R.string.server_mode),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(PodsteroidTokens.Spacing.SM))
            Text(
                text = stringResource(R.string.server_mode_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PodsteroidTokens.Spacing.LG))
            PodsteroidPrimaryButton(
                text = stringResource(R.string.server_mode_enable),
                onClick = { onEnable(); onDismiss() },
            )
        }
    }
}

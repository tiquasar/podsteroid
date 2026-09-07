/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Gates Terminal / X11 behind the device credential screen (PIN, pattern,
 * password; biometric where the device is configured for it). Uses
 * KeyguardManager rather than the androidx.biometric library so no extra
 * dependency is required.
 */
package com.tiquasar.podsteroid.ui.components

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiquasar.podsteroid.R
import com.tiquasar.podsteroid.ui.screens.settings.SettingsViewModel
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun BiometricGate(content: @Composable () -> Unit) {
    val settingsVm: SettingsViewModel = hiltViewModel()
    val enabled by settingsVm.biometricLockEnabled.collectAsStateWithLifecycle()
    if (!enabled) {
        content()
        return
    }

    var unlocked by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val keyguard = remember {
        context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) unlocked = true
    }

    if (unlocked) {
        content()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.biometric_lock_prompt),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                onClick = {
                    val intent = keyguard.createConfirmDeviceCredentialIntent(
                        context.getString(R.string.biometric_lock_title),
                        context.getString(R.string.biometric_lock_description),
                    )
                    if (intent != null) {
                        launcher.launch(intent)
                    } else {
                        // No device lock configured — nothing to gate against.
                        unlocked = true
                    }
                },
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(text = stringResource(R.string.biometric_lock_unlock))
            }
        }
    }
}

/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * K3s cluster helper. Generates the install / join commands for a control
 * plane (server) or a worker (agent) running inside the VM, and copies them
 * for pasting into the terminal. Status commands let the user verify the
 * cluster from the guest.
 */
package com.tiquasar.podsteroid.ui.screens.cluster

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
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
import com.tiquasar.podsteroid.R
import com.tiquasar.podsteroid.ui.components.AdaptiveContainer
import com.tiquasar.podsteroid.ui.components.PodsteroidGhostButton
import com.tiquasar.podsteroid.ui.components.PodsteroidPrimaryButton
import com.tiquasar.podsteroid.ui.components.PodsteroidSectionLabel
import com.tiquasar.podsteroid.ui.components.PodsteroidTopBar
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens
import androidx.compose.ui.text.font.FontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClusterScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    var isServer by remember { mutableStateOf(true) }
    var token by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("https://10.0.2.15:6443") }

    val serverCommand = buildServerCommand()
    val agentCommand = buildAgentCommand(token, serverUrl)
    val statusCommand = "sudo k3s kubectl get nodes -o wide"

    Scaffold(
        topBar = {
            PodsteroidTopBar(
                title = stringResource(R.string.cluster_title),
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
                    text = stringResource(R.string.cluster_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.cluster_mode_server), modifier = Modifier.weight(1f))
                    Switch(checked = isServer, onCheckedChange = { isServer = it })
                    Text(stringResource(R.string.cluster_mode_agent))
                }

                if (isServer) {
                    PodsteroidSectionLabel(stringResource(R.string.cluster_server))
                    CommandBlock(
                        label = stringResource(R.string.cluster_setup_command),
                        command = serverCommand,
                        onCopy = { copy(context, serverCommand) },
                    )
                    Text(
                        text = stringResource(R.string.cluster_server_token_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PodsteroidPrimaryButton(
                        text = stringResource(R.string.cluster_start_server),
                        onClick = { copy(context, serverCommand) },
                    )
                } else {
                    PodsteroidSectionLabel(stringResource(R.string.cluster_agent))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text(stringResource(R.string.cluster_token_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text(stringResource(R.string.cluster_server_url_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(PodsteroidTokens.Spacing.SM))
                    CommandBlock(
                        label = stringResource(R.string.cluster_setup_command),
                        command = agentCommand,
                        onCopy = { copy(context, agentCommand) },
                    )
                    PodsteroidPrimaryButton(
                        text = stringResource(R.string.cluster_join),
                        onClick = { copy(context, agentCommand) },
                    )
                }

                PodsteroidSectionLabel(stringResource(R.string.cluster_status))
                CommandBlock(
                    label = stringResource(R.string.cluster_status_command),
                    command = statusCommand,
                    onCopy = { copy(context, statusCommand) },
                )
            }
        }
    }
}

@Composable
private fun CommandBlock(label: String, command: String, onCopy: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(PodsteroidTokens.Spacing.XS))
        Text(
            text = command,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PodsteroidTokens.Spacing.SM),
            )
        PodsteroidGhostButton(
            text = stringResource(R.string.cluster_copy),
            onClick = onCopy,
        )
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("podsteroid-cluster", text))
    Toast.makeText(context, context.getString(R.string.cluster_copied), Toast.LENGTH_SHORT).show()
}

private fun buildServerCommand(): String = """
    |curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--disable traefik --write-kubeconfig-mode 644" sh -
    |sleep 5
    |sudo k3s kubectl get node
    |echo "Agent token:"; sudo cat /var/lib/rancher/k3s/server/node-token
""".trimMargin()

private fun buildAgentCommand(token: String, serverUrl: String): String {
    val t = token.ifBlank { "<TOKEN>" }
    val u = serverUrl.ifBlank { "https://<SERVER_IP>:6443" }
    return """
        |curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--token $t --server $u" sh -
        |sleep 5
        |sudo k3s kubectl get node
    """.trimMargin()
}

/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * New-remote-VM dialog: mirrors the on-device VM editor (distro, vCPU, RAM,
 * disk, SSH) plus the container runtime and a Tailscale toggle.
 */
package com.tiquasar.podsteroid.ui.screens.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tiquasar.podsteroid.engine.GuestDistro
import com.tiquasar.podsteroid.remote.Accelerator
import com.tiquasar.podsteroid.remote.ContainerRuntime
import com.tiquasar.podsteroid.remote.NetworkMode
import com.tiquasar.podsteroid.remote.RemoteServer
import com.tiquasar.podsteroid.remote.RemoteVmSpec
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens

@Composable
fun RemoteVmCreateDialog(
    server: RemoteServer,
    onDismiss: () -> Unit,
    onCreate: (RemoteVmSpec) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var guestDistro by remember { mutableStateOf(GuestDistro.ALPINE) }
    var vcpus by remember { mutableStateOf(2) }
    var memMb by remember { mutableStateOf(2048) }
    var diskGb by remember { mutableStateOf(20) }
    var ssh by remember { mutableStateOf(true) }
    var runtime by remember { mutableStateOf(ContainerRuntime.BOTH) }
    var tailscale by remember { mutableStateOf(true) }
    var accelerator by remember { mutableStateOf(Accelerator.AUTO) }
    var networkMode by remember { mutableStateOf(NetworkMode.NAT) }
    var network by remember { mutableStateOf(server.network) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New VM on ${server.name}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Guest distro", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM)) {
                    GuestDistro.OPTIONS.forEach { (value, label) ->
                        FilterChip(selected = guestDistro == value, onClick = { guestDistro = value }, label = { Text(label) })
                    }
                }
                OutlinedTextField(value = vcpus.toString(), onValueChange = { vcpus = it.filter { c -> c.isDigit() }.toIntOrNull() ?: 1 }, label = { Text("vCPUs") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = memMb.toString(), onValueChange = { memMb = it.filter { c -> c.isDigit() }.toIntOrNull()?.coerceAtLeast(256) ?: 256 }, label = { Text("Memory (MB)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = diskGb.toString(), onValueChange = { diskGb = it.filter { c -> c.isDigit() }.toIntOrNull() ?: 20 }, label = { Text("Disk (GB)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Container runtime", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM)) {
                    ServersViewModel.RUNTIME_OPTIONS.forEach { (value, label) ->
                        FilterChip(selected = runtime == value, onClick = { runtime = value }, label = { Text(label) })
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("SSH access", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = ssh, onCheckedChange = { ssh = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Tailscale", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = tailscale, onCheckedChange = { tailscale = it })
                }
                Text("Acceleration", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM)) {
                    FilterChip(selected = accelerator == Accelerator.AUTO, onClick = { accelerator = Accelerator.AUTO }, label = { Text("Auto") })
                    FilterChip(selected = accelerator == Accelerator.KVM, onClick = { accelerator = Accelerator.KVM }, label = { Text("KVM") })
                    FilterChip(selected = accelerator == Accelerator.TCG, onClick = { accelerator = Accelerator.TCG }, label = { Text("TCG") })
                }
                Text(
                    if (accelerator == Accelerator.TCG)
                        "Software emulation (no /dev/kvm needed) — slower."
                    else
                        "Auto picks KVM when /dev/kvm exists, else TCG.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Network", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM)) {
                    FilterChip(selected = networkMode == NetworkMode.NAT, onClick = { networkMode = NetworkMode.NAT }, label = { Text("NAT") })
                    FilterChip(selected = networkMode == NetworkMode.BRIDGE, onClick = { networkMode = NetworkMode.BRIDGE }, label = { Text("Bridge") })
                }
                OutlinedTextField(
                    value = network,
                    onValueChange = { network = it },
                    label = { Text(if (networkMode == NetworkMode.BRIDGE) "Bridge name" else "Network name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(
                        RemoteVmSpec(
                            name = name.trim().ifEmpty { "vm" },
                            guestDistro = guestDistro,
                            vcpus = vcpus.coerceAtLeast(1),
                            memMb = memMb.coerceAtLeast(256),
                            diskGb = diskGb.coerceAtLeast(1),
                            sshEnabled = ssh,
                            containerRuntime = runtime,
                            tailscaleEnabled = tailscale,
                            accelerator = accelerator,
                            networkMode = networkMode,
                            network = network.trim().ifEmpty { "default" },
                        ),
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

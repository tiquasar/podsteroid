/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Add-server dialog: host/port/user, password or private-key auth, and an
 * optional base cloud-image override. Secrets are passed back raw and encrypted
 * by the ViewModel/manager (never stored in plaintext).
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
import com.tiquasar.podsteroid.remote.AuthKind
import com.tiquasar.podsteroid.remote.ServerKind
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens

@Composable
fun ServerEditDialog(
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        host: String,
        port: Int,
        username: String,
        authKind: AuthKind,
        secret: String,
        passphrase: String?,
        baseImage: String?,
        arch: String,
        network: String,
        kind: ServerKind,
    ) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("root") }
    var isKey by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var baseImage by remember { mutableStateOf("") }
    var arch by remember { mutableStateOf("amd64") }
    var network by remember { mutableStateOf("default") }
    var kind by remember { mutableStateOf(ServerKind.LIBVIRT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add server") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = port, onValueChange = { port = it.filter { c -> c.isDigit() } }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Server type", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM)) {
                    FilterChip(selected = kind == ServerKind.LIBVIRT, onClick = { kind = ServerKind.LIBVIRT }, label = { Text("Libvirt host") })
                    FilterChip(selected = kind == ServerKind.LINUX, onClick = { kind = ServerKind.LINUX }, label = { Text("Linux server") })
                }
                Text(
                    if (kind == ServerKind.LINUX)
                        "A plain Linux box — libvirt/KVM will be installed on first provision so you can create VMs."
                    else "Assumes libvirt/KVM is already installed on the host.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Guest architecture", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM)) {
                    FilterChip(selected = arch == "amd64", onClick = { arch = "amd64" }, label = { Text("amd64") })
                    FilterChip(selected = arch == "arm64", onClick = { arch = "arm64" }, label = { Text("arm64") })
                }
                OutlinedTextField(value = network, onValueChange = { network = it }, label = { Text("libvirt network") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
                ) {
                    FilterChip(selected = !isKey, onClick = { isKey = false }, label = { Text("Password") })
                    FilterChip(selected = isKey, onClick = { isKey = true }, label = { Text("Private key") })
                }
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(if (isKey) "Private key (PEM)" else "Password") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isKey) {
                    OutlinedTextField(value = passphrase, onValueChange = { passphrase = it }, label = { Text("Key passphrase (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(value = baseImage, onValueChange = { baseImage = it }, label = { Text("Base image URL (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val portNum = port.toIntOrNull()?.coerceIn(1, 65535) ?: 22
                    onSave(
                        name.trim(),
                        host.trim(),
                        portNum,
                        username.trim().ifEmpty { "root" },
                        if (isKey) AuthKind.KEY else AuthKind.PASSWORD,
                        secret,
                        if (isKey) passphrase.ifBlank { null } else null,
                        baseImage.trim().ifBlank { null },
                        arch,
                        network.trim().ifEmpty { "default" },
                        kind,
                    )
                },
                enabled = host.isNotBlank() && secret.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

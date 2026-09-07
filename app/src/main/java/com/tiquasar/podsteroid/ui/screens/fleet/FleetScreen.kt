package com.tiquasar.podsteroid.ui.screens.fleet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tiquasar.podsteroid.remote.FleetEntry
import com.tiquasar.podsteroid.remote.FleetKind
import com.tiquasar.podsteroid.remote.RemoteVmState
import com.tiquasar.podsteroid.ui.components.AdaptiveContainer
import com.tiquasar.podsteroid.ui.components.PodsteroidPrimaryButton
import com.tiquasar.podsteroid.ui.components.PodsteroidSectionLabel
import com.tiquasar.podsteroid.ui.components.PodsteroidStatus
import com.tiquasar.podsteroid.ui.components.PodsteroidStatusColors
import com.tiquasar.podsteroid.ui.components.PodsteroidTopBar
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens

@Composable
fun FleetScreen(
    viewModel: FleetViewModel = hiltViewModel(),
    windowSizeClass: androidx.compose.material3.windowsizeclass.WindowSizeClass,
    onBack: () -> Unit,
) {
    val fleet by viewModel.fleet.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var showCluster by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        PodsteroidTopBar(
            title = "Fleet",
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    androidx.compose.material3.Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        )
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier.fillMaxSize(),
            maxWidth = 700,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = PodsteroidTokens.Spacing.MD),
                verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.MD),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
                ) {
                    PodsteroidPrimaryButton(text = "Refresh Tailscale", onClick = { viewModel.refreshFleet() }, enabled = !busy, modifier = Modifier.weight(1f))
                    PodsteroidPrimaryButton(text = "Form cluster", onClick = { showCluster = true }, modifier = Modifier.weight(1f))
                }
                PodsteroidSectionLabel("Nodes (${fleet.size})")
                if (fleet.isEmpty()) {
                    Text("No VMs yet. Add a server or start a local VM.", style = MaterialTheme.typography.bodyMedium)
                }
                fleet.forEach { entry ->
                    FleetCard(entry)
                }
            }
        }
    }
    if (showCluster) {
        ClusterDialog(
            fleet = fleet.filter { it.kind == FleetKind.REMOTE && it.state == RemoteVmState.RUNNING },
            manager = viewModel,
            onDismiss = { showCluster = false },
        )
    }
}

@Composable
private fun FleetCard(entry: FleetEntry) {
    val reachable = entry.tailscaleIp ?: entry.guestIp
    Column(
        modifier = Modifier.fillMaxWidth().padding(PodsteroidTokens.Spacing.SM),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PodsteroidStatus(
                label = entry.kind.name,
                dotColor = if (entry.kind == FleetKind.LOCAL) PodsteroidStatusColors.Running else PodsteroidStatusColors.Starting,
            )
            Text(entry.label, style = MaterialTheme.typography.titleMedium)
            entry.serverName?.let {
                Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        PodsteroidStatus(
            label = entry.stateLabel,
            dotColor = when (entry.state) {
                RemoteVmState.RUNNING -> PodsteroidStatusColors.Running
                RemoteVmState.PENDING, RemoteVmState.PAUSED -> PodsteroidStatusColors.Starting
                RemoteVmState.OTHER -> PodsteroidStatusColors.Error
                else -> PodsteroidStatusColors.Stopped
            },
        )
        Text(
            "distro ${entry.guestDistro} · runtime ${entry.containerRuntime.name}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (reachable != null) {
            Text(
                "reachable: $reachable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = PodsteroidTokens.mono(),
            )
        } else {
            Text(
                "no routable address yet (needs Tailscale / guest push)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entry.magicDns?.let {
            Text("magicDNS: $it", style = MaterialTheme.typography.bodySmall, fontFamily = PodsteroidTokens.mono())
        }
    }
}

@Composable
private fun ClusterDialog(
    fleet: List<FleetEntry>,
    manager: FleetViewModel,
    onDismiss: () -> Unit,
) {
    var controlId by remember { mutableStateOf(fleet.firstOrNull()?.id ?: "") }
    val selectedAgents = remember { mutableStateOf(setOf<String>()) }
    var token by remember { mutableStateOf<String?>(null) }
    var controlUrl by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    fun entryById(id: String) = fleet.firstOrNull { it.id == id }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Form k3s cluster") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fleet.isEmpty()) {
                    Text("Need at least one running remote VM.")
                } else {
                    Text("Control node (k3s server)", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fleet.forEach { e ->
                            FilterChip(
                                selected = controlId == e.id,
                                onClick = { controlId = e.id },
                                label = { Text(e.label) },
                            )
                        }
                    }
                    Text(
                        "Step 1 - initialize the control node (installs + starts a k3s server). " +
                            "Its join token is then read from /var/lib/rancher/k3s/server/node-token.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PodsteroidPrimaryButton(
                        text = "Init control node (server)",
                        onClick = {
                            val ctrl = entryById(controlId) ?: return@PodsteroidPrimaryButton
                            working = true
                            log = ""
                            manager.initServer(ctrl) { res ->
                                log = "control node init: ${res?.stdout?.take(200) ?: "failed"}\n"
                                working = false
                            }
                        },
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PodsteroidPrimaryButton(onClick = {
                        val ctrl = entryById(controlId) ?: return@PodsteroidPrimaryButton
                        working = true
                        log = ""
                        manager.getToken(ctrl) { t, url ->
                            token = t
                            controlUrl = url
                            log = if (t != null) "token fetched for ${ctrl.label}\n" else "failed to fetch token (is the control node running a k3s server?)\n"
                            working = false
                        }
                    }, enabled = token == null && !working, text = "Get token", modifier = Modifier.fillMaxWidth())
                    token?.let {
                        Text("Step 2 - workers run setup-k3s-agent.sh:", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Installs the k3s agent, creates an OpenRC service, enables it at boot, and starts it on each selected worker.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Workers", style = MaterialTheme.typography.bodyMedium)
                        fleet.filter { it.id != controlId }.forEach { e ->
                            Row(
                                modifier = Modifier.fillMaxWidth().toggleable(
                                    value = selectedAgents.value.contains(e.id),
                                    onValueChange = { on ->
                                        selectedAgents.value = if (on) selectedAgents.value + e.id else selectedAgents.value - e.id
                                    },
                                ),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = selectedAgents.value.contains(e.id),
                                    onClick = { },
                                    label = { Text(e.label) },
                                )
                            }
                        }
                        PodsteroidPrimaryButton(
                            text = "Join selected",
                            onClick = {
                                working = true
                                val ctrl = entryById(controlId)!!
                                selectedAgents.value.forEach { aid ->
                                    val agent = entryById(aid) ?: return@forEach
                                    manager.join(agent, controlUrl!!, it) { res ->
                                        log += "${agent.label}: ${res.stdout.take(120)}\n"
                                    }
                                }
                                working = false
                            },
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (log.isNotBlank()) {
                        Text(log, fontFamily = PodsteroidTokens.mono(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Servers screen: registered libvirt/KVM servers, each expanded with its VMs
 * and lifecycle controls, plus add-server / new-VM dialogs.
 */
package com.tiquasar.podsteroid.ui.screens.servers

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiquasar.podsteroid.R
import com.tiquasar.podsteroid.engine.GuestDistro
import com.tiquasar.podsteroid.remote.AuthKind
import com.tiquasar.podsteroid.remote.ContainerRuntime
import com.tiquasar.podsteroid.remote.PrereqReport
import com.tiquasar.podsteroid.remote.ServerKind
import com.tiquasar.podsteroid.remote.RemoteServer
import com.tiquasar.podsteroid.remote.RemoteVm
import com.tiquasar.podsteroid.remote.RemoteVmSpec
import com.tiquasar.podsteroid.remote.RemoteVmState
import com.tiquasar.podsteroid.ui.components.AdaptiveContainer
import com.tiquasar.podsteroid.ui.components.PodsteroidAppear
import com.tiquasar.podsteroid.ui.components.PodsteroidCard
import com.tiquasar.podsteroid.ui.components.PodsteroidDestructiveButton
import com.tiquasar.podsteroid.ui.components.PodsteroidEmptyState
import com.tiquasar.podsteroid.ui.components.PodsteroidGhostButton
import com.tiquasar.podsteroid.ui.components.PodsteroidPrimaryButton
import com.tiquasar.podsteroid.ui.components.PodsteroidStatus
import com.tiquasar.podsteroid.ui.components.PodsteroidStatusColors
import com.tiquasar.podsteroid.ui.components.PodsteroidTopBar
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    windowSizeClass: androidx.compose.material3.windowsizeclass.WindowSizeClass,
    onNavigateBack: () -> Unit,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val vmsMap by viewModel.vms.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val serverBusyMap by viewModel.serverBusy.collectAsStateWithLifecycle()
    val serverStatusMap by viewModel.serverStatus.collectAsStateWithLifecycle()
    val serverResultMap by viewModel.serverResult.collectAsStateWithLifecycle()
    val serverLogMap by viewModel.serverLog.collectAsStateWithLifecycle()
    val serverStateMap by viewModel.serverState.collectAsStateWithLifecycle()

    var showAddServer by remember { mutableStateOf(false) }
    var createForServer by remember { mutableStateOf<RemoteServer?>(null) }
    var terminalFor by remember { mutableStateOf<Pair<RemoteServer, RemoteVm>?>(null) }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.toastConsumed()
        }
    }

    if (showAddServer) {
        ServerEditDialog(
            onDismiss = { showAddServer = false },
            onSave = { name, host, port, username, authKind, secret, passphrase, baseImage, arch, network, kind ->
                viewModel.addServer(name, host, port, username, authKind, secret, passphrase, baseImage, arch, network, kind)
                showAddServer = false
            },
        )
    }

    createForServer?.let { srv ->
        RemoteVmCreateDialog(
            server = srv,
            onDismiss = { createForServer = null },
            onCreate = { spec -> viewModel.createVm(srv, spec); createForServer = null },
        )
    }

    terminalFor?.let { (srv, vm) ->
        RemoteTerminalDialog(
            server = srv,
            vm = vm,
            manager = viewModel.manager,
            onDismiss = { terminalFor = null },
        )
    }

    PodsteroidTopBarScaffold(
        title = "Servers",
        onBack = onNavigateBack,
        actions = {
            FilledTonalButton(onClick = { showAddServer = true }) {
                Icon(
                    painterResource(R.drawable.ic_add),
                    // Was `null` (decorative). This icon is the only content of
                    // the FAB, so with a null description the button is
                    // unlabelled for screen readers.
                    contentDescription = stringResource(R.string.add_server),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(PodsteroidTokens.Spacing.XS))
                Text(stringResource(R.string.add_server))
            }
        },
    ) { innerPadding ->
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            maxWidth = 700,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PodsteroidTokens.Spacing.XL, vertical = PodsteroidTokens.Spacing.LG),
                verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.MD),
            ) {
                if (servers.isEmpty()) {
                    PodsteroidEmptyState(
                        icon = Icons.Default.DesktopWindows,
                        title = stringResource(R.string.empty_servers_title),
                        message = stringResource(R.string.empty_servers_message),
                        actionLabel = stringResource(R.string.add_server),
                        onAction = { showAddServer = true },
                    )
                }
                servers.forEachIndexed { i, server ->
                    PodsteroidAppear(delayMillis = i * 60) {
                        ServerCard(
                            server = server,
                            vms = vmsMap[server.id] ?: emptyList(),
                            isBusy = serverBusyMap[server.id] == true,
                            status = serverStatusMap[server.id],
                            state = serverStateMap[server.id],
                            result = serverResultMap[server.id],
                            log = serverLogMap[server.id],
                            onTest = { viewModel.test(server) },
                            onRefresh = { viewModel.refresh(server) },
                            onNewVm = { createForServer = server },
                            onDelete = { viewModel.deleteServer(server) },
                            onRemove = { viewModel.removeServer(server) },
                            onProvision = { viewModel.provision(server) },
                            onStartVm = { viewModel.startVm(server, it) },
                            onStopVm = { viewModel.stopVm(server, it) },
                            onDestroyVm = { vm -> viewModel.destroyVm(server, vm) },
                            onTerminal = { vm -> terminalFor = server to vm },
                        )
                    }
                }
                Spacer(Modifier.height(PodsteroidTokens.Spacing.XL))
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: RemoteServer,
    vms: List<RemoteVm>,
    isBusy: Boolean,
    status: String?,
    state: String?,
    result: PrereqReport?,
    log: String?,
    onTest: () -> Unit,
    onRefresh: () -> Unit,
    onNewVm: () -> Unit,
    onDelete: () -> Unit,
    onRemove: () -> Unit,
    onProvision: () -> Unit,
    onStartVm: (String) -> Unit,
    onStopVm: (String) -> Unit,
    onDestroyVm: (RemoteVm) -> Unit,
    onTerminal: (RemoteVm) -> Unit,
) {
    var showResult by remember { mutableStateOf(result != null) }
    LaunchedEffect(result) { if (result != null) showResult = true }
    var showLog by remember { mutableStateOf(log != null) }
    LaunchedEffect(log) { if (log != null) showLog = true }

    PodsteroidCard(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(ServerBadge.color(server.colorIndex)),
            )
            Text(server.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            ServerStatusChip(if (isBusy && state.isNullOrBlank()) "connecting" else state)
        }
        // A single busy affordance. There used to be a LinearProgressIndicator
        // here AND a CircularProgressIndicator + status text further down, both
        // gated on the identical condition — two spinners for one state. Worse,
        // when `status` was blank while busy, neither rendered and the card
        // looked idle. One indicator, always shown while busy, with a fallback
        // label so a silent state still reads as "working".
        if (isBusy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = PodsteroidTokens.Spacing.XS),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    status?.takeIf { it.isNotBlank() } ?: "Working…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            "${server.username}@${server.host}:${server.port} · ${server.arch} · ${server.kind.name.lowercase()} · net=${server.network}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = PodsteroidTokens.mono(),
        )
        server.hostKeyFingerprint?.let {
            Text(
                "host key: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                fontFamily = PodsteroidTokens.mono(),
            )
        }
            log?.let { logText ->
                Spacer(Modifier.height(PodsteroidTokens.Spacing.SM))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Announce this as a button so screen readers and
                        // keyboard/switch-access users can operate the
                        // disclosure. A bare clickable Row is invisible to them.
                        .semantics { role = Role.Button }
                        .clickable { showLog = !showLog },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Activity",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        if (showLog) "hide ▴" else "details ▾",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                AnimatedVisibility(
                    visible = showLog,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Spacer(Modifier.height(PodsteroidTokens.Spacing.XS))
                    Text(
                        logText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = PodsteroidTokens.mono(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.XS),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val deleted = state == "deleted"
                PodsteroidGhostButton("Test", onClick = onTest, modifier = Modifier.weight(1f), enabled = !isBusy && !deleted)
                PodsteroidGhostButton("Refresh", onClick = onRefresh, modifier = Modifier.weight(1f), enabled = !isBusy && !deleted)
                PodsteroidGhostButton("New VM", onClick = onNewVm, modifier = Modifier.weight(1f), enabled = !isBusy && !deleted)
                if (deleted) {
                    PodsteroidDestructiveButton("Remove Server", onClick = onRemove, modifier = Modifier.weight(1f))
                } else {
                    PodsteroidDestructiveButton("Delete", onClick = onDelete, modifier = Modifier.weight(1f))
                }
            }
            result?.let { rep ->
                Spacer(Modifier.height(PodsteroidTokens.Spacing.SM))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { role = Role.Button }
                        .clickable { showResult = !showResult },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Connection prereqs",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        if (showResult) "hide ▴" else "details ▾",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                AnimatedVisibility(
                    visible = showResult,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Spacer(Modifier.height(PodsteroidTokens.Spacing.XS))
                    Column(verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.XS / 2)) {
                        PrereqLine("virsh", rep.virsh)
                        PrereqLine("virt-install", rep.virtInstall)
                        PrereqLine("qemu-img", rep.qemuImg)
                        PrereqLine("cloud-init", rep.cloudInit)
                        PrereqLine("default network", rep.defaultNet)
                    }
                }
            }
            Spacer(Modifier.height(PodsteroidTokens.Spacing.SM))
            PodsteroidPrimaryButton(
                "Provision libvirt",
                onClick = onProvision,
                enabled = !isBusy && state != "deleted",
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Installs qemu-kvm + libvirt + virt-install on this host, then brings up the default network so VMs can be created.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PodsteroidTokens.Spacing.SM))
            // Matches the empty state used for the server list above — previously
            // this was a bare one-line Text with no icon and no call to action,
            // so the "New VM" affordance was easy to miss on a fresh host.
            if (vms.isEmpty() && !isBusy) {
                PodsteroidEmptyState(
                    icon = Icons.Default.DesktopWindows,
                    title = "No VMs yet",
                    message = "Create a VM on this host to get started.",
                    actionLabel = "New VM",
                    onAction = onNewVm,
                )
            }
            vms.forEach { vm ->
                RemoteVmRow(
                    vm = vm,
                    badge = ServerBadge.color(server.colorIndex),
                    onStart = { onStartVm(vm.name) },
                    onStop = { onStopVm(vm.name) },
                    onDestroy = { onDestroyVm(vm) },
                    onTerminal = { onTerminal(vm) },
                )
            }
    }
}

@Composable
private fun ServerStatusChip(state: String?) {
    if (state.isNullOrBlank()) return
    val (label, color) = when (state) {
        "online" -> "Online" to PodsteroidStatusColors.Running
        "offline" -> "Offline" to PodsteroidStatusColors.Stopped
        "error" -> "Error" to PodsteroidStatusColors.Stopped
        "connecting" -> "Connecting…" to PodsteroidStatusColors.Starting
        "deleting" -> "Deleting…" to PodsteroidStatusColors.Starting
        "deleted" -> "Deleted" to PodsteroidStatusColors.Stopped
        else -> return
    }
    PodsteroidStatus(label = label, dotColor = color)
}

@Composable
private fun PrereqLine(label: String, ok: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM - PodsteroidTokens.Spacing.XS),
    ) {
        Text(
            if (ok) "✓" else "✗",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (ok) PodsteroidStatusColors.Running else PodsteroidStatusColors.Stopped,
        )
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RemoteVmRow(
    vm: RemoteVm,
    badge: Color,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDestroy: () -> Unit,
    onTerminal: () -> Unit,
) {
    val (label, color) = when (vm.state) {
        RemoteVmState.RUNNING -> "Running" to PodsteroidStatusColors.Running
        RemoteVmState.PAUSED -> "Paused" to PodsteroidStatusColors.Starting
        RemoteVmState.PENDING -> "Pending" to PodsteroidStatusColors.Starting
        RemoteVmState.SHUTOFF -> "Stopped" to PodsteroidStatusColors.Stopped
        else -> "Unknown" to PodsteroidStatusColors.Stopped
    }
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = PodsteroidTokens.Spacing.XS),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .padding(PodsteroidTokens.Spacing.SM)
                // Smoothly resize as the row expands/collapses instead of
                // snapping, so the list doesn't jump under the user's finger.
                .animateContentSize(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { role = Role.Button }
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(badge),
                    )
                    Text(vm.name, style = MaterialTheme.typography.bodyMedium)
                }
                Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.labelMedium)
                PodsteroidStatus(label = label, dotColor = color)
            }
            Text(
                "${vm.guestDistro} · ${vm.vcpus} CPU · ${vm.memMb} MB · ${vm.diskGb} GB · ${vm.containerRuntime.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Spacer(Modifier.height(PodsteroidTokens.Spacing.XS))
                Column(verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.XS / 2)) {
                    Text(
                        "uuid: ${vm.uuid}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = PodsteroidTokens.mono(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!vm.guestIp.isNullOrBlank()) {
                        Text(
                            "ip: ${vm.guestIp}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = PodsteroidTokens.mono(),
                        )
                        Text(
                            "ssh root@${vm.guestIp}  (pw: podsteroid)",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = PodsteroidTokens.mono(),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            "ip: pending (guest still booting or no DHCP lease yet)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "created: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(vm.createdAt))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (vm.state == RemoteVmState.RUNNING) {
                    PodsteroidGhostButton("Stop", onClick = onStop, modifier = Modifier.weight(1f))
                } else {
                    PodsteroidPrimaryButton("Start", onClick = onStart, modifier = Modifier.weight(1f))
                }
                PodsteroidGhostButton(
                    "Terminal",
                    onClick = onTerminal,
                    modifier = Modifier.weight(1f),
                    enabled = vm.state == RemoteVmState.RUNNING,
                )
                PodsteroidDestructiveButton("Destroy", onClick = onDestroy, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Small Scaffold wrapper with our top bar (avoids duplicating the boilerplate). */
@Composable
private fun PodsteroidTopBarScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    androidx.compose.material3.Scaffold(
        topBar = {
            PodsteroidTopBar(
                title = title,
                actions = { actions() },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { content(it) }
}

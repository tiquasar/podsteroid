package com.tiquasar.podsteroid.ui.screens.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.tiquasar.podsteroid.remote.RemoteServer
import com.tiquasar.podsteroid.remote.RemoteServerManager
import com.tiquasar.podsteroid.remote.RemoteVm
import com.tiquasar.podsteroid.remote.SshClient
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens
import java.util.concurrent.atomic.AtomicLong

/** One captured chunk. The id gives LazyColumn a stable key (chunks repeat). */
private data class TermLine(val id: Long, val text: String)

/**
 * Cap retained output. A shell session emits an unbounded number of chunks —
 * `tail -f`, a package install, or just a long uptime would otherwise grow this
 * list until the process died. Old lines are dropped from the front.
 */
private const val MAX_TERM_LINES = 1000

/**
 * A lightweight interactive terminal into a remote guest. Uses an SSH shell
 * channel (JSch) rather than the Termux PTY because the guest is reached over
 * Tailscale/SSH, not a local virtio console.
 */
@Composable
fun RemoteTerminalDialog(
    server: RemoteServer,
    vm: RemoteVm,
    manager: RemoteServerManager,
    onDismiss: () -> Unit,
) {
    val lines = remember { mutableStateListOf<TermLine>() }
    val idGen = remember { AtomicLong(0) }
    val lazyState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<SshClient.ShellSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Distinguishes "still opening the channel" from "opened, but no output yet"
    // so we can show a spinner instead of a blank pane.
    var connecting by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        manager.shellToGuest(
            server = server,
            vm = vm,
            onData = { chunk ->
                lines.add(TermLine(idGen.getAndIncrement(), chunk))
                val overflow = lines.size - MAX_TERM_LINES
                if (overflow > 0) lines.subList(0, overflow).clear()
            },
            onClosed = {
                session = null
                connecting = false
            },
        ).onSuccess {
            session = it
            connecting = false
        }.onFailure {
            error = it.message
            connecting = false
        }
    }

    // Follow the output. Previously lazyState was created and never used, so new
    // lines appeared below the fold and the terminal looked frozen.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            lazyState.animateScrollToItem(lines.size - 1)
        }
    }

    // Close the SSH shell channel when the dialog leaves the screen. Without this
    // the channel (and its server-side process) stayed open until the connection
    // was torn down — every open/close of this dialog leaked one.
    DisposableEffect(Unit) {
        onDispose {
            session?.close()
            session = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(PodsteroidTokens.Spacing.SM),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${vm.name} (${vm.tailscaleIp ?: vm.guestIp})", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = {
                session?.close()
                session = null
                onDismiss()
            }) { Text("Close") }
        }

        if (connecting) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            if (error != null && session == null) {
                Text(
                    "Connection failed: $error",
                    // Was Color.Red, which does not adapt to the theme and is
                    // near-unreadable on some dark surfaces.
                    color = MaterialTheme.colorScheme.error,
                )
            }
            LazyColumn(state = lazyState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(lines, key = { it.id }) { line ->
                    Text(
                        line.text,
                        fontFamily = PodsteroidTokens.mono(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("command") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                session?.send(input)
                input = ""
            }),
        )
        Button(
            onClick = {
                session?.send(input)
                input = ""
            },
            modifier = Modifier.fillMaxWidth(),
            // Sending to a null session silently dropped the input.
            enabled = session != null && input.isNotBlank(),
        ) { Text("Send") }
    }
}

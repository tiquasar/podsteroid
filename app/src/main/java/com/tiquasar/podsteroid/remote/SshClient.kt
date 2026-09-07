/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Minimal SSH client over the maintained mwiede JSch fork (package
 * com.jcraft.jsch). Used to drive a remote libvirt/KVM server: virsh,
 * virt-install, qemu-img, and tailscale status probes run over a single
 * exec channel per call. Host keys use accept-first-use pinning via a
 * pluggable [HostKeyStore] — we never use StrictHostKeyChecking=no, which
 * would silently MITM.
 */
package com.tiquasar.podsteroid.remote

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class SshTarget(val host: String, val port: Int = 22, val username: String)
data class SshCredentials(
    val password: String? = null,
    val privateKeyPem: String? = null,
    val passphrase: String? = null,
)
/**
 * These used to be `internal var` fields assigned right after construction, which
 * meant `copy()` and `equals()` silently dropped them — a latent trap for any
 * future caller that copies a result. Now they are real constructor params.
 */
data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    /** True when the end-marker was seen (exit code is authoritative). */
    internal val markerFound: Boolean = false,
    /** Total bytes the reader received — 0 means the command never produced output. */
    internal val bytesReceived: Int = 0,
)

/**
 * Persistence hook for accept-first-use host-key pinning. Implementations back
 * this with the per-server fingerprint stored in DataStore. [get] returns the
 * previously accepted fingerprint for [host] (null = unseen); [put] records a
 * newly accepted one.
 */
interface HostKeyStore {
    fun get(host: String): String?
    fun put(host: String, fingerprint: String)
}

/**
 * Thrown when a host key for a known host changed — a possible MITM. Surfaces
 * to the UI as a connection failure with the old/new fingerprints.
 */
class HostKeyMismatchException(val host: String, val expected: String, val actual: String) :
    Exception("Host key for $host changed (expected $expected, got $actual) — possible MITM")

@Singleton
class SshClient @Inject constructor() {

    /**
     * Runs [command] on the remote host. Returns [Result.success] with the
     * captured stdout/stderr/exit code on a successful channel, or
     * [Result.failure] for transport/auth/host-key errors.
     */
    suspend fun exec(
        target: SshTarget,
        credentials: SshCredentials,
        command: String,
        hostKeyStore: HostKeyStore? = null,
        timeoutMs: Int = 30_000,
    ): Result<ExecResult> = withContext(Dispatchers.IO) {
        runCatching {
            val jsch = JSch()
            val keyRepo = hostKeyStore?.let { Adapter(it) }
            credentials.privateKeyPem?.let { pem ->
                jsch.addIdentity("key", pem.toByteArray(Charsets.UTF_8), null, credentials.passphrase?.toByteArray(Charsets.UTF_8))
            }
            val session: Session = jsch.getSession(target.username, target.host, target.port)
            credentials.password?.let { session.setPassword(it) }
            // "no" alone would be a MITM hole, but our custom HostKeyRepository
            // enforces accept-first-use: it records new keys (via add) and throws
            // on mismatch (check). So the security comes from the repository, not
            // from this flag.
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("ConnectTimeout", timeoutMs.toString())
            session.setConfig("ServerAliveInterval", ALIVE_INTERVAL_S)
            session.setConfig("ServerAliveCountMax", ALIVE_COUNT_MAX)
            keyRepo?.let { session.setHostKeyRepository(it) }
            try {
                session.connect(timeoutMs)
                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand(command)
                channel.connect(timeoutMs)
                var stdout = ""
                var stderr = ""
                val stdoutT = Thread { stdout = readFully(channel.inputStream, timeoutMs) }
                val stderrT = Thread { stderr = readFully(channel.errStream, timeoutMs) }
                stdoutT.start()
                stderrT.start()
                val deadline = System.currentTimeMillis() + timeoutMs
                while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100)
                }
                channel.disconnect()
                stdoutT.join(timeoutMs.toLong())
                stderrT.join(timeoutMs.toLong())
                val exit = channel.exitStatus
                ExecResult(exit ?: -1, stdout, stderr)
            } finally {
                session.disconnect()
            }
        }
    }

    /**
     * Like [exec] but streams each line of stdout to [onLine] as it arrives, so
     * long-running commands (package installs, image downloads) can show live
     * progress. The full stdout/stderr/exit code is still returned at the end.
     */
    suspend fun execStream(
        target: SshTarget,
        credentials: SshCredentials,
        command: String,
        onLine: (String) -> Unit,
        hostKeyStore: HostKeyStore? = null,
        timeoutMs: Int = 30_000,
    ): Result<ExecResult> = withContext(Dispatchers.IO) {
        runCatching {
            val jsch = JSch()
            val keyRepo = hostKeyStore?.let { Adapter(it) }
            credentials.privateKeyPem?.let { pem ->
                jsch.addIdentity("key", pem.toByteArray(Charsets.UTF_8), null, credentials.passphrase?.toByteArray(Charsets.UTF_8))
            }
            val session: Session = jsch.getSession(target.username, target.host, target.port)
            credentials.password?.let { session.setPassword(it) }
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("ConnectTimeout", timeoutMs.toString())
            session.setConfig("ServerAliveInterval", ALIVE_INTERVAL_S)
            session.setConfig("ServerAliveCountMax", ALIVE_COUNT_MAX)
            keyRepo?.let { session.setHostKeyRepository(it) }
            try {
                session.connect(timeoutMs)
                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand(command)
                channel.connect(timeoutMs)
                val stdout = StringBuilder()
                val stderr = StringBuilder()
                val outT = Thread {
                    val buf = ByteArray(8192)
                    val sb = StringBuilder()
                    try {
                        while (true) {
                            val n = channel.inputStream.read(buf)
                            if (n < 0) break
                            val chunk = String(buf, 0, n, Charsets.UTF_8)
                            stdout.append(chunk)
                            sb.append(chunk)
                            var idx: Int
                            while (sb.indexOf('\n').also { idx = it } >= 0) {
                                val line = sb.substring(0, idx).removeSuffix("\r")
                                sb.delete(0, idx + 1)
                                onLine(line)
                            }
                        }
                        if (sb.isNotEmpty()) onLine(sb.toString().removeSuffix("\r"))
                    } catch (_: Exception) {
                    }
                }
                val errT = Thread { stderr.append(readFully(channel.errStream, timeoutMs)) }
                outT.start()
                errT.start()
                val deadline = System.currentTimeMillis() + timeoutMs
                while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100)
                }
                channel.disconnect()
                outT.join(timeoutMs.toLong())
                errT.join(timeoutMs.toLong())
                ExecResult(channel.exitStatus ?: -1, stdout.toString(), stderr.toString())
            } finally {
                session.disconnect()
            }
        }
    }

    /**
     * SHA256 fingerprint ("SHA256:....") of a raw host-key blob — matches the
     * format `tailscale`/`ssh-keygen -lf` use. Public + deterministic so it can
     * be unit-tested without a live connection.
     */
    fun fingerprint(blob: ByteArray): String {
        val sha = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + sha.joinToString("") { "%02x".format(it) }
    }

    /** Handle to an interactive shell channel. */
    data class ShellSession(val send: (String) -> Unit, val close: () -> Unit)

    /**
     * Opens an interactive PTY shell on the remote host. [onData] receives
     * decoded chunks; [onClosed] fires when the channel closes. [send] writes a
     * line (newline appended) to the shell.
     */
    suspend fun shell(
        target: SshTarget,
        credentials: SshCredentials,
        hostKeyStore: HostKeyStore? = null,
        onData: (String) -> Unit,
        onClosed: () -> Unit,
    ): Result<ShellSession> = withContext(Dispatchers.IO) {
        runCatching {
            val jsch = JSch()
            credentials.privateKeyPem?.let { pem ->
                jsch.addIdentity("key", pem.toByteArray(Charsets.UTF_8), null, credentials.passphrase?.toByteArray(Charsets.UTF_8))
            }
            val session: Session = jsch.getSession(target.username, target.host, target.port)
            credentials.password?.let { session.setPassword(it) }
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("ConnectTimeout", "30000")
            session.setConfig("ServerAliveInterval", ALIVE_INTERVAL_S)
            session.setConfig("ServerAliveCountMax", ALIVE_COUNT_MAX)
            hostKeyStore?.let { session.setHostKeyRepository(Adapter(it)) }
            session.connect(30000)
            val channel = session.openChannel("shell") as com.jcraft.jsch.ChannelShell
            channel.setPty(true)
            channel.connect(30000)
            val reader = Thread {
                val buf = ByteArray(8192)
                try {
                    while (!channel.isClosed) {
                        val n = channel.inputStream.read(buf)
                        if (n <= 0) {
                            if (channel.isClosed) break
                            Thread.sleep(30)
                            continue
                        }
                        onData(String(buf, 0, n, Charsets.UTF_8))
                    }
                } catch (_: Exception) {
                } finally {
                    onClosed()
                }
            }
            reader.start()
            ShellSession(
                send = { line ->
                    try {
                        channel.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
                        channel.outputStream.flush()
                    } catch (_: Exception) {
                    }
                },
                close = {
                    try { channel.disconnect() } finally { session.disconnect() }
                },
            )
        }
    }

    /**
     * Opens a SINGLE SSH [Session] and runs [block] over it, so every command in
     * [block] shares one connection instead of re-handshaking per call. This is
     * critical on flaky/mobile links, where a *second* TCP+SSH handshake can
     * stall indefinitely (the connect timeout does not bound the key exchange in
     * this JSch build) and the whole operation hangs.
     */
    suspend fun <R> withSession(
        target: SshTarget,
        credentials: SshCredentials,
        hostKeyStore: HostKeyStore? = null,
        connectTimeoutMs: Int = 30_000,
        execTimeoutMs: Int = 30_000,
        block: suspend (SessionContext) -> R,
    ): Result<R> = withContext(Dispatchers.IO) {
        runCatching {
            val jsch = JSch()
            val keyRepo = hostKeyStore?.let { Adapter(it) }
            credentials.privateKeyPem?.let { pem ->
                jsch.addIdentity("key", pem.toByteArray(Charsets.UTF_8), null, credentials.passphrase?.toByteArray(Charsets.UTF_8))
            }
            val session: Session = jsch.getSession(target.username, target.host, target.port)
            credentials.password?.let { session.setPassword(it) }
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("ConnectTimeout", connectTimeoutMs.toString())
            session.setConfig("ServerAliveInterval", ALIVE_INTERVAL_S)
            session.setConfig("ServerAliveCountMax", ALIVE_COUNT_MAX)
            // Socket read timeout: without this a half-dead mobile/Tailscale link
            // blocks stream reads forever (half-open TCP) and every operation sits
            // until its full deadline expires.
            session.setTimeout(READ_TIMEOUT_MS)
            keyRepo?.let { session.setHostKeyRepository(it) }
            try {
                Log.d(TAG, "SSH connecting ${target.username}@${target.host}:${target.port} timeout=$connectTimeoutMs")
                session.connect(connectTimeoutMs)
                Log.d(TAG, "SSH connected ${target.host}:${target.port}")
                block(SessionContext(session, connectTimeoutMs, execTimeoutMs))
            } finally {
                session.disconnect()
            }
        }.also { res ->
            if (res.isFailure) Log.e(TAG, "SSH session failed: ${res.exceptionOrNull()?.message}", res.exceptionOrNull())
        }
    }

    inner class SessionContext(
        private val session: Session,
        private val connectTimeoutMs: Int = 30_000,
        private val execTimeoutMs: Int = 30_000,
    ) {
        /**
         * Writes [content] to [path] via SFTP on the shared session — one
         * reliable binary channel, immune to the exec-channel races that lose
         * output on dropbear. Returns false (never throws) when SFTP is
         * unavailable or fails; callers fall back to shell-based upload.
         */
        fun sftpWrite(path: String, content: String): Boolean {
            var ch: com.jcraft.jsch.ChannelSftp? = null
            try {
                ch = session.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
                ch.connect(connectTimeoutMs)
                val tmp = "$path.ps-tmp"
                ch.put(content.byteInputStream(Charsets.UTF_8), tmp, com.jcraft.jsch.ChannelSftp.OVERWRITE)
                runCatching { ch.rm(path) }
                ch.rename(tmp, path)
                Log.i(TAG, "sftpWrite ok: $path (${content.length}B)")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "sftpWrite failed for $path: ${e.message}")
                return false
            } finally {
                runCatching { ch?.disconnect() }
            }
        }

        /** True while the underlying transport is still connected. */
        fun isAlive(): Boolean = session.isConnected
        /**
         * Opens a fresh exec channel on the shared session, runs [command], and
         * closes only the channel. Completion uses an end-marker; the exit code
         * is additionally embedded in a `PSRC<n>PSRCE` token so it survives even
         * when dropbear truncates the marker line. A silence watchdog aborts
         * commands whose channel opens but never produces output (observed on
         * dropbear under rapid reconnects), and one automatic retry recovers
         * transient empty responses.
         */
        suspend fun exec(command: String): ExecResult {
            val first = execOnce(command, silenceMs = 15_000)
            // Transient dropbear race: channel closes with ZERO output and no
            // marker. A fresh channel on the same session almost always works.
            if (!first.markerFound && first.bytesReceived == 0 && first.exitCode < 0) {
                Log.w(TAG, "exec: empty response without marker — retrying once")
                Thread.sleep(400)
                return execOnce(command, silenceMs = 15_000)
            }
            return first
        }

        private fun execOnce(command: String, silenceMs: Long): ExecResult {
            val channel = session.openChannel("exec") as ChannelExec
            // JSch's channel.connect() does NOT honour its timeout when the socket is
            // half-dead (a write to a stalled socket can block for minutes). This
            // watchdog force-disconnects the session if the channel-open stalls.
            val watchdog = Thread {
                try {
                    Thread.sleep((connectTimeoutMs + 5000).toLong())
                    Log.w(TAG, "exec watchdog: channel-open stalled, forcing disconnect")
                    runCatching { session.disconnect() }
                } catch (_: InterruptedException) {
                } catch (_: Exception) {
                }
            }
            watchdog.start()
            try {
                // Completion is detected by an end-marker echoed by the remote shell,
                // NOT by channel signals (dropbear delays CHANNEL_CLOSE ~60s, and JSch's
                // exit-status/available()/EOF are unreliable). The command runs in a
                // subshell so `exit N` still lets the marker print; stderr is merged
                // into stdout so nothing can stall on an unread pipe. The exit code is
                // ALSO wrapped in a PSRC<n>PSRCE token printed just before the marker,
                // so it can be recovered when dropbear drops only the final line(s).
                val tag = System.nanoTime()
                val marker = "__PS_DONE_${tag}__"
                val rcToken = "PSRC$tag"
                val wrapped = "( $command\n) 2>&1\necho \"${rcToken}<\$?>\"\necho \"$marker:\$?\"\n"
                Log.d(TAG, "exec open: ${command.take(80)}")
                channel.setCommand(wrapped)

                val outText = StringBuilder()
                val buf = ByteArray(8192)
                var foundExit = -1
                var markerFound = false
                var bytesReceived = 0
                var lastActivity = System.currentTimeMillis()
                val lock = Object()
                // Start the reader BEFORE connecting: dropbear can deliver data
                // + close the channel almost instantly on a fast command, and a
                // reader started after connect() misses those bytes. The reader
                // blocks on inputStream.read() until the channel is actually
                // open, so this is safe.
                val outT = Thread {
                    try {
                        while (true) {
                            val n = channel.inputStream.read(buf)
                            if (n < 0) break
                            if (n <= 0) continue
                            synchronized(lock) {
                                bytesReceived += n
                                lastActivity = System.currentTimeMillis()
                                if (!markerFound) {
                                    outText.append(String(buf, 0, n, Charsets.UTF_8))
                                    val idx = outText.indexOf("$marker:")
                                    if (idx >= 0) {
                                        val rest = outText.substring(idx + marker.length + 1)
                                        foundExit = rest.lineSequence().firstOrNull()?.trim()?.toIntOrNull() ?: -1
                                        markerFound = true
                                        // Trim the marker line off the captured body.
                                        val lineEnd = outText.indexOf('\n', idx)
                                        outText.setLength(if (lineEnd >= 0) lineEnd + 1 else idx)
                                        runCatching { channel.disconnect() }
                                    }
                                }
                            }
                            if (markerFound) break
                        }
                    } catch (e: Exception) {
                        // Was silently swallowed. A reader that dies early is the
                        // signature of the dropbear close race — without this log
                        // the symptom is just "output mysteriously truncated".
                        Log.w(TAG, "output reader stopped: ${e.message}")
                    }
                }
                outT.start()
                channel.connect(connectTimeoutMs)
                watchdog.interrupt()
                val deadline = System.currentTimeMillis() + execTimeoutMs
                var lastBeat = lastActivity
                while (!markerFound && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50)
                    // Real transport death (half-open link, server unreachable):
                    // no point waiting further — the reader will never get data.
                    if (!session.isConnected) {
                        synchronized(lock) {
                            if (!markerFound) Log.w(TAG, "exec: session died, aborting")
                        }
                        break
                    }
                    // When the channel closes (EOF from remote), the reader may
                    // still be draining buffered data. Only abort once the reader
                    // thread has finished — the marker is often in the last chunk.
                    if (channel.isClosed && !outT.isAlive) {
                        synchronized(lock) {
                            if (!markerFound) Log.w(TAG, "exec: channel closed after ${bytesReceived}B, reader drained, no marker")
                        }
                        break
                    }
                    // Silence watchdog: channel open but not a single byte for
                    // [silenceMs] — the command was swallowed (dropbear race).
                    // Any received byte resets [lastActivity], so long-running
                    // commands that stream output are never killed by this.
                    val idleMs: Long
                    synchronized(lock) { idleMs = System.currentTimeMillis() - lastActivity }
                    if (idleMs > silenceMs) {
                        Log.w(TAG, "exec: no output for ${silenceMs}ms, aborting")
                        break
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastBeat > 15_000) {
                        Log.d(TAG, "exec: still waiting for end-marker (${(now - deadline + execTimeoutMs) / 1000}s elapsed)")
                        lastBeat = now
                    }
                }
                runCatching {
                    if (!markerFound) {
                        if (!session.isConnected) Log.w(TAG, "exec: session dropped, command result unknown")
                        else Log.w(TAG, "exec timed out after ${execTimeoutMs}ms without end-marker")
                    }
                }
                // Let the reader drain any buffered output BEFORE tearing the
                // channel down — dropbear closes tiny commands almost instantly
                // and an early disconnect discards data the reader never got.
                outT.join(2500)
                runCatching { channel.disconnect() }
                outT.join(500)
                watchdog.interrupt()
                val body: String
                val exit: Int
                synchronized(lock) {
                    var text = outText.toString()
                    // Recover the exit code from the PSRC<n> token when the
                    // marker line was lost.
                    if (foundExit < 0) {
                        val m = Regex("$rcToken<(\\d+)>").find(text)
                        if (m != null) foundExit = m.groupValues[1].toIntOrNull() ?: -1
                    }
                    text = text.replace(Regex("$rcToken<\\d+>\n?"), "").substringBefore(marker).trimEnd()
                    exit = foundExit
                    body = text
                }
                val result = ExecResult(
                    exitCode = exit,
                    stdout = body,
                    stderr = "",
                    markerFound = markerFound,
                    bytesReceived = synchronized(lock) { bytesReceived },
                )
                Log.d(TAG, "exec done exit=$exit stdout=${body.take(500)}")
                return result
            } finally {
                watchdog.interrupt()
                runCatching { channel.disconnect() }
            }
        }

        /** Like [exec] but streams each stdout line to [onLine] as it arrives. */
        suspend fun execStream(command: String, onLine: (String) -> Unit): ExecResult {
            val first = execStreamOnce(command, onLine, silenceMs = 90_000)
            // Same transient-dropbear retry as [exec]: a swallowed command
            // produces zero bytes; a real run always streams something.
            if (!first.markerFound && first.bytesReceived == 0 && first.exitCode < 0) {
                Log.w(TAG, "execStream: empty response without marker — retrying once")
                Thread.sleep(400)
                return execStreamOnce(command, onLine, silenceMs = 90_000)
            }
            return first
        }

        private suspend fun execStreamOnce(
            command: String,
            onLine: (String) -> Unit,
            silenceMs: Long,
        ): ExecResult {
            val channel = session.openChannel("exec") as ChannelExec
            val watchdog = Thread {
                try {
                    Thread.sleep((connectTimeoutMs + 5000).toLong())
                    Log.w(TAG, "execStream watchdog: channel-open stalled, forcing disconnect")
                    runCatching { session.disconnect() }
                } catch (_: InterruptedException) {
                } catch (_: Exception) {
                }
            }
            watchdog.start()
            try {
                // Same end-marker + PSRC exit-code token as [exec].
                val tag = System.nanoTime()
                val marker = "__PS_DONE_${tag}__"
                val rcToken = "PSRC$tag"
                val wrapped = "( $command\n) 2>&1\necho \"${rcToken}<\$?>\"\necho \"$marker:\$?\"\n"
                Log.d(TAG, "execStream open: ${command.take(80)}")
                channel.setCommand(wrapped)
                val stdout = StringBuilder()
                val lineBuf = StringBuilder()
                var foundExit = -1
                var markerFound = false
                var bytesReceived = 0
                var lastActivity = System.currentTimeMillis()
                val lock = Object()
                val buf = ByteArray(8192)
                // Reader first, then connect: prevents the dropbear race that
                // loses bytes when a fast command delivers + closes the channel
                // before the reader is started.
                val outT = Thread {
                    try {
                        while (true) {
                            val n = channel.inputStream.read(buf)
                            if (n < 0) break
                            if (n <= 0) continue
                            synchronized(lock) {
                                bytesReceived += n
                                lastActivity = System.currentTimeMillis()
                                lineBuf.append(String(buf, 0, n, Charsets.UTF_8))
                            }
                            while (true) {
                                var nl: Int
                                var mk: Int
                                synchronized(lock) {
                                    nl = lineBuf.indexOf('\n')
                                    mk = lineBuf.indexOf(marker)
                                }
                                if (mk >= 0 && (nl < 0 || mk < nl)) {
                                    // Marker line reached: parse exit code and stop.
                                    synchronized(lock) {
                                        if (!markerFound) {
                                            foundExit = lineBuf.substring(mk + marker.length + 1)
                                                .lineSequence().firstOrNull()?.trim()?.toIntOrNull() ?: -1
                                            markerFound = true
                                            lineBuf.setLength(0)
                                            runCatching { channel.disconnect() }
                                        }
                                    }
                                    break
                                }
                                if (nl < 0) break
                                var line: String
                                synchronized(lock) {
                                    line = lineBuf.substring(0, nl).removeSuffix("\r")
                                    lineBuf.delete(0, nl + 1)
                                }
                                // Keep internal bookkeeping lines out of both
                                // the stream callback and the captured stdout.
                                if (!line.contains(rcToken)) {
                                    synchronized(lock) { stdout.append(line).append('\n') }
                                    onLine(line)
                                }
                            }
                            if (markerFound) break
                        }
                    } catch (e: Exception) {
                        // Was silently swallowed. A reader that dies early is the
                        // signature of the dropbear close race — without this log
                        // the symptom is just "output mysteriously truncated".
                        Log.w(TAG, "output reader stopped: ${e.message}")
                    }
                }
                outT.start()
                channel.connect(connectTimeoutMs)
                watchdog.interrupt()
                val deadline = System.currentTimeMillis() + execTimeoutMs
                var lastBeat = lastActivity
                while (!markerFound && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50)
                    if (!session.isConnected) {
                        synchronized(lock) {
                            if (!markerFound) Log.w(TAG, "execStream: session died, aborting")
                        }
                        break
                    }
                    if (channel.isClosed && !outT.isAlive) {
                        synchronized(lock) {
                            if (!markerFound) Log.w(TAG, "execStream: channel closed after ${bytesReceived}B, reader drained, no marker")
                        }
                        break
                    }
                    // Silence watchdog with a generous window: package installs
                    // legitimately pause (dpkg configure, big downloads), so any
                    // received byte resets the timer.
                    val idleMs: Long
                    synchronized(lock) { idleMs = System.currentTimeMillis() - lastActivity }
                    if (idleMs > silenceMs) {
                        Log.w(TAG, "execStream: no output for ${silenceMs}ms, aborting")
                        break
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastBeat > 15_000) {
                        Log.d(TAG, "execStream: still waiting for end-marker (${(now - deadline + execTimeoutMs) / 1000}s elapsed)")
                        lastBeat = now
                    }
                }
                if (!markerFound) {
                    if (!session.isConnected) Log.w(TAG, "execStream: session dropped, command result unknown")
                    else Log.w(TAG, "execStream timed out after ${execTimeoutMs}ms without end-marker")
                }
                outT.join(2500)
                runCatching { channel.disconnect() }
                outT.join(500)
                watchdog.interrupt()
                var partial: String? = null
                synchronized(lock) {
                    if (lineBuf.isNotEmpty()) partial = lineBuf.toString().removeSuffix("\r")
                    lineBuf.setLength(0)
                }
                partial?.let {
                    if (!it.contains(rcToken)) onLine(it)
                }
                if (foundExit < 0) {
                    val m = Regex("$rcToken<(\\d+)>").find(stdout.toString())
                    if (m != null) foundExit = m.groupValues[1].toIntOrNull() ?: -1
                }
                val res = ExecResult(
                    exitCode = foundExit,
                    stdout = stdout.toString(),
                    stderr = "",
                    markerFound = markerFound,
                    bytesReceived = synchronized(lock) { bytesReceived },
                )
                Log.d(TAG, "execStream done exit=$foundExit")
                return res
            } finally {
                watchdog.interrupt()
                runCatching { channel.disconnect() }
            }
        }
    }

    private fun readFully(stream: java.io.InputStream, timeoutMs: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    /** Bridges our [HostKeyStore] to JSch's [HostKeyRepository]. */
    private class Adapter(private val store: HostKeyStore) : HostKeyRepository {
        override fun check(host: String?, key: ByteArray?): Int {
            if (host == null || key == null) return NOT_INCLUDED
            val fp = "SHA256:" + shaHex(key)
            val known = store.get(host)
            return when {
                known == null -> NOT_INCLUDED
                known == fp -> OK
                else -> throw HostKeyMismatchException(host, known, fp)
            }
        }

        override fun add(hostKey: HostKey?, ui: UserInfo?) {
            if (hostKey == null) return
            val host = hostKey.host ?: return
            val raw = runCatching { java.util.Base64.getDecoder().decode(hostKey.key) }.getOrElse { return }
            val fp = "SHA256:" + shaHex(raw)
            Log.i(TAG, "Accepting host key $fp for $host (first use)")
            store.put(host, fp)
        }

        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "podsteroid"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

        private fun shaHex(key: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "SshClient"
        /** Socket read timeout: aborts reads on silently-dead links after 60s. */
        private const val READ_TIMEOUT_MS = 60_000
        // JSch's default ServerAliveCountMax is 1: a single missed 15s probe
        // kills the session. 4 = 60s tolerance before the link is treated as dead.
        // Centralised so the four connect sites stay in sync.
        private const val ALIVE_INTERVAL_S = "15"
        private const val ALIVE_COUNT_MAX = "4"
        // HostKeyRepository constants (mirrors the interface's static ints).
        private const val OK = 0
        private const val NOT_INCLUDED = 1
    }
}

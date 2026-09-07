/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Application class — extracts QEMU, kernel, and initrd assets on first run
 * (and on app upgrade when an asset's size changes).
 */
package com.tiquasar.podsteroid

import android.app.Application
import android.os.Build
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class PodsteroidApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Completion signal for asset extraction. The VM launch path
    // (PodsteroidService.launchPodsteroid) reads the extracted files synchronously,
    // so it MUST await this before starting the engine — see awaitAssetsReady.
    // Completed (never failed) in extractAssets' finally so a waiter can never
    // hang even if extraction throws; intactness is enforced by the size-check
    // in QemuEngine/AvfEngine's own asset reads, not by this signal.
    private val assetsReady = CompletableDeferred<Unit>()
    // Set when extraction aborts/partially fails so the launch path can surface a
    // clear error instead of booting a guest-less QEMU.
    private var assetExtractionError: String? = null

    override fun onCreate() {
        super.onCreate()
        exemptHiddenApi()
        // Extract off the main thread: the squashfs alone is ~225 MB and
        // blocking onCreate on first install/upgrade would ANR the cold start.
        appScope.launch { extractAssets() }
    }

    /**
     * Suspends until the bundled assets (qemu/, kernel, initrd, squashfs) have
     * finished extracting to [filesDir]. The foreground service awaits this
     * before launching the VM so QEMU/AVF never read a partial or missing file.
     */
    suspend fun awaitAssetsReady() = assetsReady.await()
    /** Non-null if the last extraction aborted; the launch path surfaces this. */
    fun assetError(): String? = assetExtractionError

    // Android 14+ hides @SystemApi reflection lookups (returning NoSuchMethod
    // even via getDeclared*). Prefixes needing exemption:
    //   - Landroid/system/virtualmachine/ — AVF framework (AvfDiagnostics + AvfEngine)
    //   - Landroid/system/virtualizationservice/ — AVF AIDL parcelables
    //     (CpuOptions, VirtualMachineRawConfig, IVirtualizationService) used by
    //     AvfReflect's explicit-vCPU-count hook (issue #29).
    //   - Ljava/net/UnixDomainSocketAddress — ConsoleFanout needs UDS.of(String)
    //     which Android marks BLOCKED for untrusted_app even though the class
    //     itself is on the bootclasspath.
    // No-op on sub-P; the exemption itself never throws.
    private fun exemptHiddenApi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/system/virtualmachine/",
                "Landroid/system/virtualizationservice/",
                "Landroid/system/UnixSocketAddress",
                "Ljava/net/UnixDomainSocketAddress",
            )
        }.onFailure { Log.w(TAG, "HiddenApiBypass exemption failed", it) }
    }

    private fun extractAssets() {
        try {
            // Asset extraction has a self-healing version stamp: on every install
            // or upgrade `packageInfo.lastUpdateTime` changes, so we record it in
            // `.assets_stamp` and force a re-copy on mismatch. Pure size checks
            // are deceiving because `mksquashfs -all-root -noappend` is
            // deterministic — changing service scripts inside the rootfs can
            // produce a byte-identical-size file with different content, which
            // older extraction logic silently kept stale.
            val stampFile = File(filesDir, ".assets_stamp")
            val currentStamp = runCatching {
                packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            }.getOrDefault(0L).toString()
            val previousStamp = runCatching { stampFile.readText() }.getOrDefault("")
            val forceCopy = previousStamp != currentStamp
            if (forceCopy) {
                Log.i(TAG, "asset stamp drift ($previousStamp → $currentStamp) — forcing re-extract")
            }

            // Drop any .tmp files left by a process killed mid-copy so they
            // can't accumulate or shadow a fresh atomic write. Guarded: a failure
            // here must NOT abort the whole extraction (it used to throw, was
            // swallowed by the outer try/finally, and left every asset missing).
            runCatching { deleteStaleTmpFiles(filesDir) }
                .onFailure { Log.w(TAG, "stale .tmp cleanup failed (non-fatal)", it) }

            // Fan out the top-level extractions across a small thread pool.
            // Disk-write throughput is the bottleneck for the squashfs (~225 MB),
            // but decompression, asset-FD lookup, and skip-when-size-matches all
            // overlap usefully across threads. Runs on a background coroutine
            // (not the main thread); the VM launch path awaits awaitAssetsReady.
            //
            // The Debian rootfs is OPTIONAL: a build that only bundles the Alpine
            // squashfs (e.g. an incremental `assembleDebug` after a Kotlin edit,
            // or a deliberately Alpine-only flavour) must still extract cleanly.
            // If the asset is absent we skip it instead of failing the whole
            // batch — otherwise allSucceeded stays false, the version stamp is
            // never written, and EVERY cold start re-extracts all assets.
            val topLevelAssets = runCatching { assets.list("")?.toSet() ?: emptySet() }
                .getOrDefault(emptySet())
            val debianRootfsPresent = topLevelAssets.contains("debian-rootfs.squashfs")
            if (!debianRootfsPresent) {
                Log.w(TAG, "debian-rootfs.squashfs not bundled — skipping Debian extraction; only Alpine available")
            }
            val tasks: List<() -> Unit> = listOfNotNull(
                { copyAssetDir("qemu", filesDir, forceCopy) },
                { copyAssetIfNeeded("vmlinuz-virt", filesDir, forceCopy) },
                { copyAssetIfNeeded("initrd.img", filesDir, forceCopy) },
                { copyAssetIfNeeded("alpine-rootfs.squashfs", filesDir, forceCopy) },
                if (debianRootfsPresent) {
                    { copyAssetIfNeeded("debian-rootfs.squashfs", filesDir, forceCopy) }
                } else null,
            )
            val pool = Executors.newFixedThreadPool(tasks.size.coerceAtMost(4))
            var allSucceeded = true
            try {
                // invokeAll blocks until every Callable finishes (or times out).
                // Each Callable wraps the task so a thrown exception is captured
                // in the returned Future rather than killing the worker silently.
                val futures = pool.invokeAll(tasks.map { task ->
                    java.util.concurrent.Callable<Unit> { task() }
                })
                for (f in futures) {
                    try { f.get() } catch (e: Exception) {
                        // copyAssetIfNeeded / copyAssetFileIfNeeded already log
                        // their own failures; this catches anything that escaped.
                        Log.w(TAG, "Asset extraction task failed", e)
                        allSucceeded = false
                    }
                }
            } finally {
                pool.shutdown()
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    pool.shutdownNow()
                    allSucceeded = false
                }
            }

            // Commit the new stamp ONLY if every extraction task succeeded.
            // Writing it after a failed copy (e.g. squashfs copy failed on an
            // upgrade: disk full, killed mid-copy) would mark the OLD file as
            // current — and because mksquashfs is deterministic the size check
            // can't catch it either, so a stale rootfs would boot forever. On
            // failure we leave the stamp stale so the next launch re-extracts.
            if (allSucceeded) {
                runCatching { stampFile.writeText(currentStamp) }
                    .onFailure { Log.w(TAG, "Failed to write assets stamp", it) }
            } else {
                Log.w(TAG, "asset extraction incomplete — leaving stamp stale to force re-extract next launch")
            }
        } catch (e: Exception) {
            // Never swallow: a thrown exception here used to be caught only by the
            // finally, so extraction aborted silently and the VM booted with no
            // kernel/rootfs (blank terminal, no SSH). Surface it.
            Log.e(TAG, "Asset extraction aborted — VMs cannot start until assets are present. Reinstall or restart the app.", e)
            assetExtractionError = e.message ?: e.toString()
        } finally {
            // Always release waiters — a failed/partial extract is detected by
            // the per-file size-check on the next read, not by hanging here.
            assetsReady.complete(Unit)
        }
    }

    /** Recursively removes leftover `<name>.tmp` files under [dir]. */
    private fun deleteStaleTmpFiles(dir: File) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                deleteStaleTmpFiles(child)
            } else if (child.name.endsWith(TMP_SUFFIX)) {
                runCatching { child.delete() }
            }
        }
    }

    /**
     * Copies an asset to destDir if missing OR if the size differs OR if the
     * install-time stamp drifted. The stamp is the key bit: `mksquashfs` is
     * deterministic, so an upgrade can ship a same-size squashfs with
     * different content (e.g. an init.d script edited) — size-only checks
     * would silently keep the stale copy and the VM boots the old rootfs.
     */
    private fun copyAssetIfNeeded(assetName: String, destDir: File, forceCopy: Boolean) {
        val destFile = File(destDir, assetName)
        try {
            val assetSize = try { assets.openFd(assetName).use { it.length } } catch (_: Exception) { -1L }
            if (!forceCopy && assetSize >= 0 && destFile.exists() && destFile.length() == assetSize) return

            destFile.parentFile?.mkdirs()
            copyAssetAtomically(assetName, destFile)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract $assetName", e)
        }
    }

    /**
     * Walks an asset directory tree and mirrors it under destDir.
     * Each file is copied if missing OR if its size differs OR if forceCopy
     * is true (install-stamp drift).
     */
    private fun copyAssetDir(assetPath: String, destDir: File, forceCopy: Boolean) {
        val entries = assets.list(assetPath) ?: return
        for (entry in entries) {
            val src = "$assetPath/$entry"
            val dest = File(destDir, entry)
            val subEntries = assets.list(src)
            if (subEntries != null && subEntries.isNotEmpty()) {
                dest.mkdirs()
                copyAssetDir(src, dest, forceCopy)
            } else {
                copyAssetFileIfNeeded(src, dest, forceCopy)
            }
        }
    }

    private fun copyAssetFileIfNeeded(assetPath: String, destFile: File, forceCopy: Boolean) {
        try {
            val assetSize = try { assets.openFd(assetPath).use { it.length } } catch (_: Exception) { -1L }
            if (!forceCopy && assetSize >= 0 && destFile.exists() && destFile.length() == assetSize) return

            copyAssetAtomically(assetPath, destFile)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract $assetPath", e)
        }
    }

    /**
     * Streams [assetPath] to `<destFile>.tmp`, fsyncs the data to disk, then
     * atomically renames it onto [destFile]. The final canonical path therefore
     * only ever holds a fully-written file — an async reader (the VM launch)
     * never sees a half-written squashfs/kernel. Throws on any failure so the
     * caller logs it and the stale/missing file is caught by the next size-check.
     */
    private fun copyAssetAtomically(assetPath: String, destFile: File) {
        val tmpFile = File(destFile.parentFile, destFile.name + TMP_SUFFIX)
        try {
            assets.open(assetPath).use { input ->
                java.io.FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (!tmpFile.renameTo(destFile)) {
                throw java.io.IOException("atomic rename ${tmpFile.name} -> ${destFile.name} failed")
            }
        } catch (e: Exception) {
            runCatching { tmpFile.delete() }
            throw e
        }
    }

    companion object {
        private const val TAG = "PodsteroidApp"
        private const val TMP_SUFFIX = ".tmp"
    }
}

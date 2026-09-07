/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Foreground service that hosts the VM(s). Now multi-VM: each ACTION_START /
 * ACTION_STOP carries a VM id, the registry launches/stops that VM, and this
 * service holds a single WakeLock + summary foreground notification while ANY
 * VM is active.
 */
package com.tiquasar.podsteroid.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tiquasar.podsteroid.MainActivity
import com.tiquasar.podsteroid.R
import com.tiquasar.podsteroid.data.repository.SettingsRepository
import com.tiquasar.podsteroid.data.repository.VmRepository
import com.tiquasar.podsteroid.engine.VmRegistry
import com.tiquasar.podsteroid.engine.VmState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class PodsteroidService : Service() {

    @Inject lateinit var registry: VmRegistry
    @Inject lateinit var settings: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var summaryJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /** Mirrors the "keep running in background" setting; refreshed by [onCreate]'s collector. */
    private var keepRunningInBackground = true

    private var notificationBuilder: NotificationCompat.Builder? = null
    private var stopPendingIntent: PendingIntent? = null
    private var openPendingIntent: PendingIntent? = null

    /** Per-VM notification id allocation (monotonic, never reused). */
    private val vmNotificationIds = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val nextVmNotifId = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startSummaryObserver()
        // Keep the setting mirrored so onTaskRemoved can decide synchronously
        // without suspending.
        serviceScope.launch {
            settings.keepRunningInBackground.collect { keepRunningInBackground = it }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val vmId = intent.getStringExtra(EXTRA_VM_ID) ?: VmRepository.DEFAULT_VM_ID
                val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "POST_NOTIFICATIONS not granted; foreground notification invisible")
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Starting VM..."), fgType)

                val instance = registry.instance(vmId)
                val alreadyActive = instance?.state?.value is VmState.Starting ||
                    instance?.state?.value is VmState.Running
                if (!alreadyActive) {
                    acquireWakeLock()
                    serviceScope.launch {
                        val error = withContext(Dispatchers.IO) {
                            runCatching { registry.start(vmId) }.getOrElse {
                                Log.e(TAG, "VM start failed", it)
                                getString(R.string.unknown_error)
                            }
                        }
                        if (error != null) {
                            android.widget.Toast.makeText(
                                this@PodsteroidService, error, android.widget.Toast.LENGTH_LONG,
                            ).show()
                            // Nothing started (e.g. a second AVF VM refused). Only
                            // tear down if no other VM is active, so a running VM's
                            // foreground notification + wakelock survive a refused start.
                            if (!anyActive()) {
                                releaseWakeLock()
                                stopForegroundCompat()
                                stopSelf()
                            }
                        }
                    }
                }
            }
            ACTION_STOP -> {
                val vmId = intent.getStringExtra(EXTRA_VM_ID)
                if (vmId == null) {
                    registry.instances.value.keys.forEach { registry.stop(it) }
                } else {
                    registry.stop(vmId)
                }
                // Teardown is driven by the summary observer once no VM is active.
                if (!anyActive()) {
                    releaseWakeLock()
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun anyActive(): Boolean =
        registry.instances.value.values.any {
            it.state.value is VmState.Running || it.state.value is VmState.Starting
        }

    override fun onDestroy() {
        super.onDestroy()
        summaryJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // When "keep running in background" is enabled, leave the foreground
        // service and all running VMs alive even after the app is removed from
        // recents — only an explicit Stop tears them down. This is what keeps
        // the QEMU child processes (which live in this process) from being
        // SIGKILLed when the user backgrounds / swipes away the app.
        if (keepRunningInBackground) return
        registry.instances.value.keys.forEach { registry.stop(it) }
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Podsteroid::VmWakeLock").apply {
                @SuppressLint("WakelockTimeout")
                acquire()
            }
            Log.d(TAG, "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    /**
     * One collector over the registry's per-VM state map: updates the summary
     * notification while any VM is active, and tears the service down once every
     * VM has reached a terminal state after having been active.
     */
    private fun startSummaryObserver() {
        summaryJob?.cancel()
        summaryJob = serviceScope.launch {
            var seenActive = false
            registry.allStates.collect { states ->
                val activeCount = states.values.count {
                    it is VmState.Running || it is VmState.Starting
                }
                if (activeCount > 0) {
                    seenActive = true
                    val runningCount = states.values.count { it is VmState.Running }
                    val startingCount = activeCount - runningCount
                    updateNotification(
                        when {
                            runningCount > 0 && startingCount > 0 ->
                                "$runningCount VM(s) running, $startingCount starting"
                            runningCount > 0 -> "$runningCount VM(s) running"
                            else -> "Starting VM..."
                        }
                    )
                    updatePerVmNotifications(states)
                } else {
                    if (seenActive) teardown()
                }
            }
        }
    }

    private fun teardown() {
        cancelVmNotifications()
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    /**
     * One notification per running VM with a per-VM Stop action (distinct id +
     * request code). The summary foreground notification above is the one that
     * keeps the service alive; these give the user per-VM controls.
     */
    private fun updatePerVmNotifications(states: Map<String, VmState>) {
        val nm = getSystemService(NotificationManager::class.java)
        registry.instances.value.forEach { (id, instance) ->
            val active = states[id] is VmState.Running || states[id] is VmState.Starting
            if (active) {
                val notifId = vmNotificationIds.getOrPut(id) {
                    VM_NOTIFICATION_ID_BASE + nextVmNotifId.getAndIncrement()
                }
                nm.notify(notifId, buildVmNotification(notifId, id, instance.definition.name, states[id]))
            } else {
                vmNotificationIds.remove(id)?.let { nm.cancel(it) }
            }
        }
    }

    private fun buildVmNotification(notifId: Int, vmId: String, name: String, state: VmState?): Notification {
        val openIntent = PendingIntent.getActivity(
            this, notifId, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, notifId,
            Intent(this, PodsteroidService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_VM_ID, vmId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val status = if (state is VmState.Running) getString(R.string.status_running) else getString(R.string.status_starting)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(name)
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_vm_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_stop, getString(R.string.stop), stopIntent)
            .build()
    }

    private fun cancelVmNotifications() {
        val nm = getSystemService(NotificationManager::class.java)
        vmNotificationIds.values.forEach { nm.cancel(it) }
        vmNotificationIds.clear()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 33) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Podsteroid Service", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the status of the Podman VMs"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun getOrCreateNotificationBuilder(): NotificationCompat.Builder {
        notificationBuilder?.let { return it }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PodsteroidService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        openPendingIntent = openIntent
        stopPendingIntent = stopIntent
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Podsteroid")
            .setSmallIcon(R.drawable.ic_vm_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_stop, "Stop all", stopIntent)
        notificationBuilder = builder
        return builder
    }

    private fun buildNotification(status: String): Notification =
        getOrCreateNotificationBuilder().setContentText(status).build()

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    companion object {
        private const val TAG = "PodsteroidService"
        private const val CHANNEL_ID = "podsteroid_service"
        private const val NOTIFICATION_ID = 1001
        private const val VM_NOTIFICATION_ID_BASE = 1100

        const val ACTION_START = "com.tiquasar.podsteroid.action.START"
        const val ACTION_STOP = "com.tiquasar.podsteroid.action.STOP"
        const val EXTRA_VM_ID = "com.tiquasar.podsteroid.extra.VM_ID"

        fun start(context: Context, vmId: String = VmRepository.DEFAULT_VM_ID) {
            val intent = Intent(context, PodsteroidService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VM_ID, vmId)
            }
            context.startForegroundService(intent)
        }

        /** Stop one VM, or every VM when [vmId] is null. */
        fun stop(context: Context, vmId: String? = null) {
            context.startService(Intent(context, PodsteroidService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_VM_ID, vmId)
            })
        }
    }
}

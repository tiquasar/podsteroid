/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * The runtime for one VM: owns a concrete engine plus the per-VM concerns that
 * used to be app-global — the port-forward reconciliation loop, the guest host
 * bridge, and USB passthrough. Multiple VmInstance objects run concurrently,
 * one per defined VM, each isolated behind its own engine + working directory.
 */
package com.tiquasar.podsteroid.engine

import android.content.Context
import android.util.Log
import com.tiquasar.podsteroid.data.repository.AddRuleResult
import com.tiquasar.podsteroid.data.repository.MAX_PORT_FORWARD_RULES
import com.tiquasar.podsteroid.data.repository.PortForwardRule
import com.tiquasar.podsteroid.data.repository.VmDefinition
import com.tiquasar.podsteroid.engine.hostbridge.AndroidNotificationPoster
import com.tiquasar.podsteroid.engine.hostbridge.HeadlessModeManager
import com.tiquasar.podsteroid.engine.hostbridge.HostProtocol
import com.tiquasar.podsteroid.engine.hostbridge.HostRequestDispatcher
import com.tiquasar.podsteroid.engine.hostbridge.HostRequestServer
import com.tiquasar.podsteroid.engine.usb.UsbPassthroughManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Cross-VM collaborators injected by the registry. */
class VmRuntimeDeps(
    val notificationPoster: AndroidNotificationPoster,
    val headlessModeManager: HeadlessModeManager,
    val appContext: Context,
    val openUrl: suspend (String) -> String,
    val power: suspend (String) -> String,
    val persistForwards: suspend (List<PortForwardRule>) -> Unit,
)

class VmInstance(
    val id: String,
    val engine: VmEngine,
    @Volatile var definition: VmDefinition,
    val workDir: java.io.File,
    private val deps: VmRuntimeDeps,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _desiredRules = MutableStateFlow(definition.portForwards)
    val desiredRules: StateFlow<List<PortForwardRule>> = _desiredRules.asStateFlow()

    @Volatile private var appliedRules: Set<PortForwardRule> = emptySet()
    @Volatile private var launchRules: Set<PortForwardRule> = emptySet()
    @Volatile private var implicitRules: Set<PortForwardRule> = emptySet()

    private var hostBridge: HostRequestServer? = null
    private var usb: UsbPassthroughManager? = null

    val state: StateFlow<VmState> get() = engine.state
    val bootStage: StateFlow<String> get() = engine.bootStage
    val stopping: StateFlow<Boolean> get() = engine.stopping
    val runningSinceMs: Long? get() = engine.runningSinceMs
    val backendId: String get() = engine.backendId
    val terminalSession: TerminalSession? get() = engine.terminalSession

    init {
        launchDiffLoop()
        launchHostBridgeObserver()
    }

    /** Push a new definition (e.g. from the UI / repository) into the live VM. */
    fun updateDefinition(newDef: VmDefinition) {
        definition = newDef
        _desiredRules.value = newDef.portForwards
    }

    private fun launchDiffLoop() {
        scope.launch {
            var wasRunning = false
            combine(_desiredRules, engine.state) { rules, st ->
                rules to st
            }.collect { (rules, st) ->
                if (st !is VmState.Running) {
                    appliedRules = emptySet()
                    implicitRules = emptySet()
                    wasRunning = false
                    return@collect
                }
                if (!wasRunning) {
                    appliedRules = engine.rulesAppliedAtLaunch(launchRules)
                    implicitRules = launchRules - rules.toSet()
                    wasRunning = true
                }
                val desired = rules.toSet() + implicitRules
                val (added, removed) = RuleDiff.computeRuleDiff(appliedRules, desired)
                val live = appliedRules.toMutableSet()
                for (r in removed) {
                    try {
                        engine.removePortForward(r)
                        live.remove(r)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        Log.w(TAG, "removePortForward failed for $r", e)
                    }
                }
                for (r in added) {
                    try {
                        engine.addPortForward(r)
                        live.add(r)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        Log.w(TAG, "addPortForward failed for $r", e)
                    }
                }
                appliedRules = live
            }
        }
    }

    private fun launchHostBridgeObserver() {
        scope.launch {
            engine.state.collect { st ->
                when (st) {
                    is VmState.Running -> {
                        ensureHostBridge().start()
                        if (definition.usbPassthroughEnabled) ensureUsb().start()
                    }
                    is VmState.Stopped, is VmState.Idle, is VmState.Error -> {
                        hostBridge?.stop()
                        usb?.stop()
                        deps.headlessModeManager.setActive(id, false)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun ensureHostBridge(): HostRequestServer {
        hostBridge?.let { return it }
        val dispatcher = HostRequestDispatcher(
            notifications = deps.notificationPoster,
            addForward = { rule -> addForwardPersisted(rule) },
            removeForward = { rule -> removeForwardPersisted(rule) },
            listForwards = { definition.portForwards },
            openUrl = deps.openUrl,
            power = deps.power,
            setHeadless = { action -> setHeadless(action) },
        )
        return HostRequestServer(
            openTransport = { engine.openHostTransport() },
            dispatcher = dispatcher,
            scope = scope,
        ).also { hostBridge = it }
    }

    private fun ensureUsb(): UsbPassthroughManager {
        usb?.let { return it }
        val manager = UsbPassthroughManager(deps.appContext, engine)
        usb = manager
        return manager
    }

    private suspend fun addForwardPersisted(rule: PortForwardRule): AddRuleResult {
        val current = definition.portForwards
        if (current.any { it.hostPort == rule.hostPort && it.protocol == rule.protocol }) {
            return AddRuleResult.ADDED
        }
        if (current.size >= MAX_PORT_FORWARD_RULES) return AddRuleResult.TABLE_FULL
        val merged = current.filterNot { it.hostPort == rule.hostPort && it.protocol == rule.protocol } + rule
        deps.persistForwards(merged)
        _desiredRules.value = merged
        return AddRuleResult.ADDED
    }

    private suspend fun removeForwardPersisted(rule: PortForwardRule) {
        val merged = definition.portForwards.filterNot {
            it.hostPort == rule.hostPort && it.protocol == rule.protocol
        }
        deps.persistForwards(merged)
        _desiredRules.value = merged
    }

    private suspend fun setHeadless(action: String): String = when (action) {
        "on" -> { deps.headlessModeManager.setActive(id, true); HostProtocol.ok() }
        "off" -> { deps.headlessModeManager.setActive(id, false); HostProtocol.ok() }
        "status" -> HostProtocol.ok(if (deps.headlessModeManager.isActive(id)) "on" else "off")
        else -> HostProtocol.err("usage: on|off|status")
    }

    suspend fun start(portForwards: List<PortForwardRule>, config: VmConfig) {
        launchRules = portForwards.toSet()
        _desiredRules.value = definition.portForwards
        engine.start(portForwards, config)
    }

    fun stop() = engine.stop()

    fun createTerminalSession(client: TerminalSessionClient): TerminalSession =
        engine.createTerminalSession(client)

    fun dispose() {
        scope.cancel()
        hostBridge?.stop()
        usb?.stop()
    }

    companion object {
        private const val TAG = "VmInstance"
    }
}

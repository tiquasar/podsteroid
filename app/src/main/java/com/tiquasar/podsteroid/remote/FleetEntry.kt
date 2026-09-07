package com.tiquasar.podsteroid.remote

import com.tiquasar.podsteroid.engine.GuestDistro

enum class FleetKind { LOCAL, REMOTE }

/**
 * A single node in the cross-VM fleet: either a local Android VM or a remote
 * libvirt VM. The [tailscaleIp] (when known) is the routable address that makes
 * every node reachable from every other node.
 */
data class FleetEntry(
    val id: String,
    val label: String,
    val kind: FleetKind,
    val serverName: String?,
    val stateLabel: String,
    val state: RemoteVmState,
    val guestDistro: String,
    val containerRuntime: ContainerRuntime,
    val guestIp: String?,
    val tailscaleIp: String?,
    val magicDns: String?,
)

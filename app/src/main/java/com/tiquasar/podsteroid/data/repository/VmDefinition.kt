/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * A single VM definition: the durable, per-VM identity that lives in DataStore.
 * Each VM is fully independent — its own disk image, launch config, backend
 * selection, and port-forward table. The global SettingsRepository fields
 * (vmRamMb/vmCpus/storageSizeGb/...) now serve only as *defaults* for newly
 * created VMs; a running VM reads everything from its own VmDefinition.
 */
package com.tiquasar.podsteroid.data.repository

import com.tiquasar.podsteroid.engine.EngineSelection
import org.json.JSONArray
import org.json.JSONObject

data class VmDefinition(
    val id: String,
    val name: String,
    val backend: EngineSelection = EngineSelection.AUTO,
    val ramMb: Int = 512,
    val cpus: Int = 2,
    /** QEMU-only CPU duty-cycle cap, percent (0 = unlimited). 50 ≈ "half a core". */
    val cpuLimitPercent: Int = 0,
    val storageSizeGb: Int = 2,
    val sshEnabled: Boolean = false,
    val storageAccessEnabled: Boolean = false,
    val usbPassthroughEnabled: Boolean = false,
    val bandwidthMbps: Int = 0,
    val verboseLogging: Boolean = false,
    val loadBalanceEnabled: Boolean = false,
    val qemuExtraArgs: String = SettingsRepository.DEFAULT_QEMU_EXTRA_ARGS,
    val kernelExtraCmdline: String = SettingsRepository.DEFAULT_KERNEL_EXTRA_CMDLINE,
    val x11Dpi: Int = 96,
    /** Guest distro for this VM: "alpine" or "debian". Drives which squashfs boots. */
    val guestDistro: String = "alpine",
    /** Host-side SSH forward port. Uniquely allocated so VMs can run at once. */
    val sshHostPort: Int = DEFAULT_SSH_HOST_PORT,
    /** Host-side VNC forward port for the in-app X11 viewer (guest stays 5900). */
    val vncHostPort: Int = 5900,
    /** Host-side audio forward port for the in-app X11 viewer (guest stays 4713). */
    val audioHostPort: Int = 4713,
    val portForwards: List<PortForwardRule> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("backend", backend.name)
        put("ramMb", ramMb)
        put("cpus", cpus)
        put("cpuLimitPercent", cpuLimitPercent)
        put("storageSizeGb", storageSizeGb)
        put("sshEnabled", sshEnabled)
        put("storageAccessEnabled", storageAccessEnabled)
        put("usbPassthroughEnabled", usbPassthroughEnabled)
        put("bandwidthMbps", bandwidthMbps)
        put("verboseLogging", verboseLogging)
        put("loadBalanceEnabled", loadBalanceEnabled)
        put("qemuExtraArgs", qemuExtraArgs)
        put("kernelExtraCmdline", kernelExtraCmdline)
        put("x11Dpi", x11Dpi)
        put("guestDistro", guestDistro)
        put("sshHostPort", sshHostPort)
        put("vncHostPort", vncHostPort)
        put("audioHostPort", audioHostPort)
        put("portForwards", JSONArray().apply {
            portForwards.forEach { put(it.serialize()) }
        })
    }

    companion object {
        const val DEFAULT_SSH_HOST_PORT = 9922

        fun fromJson(o: JSONObject): VmDefinition = VmDefinition(
            id = o.optString("id"),
            name = o.optString("name"),
            backend = runCatching {
                EngineSelection.valueOf(o.optString("backend", EngineSelection.AUTO.name))
            }.getOrDefault(EngineSelection.AUTO),
            ramMb = o.optInt("ramMb", 512),
            cpus = o.optInt("cpus", 2),
            cpuLimitPercent = o.optInt("cpuLimitPercent", 0),
            storageSizeGb = o.optInt("storageSizeGb", 2),
            sshEnabled = o.optBoolean("sshEnabled", false),
            storageAccessEnabled = o.optBoolean("storageAccessEnabled", false),
            usbPassthroughEnabled = o.optBoolean("usbPassthroughEnabled", false),
            bandwidthMbps = o.optInt("bandwidthMbps", 0),
            verboseLogging = o.optBoolean("verboseLogging", false),
            loadBalanceEnabled = o.optBoolean("loadBalanceEnabled", false),
            qemuExtraArgs = o.optString("qemuExtraArgs", SettingsRepository.DEFAULT_QEMU_EXTRA_ARGS),
            kernelExtraCmdline = o.optString("kernelExtraCmdline", SettingsRepository.DEFAULT_KERNEL_EXTRA_CMDLINE),
            x11Dpi = o.optInt("x11Dpi", 96),
            guestDistro = o.optString("guestDistro", "alpine"),
            sshHostPort = o.optInt("sshHostPort", 9922),
            vncHostPort = o.optInt("vncHostPort", 5900),
            audioHostPort = o.optInt("audioHostPort", 4713),
            portForwards = o.optJSONArray("portForwards")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> PortForwardRule.deserialize(arr.optString(i)) }
            } ?: emptyList(),
        )

        fun listToJson(list: List<VmDefinition>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String): List<VmDefinition> = runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}

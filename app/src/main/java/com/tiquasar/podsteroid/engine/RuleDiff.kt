/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Pure add/remove diff for port-forward reconciliation. Extracted so the unit
 * test and the per-VM diff loop share one implementation (was EngineHolder).
 */
package com.tiquasar.podsteroid.engine

import com.tiquasar.podsteroid.data.repository.PortForwardRule

object RuleDiff {
    fun computeRuleDiff(
        applied: Set<PortForwardRule>,
        desired: Set<PortForwardRule>,
    ): Pair<Set<PortForwardRule>, Set<PortForwardRule>> =
        (desired - applied) to (applied - desired)
}

package com.tiquasar.podsteroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tiquasar.podsteroid.R
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens
import com.tiquasar.podsteroid.util.DeviceResourcePolicy

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmRamChips(
    currentMb: Int,
    onChange: (Int) -> Unit,
    enabled: Boolean = true,
    showDivider: Boolean = true,
    overrideEnabled: Boolean = false,
) {
    Column(modifier = Modifier.padding(bottom = PodsteroidTokens.Spacing.SM)) {
        Text(
            "${stringResource(R.string.ram_label)}  ·  ${formatRam(currentMb)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                top = PodsteroidTokens.Spacing.MD,
                bottom = PodsteroidTokens.Spacing.SM,
            ),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
            verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
        ) {
            val options = if (overrideEnabled) DeviceResourcePolicy.RAM_EXTENDED_OPTIONS_MB else DeviceResourcePolicy.RAM_OPTIONS_MB
            options.forEach { mb ->
                FilterChip(
                    selected = mb == currentMb,
                    enabled = enabled,
                    onClick = { onChange(mb) },
                    label = {
                        Text(
                            formatRam(mb),
                            fontWeight = if (mb == currentMb) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    shape = RoundedCornerShape(PodsteroidTokens.Radius.Chip),
                    colors = PodsteroidChipColors(),
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                thickness = 1.dp,
                modifier = Modifier.padding(top = PodsteroidTokens.Spacing.MD),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmCpuChips(
    currentCpus: Int,
    onChange: (Int) -> Unit,
    enabled: Boolean = true,
    showDivider: Boolean = true,
    overrideEnabled: Boolean = false,
) {
    Column(modifier = Modifier.padding(bottom = PodsteroidTokens.Spacing.SM)) {
        Text(
            "${stringResource(R.string.cpu_cores)}  ·  $currentCpus",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                top = PodsteroidTokens.Spacing.MD,
                bottom = PodsteroidTokens.Spacing.SM,
            ),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
            verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
        ) {
            val options = if (overrideEnabled) DeviceResourcePolicy.CPU_EXTENDED_OPTIONS else DeviceResourcePolicy.CPU_OPTIONS
            options.forEach { n ->
                FilterChip(
                    selected = n == currentCpus,
                    enabled = enabled,
                    onClick = { onChange(n) },
                    label = {
                        Text(
                            "$n",
                            fontWeight = if (n == currentCpus) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    shape = RoundedCornerShape(PodsteroidTokens.Radius.Chip),
                    colors = PodsteroidChipColors(),
                )
            }
        }
        // The high chips read as "more power" and are the opposite under emulation,
        // so say so where the choice is made rather than leaving it to be discovered.
        Text(
            text = stringResource(R.string.cpu_cores_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = PodsteroidTokens.Spacing.SM),
        )
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                thickness = 1.dp,
                modifier = Modifier.padding(top = PodsteroidTokens.Spacing.MD),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmBandwidthChips(
    currentMbps: Int,
    onChange: (Int) -> Unit,
    enabled: Boolean = true,
    showDivider: Boolean = true,
    overrideEnabled: Boolean = false,
) {
    Column(modifier = Modifier.padding(bottom = PodsteroidTokens.Spacing.SM)) {
        Text(
            "${stringResource(R.string.bandwidth_limit)}  ·  ${formatBandwidth(currentMbps)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                top = PodsteroidTokens.Spacing.MD,
                bottom = PodsteroidTokens.Spacing.SM,
            ),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
            verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
        ) {
            val options = if (overrideEnabled) DeviceResourcePolicy.BANDWIDTH_EXTENDED_OPTIONS_MBPS else DeviceResourcePolicy.BANDWIDTH_OPTIONS_MBPS
            options.forEach { mbps ->
                FilterChip(
                    selected = mbps == currentMbps,
                    enabled = enabled,
                    onClick = { onChange(mbps) },
                    label = {
                        Text(
                            formatBandwidth(mbps),
                            fontWeight = if (mbps == currentMbps) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    shape = RoundedCornerShape(PodsteroidTokens.Radius.Chip),
                    colors = PodsteroidChipColors(),
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                thickness = 1.dp,
                modifier = Modifier.padding(top = PodsteroidTokens.Spacing.MD),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmCpuLimitChips(
    currentPercent: Int,
    onChange: (Int) -> Unit,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    Column(modifier = Modifier.padding(bottom = PodsteroidTokens.Spacing.SM)) {
        Text(
            "${stringResource(R.string.cpu_limit)}  ·  ${formatCpuLimit(currentPercent)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                top = PodsteroidTokens.Spacing.MD,
                bottom = PodsteroidTokens.Spacing.SM,
            ),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
            verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
        ) {
            DeviceResourcePolicy.CPU_LIMIT_PERCENT_OPTIONS.forEach { pct ->
                FilterChip(
                    selected = pct == currentPercent,
                    enabled = enabled,
                    onClick = { onChange(pct) },
                    label = {
                        Text(
                            formatCpuLimit(pct),
                            fontWeight = if (pct == currentPercent) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    shape = RoundedCornerShape(PodsteroidTokens.Radius.Chip),
                    colors = PodsteroidChipColors(),
                )
            }
        }
        Text(
            text = stringResource(R.string.cpu_limit_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = PodsteroidTokens.Spacing.SM),
        )
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                thickness = 1.dp,
                modifier = Modifier.padding(top = PodsteroidTokens.Spacing.MD),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmStorageChips(
    currentGb: Int,
    onChange: (Int) -> Unit,
    enabled: Boolean = true,
    showDivider: Boolean = true,
    overrideEnabled: Boolean = false,
) {
    Column(modifier = Modifier.padding(bottom = PodsteroidTokens.Spacing.SM)) {
        Text(
            "${stringResource(R.string.storage)}  ·  $currentGb GB",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                top = PodsteroidTokens.Spacing.MD,
                bottom = PodsteroidTokens.Spacing.SM,
            ),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
            verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
        ) {
            val options = if (overrideEnabled) DeviceResourcePolicy.STORAGE_EXTENDED_OPTIONS_GB else DeviceResourcePolicy.STORAGE_OPTIONS_GB
            options.forEach { gb ->
                FilterChip(
                    selected = gb == currentGb,
                    enabled = enabled,
                    onClick = { onChange(gb) },
                    label = {
                        Text(
                            "$gb GB",
                            fontWeight = if (gb == currentGb) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    shape = RoundedCornerShape(PodsteroidTokens.Radius.Chip),
                    colors = PodsteroidChipColors(),
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                thickness = 1.dp,
                modifier = Modifier.padding(top = PodsteroidTokens.Spacing.MD),
            )
        }
    }
}

@Composable
private fun formatCpuLimit(percent: Int): String =
    if (percent <= 0) stringResource(R.string.cpu_limit_none) else "$percent%"

@Composable
private fun formatRam(mb: Int): String = when {
    mb < 1024 -> "$mb MB"
    mb % 1024 == 0 -> "${mb / 1024} GB"
    else -> {
        val gb = mb / 1024.0
        "$gb GB"
    }
}

@Composable
private fun formatBandwidth(mbps: Int): String =
    if (mbps <= 0) stringResource(R.string.bandwidth_unlimited) else "$mbps Mbps"

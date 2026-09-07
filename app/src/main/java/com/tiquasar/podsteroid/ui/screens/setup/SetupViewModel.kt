package com.tiquasar.podsteroid.ui.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tiquasar.podsteroid.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _setupComplete = MutableStateFlow(false)
    val setupComplete: StateFlow<Boolean> = _setupComplete.asStateFlow()

    /**
     * USB passthrough is QEMU-only (it rides the QMP control socket the AVF
     * backend lacks). The setup gate is optimistic — the choice is inert on an
     * AVF VM anyway (no QMP, no qemu-xhci), and the VM edit screen shows the
     * real gate. New VMs default to AUTO → QEMU fallback, so this is safe.
     */
    fun usbPassthroughAvailable(): Boolean = true

    /**
     * Persists all setup choices in a single DataStore transaction so a process
     * kill mid-write can't leave the app in a half-completed setup state.
     */
    fun completeSetup(
        storageSizeGb: Int,
        vmRamMb: Int,
        vmCpus: Int,
        sshEnabled: Boolean,
        storageAccessEnabled: Boolean,
        usbPassthroughEnabled: Boolean,
        loadBalanceEnabled: Boolean,
        bandwidthMbps: Int,
    ) {
        viewModelScope.launch {
            settingsRepository.completeSetup(
                storageSizeGb = storageSizeGb,
                vmRamMb = vmRamMb,
                vmCpus = vmCpus,
                sshEnabled = sshEnabled,
                storageAccessEnabled = storageAccessEnabled,
                usbPassthroughEnabled = usbPassthroughEnabled,
                loadBalanceEnabled = loadBalanceEnabled,
                bandwidthMbps = bandwidthMbps,
            )
            _setupComplete.value = true
        }
    }
}

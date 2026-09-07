package com.tiquasar.podsteroid.ui.screens.backup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tiquasar.podsteroid.data.repository.ContainerBackupFile
import com.tiquasar.podsteroid.data.repository.ContainerBackupRepository
import com.tiquasar.podsteroid.data.repository.SettingsRepository
import com.tiquasar.podsteroid.engine.VmRegistry
import com.tiquasar.podsteroid.engine.VmState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ContainerBackupUiState(
    val vmRunning: Boolean = false,
    val storageAccessEnabled: Boolean = false,
    val guestPath: String = "/var/backups/podsteroid",
    val backupFiles: List<ContainerBackupFile> = emptyList(),
    val containerName: String = "",
    val imageRef: String = "",
)

@HiltViewModel
class ContainerBackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ContainerBackupRepository,
    private val settingsRepository: SettingsRepository,
    private val registry: VmRegistry,
) : ViewModel() {

    private val vmState: StateFlow<VmState> = registry.selectedEngine
        .flatMapLatest { eng -> eng?.state ?: flowOf(VmState.Idle) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, VmState.Idle)

    private val _containerName = MutableStateFlow("")
    private val _imageRef = MutableStateFlow("")
    private val _backupFiles = MutableStateFlow<List<ContainerBackupFile>>(emptyList())

    val uiState: StateFlow<ContainerBackupUiState> = combine(
        vmState,
        settingsRepository.storageAccessEnabled,
        _containerName,
        _imageRef,
        _backupFiles,
    ) { vmState, storageAccess, container, image, files ->
        ContainerBackupUiState(
            vmRunning = vmState is VmState.Running,
            storageAccessEnabled = storageAccess,
            guestPath = repository.guestBackupPathLabel(),
            backupFiles = files,
            containerName = container,
            imageRef = image,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ContainerBackupUiState())

    init {
        refresh()
    }

    fun refresh() {
        _backupFiles.value = repository.listBackupFiles()
    }

    fun setContainerName(value: String) {
        _containerName.value = value
    }

    fun setImageRef(value: String) {
        _imageRef.value = value
    }

    fun copyExportCommand(): Boolean {
        val name = _containerName.value.trim()
        if (name.isEmpty()) return false
        copyToClipboard(repository.exportCommand(name))
        return true
    }

    fun copySaveCommand(): Boolean {
        val ref = _imageRef.value.trim()
        if (ref.isEmpty()) return false
        copyToClipboard(repository.saveImageCommand(ref))
        return true
    }

    fun copyListCommand() {
        copyToClipboard(repository.listCommand())
    }

    fun copyAllCommand() {
        copyToClipboard("podsteroid-backup all")
    }

    fun formatSize(bytes: Long): String = repository.formatSize(bytes)

    fun formatDate(ms: Long): String = repository.formatDate(ms)

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("podsteroid-backup", text))
    }
}

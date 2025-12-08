package com.fyrbyadditive.famesmartblinds.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fyrbyadditive.famesmartblinds.data.model.BlindCommand
import com.fyrbyadditive.famesmartblinds.data.model.BlindDevice
import com.fyrbyadditive.famesmartblinds.data.model.BlindState
import com.fyrbyadditive.famesmartblinds.data.remote.HttpClient
import com.fyrbyadditive.famesmartblinds.data.remote.SseClient
import com.fyrbyadditive.famesmartblinds.data.repository.DeviceRepository
import com.fyrbyadditive.famesmartblinds.service.DeviceDiscovery
import com.fyrbyadditive.famesmartblinds.util.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeviceControlViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: DeviceRepository,
    private val httpClient: HttpClient,
    private val deviceDiscovery: DeviceDiscovery,
    private val sseClient: SseClient
) : ViewModel() {

    private val deviceId: String = checkNotNull(savedStateHandle["deviceId"])

    private val _device = MutableStateFlow<BlindDevice?>(null)
    val device: StateFlow<BlindDevice?> = _device.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _showRenameDialog = MutableStateFlow(false)
    val showRenameDialog: StateFlow<Boolean> = _showRenameDialog.asStateFlow()

    private val _newDeviceName = MutableStateFlow("")
    val newDeviceName: StateFlow<String> = _newDeviceName.asStateFlow()

    private val _isRestarting = MutableStateFlow(false)
    val isRestarting: StateFlow<Boolean> = _isRestarting.asStateFlow()

    private val _calibrationNagDismissed = MutableStateFlow(false)
    val calibrationNagDismissed: StateFlow<Boolean> = _calibrationNagDismissed.asStateFlow()

    // SSE connection state
    val sseConnected: StateFlow<Boolean> = sseClient.isConnected

    private var pollingJob: Job? = null

    init {
        // Load device from repository
        _device.value = deviceRepository.getDevice(deviceId)

        // Subscribe to device updates
        viewModelScope.launch {
            deviceRepository.devices.collect { devices ->
                devices[deviceId.lowercase()]?.let { updatedDevice ->
                    _device.value = updatedDevice
                }
            }
        }

        // Set up SSE callback and connect
        setupSSE()

        // Initial refresh
        refreshStatus()

        // Watch SSE connection status for fallback polling
        viewModelScope.launch {
            sseClient.isConnected.collect { isConnected ->
                if (!isConnected) {
                    // Fall back to polling if SSE disconnects during movement
                    val state = _device.value?.state
                    if (state == BlindState.OPENING || state == BlindState.CLOSING) {
                        startPolling()
                    }
                } else {
                    // SSE connected, stop polling
                    stopPolling()
                }
            }
        }
    }

    private fun setupSSE() {
        val ip = _device.value?.ipAddress ?: return

        // Set up status callback for real-time device updates
        sseClient.onStatusUpdate = { status ->
            viewModelScope.launch {
                deviceRepository.updateDevice(deviceId) { it.updateFromDeviceStatus(status) }
            }
        }

        sseClient.connect(ip)
    }

    private fun stopSSE() {
        sseClient.disconnect()
    }

    /**
     * Reconnect SSE if not connected. Call this when returning from other screens
     * (settings, logs, calibration) to ensure SSE is still active.
     */
    fun reconnectSSEIfNeeded() {
        if (!sseClient.isConnected.value) {
            setupSSE()
        }
    }

    /**
     * Force SSE reconnection. Call this when returning from settings/calibration
     * where the device may have restarted (e.g., firmware update, restart from settings).
     * Unlike reconnectSSEIfNeeded(), this always forces a fresh connection.
     */
    fun forceSSEReconnect() {
        setupSSE()
    }

    fun sendCommand(command: BlindCommand) {
        val device = _device.value ?: return
        val ip = device.ipAddress ?: return

        _isLoading.value = true

        viewModelScope.launch {
            try {
                httpClient.sendCommand(command, ip)

                // Update local state optimistically
                val newState = when (command) {
                    BlindCommand.OPEN -> BlindState.OPENING
                    BlindCommand.CLOSE -> BlindState.CLOSING
                    BlindCommand.STOP -> BlindState.STOPPED
                    BlindCommand.RESTART -> device.state
                }

                deviceRepository.updateDevice(deviceId) { it.copy(state = newState) }

                // SSE will provide real-time updates, no need to start polling
                // Polling is only used as fallback if SSE is disconnected
            } catch (e: Exception) {
                _errorMessage.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshStatus() {
        val device = _device.value ?: return
        val ip = device.ipAddress ?: return

        viewModelScope.launch {
            try {
                val status = httpClient.getStatus(ip)
                val info = httpClient.getInfo(ip)

                deviceRepository.updateDevice(deviceId) {
                    it.updateFromDeviceStatus(status).copy(
                        name = info.hostname,
                        wifiSsid = status.wifi.ssid
                    )
                }
            } catch (e: Exception) {
                // Silently ignore - device may be temporarily unavailable
            }
        }
    }

    private fun startPolling() {
        // Only use polling as fallback when SSE is not connected
        if (sseClient.isConnected.value) return
        val currentState = _device.value?.state
        if (currentState != BlindState.OPENING && currentState != BlindState.CLOSING) return
        if (pollingJob?.isActive == true) return

        pollingJob = viewModelScope.launch {
            delay(100) // Small initial delay

            while (true) {
                pollStatus()
                delay(Constants.Timeout.STATUS_POLL_INTERVAL)

                // Stop if device stopped moving
                val state = _device.value?.state
                if (state != BlindState.OPENING && state != BlindState.CLOSING) {
                    break
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun pollStatus() {
        val ip = _device.value?.ipAddress ?: return

        try {
            val status = httpClient.getStatus(ip)
            deviceRepository.updateDevice(deviceId) { it.updateFromDeviceStatus(status) }
        } catch (e: Exception) {
            // Silently ignore polling errors
        }
    }

    // Rename dialog
    fun showRenameDialog() {
        val currentName = _device.value?.name ?: ""
        _newDeviceName.value = stripDeviceIdSuffix(currentName)
        _showRenameDialog.value = true
    }

    fun hideRenameDialog() {
        _showRenameDialog.value = false
        _newDeviceName.value = ""
    }

    fun updateNewDeviceName(name: String) {
        _newDeviceName.value = name
    }

    fun renameDevice() {
        val name = _newDeviceName.value.trim()
        if (name.isEmpty()) return

        val ip = _device.value?.ipAddress ?: return

        hideRenameDialog()
        _isRestarting.value = true

        viewModelScope.launch {
            try {
                httpClient.setDeviceName(name, ip)
                httpClient.sendCommand(BlindCommand.RESTART, ip)

                // Wait for device to reboot
                delay(5000)

                // Clear registry and rescan
                deviceRepository.clear()
                deviceDiscovery.triggerDelayedDiscovery(2)

            } catch (e: Exception) {
                _errorMessage.value = "Failed to rename device: ${e.message}"
            } finally {
                _isRestarting.value = false
            }
        }
    }

    fun dismissCalibrationNag() {
        _calibrationNagDismissed.value = true
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun stripDeviceIdSuffix(name: String): String {
        val underscoreIndex = name.lastIndexOf('_')
        if (underscoreIndex != -1) {
            val suffix = name.substring(underscoreIndex + 1)
            if (suffix.length == 8 && suffix.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                return name.substring(0, underscoreIndex)
            }
        }
        return name
    }

    override fun onCleared() {
        super.onCleared()
        stopSSE()
        stopPolling()
    }
}

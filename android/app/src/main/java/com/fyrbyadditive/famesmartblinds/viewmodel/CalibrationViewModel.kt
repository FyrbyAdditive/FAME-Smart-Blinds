package com.fyrbyadditive.famesmartblinds.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fyrbyadditive.famesmartblinds.data.model.BlindCommand
import com.fyrbyadditive.famesmartblinds.data.model.BlindDevice
import com.fyrbyadditive.famesmartblinds.data.remote.HttpClient
import com.fyrbyadditive.famesmartblinds.data.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: DeviceRepository,
    private val httpClient: HttpClient
) : ViewModel() {

    private val deviceId: String = checkNotNull(savedStateHandle["deviceId"])

    private val _device = MutableStateFlow<BlindDevice?>(null)
    val device: StateFlow<BlindDevice?> = _device.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Expose calibration state as StateFlows for proper Compose recomposition
    private val _calibrationState = MutableStateFlow("idle")
    val calibrationState: StateFlow<String> = _calibrationState.asStateFlow()

    private val _cumulativePosition = MutableStateFlow(0)
    val cumulativePosition: StateFlow<Int> = _cumulativePosition.asStateFlow()

    private val _isCalibrated = MutableStateFlow(false)
    val isCalibrated: StateFlow<Boolean> = _isCalibrated.asStateFlow()

    private val _maxPosition = MutableStateFlow(0)
    val maxPosition: StateFlow<Int> = _maxPosition.asStateFlow()

    private var pollingJob: Job? = null

    init {
        _device.value = deviceRepository.getDevice(deviceId)
        updateStateFromDevice(_device.value)

        // Subscribe to device updates
        viewModelScope.launch {
            deviceRepository.devices.collect { devices ->
                devices[deviceId.lowercase()]?.let { updatedDevice ->
                    _device.value = updatedDevice
                    updateStateFromDevice(updatedDevice)
                }
            }
        }

        // Reset calibration state on the firmware if starting fresh from a completed state
        val device = _device.value
        if (device?.calibrationState == "complete") {
            viewModelScope.launch {
                val ip = device.ipAddress
                if (ip != null) {
                    try {
                        // Cancel on firmware to reset state to idle
                        httpClient.cancelCalibration(ip)
                        Log.i(TAG, "Reset firmware calibration state from complete to idle")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to reset firmware calibration state: ${e.message}")
                    }
                }
                // Update local state
                deviceRepository.updateDevice(deviceId) { it.copy(calibrationState = "idle") }
                _calibrationState.value = "idle"
                // Start polling after reset completes
                startPolling()
            }
        } else {
            startPolling()
        }
    }

    private fun updateStateFromDevice(device: BlindDevice?) {
        val newState = device?.calibrationState ?: "idle"
        if (_calibrationState.value != newState) {
            Log.i(TAG, "Calibration state changed: ${_calibrationState.value} -> $newState")
        }
        _calibrationState.value = newState
        _cumulativePosition.value = device?.cumulativePosition ?: 0
        _isCalibrated.value = device?.isCalibrated == true
        _maxPosition.value = device?.maxPosition ?: 0
    }

    fun startCalibration() {
        val ip = _device.value?.ipAddress ?: return
        _isLoading.value = true

        viewModelScope.launch {
            try {
                httpClient.startCalibration(ip)
                refreshStatus()
            } catch (e: Exception) {
                _errorMessage.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setBottom() {
        val ip = _device.value?.ipAddress ?: return
        _isLoading.value = true

        viewModelScope.launch {
            try {
                httpClient.setBottomPosition(ip)
                refreshStatus()
            } catch (e: Exception) {
                _errorMessage.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun cancelCalibration() {
        val ip = _device.value?.ipAddress ?: return
        _isLoading.value = true

        viewModelScope.launch {
            try {
                httpClient.cancelCalibration(ip)
                refreshStatus()
            } catch (e: Exception) {
                _errorMessage.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun moveOpen() {
        val ip = _device.value?.ipAddress ?: return
        viewModelScope.launch {
            try {
                // During calibration, use force to bypass limits
                httpClient.openForce(ip)
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    fun moveClose() {
        val ip = _device.value?.ipAddress ?: return
        viewModelScope.launch {
            try {
                // During calibration, use force to bypass limits
                httpClient.closeForce(ip)
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    fun stopMovement() {
        val ip = _device.value?.ipAddress ?: return
        viewModelScope.launch {
            try {
                httpClient.sendCommand(BlindCommand.STOP, ip)
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                refreshStatus()
                delay(1000)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun refreshStatus() {
        val ip = _device.value?.ipAddress ?: return
        try {
            val status = httpClient.getCalibrationStatus(ip)
            deviceRepository.updateDevice(deviceId) { it.updateFromCalibrationStatus(status) }
        } catch (e: Exception) {
            // Silently ignore polling errors
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun cancelAndDismiss(): Boolean {
        val state = _calibrationState.value
        if (state != "idle" && state != "complete") {
            cancelCalibration()
        }
        return true
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    companion object {
        private const val TAG = "CalibrationViewModel"
    }
}

package com.fyrbyadditive.famesmartblinds.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fyrbyadditive.famesmartblinds.data.model.BlindCommand
import com.fyrbyadditive.famesmartblinds.data.model.BlindDevice
import com.fyrbyadditive.famesmartblinds.data.model.DeviceOrientation
import com.fyrbyadditive.famesmartblinds.data.remote.AuthenticationRequiredException
import com.fyrbyadditive.famesmartblinds.data.remote.HttpClient
import com.fyrbyadditive.famesmartblinds.data.repository.DeviceRepository
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
class SettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: DeviceRepository,
    private val httpClient: HttpClient
) : ViewModel() {

    private val deviceId: String = checkNotNull(savedStateHandle["deviceId"])

    private val _device = MutableStateFlow<BlindDevice?>(null)
    val device: StateFlow<BlindDevice?> = _device.asStateFlow()

    private val _firmwareVersion = MutableStateFlow("Unknown")
    val firmwareVersion: StateFlow<String> = _firmwareVersion.asStateFlow()

    private val _currentOrientation = MutableStateFlow(DeviceOrientation.LEFT)
    val currentOrientation: StateFlow<DeviceOrientation> = _currentOrientation.asStateFlow()

    private val _currentSpeed = MutableStateFlow(Constants.Speed.DEFAULT.toFloat())
    val currentSpeed: StateFlow<Float> = _currentSpeed.asStateFlow()

    private val _isLoadingInfo = MutableStateFlow(false)
    val isLoadingInfo: StateFlow<Boolean> = _isLoadingInfo.asStateFlow()

    private val _isSavingOrientation = MutableStateFlow(false)
    val isSavingOrientation: StateFlow<Boolean> = _isSavingOrientation.asStateFlow()

    private val _isSavingSpeed = MutableStateFlow(false)
    val isSavingSpeed: StateFlow<Boolean> = _isSavingSpeed.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _uploadProgress = MutableStateFlow(0f)
    val uploadProgress: StateFlow<Float> = _uploadProgress.asStateFlow()

    private val _isResetting = MutableStateFlow(false)
    val isResetting: StateFlow<Boolean> = _isResetting.asStateFlow()

    private val _showResetConfirmation = MutableStateFlow(false)
    val showResetConfirmation: StateFlow<Boolean> = _showResetConfirmation.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    private var orientationLoaded = false
    private var speedLoaded = false
    private var speedSaveJob: Job? = null

    init {
        _device.value = deviceRepository.getDevice(deviceId)

        // Subscribe to device updates
        viewModelScope.launch {
            deviceRepository.devices.collect { devices ->
                devices[deviceId.lowercase()]?.let { updatedDevice ->
                    _device.value = updatedDevice
                }
            }
        }

        loadDeviceInfo()
    }

    private fun loadDeviceInfo() {
        val ip = _device.value?.ipAddress ?: return

        _isLoadingInfo.value = true
        viewModelScope.launch {
            try {
                val info = httpClient.getInfo(ip)
                _firmwareVersion.value = info.version
                _currentOrientation.value = info.deviceOrientation
                _currentSpeed.value = info.servoSpeed.toFloat()

                // Update device name from authoritative source
                deviceRepository.updateDevice(deviceId) { it.copy(name = info.hostname) }

                _isLoadingInfo.value = false

                // Delay marking as loaded so onChange doesn't fire for initial load
                delay(100)
                orientationLoaded = true
                speedLoaded = true
            } catch (e: Exception) {
                _firmwareVersion.value = "Error loading"
                _isLoadingInfo.value = false

                delay(100)
                orientationLoaded = true
                speedLoaded = true
            }
        }
    }

    fun setOrientation(orientation: DeviceOrientation) {
        if (!orientationLoaded || orientation == _currentOrientation.value) return

        val ip = _device.value?.ipAddress ?: return
        _currentOrientation.value = orientation
        _isSavingOrientation.value = true

        viewModelScope.launch {
            try {
                httpClient.setOrientation(orientation, ip, deviceId)
                _isSavingOrientation.value = false
                _successMessage.value = "Orientation updated to ${orientation.displayName}"
            } catch (e: AuthenticationRequiredException) {
                _isSavingOrientation.value = false
                // Auth modal will be shown by AuthenticationManager
                loadDeviceInfo()
            } catch (e: Exception) {
                _isSavingOrientation.value = false
                _errorMessage.value = e.message
                // Reload to revert
                loadDeviceInfo()
            }
        }
    }

    fun setSpeed(speed: Float) {
        if (!speedLoaded) return
        _currentSpeed.value = speed

        // Debounce
        speedSaveJob?.cancel()
        speedSaveJob = viewModelScope.launch {
            delay(500)
            saveSpeed(speed.toInt())
        }
    }

    private suspend fun saveSpeed(speed: Int) {
        val ip = _device.value?.ipAddress ?: return
        _isSavingSpeed.value = true

        try {
            httpClient.setSpeed(speed, ip, deviceId)
            _isSavingSpeed.value = false
        } catch (e: AuthenticationRequiredException) {
            _isSavingSpeed.value = false
            // Auth modal will be shown by AuthenticationManager
        } catch (e: Exception) {
            _isSavingSpeed.value = false
            _errorMessage.value = e.message
        }
    }

    fun uploadFirmware(firmwareData: ByteArray) {
        val ip = _device.value?.ipAddress ?: return

        _isUploading.value = true
        _uploadProgress.value = 0f

        viewModelScope.launch {
            try {
                httpClient.uploadFirmware(firmwareData, ip, deviceId) { progress ->
                    _uploadProgress.value = progress
                }
                _isUploading.value = false
                _successMessage.value = "Firmware updated successfully. Device is restarting..."

                // Reload info after a delay
                delay(5000)
                loadDeviceInfo()
            } catch (e: AuthenticationRequiredException) {
                _isUploading.value = false
                // Auth modal will be shown by AuthenticationManager
            } catch (e: Exception) {
                _isUploading.value = false
                _errorMessage.value = e.message
            }
        }
    }

    fun restartDevice() {
        val ip = _device.value?.ipAddress ?: return

        viewModelScope.launch {
            try {
                httpClient.sendCommand(BlindCommand.RESTART, ip, deviceId)
                _successMessage.value = "Device is restarting..."
            } catch (e: AuthenticationRequiredException) {
                // Auth modal will be shown by AuthenticationManager
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    fun showResetConfirmation() {
        _showResetConfirmation.value = true
    }

    fun hideResetConfirmation() {
        _showResetConfirmation.value = false
    }

    fun performFactoryReset(): Boolean {
        val ip = _device.value?.ipAddress ?: return false

        _showResetConfirmation.value = false
        _isResetting.value = true

        viewModelScope.launch {
            try {
                httpClient.factoryReset(ip, deviceId)
                _isResetting.value = false
                _successMessage.value = "Factory reset complete. The device will restart and need to be set up again."

                // Remove device from registry
                deviceRepository.remove(deviceId)
            } catch (e: AuthenticationRequiredException) {
                _isResetting.value = false
                // Auth modal will be shown by AuthenticationManager
            } catch (e: Exception) {
                _isResetting.value = false
                _errorMessage.value = e.message
            }
        }

        return true // Return true to navigate back
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearSuccess() {
        _successMessage.value = null
    }
}

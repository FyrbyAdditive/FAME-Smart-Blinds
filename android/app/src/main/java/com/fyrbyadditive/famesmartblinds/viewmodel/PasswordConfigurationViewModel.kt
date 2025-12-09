package com.fyrbyadditive.famesmartblinds.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fyrbyadditive.famesmartblinds.data.model.BlindDevice
import com.fyrbyadditive.famesmartblinds.data.remote.HttpClient
import com.fyrbyadditive.famesmartblinds.data.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PasswordConfigurationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: DeviceRepository,
    private val httpClient: HttpClient
) : ViewModel() {

    private val deviceId: String = checkNotNull(savedStateHandle["deviceId"])

    private val _device = MutableStateFlow<BlindDevice?>(null)
    val device: StateFlow<BlindDevice?> = _device.asStateFlow()

    private val _devicePassword = MutableStateFlow("")
    val devicePassword: StateFlow<String> = _devicePassword.asStateFlow()

    private val _confirmPassword = MutableStateFlow("")
    val confirmPassword: StateFlow<String> = _confirmPassword.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    val passwordsMatch: Boolean
        get() = _devicePassword.value.isEmpty() || _devicePassword.value == _confirmPassword.value

    init {
        _device.value = deviceRepository.getDevice(deviceId)

        viewModelScope.launch {
            deviceRepository.devices.collect { devices ->
                devices[deviceId.lowercase()]?.let { updatedDevice ->
                    _device.value = updatedDevice
                }
            }
        }
    }

    fun updateDevicePassword(password: String) {
        _devicePassword.value = password
    }

    fun updateConfirmPassword(password: String) {
        _confirmPassword.value = password
    }

    fun saveDevicePassword() {
        val ip = _device.value?.ipAddress ?: return
        if (!passwordsMatch) return

        _isSaving.value = true

        viewModelScope.launch {
            try {
                httpClient.setDevicePassword(_devicePassword.value, ip)
                _isSaving.value = false
                _successMessage.value = if (_devicePassword.value.isEmpty()) {
                    "Password protection removed."
                } else {
                    "Device password updated successfully."
                }
                _devicePassword.value = ""
                _confirmPassword.value = ""
            } catch (e: Exception) {
                _isSaving.value = false
                _errorMessage.value = e.message
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearSuccess() {
        _successMessage.value = null
    }
}

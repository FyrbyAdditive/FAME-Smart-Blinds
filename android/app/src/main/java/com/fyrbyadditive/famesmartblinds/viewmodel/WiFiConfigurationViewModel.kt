package com.fyrbyadditive.famesmartblinds.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fyrbyadditive.famesmartblinds.data.model.BlindDevice
import com.fyrbyadditive.famesmartblinds.data.remote.AuthenticationRequiredException
import com.fyrbyadditive.famesmartblinds.data.remote.HttpClient
import com.fyrbyadditive.famesmartblinds.data.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WiFiConfigurationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: DeviceRepository,
    private val httpClient: HttpClient
) : ViewModel() {

    private val deviceId: String = checkNotNull(savedStateHandle["deviceId"])

    private val _device = MutableStateFlow<BlindDevice?>(null)
    val device: StateFlow<BlindDevice?> = _device.asStateFlow()

    private val _wifiSsid = MutableStateFlow("")
    val wifiSsid: StateFlow<String> = _wifiSsid.asStateFlow()

    private val _wifiPassword = MutableStateFlow("")
    val wifiPassword: StateFlow<String> = _wifiPassword.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _showRestartDialog = MutableStateFlow(false)
    val showRestartDialog: StateFlow<Boolean> = _showRestartDialog.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    init {
        _device.value = deviceRepository.getDevice(deviceId)

        viewModelScope.launch {
            deviceRepository.devices.collect { devices ->
                devices[deviceId.lowercase()]?.let { updatedDevice ->
                    _device.value = updatedDevice
                }
            }
        }

        loadCurrentConfig()
    }

    private fun loadCurrentConfig() {
        val ip = _device.value?.ipAddress ?: return

        _isLoading.value = true
        viewModelScope.launch {
            try {
                val info = httpClient.getInfo(ip)
                _wifiSsid.value = info.wifiSsid ?: ""
                _isLoading.value = false
            } catch (e: Exception) {
                _isLoading.value = false
                // Don't show error, just leave fields empty
            }
        }
    }

    fun updateWifiSsid(ssid: String) {
        _wifiSsid.value = ssid
    }

    fun updateWifiPassword(password: String) {
        _wifiPassword.value = password
    }

    fun showRestartDialog() {
        _showRestartDialog.value = true
    }

    fun hideRestartDialog() {
        _showRestartDialog.value = false
    }

    fun saveWifiConfig() {
        val ip = _device.value?.ipAddress ?: return
        val ssid = _wifiSsid.value.trim()
        if (ssid.isEmpty()) return

        _showRestartDialog.value = false
        _isSaving.value = true

        viewModelScope.launch {
            try {
                httpClient.setWifiCredentials(ssid, _wifiPassword.value, ip, deviceId)
                _isSaving.value = false
                _successMessage.value = "WiFi settings saved. The device is restarting..."
                _wifiSsid.value = ""
                _wifiPassword.value = ""
            } catch (e: AuthenticationRequiredException) {
                _isSaving.value = false
                // Auth modal will be shown by AuthenticationManager
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

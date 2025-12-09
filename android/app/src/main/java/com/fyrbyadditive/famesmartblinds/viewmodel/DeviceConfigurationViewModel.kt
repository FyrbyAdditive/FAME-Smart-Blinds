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
class DeviceConfigurationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: DeviceRepository,
    private val httpClient: HttpClient
) : ViewModel() {

    private val deviceId: String = checkNotNull(savedStateHandle["deviceId"])

    private val _device = MutableStateFlow<BlindDevice?>(null)
    val device: StateFlow<BlindDevice?> = _device.asStateFlow()

    // WiFi Configuration
    private val _wifiSsid = MutableStateFlow("")
    val wifiSsid: StateFlow<String> = _wifiSsid.asStateFlow()

    private val _wifiPassword = MutableStateFlow("")
    val wifiPassword: StateFlow<String> = _wifiPassword.asStateFlow()

    private val _isSavingWifi = MutableStateFlow(false)
    val isSavingWifi: StateFlow<Boolean> = _isSavingWifi.asStateFlow()

    private val _showWifiRestartDialog = MutableStateFlow(false)
    val showWifiRestartDialog: StateFlow<Boolean> = _showWifiRestartDialog.asStateFlow()

    // MQTT Configuration
    private val _mqttBroker = MutableStateFlow("")
    val mqttBroker: StateFlow<String> = _mqttBroker.asStateFlow()

    private val _mqttPort = MutableStateFlow("1883")
    val mqttPort: StateFlow<String> = _mqttPort.asStateFlow()

    private val _mqttUser = MutableStateFlow("")
    val mqttUser: StateFlow<String> = _mqttUser.asStateFlow()

    private val _mqttPassword = MutableStateFlow("")
    val mqttPassword: StateFlow<String> = _mqttPassword.asStateFlow()

    private val _isSavingMqtt = MutableStateFlow(false)
    val isSavingMqtt: StateFlow<Boolean> = _isSavingMqtt.asStateFlow()

    // Device Password
    private val _devicePassword = MutableStateFlow("")
    val devicePassword: StateFlow<String> = _devicePassword.asStateFlow()

    private val _confirmPassword = MutableStateFlow("")
    val confirmPassword: StateFlow<String> = _confirmPassword.asStateFlow()

    private val _isSavingPassword = MutableStateFlow(false)
    val isSavingPassword: StateFlow<Boolean> = _isSavingPassword.asStateFlow()

    val passwordsMatch: Boolean
        get() = _devicePassword.value.isEmpty() || _devicePassword.value == _confirmPassword.value

    // Messages
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

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
    }

    // WiFi functions
    fun updateWifiSsid(ssid: String) {
        _wifiSsid.value = ssid
    }

    fun updateWifiPassword(password: String) {
        _wifiPassword.value = password
    }

    fun showWifiRestartDialog() {
        _showWifiRestartDialog.value = true
    }

    fun hideWifiRestartDialog() {
        _showWifiRestartDialog.value = false
    }

    fun saveWifiConfig() {
        val ip = _device.value?.ipAddress ?: return
        val ssid = _wifiSsid.value.trim()
        if (ssid.isEmpty()) return

        _showWifiRestartDialog.value = false
        _isSavingWifi.value = true

        viewModelScope.launch {
            try {
                httpClient.setWifiCredentials(ssid, _wifiPassword.value, ip)
                _isSavingWifi.value = false
                _successMessage.value = "WiFi settings saved. The device is restarting..."
                // Clear fields after success
                _wifiSsid.value = ""
                _wifiPassword.value = ""
            } catch (e: Exception) {
                _isSavingWifi.value = false
                _errorMessage.value = e.message
            }
        }
    }

    // MQTT functions
    fun updateMqttBroker(broker: String) {
        _mqttBroker.value = broker
    }

    fun updateMqttPort(port: String) {
        _mqttPort.value = port
    }

    fun updateMqttUser(user: String) {
        _mqttUser.value = user
    }

    fun updateMqttPassword(password: String) {
        _mqttPassword.value = password
    }

    fun saveMqttConfig() {
        val ip = _device.value?.ipAddress ?: return
        val broker = _mqttBroker.value.trim()
        if (broker.isEmpty()) return

        _isSavingMqtt.value = true
        val port = _mqttPort.value.toIntOrNull() ?: 1883

        viewModelScope.launch {
            try {
                httpClient.setMqttConfig(
                    broker = broker,
                    port = port,
                    user = _mqttUser.value,
                    password = _mqttPassword.value,
                    ipAddress = ip
                )
                _isSavingMqtt.value = false
                _successMessage.value = "MQTT settings saved successfully."
            } catch (e: Exception) {
                _isSavingMqtt.value = false
                _errorMessage.value = e.message
            }
        }
    }

    // Password functions
    fun updateDevicePassword(password: String) {
        _devicePassword.value = password
    }

    fun updateConfirmPassword(password: String) {
        _confirmPassword.value = password
    }

    fun saveDevicePassword() {
        val ip = _device.value?.ipAddress ?: return
        if (!passwordsMatch) return

        _isSavingPassword.value = true

        viewModelScope.launch {
            try {
                httpClient.setDevicePassword(_devicePassword.value, ip)
                _isSavingPassword.value = false
                _successMessage.value = if (_devicePassword.value.isEmpty()) {
                    "Password protection removed."
                } else {
                    "Device password updated successfully."
                }
                // Clear fields after success
                _devicePassword.value = ""
                _confirmPassword.value = ""
            } catch (e: Exception) {
                _isSavingPassword.value = false
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

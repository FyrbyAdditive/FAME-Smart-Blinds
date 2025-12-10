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
class MQTTConfigurationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: DeviceRepository,
    private val httpClient: HttpClient
) : ViewModel() {

    private val deviceId: String = checkNotNull(savedStateHandle["deviceId"])

    private val _device = MutableStateFlow<BlindDevice?>(null)
    val device: StateFlow<BlindDevice?> = _device.asStateFlow()

    private val _mqttBroker = MutableStateFlow("")
    val mqttBroker: StateFlow<String> = _mqttBroker.asStateFlow()

    private val _mqttPort = MutableStateFlow("1883")
    val mqttPort: StateFlow<String> = _mqttPort.asStateFlow()

    private val _mqttUser = MutableStateFlow("")
    val mqttUser: StateFlow<String> = _mqttUser.asStateFlow()

    private val _mqttPassword = MutableStateFlow("")
    val mqttPassword: StateFlow<String> = _mqttPassword.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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
                _mqttBroker.value = info.mqttBroker ?: ""
                info.mqttPort?.let { _mqttPort.value = it.toString() }
                _mqttUser.value = info.mqttUser ?: ""
                _isLoading.value = false
            } catch (e: Exception) {
                _isLoading.value = false
                // Don't show error, just leave fields empty
            }
        }
    }

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

        _isSaving.value = true
        val port = _mqttPort.value.toIntOrNull() ?: 1883

        viewModelScope.launch {
            try {
                httpClient.setMqttConfig(
                    broker = broker,
                    port = port,
                    user = _mqttUser.value,
                    password = _mqttPassword.value,
                    ipAddress = ip,
                    deviceId = deviceId
                )
                _isSaving.value = false
                _successMessage.value = if (broker.isEmpty()) {
                    "MQTT has been disabled."
                } else {
                    "MQTT settings saved successfully."
                }
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

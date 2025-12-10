package com.fyrbyadditive.famesmartblinds.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fyrbyadditive.famesmartblinds.data.local.AuthenticationManager
import com.fyrbyadditive.famesmartblinds.data.local.SessionExpiry
import com.fyrbyadditive.famesmartblinds.data.remote.HttpClient
import com.fyrbyadditive.famesmartblinds.data.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for handling global authentication dialog in navigation
 */
@HiltViewModel
class AuthenticationNavViewModel @Inject constructor(
    private val authManager: AuthenticationManager,
    private val httpClient: HttpClient,
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    val deviceNeedingAuth: StateFlow<String?> = authManager.deviceNeedingAuth

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Get device name and IP address for the authentication dialog
     * Returns Pair(deviceName, ipAddress) or null if device not found
     */
    fun getDeviceInfo(deviceId: String): Pair<String, String>? {
        val device = deviceRepository.getDevice(deviceId) ?: return null
        val ipAddress = device.ipAddress ?: return null
        return Pair(device.name, ipAddress)
    }

    /**
     * Attempt to authenticate with the device
     */
    fun authenticate(deviceId: String, ipAddress: String, password: String, expiry: SessionExpiry) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val success = httpClient.testAuthentication(ipAddress, deviceId, password)
                if (success) {
                    // Save credentials
                    authManager.authenticate(deviceId, password, expiry)
                    // Clear the auth request
                    authManager.clearAuthenticationRequest()
                } else {
                    _errorMessage.value = "Invalid password"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Authentication failed"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Dismiss the authentication dialog without authenticating
     */
    fun dismissAuthDialog() {
        authManager.clearAuthenticationRequest()
        _errorMessage.value = null
    }
}

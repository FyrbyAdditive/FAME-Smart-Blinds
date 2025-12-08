package com.fyrbyadditive.famesmartblinds.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fyrbyadditive.famesmartblinds.data.remote.HttpClient
import com.fyrbyadditive.famesmartblinds.data.remote.SseClient
import com.fyrbyadditive.famesmartblinds.data.remote.SseEndpoint
import com.fyrbyadditive.famesmartblinds.data.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: DeviceRepository,
    private val httpClient: HttpClient
) : ViewModel() {

    private val deviceId: String = checkNotNull(savedStateHandle["deviceId"])

    // Own SSE client for log streaming - connects to /events/logs endpoint
    private val sseClient = SseClient()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val sseConnected: StateFlow<Boolean> = sseClient.isConnected

    private var ipAddress: String? = null

    init {
        // Get IP address from device
        ipAddress = deviceRepository.getDevice(deviceId)?.ipAddress

        // Set up SSE callback for real-time logs
        setupSSE()

        // Load initial logs
        loadLogs()
    }

    private fun setupSSE() {
        val ip = ipAddress ?: return

        // Connect to the dedicated logs endpoint
        sseClient.onLogReceived = { logEntry ->
            viewModelScope.launch {
                _logs.value = _logs.value + logEntry
            }
        }
        sseClient.connect(ip, SseEndpoint.LOGS)
    }

    fun loadLogs() {
        val ip = ipAddress ?: run {
            _error.value = "Device not connected"
            return
        }

        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val fetchedLogs = httpClient.getLogs(ip)
                _logs.value = fetchedLogs
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load logs"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearLogs() {
        val ip = ipAddress ?: return

        viewModelScope.launch {
            try {
                httpClient.clearLogs(ip)
                _logs.value = emptyList()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to clear logs"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Reconnect SSE if not connected. Call this when the app resumes
     * from sleep to restore the log stream.
     */
    fun reconnectSSEIfNeeded() {
        if (!sseClient.isConnected.value) {
            setupSSE()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Disconnect our own SSE client when leaving
        sseClient.disconnect()
    }
}

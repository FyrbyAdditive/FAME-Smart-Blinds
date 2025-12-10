package com.fyrbyadditive.famesmartblinds.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fyrbyadditive.famesmartblinds.data.model.BlindDevice
import com.fyrbyadditive.famesmartblinds.data.repository.DeviceRepository
import com.fyrbyadditive.famesmartblinds.service.DeviceDiscovery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

import android.util.Log

@HiltViewModel
class DeviceListViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val deviceDiscovery: DeviceDiscovery
) : ViewModel() {

    init {
        // Start discovery immediately when ViewModel is created
        Log.i("DeviceListViewModel", "init - starting discovery")
        deviceDiscovery.startContinuousDiscovery()
    }

    /**
     * Only WiFi-configured devices (have IP address)
     */
    val configuredDevices: StateFlow<List<BlindDevice>> = deviceRepository.devices
        .map { devices ->
            val filtered = devices.values.filter { it.ipAddress != null }.sortedBy { it.name }
            Log.i("DeviceListViewModel", "configuredDevices updated: ${filtered.size} devices (${filtered.map { it.name }})")
            filtered
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isSearching: StateFlow<Boolean> = deviceDiscovery.isSearching

    private val _showManualIpDialog = MutableStateFlow(false)
    val showManualIpDialog: StateFlow<Boolean> = _showManualIpDialog.asStateFlow()

    private val _manualIp = MutableStateFlow("")
    val manualIp: StateFlow<String> = _manualIp.asStateFlow()

    fun startDiscovery() {
        Log.i("DeviceListViewModel", "startDiscovery() called")
        deviceDiscovery.startContinuousDiscovery()
    }

    fun stopDiscovery() {
        deviceDiscovery.stopContinuousDiscovery()
    }

    fun refresh() {
        viewModelScope.launch {
            deviceDiscovery.triggerManualRefresh()
        }
    }

    fun showManualIpDialog() {
        _showManualIpDialog.value = true
    }

    fun hideManualIpDialog() {
        _showManualIpDialog.value = false
        _manualIp.value = ""
    }

    fun updateManualIp(ip: String) {
        _manualIp.value = ip
    }

    fun connectToManualIp() {
        val ip = _manualIp.value.trim()
        Log.i("DeviceListViewModel", "connectToManualIp called with IP: $ip")
        if (ip.isNotEmpty()) {
            viewModelScope.launch {
                Log.i("DeviceListViewModel", "Calling checkDeviceAt($ip)")
                deviceDiscovery.checkDeviceAt(ip)
                Log.i("DeviceListViewModel", "checkDeviceAt completed")
            }
        }
        hideManualIpDialog()
    }
}

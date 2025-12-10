package com.fyrbyadditive.famesmartblinds.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fyrbyadditive.famesmartblinds.data.local.AuthenticationManager
import com.fyrbyadditive.famesmartblinds.data.local.SessionExpiry
import com.fyrbyadditive.famesmartblinds.data.model.BlindCommand
import com.fyrbyadditive.famesmartblinds.data.model.BlindDevice
import com.fyrbyadditive.famesmartblinds.data.model.DeviceOrientation
import com.fyrbyadditive.famesmartblinds.data.model.WiFiNetwork
import com.fyrbyadditive.famesmartblinds.data.repository.DeviceRepository
import com.fyrbyadditive.famesmartblinds.service.BleManager
import com.fyrbyadditive.famesmartblinds.service.DeviceDiscovery
import com.fyrbyadditive.famesmartblinds.util.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SetupStep {
    SELECT_DEVICE,
    CONNECT_BLE,
    CONFIGURE_WIFI,
    CONFIGURE_NAME,
    CONFIGURE_ORIENTATION,
    CONFIGURE_PASSWORD,
    COMPLETE
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val bleManager: BleManager,
    private val deviceDiscovery: DeviceDiscovery,
    private val authManager: AuthenticationManager
) : ViewModel() {

    private val _setupStep = MutableStateFlow(SetupStep.SELECT_DEVICE)
    val setupStep: StateFlow<SetupStep> = _setupStep.asStateFlow()

    private val _selectedDeviceId = MutableStateFlow<String?>(null)
    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId.asStateFlow()

    val selectedDevice: BlindDevice?
        get() = _selectedDeviceId.value?.let { deviceRepository.getDevice(it) }

    /**
     * BLE-discoverable devices that need setup (have peripheral but no WiFi connection).
     * This reactively updates when the repository's devices change.
     */
    val availableDevices: StateFlow<List<BlindDevice>> = deviceRepository.devices
        .map { devices ->
            devices.values
                .filter { it.bluetoothDevice != null && it.ipAddress == null }
                .sortedBy { it.name }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isScanning: StateFlow<Boolean> = bleManager.isScanning
    val connectionState: StateFlow<BleManager.ConnectionState> = bleManager.connectionState
    val isPoweredOn: StateFlow<Boolean> = bleManager.isPoweredOn

    // WiFi config
    private val _wifiSsid = MutableStateFlow("")
    val wifiSsid: StateFlow<String> = _wifiSsid.asStateFlow()

    private val _wifiPassword = MutableStateFlow("")
    val wifiPassword: StateFlow<String> = _wifiPassword.asStateFlow()

    private val _wifiConnecting = MutableStateFlow(false)
    val wifiConnecting: StateFlow<Boolean> = _wifiConnecting.asStateFlow()

    private val _wifiStatus = MutableStateFlow("")
    val wifiStatus: StateFlow<String> = _wifiStatus.asStateFlow()

    private val _wifiFailed = MutableStateFlow(false)
    val wifiFailed: StateFlow<Boolean> = _wifiFailed.asStateFlow()

    // WiFi scanning
    val scannedWifiNetworks: StateFlow<List<WiFiNetwork>> = bleManager.scannedWifiNetworks
    val isWifiScanning: StateFlow<Boolean> = bleManager.isWifiScanning

    private val _showManualEntry = MutableStateFlow(false)
    val showManualEntry: StateFlow<Boolean> = _showManualEntry.asStateFlow()

    private val _selectedNetwork = MutableStateFlow<WiFiNetwork?>(null)
    val selectedNetwork: StateFlow<WiFiNetwork?> = _selectedNetwork.asStateFlow()

    // Device name config
    private val _deviceName = MutableStateFlow("My Blinds")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _isSavingName = MutableStateFlow(false)
    val isSavingName: StateFlow<Boolean> = _isSavingName.asStateFlow()

    // Orientation config
    private val _orientation = MutableStateFlow(DeviceOrientation.LEFT)
    val orientation: StateFlow<DeviceOrientation> = _orientation.asStateFlow()

    private val _isSavingOrientation = MutableStateFlow(false)
    val isSavingOrientation: StateFlow<Boolean> = _isSavingOrientation.asStateFlow()

    // Password config
    private val _devicePassword = MutableStateFlow("")
    val devicePassword: StateFlow<String> = _devicePassword.asStateFlow()

    private val _confirmPassword = MutableStateFlow("")
    val confirmPassword: StateFlow<String> = _confirmPassword.asStateFlow()

    private val _isSavingPassword = MutableStateFlow(false)
    val isSavingPassword: StateFlow<Boolean> = _isSavingPassword.asStateFlow()

    val passwordsMatch: Boolean
        get() = _devicePassword.value.isEmpty() || _devicePassword.value == _confirmPassword.value

    // Finishing
    private val _isFinishing = MutableStateFlow(false)
    val isFinishing: StateFlow<Boolean> = _isFinishing.asStateFlow()

    private var wifiTimeoutJob: Job? = null
    private var statusPollingJob: Job? = null

    init {
        // Listen for connection state changes
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                when (state) {
                    BleManager.ConnectionState.CONNECTED -> {
                        if (_setupStep.value == SetupStep.CONNECT_BLE) {
                            _setupStep.value = SetupStep.CONFIGURE_WIFI
                        }
                    }
                    BleManager.ConnectionState.DISCONNECTED -> {
                        if (_setupStep.value == SetupStep.CONNECT_BLE) {
                            _setupStep.value = SetupStep.SELECT_DEVICE
                        }
                    }
                    else -> {}
                }
            }
        }

        // Listen for status updates
        bleManager.onStatusUpdate = { status ->
            handleStatusUpdate(status)
        }
    }

    fun startScanning() {
        deviceRepository.clearBleOnlyDevices()
        bleManager.startScanning()
    }

    fun stopScanning() {
        bleManager.stopScanning()
    }

    fun selectDevice(deviceId: String) {
        _selectedDeviceId.value = deviceId
    }

    fun continueToConnect() {
        val deviceId = _selectedDeviceId.value ?: return

        // Stop BLE device scanning - we're done selecting
        bleManager.stopScanning()

        deviceRepository.markDeviceInSetup(deviceId)
        bleManager.connect(deviceId)
        _setupStep.value = SetupStep.CONNECT_BLE
    }

    fun updateWifiSsid(ssid: String) {
        _wifiSsid.value = ssid
    }

    fun updateWifiPassword(password: String) {
        _wifiPassword.value = password
    }

    fun triggerWifiScan() {
        bleManager.triggerWifiScan()
    }

    fun selectNetwork(network: WiFiNetwork) {
        _selectedNetwork.value = network
        _wifiSsid.value = network.ssid
    }

    fun toggleManualEntry() {
        _showManualEntry.value = !_showManualEntry.value
        if (_showManualEntry.value) {
            _selectedNetwork.value = null
        }
    }

    fun setShowManualEntry(show: Boolean) {
        _showManualEntry.value = show
        if (show) {
            _selectedNetwork.value = null
        }
    }

    fun configureWifi() {
        val ssid = _wifiSsid.value.trim()
        if (ssid.isEmpty()) return

        _wifiFailed.value = false
        _wifiConnecting.value = true
        _wifiStatus.value = "Sending credentials..."

        bleManager.configureWifi(ssid, _wifiPassword.value)

        // Start polling for status
        startStatusPolling()

        // Timeout after 20 seconds
        wifiTimeoutJob?.cancel()
        wifiTimeoutJob = viewModelScope.launch {
            delay(20000)
            if (_wifiConnecting.value) {
                stopStatusPolling()
                _wifiConnecting.value = false
                _wifiStatus.value = "Connection timed out. Check your credentials."
                _wifiFailed.value = true
            }
        }
    }

    private fun startStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = viewModelScope.launch {
            while (true) {
                bleManager.readCharacteristic(Constants.BLE.STATUS_UUID)
                delay(1000)
            }
        }
    }

    private fun stopStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
    }

    private fun handleStatusUpdate(status: String) {
        if (!_wifiConnecting.value) return

        if (status.contains("wifi_connected") ||
            status.startsWith("wifi:1") ||
            status.contains("wifi:172.") ||
            status.contains("wifi:192.") ||
            status.contains("wifi:10.")) {
            // Successfully connected!
            wifiTimeoutJob?.cancel()
            stopStatusPolling()
            _wifiConnecting.value = false
            _wifiStatus.value = "Connected!"
            _setupStep.value = SetupStep.CONFIGURE_NAME
        } else if (status.contains("wifi_failed")) {
            // Connection failed
            wifiTimeoutJob?.cancel()
            stopStatusPolling()
            _wifiConnecting.value = false
            _wifiStatus.value = "Connection failed. Check your credentials."
            _wifiFailed.value = true
        } else if (status.contains("wifi_connecting") || status.contains("wifi:connecting")) {
            _wifiStatus.value = "Connecting to network..."
        }
    }

    fun updateDeviceName(name: String) {
        _deviceName.value = name
    }

    fun configureDeviceName() {
        val name = _deviceName.value.trim()
        if (name.isEmpty()) return

        _isSavingName.value = true
        bleManager.configureDeviceName(name)

        viewModelScope.launch {
            delay(1500)
            _isSavingName.value = false
            _setupStep.value = SetupStep.CONFIGURE_ORIENTATION
        }
    }

    fun updateOrientation(orientation: DeviceOrientation) {
        _orientation.value = orientation
    }

    fun configureOrientation() {
        _isSavingOrientation.value = true
        bleManager.configureOrientation(_orientation.value)

        viewModelScope.launch {
            delay(1500)
            _isSavingOrientation.value = false
            _setupStep.value = SetupStep.CONFIGURE_PASSWORD
        }
    }

    fun updateDevicePassword(password: String) {
        _devicePassword.value = password
    }

    fun updateConfirmPassword(password: String) {
        _confirmPassword.value = password
    }

    fun configurePassword() {
        if (_devicePassword.value.isNotEmpty() && !passwordsMatch) return

        _isSavingPassword.value = true

        if (_devicePassword.value.isNotEmpty()) {
            bleManager.configureDevicePassword(_devicePassword.value)

            // Save password to secure storage for future HTTP access
            val deviceId = _selectedDeviceId.value
            if (deviceId != null) {
                authManager.authenticate(
                    deviceId = deviceId,
                    password = _devicePassword.value,
                    expiry = SessionExpiry.UNLIMITED  // Device setup = permanent auth
                )
            }
        }

        viewModelScope.launch {
            delay(1500)
            _isSavingPassword.value = false
            _setupStep.value = SetupStep.COMPLETE
        }
    }

    fun skipPassword() {
        _setupStep.value = SetupStep.COMPLETE
    }

    fun finishSetup(): Boolean {
        _isFinishing.value = true

        // Send restart command
        bleManager.sendCommand(BlindCommand.RESTART)

        // Start cooldown
        viewModelScope.launch {
            deviceRepository.startScanCooldown(6)
        }

        // Disconnect BLE
        bleManager.disconnect()

        // Clear setup flag
        deviceRepository.markDeviceInSetup(null)

        // Clear BLE-only devices (newly setup device will be re-discovered via mDNS)
        deviceRepository.clearBleOnlyDevices()

        // Schedule discovery
        deviceDiscovery.triggerDelayedDiscovery(2)

        return true // Return true to dismiss
    }

    fun cancelSetup() {
        deviceRepository.markDeviceInSetup(null)
        bleManager.disconnect()
        wifiTimeoutJob?.cancel()
        stopStatusPolling()
        resetState()
    }

    private fun resetState() {
        _setupStep.value = SetupStep.SELECT_DEVICE
        _selectedDeviceId.value = null
        _wifiSsid.value = ""
        _wifiPassword.value = ""
        _wifiConnecting.value = false
        _wifiStatus.value = ""
        _wifiFailed.value = false
        _showManualEntry.value = false
        _selectedNetwork.value = null
        _deviceName.value = "My Blinds"
        _isSavingName.value = false
        _orientation.value = DeviceOrientation.LEFT
        _isSavingOrientation.value = false
        _devicePassword.value = ""
        _confirmPassword.value = ""
        _isSavingPassword.value = false
        _isFinishing.value = false
    }

    val progressValue: Float
        get() = when (_setupStep.value) {
            SetupStep.SELECT_DEVICE -> 0f
            SetupStep.CONNECT_BLE -> 0.14f
            SetupStep.CONFIGURE_WIFI -> 0.28f
            SetupStep.CONFIGURE_NAME -> 0.42f
            SetupStep.CONFIGURE_ORIENTATION -> 0.57f
            SetupStep.CONFIGURE_PASSWORD -> 0.71f
            SetupStep.COMPLETE -> 1f
        }

    override fun onCleared() {
        super.onCleared()
        wifiTimeoutJob?.cancel()
        stopStatusPolling()
        bleManager.onStatusUpdate = null
    }
}

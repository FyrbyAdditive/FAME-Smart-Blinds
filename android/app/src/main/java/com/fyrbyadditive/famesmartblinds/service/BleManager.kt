package com.fyrbyadditive.famesmartblinds.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.fyrbyadditive.famesmartblinds.data.model.BlindCommand
import com.fyrbyadditive.famesmartblinds.data.model.DeviceOrientation
import com.fyrbyadditive.famesmartblinds.data.model.WiFiNetwork
import com.fyrbyadditive.famesmartblinds.data.model.WiFiScanResponse
import com.fyrbyadditive.famesmartblinds.data.repository.DeviceRepository
import com.fyrbyadditive.famesmartblinds.util.Constants
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Bluetooth LE operations for initial device setup.
 *
 * BLE is only used during initial device setup when the device has no WiFi.
 * Once WiFi is configured, the device disables BLE advertising and all
 * communication happens over HTTP.
 */
@Singleton
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceRepository: DeviceRepository
) {
    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING
    }

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isPoweredOn = MutableStateFlow(false)
    val isPoweredOn: StateFlow<Boolean> = _isPoweredOn.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceId = MutableStateFlow<String?>(null)
    val connectedDeviceId: StateFlow<String?> = _connectedDeviceId.asStateFlow()

    private val _scannedWifiNetworks = MutableStateFlow<List<WiFiNetwork>>(emptyList())
    val scannedWifiNetworks: StateFlow<List<WiFiNetwork>> = _scannedWifiNetworks.asStateFlow()

    private val _isWifiScanning = MutableStateFlow(false)
    val isWifiScanning: StateFlow<Boolean> = _isWifiScanning.asStateFlow()

    var onStatusUpdate: ((String) -> Unit)? = null

    private val gson = Gson()

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    private var connectedGatt: BluetoothGatt? = null
    private var characteristics: MutableMap<UUID, BluetoothGattCharacteristic> = mutableMapOf()

    // Track pending notification subscriptions - we wait for these before marking connected
    private val pendingNotificationSubscriptions = mutableSetOf<UUID>()

    // Queue for descriptor writes (Android BLE requires sequential writes)
    private val descriptorWriteQueue = mutableListOf<BluetoothGattDescriptor>()
    private var isWritingDescriptor = false

    // Queue for characteristic writes (Android BLE requires sequential operations)
    private data class CharacteristicWrite(val uuid: UUID, val value: String)
    private val characteristicWriteQueue = mutableListOf<CharacteristicWrite>()
    private var isWritingCharacteristic = false

    private var scanJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        checkBluetoothState()
    }

    private fun checkBluetoothState() {
        _isPoweredOn.value = bluetoothAdapter?.isEnabled == true
    }

    // MARK: - Permissions

    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    // MARK: - Scanning

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Missing BLE permissions")
            return
        }

        checkBluetoothState()
        if (!_isPoweredOn.value) {
            Log.w(TAG, "Cannot scan - Bluetooth not powered on")
            return
        }

        Log.d(TAG, "Starting BLE scan for FAME Smart Blinds devices")
        _isScanning.value = true

        // Clear previous BLE-only devices
        deviceRepository.clearBleOnlyDevices()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(Constants.BLE.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan: ${e.message}")
            _isScanning.value = false
            return
        }

        // Stop scanning after timeout
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(Constants.Timeout.BLE_SCAN)
            stopScanning()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!hasRequiredPermissions()) return

        Log.d(TAG, "Stopping BLE scan")
        _isScanning.value = false
        scanJob?.cancel()
        scanJob = null

        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val advertisedName = result.scanRecord?.deviceName
            val rssi = result.rssi

            Log.d(TAG, "Discovered - advertised: '$advertisedName' rssi: $rssi")

            // Only use advertised name to avoid stale cached entries
            if (advertisedName.isNullOrEmpty()) {
                Log.d(TAG, "Skipping - no advertised name")
                return
            }

            deviceRepository.updateFromBle(device, advertisedName, rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            _isScanning.value = false
        }
    }

    // MARK: - Connection

    @SuppressLint("MissingPermission")
    fun connect(deviceId: String) {
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Missing BLE permissions")
            return
        }

        val device = deviceRepository.getDevice(deviceId)
        val bluetoothDevice = device?.bluetoothDevice

        if (bluetoothDevice == null) {
            Log.w(TAG, "No Bluetooth device found for $deviceId")
            return
        }

        Log.d(TAG, "Connecting to ${device.name}")
        _connectionState.value = ConnectionState.CONNECTING
        _connectedDeviceId.value = deviceId

        connectedGatt?.close()
        connectedGatt = bluetoothDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        // Connection timeout
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(Constants.Timeout.BLE_CONNECTION)
            if (_connectionState.value == ConnectionState.CONNECTING) {
                Log.w(TAG, "Connection timeout")
                disconnect()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (!hasRequiredPermissions()) return

        Log.d(TAG, "Disconnecting")
        _connectionState.value = ConnectionState.DISCONNECTING
        connectionTimeoutJob?.cancel()

        try {
            connectedGatt?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting: ${e.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to device - requesting MTU and starting service discovery")
                    connectionTimeoutJob?.cancel()
                    // Request larger MTU for WiFi scan results (JSON can be 500+ bytes)
                    // Default BLE MTU is only 23 bytes (20 for data), we need more
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from device")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _connectedDeviceId.value = null
                    characteristics.clear()
                    pendingNotificationSubscriptions.clear()
                    descriptorWriteQueue.clear()
                    isWritingDescriptor = false
                    characteristicWriteQueue.clear()
                    isWritingCharacteristic = false
                    connectedGatt?.close()
                    connectedGatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed to $mtu bytes")
            } else {
                Log.w(TAG, "MTU change failed with status $status, using default")
            }
            // Proceed with service discovery regardless of MTU result
            Log.d(TAG, "Starting service discovery")
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }

            val service = gatt.getService(Constants.BLE.SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "FAME Smart Blinds service not found")
                return
            }

            Log.d(TAG, "Discovered FAME Smart Blinds service")

            // Clear pending subscriptions and descriptor queue
            pendingNotificationSubscriptions.clear()
            descriptorWriteQueue.clear()
            isWritingDescriptor = false

            // Store all characteristics
            Constants.BLE.ALL_CHARACTERISTIC_UUIDS.forEach { uuid ->
                service.getCharacteristic(uuid)?.let { char ->
                    characteristics[uuid] = char
                    Log.d(TAG, "Found characteristic: $uuid")

                    // Enable notifications for status and WiFi scan results
                    // Track WiFi scan results as required subscription before marking connected
                    if ((uuid == Constants.BLE.STATUS_UUID || uuid == Constants.BLE.WIFI_SCAN_RESULTS_UUID) &&
                        char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                        gatt.setCharacteristicNotification(char, true)
                        char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.let { desc ->
                            if (uuid == Constants.BLE.WIFI_SCAN_RESULTS_UUID) {
                                Log.d(TAG, "Queueing notification subscription for WiFi scan results (REQUIRED)")
                                pendingNotificationSubscriptions.add(uuid)
                            } else {
                                Log.d(TAG, "Queueing notification subscription for $uuid")
                            }
                            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            descriptorWriteQueue.add(desc)
                        }
                    }
                }
            }

            // If no pending subscriptions, mark as connected immediately
            // Otherwise, start processing the descriptor write queue
            if (pendingNotificationSubscriptions.isEmpty() && descriptorWriteQueue.isEmpty()) {
                Log.d(TAG, "No pending subscriptions - setting state to CONNECTED")
                _connectionState.value = ConnectionState.CONNECTED
            } else {
                Log.d(TAG, "Starting descriptor write queue (${descriptorWriteQueue.size} items)")
                processNextDescriptorWrite(gatt)
            }
        }

        @SuppressLint("MissingPermission")
        private fun processNextDescriptorWrite(gatt: BluetoothGatt) {
            if (isWritingDescriptor || descriptorWriteQueue.isEmpty()) {
                return
            }

            val descriptor = descriptorWriteQueue.removeAt(0)
            isWritingDescriptor = true
            Log.d(TAG, "Writing descriptor for ${descriptor.characteristic.uuid}")
            gatt.writeDescriptor(descriptor)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            isWritingDescriptor = false
            val charUuid = descriptor.characteristic.uuid

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor write successful for $charUuid")

                // Check if this was a required subscription we were waiting for
                if (pendingNotificationSubscriptions.contains(charUuid)) {
                    pendingNotificationSubscriptions.remove(charUuid)
                    Log.d(TAG, "Required subscription confirmed for $charUuid, remaining: ${pendingNotificationSubscriptions.size}")
                }
            } else {
                Log.e(TAG, "Descriptor write failed for $charUuid: $status")

                // If subscription failed for a required characteristic, remove it from pending
                // and proceed anyway - we'll fall back to polling
                if (pendingNotificationSubscriptions.contains(charUuid)) {
                    pendingNotificationSubscriptions.remove(charUuid)
                    Log.d(TAG, "Subscription failed for $charUuid - removing from pending (will use polling fallback), remaining: ${pendingNotificationSubscriptions.size}")
                }
            }

            // Process next descriptor in queue
            if (descriptorWriteQueue.isNotEmpty()) {
                processNextDescriptorWrite(gatt)
            } else {
                if (pendingNotificationSubscriptions.isEmpty() && _connectionState.value != ConnectionState.CONNECTED) {
                    // All descriptor writes done and no pending subscriptions
                    Log.d(TAG, "All subscriptions resolved - setting state to CONNECTED")
                    _connectionState.value = ConnectionState.CONNECTED
                }
                // Try to process any queued characteristic writes now that descriptors are done
                processNextCharacteristicWrite()
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicValue(characteristic)
            }
        }

        // New callback for API 33+
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicValueFromBytes(characteristic.uuid, value)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicValue(characteristic)
        }

        // New callback for API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val stringValue = value.toString(Charsets.UTF_8)
            Log.d(TAG, "Characteristic changed (API 33+) ${characteristic.uuid}: '$stringValue'")
            handleCharacteristicValueFromBytes(characteristic.uuid, value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            isWritingCharacteristic = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Successfully wrote to ${characteristic.uuid}")
            } else {
                Log.e(TAG, "Failed to write to ${characteristic.uuid}: $status")
            }
            // Process next write in queue
            processNextCharacteristicWrite()
        }
    }

    private fun handleCharacteristicValue(characteristic: BluetoothGattCharacteristic) {
        val value = characteristic.value?.toString(Charsets.UTF_8) ?: return
        Log.d(TAG, "Characteristic ${characteristic.uuid}: '$value'")
        handleCharacteristicValueFromBytes(characteristic.uuid, characteristic.value ?: return)
    }

    private fun handleCharacteristicValueFromBytes(uuid: UUID, valueBytes: ByteArray) {
        val value = valueBytes.toString(Charsets.UTF_8)

        when (uuid) {
            Constants.BLE.STATUS_UUID -> {
                onStatusUpdate?.invoke(value)
            }
            Constants.BLE.WIFI_SCAN_RESULTS_UUID -> {
                Log.d(TAG, "WiFi scan results received: $value")
                parseWifiScanResults(value)
            }
            // Other characteristics can update device state via repository if needed
        }
    }

    private fun parseWifiScanResults(jsonString: String) {
        Log.d(TAG, "[WIFI-SCAN] parseWifiScanResults called with: $jsonString")

        // Cancel timeout since we received results
        wifiScanTimeoutJob?.cancel()
        Log.d(TAG, "[WIFI-SCAN] Setting isWifiScanning = false (received results)")
        _isWifiScanning.value = false

        try {
            val response = gson.fromJson(jsonString, WiFiScanResponse::class.java)
            // Sort by signal strength (strongest first)
            _scannedWifiNetworks.value = response.networks.sortedByDescending { it.rssi }
            Log.d(TAG, "Parsed ${_scannedWifiNetworks.value.size} WiFi networks")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode WiFi scan results: ${e.message}")
        }
    }

    // MARK: - Write Operations

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(uuid: UUID, value: String) {
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Cannot write to $uuid - missing permissions")
            return
        }

        // Queue the write - Android BLE requires sequential operations
        characteristicWriteQueue.add(CharacteristicWrite(uuid, value))
        Log.d(TAG, "Queued write '$value' to $uuid (queue size: ${characteristicWriteQueue.size})")
        processNextCharacteristicWrite()
    }

    @SuppressLint("MissingPermission")
    private fun processNextCharacteristicWrite() {
        // Don't write if another write or descriptor write is in progress
        if (isWritingCharacteristic || isWritingDescriptor || characteristicWriteQueue.isEmpty()) {
            return
        }

        val gatt = connectedGatt ?: return
        val write = characteristicWriteQueue.removeAt(0)

        val characteristic = characteristics[write.uuid]
        if (characteristic == null) {
            Log.w(TAG, "Cannot write to ${write.uuid} - characteristic not found")
            // Try next in queue
            processNextCharacteristicWrite()
            return
        }

        isWritingCharacteristic = true
        characteristic.value = write.value.toByteArray(Charsets.UTF_8)

        val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        characteristic.writeType = writeType

        val result = gatt.writeCharacteristic(characteristic)
        Log.d(TAG, "Writing '${write.value}' to ${write.uuid} (result: $result)")

        // If write type is NO_RESPONSE, we won't get a callback, so process next immediately
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            isWritingCharacteristic = false
            processNextCharacteristicWrite()
        }
    }

    @SuppressLint("MissingPermission")
    fun readCharacteristic(uuid: UUID) {
        if (!hasRequiredPermissions()) return

        val characteristic = characteristics[uuid]
        if (characteristic == null) {
            Log.w(TAG, "Characteristic $uuid not found")
            return
        }

        connectedGatt?.readCharacteristic(characteristic)
    }

    // MARK: - Configuration Methods

    fun configureWifi(ssid: String, password: String) {
        Log.d(TAG, "Configuring WiFi - SSID: $ssid")
        writeCharacteristic(Constants.BLE.WIFI_SSID_UUID, ssid)

        // Small delay before writing password
        scope.launch {
            delay(300)
            writeCharacteristic(Constants.BLE.WIFI_PASSWORD_UUID, password)
        }
    }

    fun configureMqtt(broker: String, port: Int = 1883) {
        val value = "$broker:$port"
        Log.d(TAG, "Configuring MQTT - $value")
        writeCharacteristic(Constants.BLE.MQTT_BROKER_UUID, value)
    }

    fun configureDeviceName(name: String) {
        Log.d(TAG, "Configuring device name - $name")
        writeCharacteristic(Constants.BLE.DEVICE_NAME_UUID, name)
    }

    fun configureDevicePassword(password: String) {
        Log.d(TAG, "Configuring device password")
        writeCharacteristic(Constants.BLE.DEVICE_PASSWORD_UUID, password)
    }

    fun configureOrientation(orientation: DeviceOrientation) {
        Log.d(TAG, "Configuring orientation - ${orientation.value}")
        writeCharacteristic(Constants.BLE.ORIENTATION_UUID, orientation.value)
    }

    fun sendCommand(command: BlindCommand) {
        Log.d(TAG, "Sending command - ${command.value}")
        writeCharacteristic(Constants.BLE.COMMAND_UUID, command.value)
    }

    // MARK: - WiFi Scanning

    private var wifiScanTimeoutJob: Job? = null

    fun triggerWifiScan() {
        val isConnected = _connectionState.value == ConnectionState.CONNECTED
        val alreadyScanning = _isWifiScanning.value
        Log.d(TAG, "[WIFI-SCAN] triggerWifiScan called (characteristics: ${characteristics.size}, connected: $isConnected, alreadyScanning: $alreadyScanning)")

        // Don't restart scan if already scanning
        if (alreadyScanning) {
            Log.d(TAG, "[WIFI-SCAN] Already scanning, ignoring duplicate call")
            return
        }

        // Log all available characteristics for debugging
        val uuids = characteristics.keys.map { it.toString().uppercase() }.sorted()
        Log.d(TAG, "[WIFI-SCAN] Available characteristics: ${uuids.joinToString(", ")}")

        // Check if the scan trigger characteristic exists
        val hasTrigger = characteristics.containsKey(Constants.BLE.WIFI_SCAN_TRIGGER_UUID)
        val hasResults = characteristics.containsKey(Constants.BLE.WIFI_SCAN_RESULTS_UUID)
        Log.d(TAG, "[WIFI-SCAN] Has scan trigger: $hasTrigger, Has scan results: $hasResults")

        if (!hasTrigger) {
            Log.e(TAG, "[WIFI-SCAN] ERROR: Scan trigger characteristic not found! Device may need firmware update.")
            // Don't even try if the characteristic doesn't exist
            return
        }

        Log.d(TAG, "[WIFI-SCAN] Setting isWifiScanning = true")
        _isWifiScanning.value = true
        _scannedWifiNetworks.value = emptyList()

        // Cancel any previous timeout
        wifiScanTimeoutJob?.cancel()

        // This writes to the BLE characteristic to tell the ESP32 to scan for WiFi networks
        writeCharacteristic(Constants.BLE.WIFI_SCAN_TRIGGER_UUID, "SCAN")

        // Set a timeout - WiFi scan should complete within 20 seconds
        wifiScanTimeoutJob = scope.launch {
            Log.d(TAG, "[WIFI-SCAN] Timeout job started, waiting 20 seconds...")
            delay(20_000)
            if (_isWifiScanning.value) {
                Log.w(TAG, "[WIFI-SCAN] Timeout - no WiFi networks received from device")
                Log.d(TAG, "[WIFI-SCAN] Setting isWifiScanning = false (timeout)")
                _isWifiScanning.value = false
            } else {
                Log.d(TAG, "[WIFI-SCAN] Timeout fired but isWifiScanning was already false")
            }
        }
    }

    companion object {
        private const val TAG = "BleManager"
    }
}

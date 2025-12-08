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
import com.fyrbyadditive.famesmartblinds.data.repository.DeviceRepository
import com.fyrbyadditive.famesmartblinds.util.Constants
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

    var onStatusUpdate: ((String) -> Unit)? = null

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    private var connectedGatt: BluetoothGatt? = null
    private var characteristics: MutableMap<UUID, BluetoothGattCharacteristic> = mutableMapOf()

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
                    Log.d(TAG, "Connected to device")
                    connectionTimeoutJob?.cancel()
                    _connectionState.value = ConnectionState.CONNECTED
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from device")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _connectedDeviceId.value = null
                    characteristics.clear()
                    connectedGatt?.close()
                    connectedGatt = null
                }
            }
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

            // Store all characteristics
            Constants.BLE.ALL_CHARACTERISTIC_UUIDS.forEach { uuid ->
                service.getCharacteristic(uuid)?.let { char ->
                    characteristics[uuid] = char
                    Log.d(TAG, "Found characteristic: $uuid")

                    // Read initial value
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                        gatt.readCharacteristic(char)
                    }

                    // Enable notifications for status
                    if (uuid == Constants.BLE.STATUS_UUID &&
                        char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                        gatt.setCharacteristicNotification(char, true)
                        char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.let { desc ->
                            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(desc)
                        }
                    }
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicValue(characteristic)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicValue(characteristic)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Successfully wrote to ${characteristic.uuid}")
            } else {
                Log.e(TAG, "Failed to write to ${characteristic.uuid}: $status")
            }
        }
    }

    private fun handleCharacteristicValue(characteristic: BluetoothGattCharacteristic) {
        val value = characteristic.value?.toString(Charsets.UTF_8) ?: return
        Log.d(TAG, "Characteristic ${characteristic.uuid}: '$value'")

        when (characteristic.uuid) {
            Constants.BLE.STATUS_UUID -> {
                onStatusUpdate?.invoke(value)
            }
            // Other characteristics can update device state via repository if needed
        }
    }

    // MARK: - Write Operations

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(uuid: UUID, value: String) {
        if (!hasRequiredPermissions()) return

        val characteristic = characteristics[uuid]
        if (characteristic == null) {
            Log.w(TAG, "Characteristic $uuid not found")
            return
        }

        characteristic.value = value.toByteArray(Charsets.UTF_8)

        val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        characteristic.writeType = writeType

        connectedGatt?.writeCharacteristic(characteristic)
        Log.d(TAG, "Wrote '$value' to $uuid")
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

    companion object {
        private const val TAG = "BleManager"
    }
}

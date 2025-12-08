package com.fyrbyadditive.famesmartblinds.data.repository

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.fyrbyadditive.famesmartblinds.data.model.BlindDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for all discovered devices, keyed by deviceId.
 *
 * Devices can be discovered via two methods:
 * - BLE (Bluetooth): Only used during initial device setup when the device has no WiFi.
 *   After setup completes (WiFi connected), the device disables BLE advertising.
 * - mDNS/HTTP: Primary discovery method for configured devices. All control, status,
 *   and configuration changes happen over HTTP after initial setup.
 *
 * The repository unifies both discovery sources using the deviceId (8-char hex MAC suffix)
 * as a stable identifier across BLE and HTTP.
 */
@Singleton
class DeviceRepository @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _devices = MutableStateFlow<Map<String, BlindDevice>>(emptyMap())

    /**
     * All devices as a map keyed by deviceId
     */
    val devices: StateFlow<Map<String, BlindDevice>> = _devices.asStateFlow()

    /**
     * All devices as a sorted list for UI display.
     * Reactively updates when devices change.
     */
    val deviceList: StateFlow<List<BlindDevice>> = _devices
        .map { it.values.sortedBy { device -> device.name } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _scanCooldownActive = MutableStateFlow(false)
    val scanCooldownActive: StateFlow<Boolean> = _scanCooldownActive.asStateFlow()

    private var deviceInSetup: String? = null

    init {
        Log.d(TAG, "DeviceRepository initialized")
    }

    /**
     * Get devices as a sorted list
     */
    fun getDeviceList(): List<BlindDevice> {
        return _devices.value.values.sortedBy { it.name }
    }

    /**
     * Get only WiFi-configured devices (for main device list)
     */
    fun getConfiguredDevices(): List<BlindDevice> {
        return _devices.value.values
            .filter { it.ipAddress != null }
            .sortedBy { it.name }
    }

    /**
     * Get only BLE devices that need setup (for setup wizard)
     */
    fun getUnconfiguredDevices(): List<BlindDevice> {
        return _devices.value.values
            .filter { it.bluetoothDevice != null && it.ipAddress == null }
            .sortedBy { it.name }
    }

    /**
     * Update or create a device from BLE discovery.
     * Only called during initial setup when device is advertising (before WiFi configured).
     */
    fun updateFromBle(bluetoothDevice: BluetoothDevice, advertisedName: String?, rssi: Int) {
        val name = advertisedName ?: bluetoothDevice.name ?: "Unknown"
        val deviceId = BlindDevice.extractDeviceId(name).lowercase()

        // Skip devices without a valid deviceId
        if (deviceId.isEmpty()) {
            Log.d(TAG, "Skipping BLE device without deviceId: $name")
            return
        }

        _devices.update { current ->
            val existing = current[deviceId]
            if (existing != null) {
                // Update existing device with BLE info
                Log.d(TAG, "Updated BLE info for $deviceId: ${existing.name}")
                current + (deviceId to existing.copy(
                    bluetoothDevice = bluetoothDevice,
                    rssi = rssi,
                    name = name,
                    lastSeen = Date()
                ))
            } else {
                // Create new device
                val newDevice = BlindDevice.fromBle(bluetoothDevice, rssi, advertisedName)
                Log.d(TAG, "Added new BLE device $deviceId: $name")
                current + (deviceId to newDevice)
            }
        }
    }

    /**
     * Update or create a device from mDNS/HTTP discovery.
     * Primary discovery method for configured devices.
     */
    fun updateFromHttp(name: String, ipAddress: String, deviceId: String, macAddress: String = "") {
        Log.i(TAG, ">>> updateFromHttp called: name=$name, ip=$ipAddress, deviceId=$deviceId")

        // Skip devices without a valid deviceId
        if (deviceId.isEmpty()) {
            Log.d(TAG, "Skipping HTTP device without deviceId: $name")
            return
        }

        val normalizedId = deviceId.lowercase()

        // Skip devices currently being set up via BLE
        if (normalizedId == deviceInSetup) {
            Log.d(TAG, "Skipping HTTP update for device $normalizedId - currently in setup")
            return
        }

        _devices.update { current ->
            Log.i(TAG, "Current devices before update: ${current.keys}")
            val existing = current[normalizedId]
            if (existing != null) {
                // Update existing device with HTTP info
                Log.d(TAG, "Updated HTTP info for $normalizedId: IP=$ipAddress")
                current + (normalizedId to existing.copy(
                    ipAddress = ipAddress,
                    wifiConnected = true,
                    name = name,
                    macAddress = if (macAddress.isNotEmpty()) macAddress else existing.macAddress,
                    lastSeen = Date()
                ))
            } else {
                // Create new device from HTTP discovery
                val newDevice = BlindDevice.fromHttp(name, ipAddress, normalizedId).copy(
                    macAddress = macAddress
                )
                Log.i(TAG, ">>> Added new HTTP device $normalizedId: $name at $ipAddress")
                val updated = current + (normalizedId to newDevice)
                Log.i(TAG, "Devices after update: ${updated.keys}")
                updated
            }
        }

        Log.i(TAG, "<<< updateFromHttp complete. Total devices: ${_devices.value.size}")
    }

    /**
     * Update a device with new state
     */
    fun updateDevice(deviceId: String, update: (BlindDevice) -> BlindDevice) {
        val normalizedId = deviceId.lowercase()
        _devices.update { current ->
            val existing = current[normalizedId]
            if (existing != null) {
                current + (normalizedId to update(existing))
            } else {
                current
            }
        }
    }

    /**
     * Get a device by its deviceId
     */
    fun getDevice(deviceId: String): BlindDevice? {
        return _devices.value[deviceId.lowercase()]
    }

    /**
     * Mark a device as currently being set up via BLE.
     */
    fun markDeviceInSetup(deviceId: String?) {
        deviceInSetup = deviceId?.lowercase()
        if (deviceInSetup != null) {
            Log.d(TAG, "Device $deviceInSetup marked as in setup")
        } else {
            Log.d(TAG, "Device setup completed, clearing setup flag")
        }
    }

    /**
     * Check if a device is currently being set up
     */
    fun isDeviceInSetup(deviceId: String): Boolean {
        return deviceInSetup == deviceId.lowercase()
    }

    /**
     * Clear all devices
     */
    fun clear() {
        _devices.value = emptyMap()
        Log.d(TAG, "Cleared all devices")
    }

    /**
     * Clear only BLE-discovered devices (those without IP addresses)
     */
    fun clearBleOnlyDevices() {
        _devices.update { current ->
            val filtered = current.filterValues { it.ipAddress != null || it.bluetoothDevice == null }
            val removedCount = current.size - filtered.size
            if (removedCount > 0) {
                Log.d(TAG, "Cleared $removedCount BLE-only devices")
            }
            filtered
        }
    }

    /**
     * Remove a specific device
     */
    fun remove(deviceId: String) {
        val normalizedId = deviceId.lowercase()
        _devices.update { current ->
            val device = current[normalizedId]
            if (device != null) {
                Log.d(TAG, "Removed device: $normalizedId (${device.name})")
                current - normalizedId
            } else {
                current
            }
        }
    }

    /**
     * Remove stale devices not seen recently
     */
    fun removeStale(olderThanMs: Long = 300_000) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        _devices.update { current ->
            val filtered = current.filterValues { it.lastSeen.time > cutoff }
            val removedCount = current.size - filtered.size
            if (removedCount > 0) {
                Log.d(TAG, "Removed $removedCount stale devices")
            }
            filtered
        }
    }

    /**
     * Start a cooldown period where scanning should be blocked
     */
    suspend fun startScanCooldown(seconds: Int = 5) {
        _scanCooldownActive.value = true
        Log.d(TAG, "Scan cooldown started for $seconds seconds")
        kotlinx.coroutines.delay(seconds * 1000L)
        _scanCooldownActive.value = false
        Log.d(TAG, "Scan cooldown ended")
    }

    companion object {
        private const val TAG = "DeviceRepository"
    }
}

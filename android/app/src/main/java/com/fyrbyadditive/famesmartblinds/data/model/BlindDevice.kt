package com.fyrbyadditive.famesmartblinds.data.model

import android.bluetooth.BluetoothDevice
import com.fyrbyadditive.famesmartblinds.data.remote.CalibrationStatusResponse
import com.fyrbyadditive.famesmartblinds.data.remote.DeviceStatus
import java.util.Date
import java.util.UUID

/**
 * Represents a FAME Smart Blinds device.
 *
 * Architecture note: BLE is only used during initial device setup (when device has no WiFi).
 * Once WiFi is configured, the device disables BLE advertising and all communication
 * (control, configuration, status) happens over HTTP via mDNS discovery.
 */
data class BlindDevice(
    val id: UUID = UUID.randomUUID(),

    // BLE properties - only used during initial setup before WiFi is configured
    val bluetoothDevice: BluetoothDevice? = null,
    val rssi: Int = 0,

    // Device info
    val name: String = "Unknown Device",
    val deviceId: String = "",  // 8-char hex ID from MAC address, used as stable identifier
    val macAddress: String = "",

    // Configuration (readable via BLE during setup)
    val wifiSsid: String = "",
    val mqttBroker: String = "",

    // Connection state
    val bleConnected: Boolean = false,  // Only true during setup
    val wifiConnected: Boolean = false,
    val mqttConnected: Boolean = false,

    // Network info (when WiFi connected - primary communication method post-setup)
    val ipAddress: String? = null,

    // Blind state
    val state: BlindState = BlindState.UNKNOWN,
    val position: Int = 0,

    // Calibration state
    val isCalibrated: Boolean = false,
    val cumulativePosition: Int = 0,
    val maxPosition: Int = 0,
    val calibrationState: String = "idle",

    // Status string from device
    val statusString: String = "",

    // Timestamps
    val lastSeen: Date = Date()
) {
    /**
     * Whether this device can be controlled via HTTP (has WiFi connection)
     */
    val canControlViaHttp: Boolean
        get() = ipAddress != null

    /**
     * Whether this device needs setup (found via BLE but no WiFi)
     */
    val needsSetup: Boolean
        get() = ipAddress == null && bluetoothDevice != null

    /**
     * Update device from status string like "wifi:192.168.1.100,mqtt:ok,servo:ok"
     */
    fun updateFromStatus(status: String): BlindDevice {
        var device = this.copy(statusString = status)

        status.split(",").forEach { component ->
            val parts = component.split(":")
            if (parts.size >= 2) {
                val key = parts[0]
                val value = parts[1]

                device = when (key) {
                    "wifi" -> {
                        when {
                            value == "connecting" -> device.copy(wifiConnected = false)
                            value == "disconnected" -> device.copy(wifiConnected = false, ipAddress = null)
                            else -> device.copy(wifiConnected = true, ipAddress = value)
                        }
                    }
                    "mqtt" -> device.copy(mqttConnected = value == "ok")
                    else -> device
                }
            }
        }

        return device
    }

    /**
     * Update device from HTTP status response
     */
    fun updateFromDeviceStatus(status: DeviceStatus): BlindDevice {
        return copy(
            state = BlindState.fromString(status.state),
            position = status.position,
            isCalibrated = status.calibration?.calibrated ?: false,
            cumulativePosition = status.calibration?.cumulativePosition ?: 0,
            maxPosition = status.calibration?.maxPosition ?: 0,
            calibrationState = status.calibration?.state ?: "idle"
        )
    }

    /**
     * Update device from calibration status response
     */
    fun updateFromCalibrationStatus(status: CalibrationStatusResponse): BlindDevice {
        return copy(
            isCalibrated = status.calibrated,
            cumulativePosition = status.position,
            maxPosition = status.maxPosition,
            calibrationState = status.calibrationState
        )
    }

    companion object {
        /**
         * Extract device ID from a name like "FAMEBlinds_23c57e80"
         */
        fun extractDeviceId(name: String?): String {
            if (name == null) return ""

            val underscoreIndex = name.lastIndexOf('_')
            if (underscoreIndex != -1) {
                val suffix = name.substring(underscoreIndex + 1)
                // Validate it looks like a hex device ID (8 hex chars)
                if (suffix.length == 8 && suffix.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                    return suffix.lowercase()
                }
            }
            return ""
        }

        /**
         * Create from BLE discovery - used during initial device setup only
         */
        fun fromBle(
            bluetoothDevice: BluetoothDevice,
            rssi: Int = 0,
            advertisedName: String? = null
        ): BlindDevice {
            val name = advertisedName ?: bluetoothDevice.name ?: "Unknown Device"
            return BlindDevice(
                bluetoothDevice = bluetoothDevice,
                rssi = rssi,
                name = name,
                deviceId = extractDeviceId(name),
                lastSeen = Date()
            )
        }

        /**
         * Create from mDNS/HTTP discovery - primary discovery method for configured devices
         */
        fun fromHttp(
            name: String,
            ipAddress: String,
            deviceId: String = ""
        ): BlindDevice {
            return BlindDevice(
                name = name,
                ipAddress = ipAddress,
                deviceId = deviceId,
                wifiConnected = true,
                lastSeen = Date()
            )
        }
    }
}

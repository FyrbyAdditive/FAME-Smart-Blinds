package com.fyrbyadditive.famesmartblinds.data.remote

import com.fyrbyadditive.famesmartblinds.data.model.DeviceOrientation

/**
 * Response from /status endpoint
 */
data class DeviceStatus(
    val state: String,
    val position: Int,
    val wifi: WifiStatus,
    val calibration: CalibrationStatus?,
    val uptime: Int
) {
    data class WifiStatus(
        val ssid: String,
        val rssi: Int,
        val ip: String
    )

    data class CalibrationStatus(
        val calibrated: Boolean,
        val cumulativePosition: Int,
        val maxPosition: Int,
        val state: String
    )
}

/**
 * Response from /info endpoint
 */
data class DeviceInfo(
    val device: String,
    val version: String,
    val mac: String,
    val deviceId: String,
    val hostname: String,
    val orientation: String?,
    val speed: Int?,
    val wifiSsid: String?,
    val mqttBroker: String?,
    val mqttPort: Int?,
    val mqttUser: String?,
    val passwordRequired: Boolean?
) {
    val deviceOrientation: DeviceOrientation
        get() = DeviceOrientation.fromString(orientation)

    val servoSpeed: Int
        get() = speed ?: 500

    val requiresPassword: Boolean
        get() = passwordRequired == true
}

/**
 * Response from /calibrate/status endpoint
 */
data class CalibrationStatusResponse(
    val calibrated: Boolean,
    val position: Int,
    val maxPosition: Int,
    val calibrationState: String
)

/**
 * Response from /logs endpoint
 */
data class DeviceLogsResponse(
    val logs: List<String>
)

/**
 * Response from OTA endpoints
 */
data class OTAResponse(
    val success: Boolean,
    val message: String?,
    val error: String?
)

/**
 * Response from /ota/status endpoint
 */
data class OTAStatusResponse(
    val inProgress: Boolean,
    val received: Int,
    val total: Int,
    val progress: Int?,
    val freeHeap: Int?
)

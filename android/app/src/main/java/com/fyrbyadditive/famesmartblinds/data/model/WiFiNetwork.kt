package com.fyrbyadditive.famesmartblinds.data.model

import com.google.gson.annotations.SerializedName

/**
 * Signal strength classification for WiFi networks
 */
enum class SignalStrength {
    EXCELLENT,
    GOOD,
    FAIR,
    WEAK;

    companion object {
        fun fromRssi(rssi: Int): SignalStrength {
            return when {
                rssi >= -50 -> EXCELLENT
                rssi >= -60 -> GOOD
                rssi >= -70 -> FAIR
                else -> WEAK
            }
        }
    }
}

/**
 * Represents a WiFi network discovered during BLE setup scanning.
 * Uses compact JSON field names to fit within BLE characteristic size limits.
 */
data class WiFiNetwork(
    @SerializedName("s") val ssid: String,
    @SerializedName("r") val rssi: Int,
    @SerializedName("e") val encrypted: Int
) {
    val isSecured: Boolean get() = encrypted == 1

    val signalStrength: SignalStrength get() = SignalStrength.fromRssi(rssi)
}

/**
 * Response wrapper for WiFi scan results from firmware.
 * Format: {"n":[{"s":"SSID","r":-65,"e":1},...]}
 */
data class WiFiScanResponse(
    @SerializedName("n") val networks: List<WiFiNetwork>
)

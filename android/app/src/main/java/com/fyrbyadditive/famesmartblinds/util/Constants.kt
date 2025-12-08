package com.fyrbyadditive.famesmartblinds.util

import java.util.UUID

/**
 * Application constants matching the iOS app and firmware
 */
object Constants {
    /**
     * BLE UUIDs (must match firmware)
     */
    object BLE {
        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")

        val WIFI_SSID_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        val WIFI_PASSWORD_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a9")
        val DEVICE_NAME_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26aa")
        val MQTT_BROKER_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26ab")
        val STATUS_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26ac")
        val COMMAND_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26ad")
        val DEVICE_PASSWORD_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26ae")
        val ORIENTATION_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26af")

        val ALL_CHARACTERISTIC_UUIDS = listOf(
            WIFI_SSID_UUID, WIFI_PASSWORD_UUID, DEVICE_NAME_UUID,
            MQTT_BROKER_UUID, STATUS_UUID, COMMAND_UUID, DEVICE_PASSWORD_UUID, ORIENTATION_UUID
        )
    }

    /**
     * HTTP endpoints
     */
    object HTTP {
        const val DEFAULT_PORT = 80
        const val STATUS_ENDPOINT = "/status"
        const val INFO_ENDPOINT = "/info"
        const val COMMAND_ENDPOINT = "/command"
        const val OPEN_ENDPOINT = "/open"
        const val CLOSE_ENDPOINT = "/close"
        const val STOP_ENDPOINT = "/stop"
    }

    /**
     * Device discovery
     */
    object Discovery {
        const val BONJOUR_SERVICE_TYPE = "_famesmartblinds._tcp"
        const val BONJOUR_DOMAIN = "local."
        const val HTTP_SERVICE_TYPE = "_http._tcp."
    }

    /**
     * Timeouts in milliseconds
     */
    object Timeout {
        const val BLE_CONNECTION = 10_000L
        const val BLE_SCAN = 15_000L
        const val HTTP_REQUEST = 5_000L
        const val WIFI_CONNECTION = 30_000L
        const val STATUS_POLL_INTERVAL = 250L
        const val DISCOVERY_INTERVAL = 30_000L
    }

    /**
     * Speed limits
     */
    object Speed {
        const val MIN = 100
        const val MAX = 3400
        const val DEFAULT = 500
        const val STEP = 50
    }
}

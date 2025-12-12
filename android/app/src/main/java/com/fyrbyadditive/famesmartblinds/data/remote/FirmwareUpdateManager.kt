package com.fyrbyadditive.famesmartblinds.data.remote

import android.util.Log
import com.fyrbyadditive.zapsdk.FirmwareType
import com.fyrbyadditive.zapsdk.NotFoundException
import com.fyrbyadditive.zapsdk.ZAPClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Information about an available firmware update
 */
data class UpdateInfo(
    val version: String,
    val releaseNotes: String?,
    val requiresAppUpdate: Boolean,
    val requiredAppVersion: String?,
    val canFlash: Boolean,
    val requiredFirmwareVersion: String?
)

/**
 * Manages firmware update checking and downloading via the ZAP service.
 */
@Singleton
class FirmwareUpdateManager @Inject constructor() {

    private val client = ZAPClient()

    companion object {
        private const val TAG = "FirmwareUpdateManager"
        private const val PRODUCT_SLUG = "fame-smart-blinds"
        private const val BOARD_TYPE = "xiao-esp32-c3"
    }

    /**
     * Check for firmware updates for the given device.
     *
     * @param currentFirmwareVersion The device's current firmware version
     * @param currentAppVersion The current app version (e.g., "1.0.2")
     * @return UpdateInfo if an update is available, null otherwise
     */
    suspend fun checkForUpdate(
        currentFirmwareVersion: String,
        currentAppVersion: String
    ): UpdateInfo? {
        return try {
            Log.d(TAG, "Checking for updates. Current firmware: $currentFirmwareVersion, App: $currentAppVersion")

            val latestFirmware = client.getLatestFirmware(PRODUCT_SLUG)

            Log.d(TAG, "Latest firmware available: ${latestFirmware.version}")

            // Compare versions
            if (!isNewerVersion(currentFirmwareVersion, latestFirmware.version)) {
                Log.d(TAG, "No update available - already on latest or newer")
                return null
            }

            // Check if current app meets minimum requirement to flash this firmware
            val minFlashVersion = latestFirmware.minAppVersionFlash
            val canFlash = minFlashVersion?.let { required ->
                isVersionAtLeast(currentAppVersion, required)
            } ?: true

            // Check if app update will be required after flashing (to run the new firmware)
            val minRunVersion = latestFirmware.minAppVersionRun
            val requiresAppUpdate = minRunVersion?.let { required ->
                !isVersionAtLeast(currentAppVersion, required)
            } ?: false

            Log.d(TAG, "Update available: ${latestFirmware.version}, canFlash=$canFlash, requiresAppUpdate=$requiresAppUpdate")

            UpdateInfo(
                version = latestFirmware.version,
                releaseNotes = latestFirmware.releaseNotes,
                requiresAppUpdate = requiresAppUpdate,
                requiredAppVersion = minRunVersion,
                canFlash = canFlash,
                requiredFirmwareVersion = minFlashVersion
            )
        } catch (e: NotFoundException) {
            Log.w(TAG, "No firmware found for product: $PRODUCT_SLUG")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
            throw e
        }
    }

    /**
     * Download firmware from the ZAP service.
     *
     * @param version The firmware version to download
     * @param onProgress Progress callback (0.0 to 1.0) - called once with 1.0 when download completes
     * @return The firmware binary data
     */
    suspend fun downloadFirmware(
        version: String,
        onProgress: (Float) -> Unit
    ): ByteArray {
        Log.d(TAG, "Downloading firmware version: $version")

        val result = client.downloadFirmware(
            product = PRODUCT_SLUG,
            version = version,
            type = FirmwareType.UPDATE,
            board = BOARD_TYPE,
            validateChecksum = true
        )

        // Note: Android SDK doesn't support progress callbacks for download,
        // so we just signal completion
        onProgress(1.0f)

        Log.d(TAG, "Downloaded ${result.data.size} bytes, MD5: ${result.md5}, SHA256: ${result.sha256}")

        return result.data
    }

    /**
     * Compare two version strings to determine if `version` is newer than `current`.
     * Supports semantic versioning (e.g., "1.0.2", "1.2.0", "2.0.0")
     */
    private fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = parseVersion(current)
        val latestParts = parseVersion(latest)

        for (i in 0 until maxOf(currentParts.size, latestParts.size)) {
            val currentPart = currentParts.getOrElse(i) { 0 }
            val latestPart = latestParts.getOrElse(i) { 0 }

            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }

        return false // Equal versions
    }

    /**
     * Check if `version` is at least `required`.
     */
    private fun isVersionAtLeast(version: String, required: String): Boolean {
        val versionParts = parseVersion(version)
        val requiredParts = parseVersion(required)

        for (i in 0 until maxOf(versionParts.size, requiredParts.size)) {
            val vPart = versionParts.getOrElse(i) { 0 }
            val rPart = requiredParts.getOrElse(i) { 0 }

            if (vPart > rPart) return true
            if (vPart < rPart) return false
        }

        return true // Equal versions
    }

    /**
     * Parse a version string into numeric parts.
     * Handles versions like "1.0.2", "1.2", "v1.0.0"
     */
    private fun parseVersion(version: String): List<Int> {
        return version
            .removePrefix("v")
            .removePrefix("V")
            .split(".")
            .mapNotNull { it.toIntOrNull() }
    }
}

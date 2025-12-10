package com.fyrbyadditive.famesmartblinds.data.remote

import android.util.Log
import com.fyrbyadditive.famesmartblinds.data.local.AuthenticationManager
import com.fyrbyadditive.famesmartblinds.data.model.BlindCommand
import com.fyrbyadditive.famesmartblinds.data.model.DeviceOrientation
import com.fyrbyadditive.famesmartblinds.util.CRC32
import com.fyrbyadditive.famesmartblinds.util.Constants
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP client for communicating with FAME Smart Blinds devices
 */
@Singleton
class HttpClient @Inject constructor(
    private val authManager: AuthenticationManager
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(Constants.Timeout.HTTP_REQUEST, TimeUnit.MILLISECONDS)
        .readTimeout(Constants.Timeout.HTTP_REQUEST, TimeUnit.MILLISECONDS)
        .writeTimeout(Constants.Timeout.HTTP_REQUEST, TimeUnit.MILLISECONDS)
        .build()

    private val gson = Gson()

    // MARK: - Authentication Helpers

    /**
     * Add authentication header to request if we have a valid session
     */
    private fun Request.Builder.addAuthHeader(deviceId: String): Request.Builder {
        val password = authManager.getPassword(deviceId)
        if (password != null) {
            addHeader("X-Device-Password", password)
        }
        return this
    }

    /**
     * Check response for 401 and handle auth failure
     */
    private fun checkAuthResponse(response: okhttp3.Response, deviceId: String) {
        if (response.code == 401) {
            authManager.handleAuthenticationRequired(deviceId)
            throw AuthenticationRequiredException(deviceId)
        }
    }

    // MARK: - Device Control

    /**
     * Send a command to the device (PROTECTED)
     */
    suspend fun sendCommand(command: BlindCommand, ipAddress: String, deviceId: String) = withContext(Dispatchers.IO) {
        val endpoint = when (command) {
            BlindCommand.OPEN -> Constants.HTTP.OPEN_ENDPOINT
            BlindCommand.CLOSE -> Constants.HTTP.CLOSE_ENDPOINT
            BlindCommand.STOP -> Constants.HTTP.STOP_ENDPOINT
            BlindCommand.RESTART -> "/restart"
        }

        val url = "http://$ipAddress$endpoint"
        Log.d(TAG, "POST $url")

        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .addAuthHeader(deviceId)
            .build()

        val response = client.newCall(request).execute()
        checkAuthResponse(response, deviceId)
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }
        Log.d(TAG, "Command sent successfully")
    }

    // MARK: - Device Configuration

    /**
     * Set device name (PROTECTED)
     */
    suspend fun setDeviceName(name: String, ipAddress: String, deviceId: String) = withContext(Dispatchers.IO) {
        val encodedName = URLEncoder.encode(name, "UTF-8")
        val url = "http://$ipAddress/name?name=$encodedName"
        Log.d(TAG, "POST $url")

        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .addAuthHeader(deviceId)
            .build()

        val response = client.newCall(request).execute()
        checkAuthResponse(response, deviceId)
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }
        Log.d(TAG, "Device name set successfully")
    }

    /**
     * Set device password (PROTECTED)
     */
    suspend fun setDevicePassword(password: String, ipAddress: String, deviceId: String) = withContext(Dispatchers.IO) {
        val encodedPassword = URLEncoder.encode(password, "UTF-8")
        val url = "http://$ipAddress/password?password=$encodedPassword"
        Log.d(TAG, "POST /password")

        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .addAuthHeader(deviceId)
            .build()

        val response = client.newCall(request).execute()
        checkAuthResponse(response, deviceId)
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }
        Log.d(TAG, "Device password set successfully")
    }

    /**
     * Set MQTT configuration (PROTECTED)
     */
    suspend fun setMqttConfig(
        broker: String,
        port: Int = 1883,
        user: String = "",
        password: String = "",
        ipAddress: String,
        deviceId: String
    ) = withContext(Dispatchers.IO) {
        val encodedBroker = URLEncoder.encode(broker, "UTF-8")
        var urlString = "http://$ipAddress/mqtt?broker=$encodedBroker&port=$port"
        if (user.isNotEmpty()) {
            urlString += "&user=${URLEncoder.encode(user, "UTF-8")}"
        }
        if (password.isNotEmpty()) {
            urlString += "&password=${URLEncoder.encode(password, "UTF-8")}"
        }

        Log.d(TAG, "POST /mqtt")

        val request = Request.Builder()
            .url(urlString)
            .post("".toRequestBody(null))
            .addAuthHeader(deviceId)
            .build()

        val response = client.newCall(request).execute()
        checkAuthResponse(response, deviceId)
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }
        Log.d(TAG, "MQTT config set successfully")
    }

    /**
     * Set WiFi credentials (PROTECTED)
     */
    suspend fun setWifiCredentials(ssid: String, password: String, ipAddress: String, deviceId: String) = withContext(Dispatchers.IO) {
        val encodedSsid = URLEncoder.encode(ssid, "UTF-8")
        val encodedPassword = URLEncoder.encode(password, "UTF-8")
        val url = "http://$ipAddress/wifi?ssid=$encodedSsid&password=$encodedPassword"
        Log.d(TAG, "POST /wifi")

        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .addAuthHeader(deviceId)
            .build()

        val response = client.newCall(request).execute()
        checkAuthResponse(response, deviceId)
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }
        Log.d(TAG, "WiFi credentials set successfully")
    }

    /**
     * Set device orientation (PROTECTED)
     */
    suspend fun setOrientation(orientation: DeviceOrientation, ipAddress: String, deviceId: String) = withContext(Dispatchers.IO) {
        val url = "http://$ipAddress/orientation?orientation=${orientation.value}"
        Log.d(TAG, "POST /orientation: ${orientation.value}")

        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .addAuthHeader(deviceId)
            .build()

        val response = client.newCall(request).execute()
        checkAuthResponse(response, deviceId)
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }
        Log.d(TAG, "Orientation set successfully")
    }

    /**
     * Set servo speed (PROTECTED)
     */
    suspend fun setSpeed(speed: Int, ipAddress: String, deviceId: String) = withContext(Dispatchers.IO) {
        val url = "http://$ipAddress/speed"
        Log.d(TAG, "POST /speed: $speed")

        val request = Request.Builder()
            .url(url)
            .post("value=$speed".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .addAuthHeader(deviceId)
            .build()

        val response = client.newCall(request).execute()
        checkAuthResponse(response, deviceId)
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }
        Log.d(TAG, "Speed set successfully")
    }

    // MARK: - Device Status

    /**
     * Get device status
     */
    suspend fun getStatus(ipAddress: String): DeviceStatus = withContext(Dispatchers.IO) {
        val url = "http://$ipAddress${Constants.HTTP.STATUS_ENDPOINT}"
        Log.d(TAG, "GET $url")

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }

        val body = response.body?.string() ?: throw HttpException(0, "Empty response")
        val status = gson.fromJson(body, DeviceStatus::class.java)
        Log.d(TAG, "Status received: $status")
        status
    }

    /**
     * Get device info
     */
    suspend fun getInfo(ipAddress: String): DeviceInfo = withContext(Dispatchers.IO) {
        val url = "http://$ipAddress${Constants.HTTP.INFO_ENDPOINT}"
        Log.d(TAG, "GET $url")

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }

        val body = response.body?.string() ?: throw HttpException(0, "Empty response")
        val info = gson.fromJson(body, DeviceInfo::class.java)
        Log.d(TAG, "Info received: $info")
        info
    }

    /**
     * Check if device is reachable
     */
    suspend fun checkDevice(ipAddress: String): Boolean = withContext(Dispatchers.IO) {
        try {
            getInfo(ipAddress)
            true
        } catch (e: Exception) {
            false
        }
    }

    // MARK: - Calibration

    /**
     * Start calibration (PROTECTED)
     */
    suspend fun startCalibration(ipAddress: String, deviceId: String) = withContext(Dispatchers.IO) {
        val url = "http://$ipAddress/calibrate/start"
        Log.d(TAG, "POST /calibrate/start")

        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .addAuthHeader(deviceId)
            .build()

        val response = client.newCall(request).execute()
        checkAuthResponse(response, deviceId)
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }
        Log.d(TAG, "Calibration started")
    }

    /**
     * Set bottom position during calibration (PROTECTED)
     */
    suspend fun setBottomPosition(ipAddress: String, deviceId: String) = withContext(Dispatchers.IO) {
        val url = "http://$ipAddress/calibrate/setbottom"
        Log.d(TAG, "POST /calibrate/setbottom")

        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .addAuthHeader(deviceId)
            .build()

        val response = client.newCall(request).execute()
        checkAuthResponse(response, deviceId)
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }
        Log.d(TAG, "Bottom position set")
    }

    /**
     * Cancel calibration (PROTECTED)
     */
    suspend fun cancelCalibration(ipAddress: String, deviceId: String) = withContext(Dispatchers.IO) {
        val url = "http://$ipAddress/calibrate/cancel"
        Log.d(TAG, "POST /calibrate/cancel")

        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .addAuthHeader(deviceId)
            .build()

        val response = client.newCall(request).execute()
        checkAuthResponse(response, deviceId)
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }
        Log.d(TAG, "Calibration cancelled")
    }

    /**
     * Get calibration status (PROTECTED)
     */
    suspend fun getCalibrationStatus(ipAddress: String, deviceId: String): CalibrationStatusResponse = withContext(Dispatchers.IO) {
        val url = "http://$ipAddress/calibrate/status"
        Log.d(TAG, "GET /calibrate/status")

        val request = Request.Builder()
            .url(url)
            .get()
            .addAuthHeader(deviceId)
            .build()

        val response = client.newCall(request).execute()
        checkAuthResponse(response, deviceId)
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }

        val body = response.body?.string() ?: throw HttpException(0, "Empty response")
        val status = gson.fromJson(body, CalibrationStatusResponse::class.java)
        Log.d(TAG, "Calibration status: $status")
        status
    }

    /**
     * Force open (bypasses calibration limits) (PROTECTED)
     */
    suspend fun openForce(ipAddress: String, deviceId: String) = withContext(Dispatchers.IO) {
        val url = "http://$ipAddress/open/force"
        Log.d(TAG, "POST /open/force")

        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .addAuthHeader(deviceId)
            .build()

        val response = client.newCall(request).execute()
        checkAuthResponse(response, deviceId)
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }
        Log.d(TAG, "Force open sent")
    }

    /**
     * Force close (bypasses calibration limits) (PROTECTED)
     */
    suspend fun closeForce(ipAddress: String, deviceId: String) = withContext(Dispatchers.IO) {
        val url = "http://$ipAddress/close/force"
        Log.d(TAG, "POST /close/force")

        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .addAuthHeader(deviceId)
            .build()

        val response = client.newCall(request).execute()
        checkAuthResponse(response, deviceId)
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }
        Log.d(TAG, "Force close sent")
    }

    // MARK: - Firmware Update (Chunked Protocol)

    /**
     * Upload firmware using chunked protocol for memory-constrained devices (PROTECTED)
     */
    suspend fun uploadFirmware(
        firmwareData: ByteArray,
        ipAddress: String,
        deviceId: String,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val chunkSize = 8192 // 8KB chunks
        val totalSize = firmwareData.size
        val totalChunks = (totalSize + chunkSize - 1) / chunkSize

        Log.d(TAG, "[OTA] Starting chunked upload: $totalSize bytes in $totalChunks chunks")

        // Step 1: Initialize OTA
        val beginUrl = "http://$ipAddress/ota/begin?size=$totalSize"
        val beginRequest = Request.Builder()
            .url(beginUrl)
            .post("".toRequestBody(null))
            .addAuthHeader(deviceId)
            .build()

        val beginClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val beginResponse = beginClient.newCall(beginRequest).execute()
        checkAuthResponse(beginResponse, deviceId)
        if (!beginResponse.isSuccessful) {
            throw HttpException(beginResponse.code, beginResponse.body?.string() ?: "Begin failed")
        }

        Log.d(TAG, "[OTA] Begin successful, sending chunks...")

        // Step 2: Send chunks
        val chunkClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        for (chunkIndex in 0 until totalChunks) {
            val startOffset = chunkIndex * chunkSize
            val endOffset = minOf(startOffset + chunkSize, totalSize)
            val chunkData = firmwareData.copyOfRange(startOffset, endOffset)

            // Calculate CRC32 for this chunk
            val crc = CRC32.calculate(chunkData)
            val crcHex = CRC32.toHexString(crc)

            val chunkUrl = "http://$ipAddress/ota/chunk?index=$chunkIndex&crc=$crcHex"
            val chunkRequest = Request.Builder()
                .url(chunkUrl)
                .post(chunkData.toRequestBody("application/octet-stream".toMediaType()))
                .addHeader("Content-Length", chunkData.size.toString())
                .addAuthHeader(deviceId)
                .build()

            val chunkResponse = chunkClient.newCall(chunkRequest).execute()
            checkAuthResponse(chunkResponse, deviceId)
            if (!chunkResponse.isSuccessful) {
                // Abort OTA on failure
                try { abortOTA(ipAddress, deviceId) } catch (_: Exception) {}
                throw HttpException(chunkResponse.code, "Chunk $chunkIndex failed: ${chunkResponse.body?.string()}")
            }

            // Update progress
            val progress = endOffset.toFloat() / totalSize.toFloat()
            withContext(Dispatchers.Main) {
                onProgress(progress)
            }

            // Log every 10 chunks
            if (chunkIndex % 10 == 0 || chunkIndex == totalChunks - 1) {
                Log.d(TAG, "[OTA] Chunk ${chunkIndex + 1}/$totalChunks sent (${(progress * 100).toInt()}%)")
            }
        }

        // Step 3: Finalize OTA
        Log.d(TAG, "[OTA] All chunks sent, finalizing...")

        val endUrl = "http://$ipAddress/ota/end"
        val endRequest = Request.Builder()
            .url(endUrl)
            .post("".toRequestBody(null))
            .addAuthHeader(deviceId)
            .build()

        val endResponse = chunkClient.newCall(endRequest).execute()
        checkAuthResponse(endResponse, deviceId)
        if (!endResponse.isSuccessful) {
            throw HttpException(endResponse.code, "Finalize failed: ${endResponse.body?.string()}")
        }

        Log.d(TAG, "[OTA] Firmware upload complete, device restarting...")
    }

    /**
     * Abort an in-progress OTA update (PROTECTED)
     */
    suspend fun abortOTA(ipAddress: String, deviceId: String) = withContext(Dispatchers.IO) {
        try {
            val url = "http://$ipAddress/ota/abort"
            val request = Request.Builder()
                .url(url)
                .post("".toRequestBody(null))
                .addAuthHeader(deviceId)
                .build()

            client.newCall(request).execute()
            Log.d(TAG, "[OTA] Aborted")
        } catch (e: Exception) {
            Log.w(TAG, "[OTA] Abort failed: ${e.message}")
        }
    }

    // MARK: - Device Logs

    /**
     * Get device logs (PROTECTED)
     */
    suspend fun getLogs(ipAddress: String, deviceId: String): List<String> = withContext(Dispatchers.IO) {
        val url = "http://$ipAddress/logs"
        Log.d(TAG, "GET /logs")

        val request = Request.Builder()
            .url(url)
            .get()
            .addAuthHeader(deviceId)
            .build()

        val response = client.newCall(request).execute()
        checkAuthResponse(response, deviceId)
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }

        val body = response.body?.string() ?: throw HttpException(0, "Empty response")
        val logsResponse = gson.fromJson(body, DeviceLogsResponse::class.java)
        Log.d(TAG, "Received ${logsResponse.logs.size} log entries")
        logsResponse.logs
    }

    /**
     * Clear device logs (PROTECTED)
     */
    suspend fun clearLogs(ipAddress: String, deviceId: String) = withContext(Dispatchers.IO) {
        val url = "http://$ipAddress/logs"
        Log.d(TAG, "DELETE /logs")

        val request = Request.Builder()
            .url(url)
            .delete()
            .addAuthHeader(deviceId)
            .build()

        val response = client.newCall(request).execute()
        checkAuthResponse(response, deviceId)
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }
        Log.d(TAG, "Logs cleared")
    }

    // MARK: - Factory Reset

    /**
     * Factory reset device (PROTECTED)
     */
    suspend fun factoryReset(ipAddress: String, deviceId: String) = withContext(Dispatchers.IO) {
        val url = "http://$ipAddress/factory-reset"
        Log.d(TAG, "POST /factory-reset")

        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .addAuthHeader(deviceId)
            .build()

        val longTimeoutClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val response = longTimeoutClient.newCall(request).execute()
        checkAuthResponse(response, deviceId)
        if (!response.isSuccessful) {
            throw HttpException(response.code, response.body?.string() ?: "Unknown error")
        }
        Log.d(TAG, "Factory reset successful, device restarting...")
    }

    // MARK: - Authentication

    /**
     * Test authentication by calling a protected endpoint
     */
    suspend fun testAuthentication(ipAddress: String, deviceId: String, password: String): Boolean = withContext(Dispatchers.IO) {
        val url = "http://$ipAddress/logs"
        Log.d(TAG, "Testing authentication for device: $deviceId")

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("X-Device-Password", password)
            .build()

        try {
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            Log.d(TAG, "Authentication test result: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Authentication test failed: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "HttpClient"
    }
}

/**
 * HTTP exception with status code and message
 */
class HttpException(val code: Int, message: String) : IOException("HTTP $code: $message")

/**
 * Exception thrown when authentication is required (401 response)
 */
class AuthenticationRequiredException(val deviceId: String) : IOException("Authentication required for device: $deviceId")

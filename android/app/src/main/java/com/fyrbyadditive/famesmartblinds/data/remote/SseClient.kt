package com.fyrbyadditive.famesmartblinds.data.remote

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSE endpoint types
 */
enum class SseEndpoint(val path: String) {
    STATUS("/events"),       // For device status updates
    LOGS("/events/logs")     // For log streaming
}

/**
 * Server-Sent Events client for real-time device updates.
 * Connects to the device's /events or /events/logs endpoint.
 */
@Singleton
class SseClient @Inject constructor() {

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var eventSource: EventSource? = null
    private var currentIpAddress: String? = null
    private var currentEndpoint: SseEndpoint = SseEndpoint.STATUS
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = false

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Client configured for SSE - no timeouts on read
    private val sseClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)  // No timeout for SSE
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Callback for status updates
    var onStatusUpdate: ((DeviceStatus) -> Unit)? = null

    // Callback for log entries
    var onLogReceived: ((String) -> Unit)? = null

    /**
     * Connect to a device's SSE endpoint
     * @param ipAddress The device IP address
     * @param endpoint Which SSE endpoint to connect to (STATUS for device control, LOGS for log streaming)
     */
    fun connect(ipAddress: String, endpoint: SseEndpoint = SseEndpoint.STATUS) {
        disconnect()

        currentIpAddress = ipAddress
        currentEndpoint = endpoint
        shouldReconnect = true
        reconnectAttempts = 0

        startConnection()
    }

    /**
     * Disconnect from the SSE endpoint
     */
    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        eventSource?.cancel()
        eventSource = null
        currentIpAddress = null
        _isConnected.value = false
    }

    private fun startConnection() {
        val ipAddress = currentIpAddress ?: return
        if (!shouldReconnect) return

        val url = "http://$ipAddress${currentEndpoint.path}"
        Log.d(TAG, "Connecting to $url")

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        val factory = EventSources.createFactory(sseClient)

        eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.d(TAG, "SSE connection opened")
                _isConnected.value = true
                reconnectAttempts = 0
                _lastError.value = null
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                when (type) {
                    "open" -> {
                        Log.d(TAG, "SSE open event received")
                    }
                    "status" -> {
                        parseAndDeliverStatus(data)
                    }
                    "log" -> {
                        onLogReceived?.invoke(data)
                    }
                    else -> {
                        Log.d(TAG, "SSE unknown event type: $type, data: $data")
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Log.d(TAG, "SSE connection closed")
                _isConnected.value = false
                scheduleReconnect()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (t?.message?.contains("canceled", ignoreCase = true) == true) {
                    Log.d(TAG, "SSE connection cancelled")
                } else {
                    Log.e(TAG, "SSE connection error: ${t?.message}")
                    _lastError.value = t?.message ?: "Connection failed"
                }
                _isConnected.value = false
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnection attempts reached")
            return
        }

        reconnectAttempts++
        val delay = minOf(1L shl reconnectAttempts, 30L) // Exponential backoff, max 30s
        Log.d(TAG, "Reconnecting in ${delay}s (attempt $reconnectAttempts)")

        reconnectJob = scope.launch {
            delay(delay * 1000)
            if (isActive && shouldReconnect) {
                startConnection()
            }
        }
    }

    private fun parseAndDeliverStatus(jsonString: String) {
        try {
            val status = gson.fromJson(jsonString, DeviceStatus::class.java)
            Log.d(TAG, "SSE received status: state=${status.state}, position=${status.calibration?.cumulativePosition ?: -1}")
            onStatusUpdate?.invoke(status)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse status: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SseClient"
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }
}

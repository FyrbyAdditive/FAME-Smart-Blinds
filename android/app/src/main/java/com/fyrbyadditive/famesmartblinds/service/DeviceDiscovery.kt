package com.fyrbyadditive.famesmartblinds.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.fyrbyadditive.famesmartblinds.data.remote.HttpClient
import com.fyrbyadditive.famesmartblinds.data.repository.DeviceRepository
import com.fyrbyadditive.famesmartblinds.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles mDNS/NSD discovery for WiFi-configured FAME Smart Blinds devices.
 *
 * Supports two modes:
 * - Continuous discovery: Runs in background while app is active, automatically detecting devices
 * - Manual refresh: Immediately re-verifies all known devices when user taps refresh
 *
 * BLE scanning is handled separately by BleManager (only used for unconfigured devices).
 */
@Singleton
class DeviceDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceRepository: DeviceRepository,
    private val httpClient: HttpClient
) {
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _isContinuousDiscoveryActive = MutableStateFlow(false)
    val isContinuousDiscoveryActive: StateFlow<Boolean> = _isContinuousDiscoveryActive.asStateFlow()

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var periodicDiscoveryJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val resolveQueue = mutableListOf<NsdServiceInfo>()
    private var isResolving = false

    init {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    // MARK: - Continuous Discovery

    /**
     * Start continuous mDNS discovery. Called when app becomes active.
     */
    fun startContinuousDiscovery() {
        if (_isContinuousDiscoveryActive.value) {
            Log.d(TAG, "Continuous discovery already active")
            return
        }

        Log.i(TAG, "=== Starting continuous mDNS discovery ===")
        Log.i(TAG, "NSD Manager available: ${nsdManager != null}")
        _isContinuousDiscoveryActive.value = true

        startNsdDiscovery()
        startPeriodicDiscovery()
    }

    /**
     * Stop continuous discovery. Called when app goes to background.
     */
    fun stopContinuousDiscovery() {
        Log.d(TAG, "Stopping continuous discovery")
        _isContinuousDiscoveryActive.value = false
        _isSearching.value = false

        stopNsdDiscovery()
        periodicDiscoveryJob?.cancel()
        periodicDiscoveryJob = null
        resolveQueue.clear()
    }

    // MARK: - NSD Discovery

    private fun startNsdDiscovery() {
        stopNsdDiscovery() // Stop any existing discovery

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "NSD discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Found service: ${serviceInfo.serviceName}")
                // Queue for resolution
                synchronized(resolveQueue) {
                    resolveQueue.add(serviceInfo)
                }
                processResolveQueue()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD discovery start failed: $errorCode")
                _isSearching.value = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD discovery stop failed: $errorCode")
            }
        }

        try {
            nsdManager?.discoverServices(
                Constants.Discovery.HTTP_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
            Log.d(TAG, "NSD discovery initiated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD discovery: ${e.message}")
        }
    }

    private fun stopNsdDiscovery() {
        discoveryListener?.let { listener ->
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping NSD discovery: ${e.message}")
            }
        }
        discoveryListener = null
    }

    private fun processResolveQueue() {
        synchronized(resolveQueue) {
            if (isResolving || resolveQueue.isEmpty()) return
            isResolving = true
        }

        val serviceInfo: NsdServiceInfo
        synchronized(resolveQueue) {
            serviceInfo = resolveQueue.removeAt(0)
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                synchronized(resolveQueue) {
                    isResolving = false
                }
                processResolveQueue()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host
                if (host != null) {
                    val ipAddress = host.hostAddress
                    if (ipAddress != null && ipAddress != "0.0.0.0") {
                        Log.d(TAG, "Resolved ${serviceInfo.serviceName} to $ipAddress")
                        scope.launch {
                            checkDeviceAt(ipAddress)
                        }
                    }
                }
                synchronized(resolveQueue) {
                    isResolving = false
                }
                processResolveQueue()
            }
        }

        try {
            nsdManager?.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving service: ${e.message}")
            synchronized(resolveQueue) {
                isResolving = false
            }
            processResolveQueue()
        }
    }

    // MARK: - Periodic Discovery

    private fun startPeriodicDiscovery() {
        periodicDiscoveryJob?.cancel()
        periodicDiscoveryJob = scope.launch {
            // Initial delay, then restart discovery periodically
            delay(2000)

            while (isActive) {
                Log.d(TAG, "Running periodic discovery cycle")

                // Restart NSD to trigger fresh queries
                withContext(Dispatchers.Main) {
                    startNsdDiscovery()
                }

                // Also verify known devices directly via HTTP
                verifyAllKnownDevices()

                // Wait before next cycle
                delay(Constants.Timeout.DISCOVERY_INTERVAL)
            }
        }
    }

    private suspend fun verifyAllKnownDevices() {
        val deviceInfos = deviceRepository.getConfiguredDevices()
            .mapNotNull { device -> device.ipAddress?.let { ip -> ip to device.deviceId } }

        if (deviceInfos.isEmpty()) return

        Log.d(TAG, "Verifying ${deviceInfos.size} known devices")

        coroutineScope {
            deviceInfos.forEach { (ip, deviceId) ->
                launch {
                    verifyDevice(ip, deviceId)
                }
            }
        }
    }

    private suspend fun verifyDevice(ipAddress: String, expectedDeviceId: String) {
        try {
            val info = httpClient.getInfo(ipAddress)

            if (info.device != "FAMESmartBlinds") return

            deviceRepository.updateFromHttp(
                name = info.hostname,
                ipAddress = ipAddress,
                deviceId = info.deviceId,
                macAddress = info.mac
            )
        } catch (e: Exception) {
            Log.d(TAG, "Device at $ipAddress unreachable: ${e.message}")
        }
    }

    // MARK: - Manual Discovery

    /**
     * Trigger a manual refresh - immediately re-verifies all known devices.
     */
    suspend fun triggerManualRefresh() {
        Log.d(TAG, "Manual refresh triggered")
        _isSearching.value = true

        // Re-verify all known WiFi devices
        verifyAllKnownDevices()

        // Restart NSD to catch any new announcements
        if (_isContinuousDiscoveryActive.value) {
            withContext(Dispatchers.Main) {
                stopNsdDiscovery()
                startNsdDiscovery()
            }
        }

        _isSearching.value = false
    }

    /**
     * Check if a specific IP address has a FAME Smart Blinds device
     */
    suspend fun checkDeviceAt(ipAddress: String) {
        Log.i(TAG, ">>> Checking device at $ipAddress")

        try {
            val info = httpClient.getInfo(ipAddress)
            Log.i(TAG, "Got info response: device=${info.device}, hostname=${info.hostname}")

            // Verify this is a FAME Smart Blinds device
            if (info.device != "FAMESmartBlinds") {
                Log.w(TAG, "Device at $ipAddress is not a FAME Smart Blinds device: ${info.device}")
                return
            }

            deviceRepository.updateFromHttp(
                name = info.hostname,
                ipAddress = ipAddress,
                deviceId = info.deviceId,
                macAddress = info.mac
            )

            Log.i(TAG, "<<< Found device: ${info.hostname} at $ipAddress (deviceId: ${info.deviceId})")
        } catch (e: Exception) {
            Log.e(TAG, "!!! Failed to check device at $ipAddress: ${e.message}", e)
        }
    }

    /**
     * Schedule discovery restart after a delay.
     * Used after device setup completes to find the newly configured device.
     */
    fun triggerDelayedDiscovery(delaySeconds: Int) {
        Log.d(TAG, "Scheduling mDNS discovery restart in $delaySeconds seconds")
        scope.launch {
            delay(delaySeconds * 1000L)
            Log.d(TAG, "Running post-setup mDNS discovery")
            withContext(Dispatchers.Main) {
                startNsdDiscovery()
            }
        }
    }

    companion object {
        private const val TAG = "DeviceDiscovery"
    }
}

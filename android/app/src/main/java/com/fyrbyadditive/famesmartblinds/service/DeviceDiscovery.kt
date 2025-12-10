package com.fyrbyadditive.famesmartblinds.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.fyrbyadditive.famesmartblinds.data.remote.HttpClient
import com.fyrbyadditive.famesmartblinds.data.repository.DeviceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bulletproof mDNS/NSD discovery for WiFi-configured FAME Smart Blinds devices.
 *
 * Android's NsdManager is notoriously unreliable compared to iOS's NetServiceBrowser.
 * This implementation uses multiple strategies to ensure reliable discovery:
 *
 * 1. Aggressive NSD cycling - restarts discovery frequently to catch services
 * 2. Sequential service resolution with proper queue management
 * 3. Direct HTTP verification of known devices as fallback
 * 4. Proper listener lifecycle management to avoid stuck states
 *
 * The service type is _famesmartblinds._tcp. which the firmware advertises.
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
    private var currentDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveryJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Resolution queue management
    private val resolveQueue = mutableListOf<NsdServiceInfo>()
    private val resolveMutex = Mutex()
    private val isResolving = AtomicBoolean(false)
    private val discoveryGeneration = AtomicInteger(0)

    // Track discovered IPs to avoid duplicate checks
    private val discoveredIps = mutableSetOf<String>()
    private val discoveredIpsMutex = Mutex()

    companion object {
        private const val TAG = "DeviceDiscovery"
        private const val SERVICE_TYPE = "_famesmartblinds._tcp."

        // Discovery timing - aggressive cycling for reliability
        private const val QUICK_RESTART_DELAY_MS = 3000L      // Quick restart after 3s to catch already-online devices
        private const val DISCOVERY_CYCLE_INTERVAL_MS = 15000L // Time between regular discovery cycles
        private const val NSD_DISCOVERY_DURATION_MS = 12000L   // How long each NSD session runs before restart
    }

    init {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        Log.i(TAG, "DeviceDiscovery initialized, NsdManager available: ${nsdManager != null}")
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

        Log.i(TAG, "╔══════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  Starting Continuous mDNS Discovery                      ║")
        Log.i(TAG, "║  Service Type: $SERVICE_TYPE                    ║")
        Log.i(TAG, "╚══════════════════════════════════════════════════════════╝")

        _isContinuousDiscoveryActive.value = true
        discoveryGeneration.incrementAndGet()

        // Start NSD immediately - don't wait for the discovery loop
        // Android NSD often misses services on first query, so we start
        // right away and the loop will restart it to catch stragglers
        startNsdDiscovery()

        startDiscoveryLoop()
    }

    /**
     * Stop continuous discovery. Called when app goes to background.
     */
    fun stopContinuousDiscovery() {
        Log.i(TAG, "Stopping continuous discovery")
        _isContinuousDiscoveryActive.value = false
        _isSearching.value = false

        discoveryJob?.cancel()
        discoveryJob = null
        stopNsdDiscoverySafely()

        scope.launch {
            resolveMutex.withLock {
                resolveQueue.clear()
            }
            discoveredIpsMutex.withLock {
                discoveredIps.clear()
            }
        }
    }

    // MARK: - Discovery Loop

    private fun startDiscoveryLoop() {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            val currentGeneration = discoveryGeneration.get()

            // Quick restart after 3 seconds to catch services missed on first query
            // Android NSD is notorious for missing already-advertising services
            delay(QUICK_RESTART_DELAY_MS)

            if (!isActive || !_isContinuousDiscoveryActive.value ||
                currentGeneration != discoveryGeneration.get()) return@launch

            Log.i(TAG, "┌─────────────────────────────────────────────────┐")
            Log.i(TAG, "│  Quick Restart (catch already-online devices)   │")
            Log.i(TAG, "└─────────────────────────────────────────────────┘")

            withContext(Dispatchers.Main) {
                stopNsdDiscoverySafely()
                startNsdDiscovery()
            }

            // Now enter the regular discovery loop
            while (isActive && _isContinuousDiscoveryActive.value &&
                   currentGeneration == discoveryGeneration.get()) {

                // Wait for discovery duration before cycling
                delay(NSD_DISCOVERY_DURATION_MS)

                if (!isActive || !_isContinuousDiscoveryActive.value) break

                Log.i(TAG, "┌─────────────────────────────────────────────────┐")
                Log.i(TAG, "│  Discovery Cycle                                │")
                Log.i(TAG, "└─────────────────────────────────────────────────┘")

                // Clear discovered IPs for this cycle
                discoveredIpsMutex.withLock {
                    discoveredIps.clear()
                }

                // Restart NSD to catch new services
                withContext(Dispatchers.Main) {
                    stopNsdDiscoverySafely()
                    startNsdDiscovery()
                }

                // Also verify known devices directly via HTTP
                verifyAllKnownDevices()

                // Wait before next cycle
                delay(DISCOVERY_CYCLE_INTERVAL_MS)
            }
        }
    }

    // MARK: - NSD Discovery

    private fun startNsdDiscovery() {
        val manager = nsdManager ?: run {
            Log.e(TAG, "NsdManager is null, cannot start discovery")
            return
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "✓ NSD discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "★ Found service: ${serviceInfo.serviceName} (type: ${serviceInfo.serviceType})")

                // Queue for resolution
                scope.launch {
                    resolveMutex.withLock {
                        // Avoid duplicates
                        if (resolveQueue.none { it.serviceName == serviceInfo.serviceName }) {
                            resolveQueue.add(serviceInfo)
                            Log.d(TAG, "  Queued for resolution (queue size: ${resolveQueue.size})")
                        }
                    }
                    processResolveQueue()
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "✗ NSD discovery start FAILED: errorCode=$errorCode")
                _isSearching.value = false

                // Retry after a short delay
                scope.launch {
                    delay(2000)
                    if (_isContinuousDiscoveryActive.value) {
                        withContext(Dispatchers.Main) {
                            startNsdDiscovery()
                        }
                    }
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD discovery stop failed: errorCode=$errorCode")
            }
        }

        currentDiscoveryListener = listener

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            _isSearching.value = true
            Log.d(TAG, "NSD discoverServices() called for $SERVICE_TYPE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD discovery: ${e.message}", e)
            currentDiscoveryListener = null
        }
    }

    private fun stopNsdDiscoverySafely() {
        val listener = currentDiscoveryListener ?: return
        currentDiscoveryListener = null

        try {
            nsdManager?.stopServiceDiscovery(listener)
            Log.d(TAG, "NSD discovery stopped")
        } catch (e: IllegalArgumentException) {
            // Listener was not registered - this is fine
            Log.d(TAG, "NSD listener was not registered (already stopped)")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping NSD discovery: ${e.message}")
        }
    }

    // MARK: - Service Resolution

    private fun processResolveQueue() {
        if (!isResolving.compareAndSet(false, true)) {
            return // Already resolving
        }

        scope.launch {
            try {
                while (_isContinuousDiscoveryActive.value) {
                    val serviceInfo = resolveMutex.withLock {
                        resolveQueue.removeFirstOrNull()
                    } ?: break

                    resolveService(serviceInfo)
                }
            } finally {
                isResolving.set(false)
            }
        }
    }

    private suspend fun resolveService(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: return

        Log.d(TAG, "Resolving service: ${serviceInfo.serviceName}")

        val result = suspendCancellableCoroutine<String?> { continuation ->
            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "  ✗ Resolve failed for ${service.serviceName}: errorCode=$errorCode")
                    if (continuation.isActive) {
                        continuation.resume(null) {}
                    }
                }

                override fun onServiceResolved(service: NsdServiceInfo) {
                    val host = service.host
                    val ipAddress = host?.hostAddress

                    if (ipAddress != null && ipAddress != "0.0.0.0") {
                        Log.i(TAG, "  ✓ Resolved ${service.serviceName} → $ipAddress")
                        if (continuation.isActive) {
                            continuation.resume(ipAddress) {}
                        }
                    } else {
                        Log.w(TAG, "  ✗ Resolved ${service.serviceName} but got invalid IP: $ipAddress")
                        if (continuation.isActive) {
                            continuation.resume(null) {}
                        }
                    }
                }
            }

            try {
                manager.resolveService(serviceInfo, resolveListener)
            } catch (e: Exception) {
                Log.w(TAG, "  ✗ Exception resolving ${serviceInfo.serviceName}: ${e.message}")
                if (continuation.isActive) {
                    continuation.resume(null) {}
                }
            }
        }

        // Check device at resolved IP
        if (result != null) {
            val shouldCheck = discoveredIpsMutex.withLock {
                if (result in discoveredIps) {
                    false
                } else {
                    discoveredIps.add(result)
                    true
                }
            }

            if (shouldCheck) {
                checkDeviceAt(result)
            }
        }

        // Small delay between resolutions to avoid overwhelming NsdManager
        delay(100)
    }

    // MARK: - HTTP Verification

    private suspend fun verifyAllKnownDevices() {
        val deviceInfos = deviceRepository.getConfiguredDevices()
            .mapNotNull { device -> device.ipAddress?.let { ip -> ip to device.deviceId } }

        if (deviceInfos.isEmpty()) {
            Log.d(TAG, "No known devices to verify")
            return
        }

        Log.i(TAG, "Verifying ${deviceInfos.size} known device(s) via HTTP")

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

            if (info.device != "FAMESmartBlinds") {
                Log.d(TAG, "Device at $ipAddress is not a FAME device: ${info.device}")
                return
            }

            deviceRepository.updateFromHttp(
                name = info.hostname,
                ipAddress = ipAddress,
                deviceId = info.deviceId,
                macAddress = info.mac
            )
            Log.d(TAG, "  ✓ Verified device at $ipAddress: ${info.hostname}")
        } catch (e: Exception) {
            Log.d(TAG, "  ✗ Device at $ipAddress unreachable: ${e.message}")
        }
    }

    // MARK: - Manual Discovery

    /**
     * Trigger a manual refresh - re-verifies known devices and restarts mDNS.
     */
    suspend fun triggerManualRefresh() {
        Log.i(TAG, "Manual refresh triggered")
        _isSearching.value = true

        try {
            // Clear discovered IPs to allow re-checking
            discoveredIpsMutex.withLock {
                discoveredIps.clear()
            }

            // Re-verify all known WiFi devices
            verifyAllKnownDevices()

            // Restart NSD discovery
            if (_isContinuousDiscoveryActive.value) {
                withContext(Dispatchers.Main) {
                    stopNsdDiscoverySafely()
                    startNsdDiscovery()
                }
            }
        } finally {
            _isSearching.value = false
        }
    }

    /**
     * Check if a specific IP address has a FAME Smart Blinds device
     */
    suspend fun checkDeviceAt(ipAddress: String) {
        Log.i(TAG, ">>> Checking device at $ipAddress")

        try {
            val info = httpClient.getInfo(ipAddress)
            Log.i(TAG, "    Got info: device=${info.device}, hostname=${info.hostname}, deviceId=${info.deviceId}")

            // Verify this is a FAME Smart Blinds device
            if (info.device != "FAMESmartBlinds") {
                Log.w(TAG, "<<< Device at $ipAddress is NOT a FAME device: ${info.device}")
                return
            }

            deviceRepository.updateFromHttp(
                name = info.hostname,
                ipAddress = ipAddress,
                deviceId = info.deviceId,
                macAddress = info.mac
            )

            Log.i(TAG, "<<< ✓ Found FAME device: ${info.hostname} at $ipAddress (deviceId: ${info.deviceId})")
        } catch (e: Exception) {
            Log.e(TAG, "<<< ✗ Failed to check device at $ipAddress: ${e.message}")
        }
    }

    /**
     * Schedule discovery restart after a delay.
     * Used after device setup completes to find the newly configured device.
     */
    fun triggerDelayedDiscovery(delaySeconds: Int) {
        Log.d(TAG, "Scheduling discovery restart in $delaySeconds seconds")
        scope.launch {
            delay(delaySeconds * 1000L)
            Log.d(TAG, "Running post-setup discovery")

            // Clear IPs and restart
            discoveredIpsMutex.withLock {
                discoveredIps.clear()
            }

            withContext(Dispatchers.Main) {
                stopNsdDiscoverySafely()
                startNsdDiscovery()
            }
        }
    }
}

package com.fyrbyadditive.famesmartblinds

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.fyrbyadditive.famesmartblinds.ui.navigation.FAMESmartBlindsNavHost
import com.fyrbyadditive.famesmartblinds.ui.theme.FAMESmartBlindsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var multicastLock: WifiManager.MulticastLock? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { (permission, granted) ->
            Log.d("MainActivity", "Permission $permission: ${if (granted) "granted" else "denied"}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request necessary permissions
        requestPermissions()

        // Acquire multicast lock for mDNS discovery
        acquireMulticastLock()

        setContent {
            FAMESmartBlindsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FAMESmartBlindsNavHost()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMulticastLock()
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: Only need Bluetooth permissions, no location required
            // (BLUETOOTH_SCAN has neverForLocation flag in manifest)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            // Android < 12: Location permission required for BLE scanning
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d("MainActivity", "Requesting permissions: $permissionsToRequest")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("FAMESmartBlinds_mDNS")
            multicastLock?.setReferenceCounted(true)
            multicastLock?.acquire()
            Log.d("MainActivity", "Multicast lock acquired for mDNS discovery")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to acquire multicast lock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d("MainActivity", "Multicast lock released")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to release multicast lock: ${e.message}")
        }
    }
}

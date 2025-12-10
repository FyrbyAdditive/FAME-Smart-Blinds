package com.fyrbyadditive.famesmartblinds.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session expiry duration options
 */
enum class SessionExpiry(val displayName: String, val milliseconds: Long?) {
    HOUR("1 Hour", 3600_000L),
    DAY("1 Day", 86400_000L),
    WEEK("1 Week", 604800_000L),
    MONTH("1 Month", 2592000_000L),
    YEAR("1 Year", 31536000_000L),
    UNLIMITED("Unlimited", null)
}

/**
 * Represents an active authentication session for a device
 */
data class AuthSession(
    val deviceId: String,
    val authenticatedAt: Long,
    val expiresAt: Long?  // null means never expires
) {
    val isExpired: Boolean
        get() = expiresAt != null && System.currentTimeMillis() > expiresAt
}

/**
 * Manages authentication state for devices.
 * - Passwords are stored securely using EncryptedSharedPreferences
 * - Session metadata (expiry times) stored in regular SharedPreferences
 * - Provides a central point for checking/managing auth state
 */
@Singleton
class AuthenticationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureCredentialStorage
) {
    companion object {
        private const val TAG = "AuthenticationManager"
        private const val PREFS_NAME = "auth_sessions"
        private const val KEY_SESSIONS = "active_sessions"
        private const val KEY_DEFAULT_EXPIRY = "default_session_expiry"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _activeSessions = MutableStateFlow<Map<String, AuthSession>>(emptyMap())
    val activeSessions: StateFlow<Map<String, AuthSession>> = _activeSessions.asStateFlow()

    private val _deviceNeedingAuth = MutableStateFlow<String?>(null)
    val deviceNeedingAuth: StateFlow<String?> = _deviceNeedingAuth.asStateFlow()

    var defaultSessionExpiry: SessionExpiry = SessionExpiry.WEEK
        private set

    init {
        loadSessions()
        loadDefaultExpiry()
        cleanExpiredSessions()
    }

    /**
     * Check if we have a valid session for a device
     */
    fun hasValidSession(deviceId: String): Boolean {
        val session = _activeSessions.value[deviceId] ?: return false
        return !session.isExpired
    }

    /**
     * Get the password for a device if we have a valid session
     */
    fun getPassword(deviceId: String): String? {
        if (!hasValidSession(deviceId)) return null
        return secureStorage.getPassword(deviceId)
    }

    /**
     * Authenticate with a device - stores password and creates session
     */
    fun authenticate(deviceId: String, password: String, expiry: SessionExpiry = defaultSessionExpiry) {
        // Save password securely
        secureStorage.savePassword(deviceId, password)

        // Create session
        val expiresAt = expiry.milliseconds?.let { System.currentTimeMillis() + it }

        val session = AuthSession(
            deviceId = deviceId,
            authenticatedAt = System.currentTimeMillis(),
            expiresAt = expiresAt
        )

        val sessions = _activeSessions.value.toMutableMap()
        sessions[deviceId] = session
        _activeSessions.value = sessions
        saveSessions()

        Log.d(TAG, "Authenticated device: $deviceId, expires: ${expiresAt?.let { Date(it) } ?: "never"}")
    }

    /**
     * Clear authentication for a device (e.g., on 401 or user logout)
     */
    fun clearAuthentication(deviceId: String) {
        secureStorage.deletePassword(deviceId)
        val sessions = _activeSessions.value.toMutableMap()
        sessions.remove(deviceId)
        _activeSessions.value = sessions
        saveSessions()
        Log.d(TAG, "Cleared authentication for device: $deviceId")
    }

    /**
     * Handle a 401 response - clears session and signals need for re-auth
     */
    fun handleAuthenticationRequired(deviceId: String) {
        clearAuthentication(deviceId)
        _deviceNeedingAuth.value = deviceId
    }

    /**
     * Clear the pending auth request
     */
    fun clearAuthenticationRequest() {
        _deviceNeedingAuth.value = null
    }

    /**
     * Update the default session expiry preference
     */
    fun setDefaultSessionExpiry(expiry: SessionExpiry) {
        defaultSessionExpiry = expiry
        prefs.edit().putString(KEY_DEFAULT_EXPIRY, expiry.name).apply()
    }

    // MARK: - Persistence

    private fun loadSessions() {
        val json = prefs.getString(KEY_SESSIONS, null) ?: return
        try {
            val type = object : TypeToken<Map<String, AuthSession>>() {}.type
            val sessions: Map<String, AuthSession> = gson.fromJson(json, type)
            _activeSessions.value = sessions
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions", e)
        }
    }

    private fun saveSessions() {
        val json = gson.toJson(_activeSessions.value)
        prefs.edit().putString(KEY_SESSIONS, json).apply()
    }

    private fun loadDefaultExpiry() {
        val name = prefs.getString(KEY_DEFAULT_EXPIRY, null)
        if (name != null) {
            defaultSessionExpiry = try {
                SessionExpiry.valueOf(name)
            } catch (e: Exception) {
                SessionExpiry.WEEK
            }
        }
    }

    private fun cleanExpiredSessions() {
        val expiredDeviceIds = _activeSessions.value.filter { it.value.isExpired }.keys
        if (expiredDeviceIds.isEmpty()) return

        val sessions = _activeSessions.value.toMutableMap()
        for (deviceId in expiredDeviceIds) {
            secureStorage.deletePassword(deviceId)
            sessions.remove(deviceId)
        }
        _activeSessions.value = sessions
        saveSessions()
        Log.d(TAG, "Cleaned ${expiredDeviceIds.size} expired sessions")
    }
}

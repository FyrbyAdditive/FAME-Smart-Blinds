package com.fyrbyadditive.famesmartblinds.data.local

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for device passwords using EncryptedSharedPreferences.
 * Passwords are stored per device ID to support multiple devices with different passwords.
 */
@Singleton
class SecureCredentialStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SecureCredentialStorage"
        private const val PREFS_NAME = "secure_device_credentials"
        private const val KEY_PREFIX_PASSWORD = "password_"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Save a password for a device
     */
    fun savePassword(deviceId: String, password: String) {
        encryptedPrefs.edit().putString("$KEY_PREFIX_PASSWORD$deviceId", password).apply()
        Log.d(TAG, "Saved password for device: $deviceId")
    }

    /**
     * Get the password for a device
     */
    fun getPassword(deviceId: String): String? {
        return encryptedPrefs.getString("$KEY_PREFIX_PASSWORD$deviceId", null)
    }

    /**
     * Delete the password for a device
     */
    fun deletePassword(deviceId: String) {
        encryptedPrefs.edit().remove("$KEY_PREFIX_PASSWORD$deviceId").apply()
        Log.d(TAG, "Deleted password for device: $deviceId")
    }

    /**
     * Check if a password exists for a device
     */
    fun hasPassword(deviceId: String): Boolean {
        return encryptedPrefs.contains("$KEY_PREFIX_PASSWORD$deviceId")
    }

    /**
     * Delete all stored passwords
     */
    fun deleteAllPasswords() {
        encryptedPrefs.edit().clear().apply()
        Log.d(TAG, "Deleted all passwords")
    }
}

import Foundation
import Security

/// Manages secure storage of device passwords in the iOS Keychain.
/// Passwords are stored per device ID to support multiple devices with different passwords.
class KeychainManager {
    static let shared = KeychainManager()

    private let service = "com.fyrbyadditive.famesmartblinds"

    private init() {}

    // MARK: - Password Storage

    /// Save a password for a device
    func savePassword(_ password: String, forDeviceId deviceId: String) throws {
        guard let data = password.data(using: .utf8) else {
            throw KeychainError.encodingFailed
        }

        // Delete any existing item first
        try? deletePassword(forDeviceId: deviceId)

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: deviceId,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlocked
        ]

        let status = SecItemAdd(query as CFDictionary, nil)

        guard status == errSecSuccess else {
            throw KeychainError.saveFailed(status)
        }

        print("[Keychain] Saved password for device: \(deviceId)")
    }

    /// Get the password for a device
    func getPassword(forDeviceId deviceId: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: deviceId,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        guard status == errSecSuccess,
              let data = result as? Data,
              let password = String(data: data, encoding: .utf8) else {
            return nil
        }

        return password
    }

    /// Delete the password for a device
    func deletePassword(forDeviceId deviceId: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: deviceId
        ]

        let status = SecItemDelete(query as CFDictionary)

        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.deleteFailed(status)
        }

        print("[Keychain] Deleted password for device: \(deviceId)")
    }

    /// Check if a password exists for a device
    func hasPassword(forDeviceId deviceId: String) -> Bool {
        return getPassword(forDeviceId: deviceId) != nil
    }

    /// Delete all stored passwords
    func deleteAllPasswords() throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service
        ]

        let status = SecItemDelete(query as CFDictionary)

        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.deleteFailed(status)
        }

        print("[Keychain] Deleted all passwords")
    }
}

// MARK: - Errors

enum KeychainError: LocalizedError {
    case encodingFailed
    case saveFailed(OSStatus)
    case deleteFailed(OSStatus)

    var errorDescription: String? {
        switch self {
        case .encodingFailed:
            return "Failed to encode password"
        case .saveFailed(let status):
            return "Failed to save password (status: \(status))"
        case .deleteFailed(let status):
            return "Failed to delete password (status: \(status))"
        }
    }
}

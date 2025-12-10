import Foundation
import Combine

/// Session expiry duration options
enum SessionExpiry: String, CaseIterable, Identifiable {
    case hour = "1 Hour"
    case day = "1 Day"
    case week = "1 Week"
    case month = "1 Month"
    case year = "1 Year"
    case unlimited = "Unlimited"

    var id: String { rawValue }

    var timeInterval: TimeInterval? {
        switch self {
        case .hour: return 3600
        case .day: return 86400
        case .week: return 604800
        case .month: return 2592000  // 30 days
        case .year: return 31536000
        case .unlimited: return nil
        }
    }
}

/// Represents an active authentication session for a device
struct AuthSession: Codable {
    let deviceId: String
    let authenticatedAt: Date
    let expiresAt: Date?

    var isExpired: Bool {
        guard let expiresAt = expiresAt else { return false }
        return Date() > expiresAt
    }
}

/// Manages authentication state for devices.
/// - Passwords are stored securely in Keychain
/// - Session metadata (expiry times) stored in UserDefaults
/// - Provides a central point for checking/managing auth state
@MainActor
class AuthenticationManager: ObservableObject {
    static let shared = AuthenticationManager()

    private let keychain = KeychainManager.shared
    private let userDefaultsKey = "deviceAuthSessions"

    /// Currently active sessions (non-expired)
    @Published private(set) var activeSessions: [String: AuthSession] = [:]

    /// Default session expiry for new authentications
    @Published var defaultSessionExpiry: SessionExpiry = .week

    /// Device that needs authentication (set when 401 received)
    @Published var deviceNeedingAuth: String?

    /// Callback when authentication is needed
    var onAuthenticationNeeded: ((String) -> Void)?

    private init() {
        loadSessions()
        cleanExpiredSessions()
    }

    // MARK: - Session Management

    /// Check if we have a valid session for a device
    func hasValidSession(forDeviceId deviceId: String) -> Bool {
        guard let session = activeSessions[deviceId] else { return false }
        return !session.isExpired
    }

    /// Get the password for a device if we have a valid session
    func getPassword(forDeviceId deviceId: String) -> String? {
        guard hasValidSession(forDeviceId: deviceId) else { return nil }
        return keychain.getPassword(forDeviceId: deviceId)
    }

    /// Authenticate with a device - stores password and creates session
    func authenticate(deviceId: String, password: String, expiry: SessionExpiry? = nil) throws {
        let sessionExpiry = expiry ?? defaultSessionExpiry

        // Save password to keychain
        try keychain.savePassword(password, forDeviceId: deviceId)

        // Create session
        let expiresAt: Date?
        if let interval = sessionExpiry.timeInterval {
            expiresAt = Date().addingTimeInterval(interval)
        } else {
            expiresAt = nil
        }

        let session = AuthSession(
            deviceId: deviceId,
            authenticatedAt: Date(),
            expiresAt: expiresAt
        )

        activeSessions[deviceId] = session
        saveSessions()

        print("[Auth] Authenticated device: \(deviceId), expires: \(expiresAt?.description ?? "never")")
    }

    /// Clear authentication for a device (e.g., on 401 or user logout)
    func clearAuthentication(forDeviceId deviceId: String) {
        try? keychain.deletePassword(forDeviceId: deviceId)
        activeSessions.removeValue(forKey: deviceId)
        saveSessions()
        print("[Auth] Cleared authentication for device: \(deviceId)")
    }

    /// Handle a 401 response - clears session and signals need for re-auth
    func handleAuthenticationRequired(forDeviceId deviceId: String) {
        clearAuthentication(forDeviceId: deviceId)
        deviceNeedingAuth = deviceId
        onAuthenticationNeeded?(deviceId)
    }

    /// Clear the pending auth request
    func clearAuthenticationRequest() {
        deviceNeedingAuth = nil
    }

    // MARK: - Persistence

    private func loadSessions() {
        guard let data = UserDefaults.standard.data(forKey: userDefaultsKey),
              let sessions = try? JSONDecoder().decode([String: AuthSession].self, from: data) else {
            return
        }
        activeSessions = sessions
    }

    private func saveSessions() {
        guard let data = try? JSONEncoder().encode(activeSessions) else { return }
        UserDefaults.standard.set(data, forKey: userDefaultsKey)
    }

    private func cleanExpiredSessions() {
        let expiredDeviceIds = activeSessions.filter { $0.value.isExpired }.map { $0.key }
        for deviceId in expiredDeviceIds {
            try? keychain.deletePassword(forDeviceId: deviceId)
            activeSessions.removeValue(forKey: deviceId)
        }
        if !expiredDeviceIds.isEmpty {
            saveSessions()
            print("[Auth] Cleaned \(expiredDeviceIds.count) expired sessions")
        }
    }

    // MARK: - Session Expiry Settings

    /// Update the default session expiry preference
    func setDefaultSessionExpiry(_ expiry: SessionExpiry) {
        defaultSessionExpiry = expiry
        UserDefaults.standard.set(expiry.rawValue, forKey: "defaultSessionExpiry")
    }

    /// Load saved session expiry preference
    func loadDefaultSessionExpiry() {
        if let saved = UserDefaults.standard.string(forKey: "defaultSessionExpiry"),
           let expiry = SessionExpiry(rawValue: saved) {
            defaultSessionExpiry = expiry
        }
    }
}

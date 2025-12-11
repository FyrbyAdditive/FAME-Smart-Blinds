import Foundation
import ZAPSDK

/// Information about an available firmware update
struct UpdateInfo {
    let version: String
    let releaseNotes: String?
    let requiresAppUpdate: Bool
    let requiredAppVersion: String?
    let canFlash: Bool
    let requiredFirmwareVersion: String?
}

/// Service for checking and downloading firmware updates via ZAP
class FirmwareUpdateService {
    static let shared = FirmwareUpdateService()

    private let client = ZAPClient()
    private let productSlug = "fame-smart-blinds"
    private let boardType = "xiao-esp32-c3"

    private init() {}

    /// Check for firmware updates
    /// - Parameters:
    ///   - currentFirmwareVersion: The device's current firmware version
    ///   - currentAppVersion: The current app version (e.g., "1.0.2")
    /// - Returns: UpdateInfo if an update is available, nil otherwise
    func checkForUpdate(
        currentFirmwareVersion: String,
        currentAppVersion: String
    ) async throws -> UpdateInfo? {
        print("[FirmwareUpdate] Checking for updates. Current firmware: \(currentFirmwareVersion), App: \(currentAppVersion)")

        let latestFirmware = try await client.getLatestFirmware(product: productSlug)

        print("[FirmwareUpdate] Latest firmware available: \(latestFirmware.version)")

        // Compare versions
        if !isNewerVersion(current: currentFirmwareVersion, latest: latestFirmware.version) {
            print("[FirmwareUpdate] No update available - already on latest or newer")
            return nil
        }

        // Check if current app meets minimum requirement to flash this firmware
        // The SDK provides minAppVersionFlash for the minimum iOS app version to flash
        let minFlashVersion = latestFirmware.minAppVersionFlash
        let canFlash: Bool
        if let required = minFlashVersion {
            canFlash = isVersionAtLeast(version: currentAppVersion, required: required)
        } else {
            canFlash = true
        }

        // Check if app update will be required after flashing (to run the new firmware)
        // The SDK provides minAppVersionRun for the minimum app version needed to run the firmware
        let minRunVersion = latestFirmware.minAppVersionRun
        let requiresAppUpdate: Bool
        if let required = minRunVersion {
            requiresAppUpdate = !isVersionAtLeast(version: currentAppVersion, required: required)
        } else {
            requiresAppUpdate = false
        }

        // requiredFirmwareVersion is no longer directly available in SDK v1.0.1
        let requiredFirmwareVersion: String? = nil

        print("[FirmwareUpdate] Update available: \(latestFirmware.version), canFlash=\(canFlash), requiresAppUpdate=\(requiresAppUpdate)")

        return UpdateInfo(
            version: latestFirmware.version,
            releaseNotes: latestFirmware.releaseNotes,
            requiresAppUpdate: requiresAppUpdate,
            requiredAppVersion: minRunVersion,
            canFlash: canFlash,
            requiredFirmwareVersion: requiredFirmwareVersion
        )
    }

    /// Download firmware from the ZAP service
    /// - Parameters:
    ///   - version: The firmware version to download
    ///   - progress: Progress callback (0.0 to 1.0)
    /// - Returns: The firmware binary data
    func downloadFirmware(
        version: String,
        progress: @escaping (Double) -> Void
    ) async throws -> Data {
        print("[FirmwareUpdate] Downloading firmware version: \(version)")

        let result = try await client.downloadFirmware(
            product: productSlug,
            version: version,
            type: .update,
            board: boardType,
            validateChecksum: true
        )

        // Note: ZAPClient doesn't support progress callbacks for download
        // but the download is typically fast enough that this isn't an issue
        progress(1.0)

        print("[FirmwareUpdate] Downloaded \(result.data.count) bytes")

        return result.data
    }

    // MARK: - Version Comparison

    /// Compare two version strings to determine if `latest` is newer than `current`
    private func isNewerVersion(current: String, latest: String) -> Bool {
        let currentParts = parseVersion(current)
        let latestParts = parseVersion(latest)

        let maxCount = max(currentParts.count, latestParts.count)
        for i in 0..<maxCount {
            let currentPart = i < currentParts.count ? currentParts[i] : 0
            let latestPart = i < latestParts.count ? latestParts[i] : 0

            if latestPart > currentPart { return true }
            if latestPart < currentPart { return false }
        }

        return false // Equal versions
    }

    /// Check if `version` is at least `required`
    private func isVersionAtLeast(version: String, required: String) -> Bool {
        let versionParts = parseVersion(version)
        let requiredParts = parseVersion(required)

        let maxCount = max(versionParts.count, requiredParts.count)
        for i in 0..<maxCount {
            let vPart = i < versionParts.count ? versionParts[i] : 0
            let rPart = i < requiredParts.count ? requiredParts[i] : 0

            if vPart > rPart { return true }
            if vPart < rPart { return false }
        }

        return true // Equal versions
    }

    /// Parse a version string into numeric parts
    /// Handles versions like "1.0.2", "1.2", "v1.0.0"
    private func parseVersion(_ version: String) -> [Int] {
        var v = version
        if v.hasPrefix("v") || v.hasPrefix("V") {
            v = String(v.dropFirst())
        }
        return v.split(separator: ".").compactMap { Int($0) }
    }
}

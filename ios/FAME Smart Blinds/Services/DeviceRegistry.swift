import Foundation
import CoreBluetooth
import Combine
import os.log

private let logger = Logger(subsystem: "com.famesmartblinds", category: "Registry")

/// Single source of truth for all discovered devices, keyed by deviceId.
///
/// ## Architecture Overview
/// Devices can be discovered via two methods:
/// - **BLE (Bluetooth)**: Only used during initial device setup when the device has no WiFi.
///   After setup completes (WiFi connected), the device disables BLE advertising.
/// - **mDNS/HTTP**: Primary discovery method for configured devices. All control, status,
///   and configuration changes happen over HTTP after initial setup.
///
/// The registry unifies both discovery sources using the deviceId (8-char hex MAC suffix)
/// as a stable identifier across BLE and HTTP.
@MainActor
class DeviceRegistry: ObservableObject {
    static let shared = DeviceRegistry()

    /// Internal storage - keyed by deviceId (the 8-char hex suffix)
    private var _devices: [String: BlindDevice] = [:]

    /// Devices as an array for UI display - directly published for SwiftUI observation
    @Published private(set) var deviceList: [BlindDevice] = []

    /// Cooldown to prevent scanning too soon after device setup/restart
    @Published private(set) var scanCooldownActive = false
    private var cooldownEndTime: Date?

    /// Device currently being set up via BLE - HTTP discovery should not update this device
    /// to prevent it from appearing in the "configured devices" list during setup
    private var deviceInSetup: String?

    private init() {
        let ptr = Unmanaged.passUnretained(self).toOpaque()
        NSLog("[Registry] Initialized, instance: \(ptr)")
    }

    /// Start a cooldown period where scanning should be blocked
    func startScanCooldown(seconds: TimeInterval = 5) {
        scanCooldownActive = true
        cooldownEndTime = Date().addingTimeInterval(seconds)
        NSLog("[Registry] Scan cooldown started for %.0f seconds", seconds)

        Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            self.scanCooldownActive = false
            self.cooldownEndTime = nil
            NSLog("[Registry] Scan cooldown ended")
        }
    }

    /// Check if scanning is currently allowed
    var canScan: Bool {
        !scanCooldownActive
    }

    /// Refresh the published deviceList from internal storage
    private func refreshList() {
        deviceList = Array(_devices.values).sorted { $0.name < $1.name }
        let count = self.deviceList.count
        let ptr = Unmanaged.passUnretained(self).toOpaque()
        NSLog("[Registry] refreshList on instance \(ptr), count: \(count)")
    }

    /// Update or create a device from BLE discovery.
    /// Only called during initial setup when device is advertising (before WiFi configured).
    func updateFromBLE(peripheral: CBPeripheral, advertisedName: String?, rssi: Int) {
        let name = advertisedName ?? peripheral.name ?? "Unknown"
        let deviceId = BlindDevice.extractDeviceId(from: name).lowercased()

        // Skip devices without a valid deviceId - they can't be reliably tracked
        guard !deviceId.isEmpty else {
            NSLog("[Registry] Skipping BLE device without deviceId: %@", name)
            return
        }

        if let existing = _devices[deviceId] {
            // Update existing device with BLE info
            existing.peripheral = peripheral
            existing.rssi = rssi
            existing.lastSeen = Date()
            // Update name from BLE if available
            // Note: HTTP discovery will update the name with the authoritative hostname from /info
            if advertisedName != nil {
                existing.name = name
            }
            NSLog("[Registry] Updated BLE info for %@: %@", deviceId, existing.name)
        } else {
            // Create new device
            let device = BlindDevice(peripheral: peripheral, rssi: rssi, advertisedName: advertisedName)
            _devices[deviceId] = device
            NSLog("[Registry] Added new BLE device %@: %@", deviceId, device.name)
        }
        refreshList()
    }

    /// Update or create a device from mDNS/HTTP discovery.
    /// Primary discovery method for configured devices - called when device is found via Bonjour.
    func updateFromHTTP(name: String, ipAddress: String, deviceId: String, macAddress: String = "") {
        // Skip devices without a valid deviceId
        guard !deviceId.isEmpty else {
            NSLog("[Registry] Skipping HTTP device without deviceId: %@", name)
            return
        }

        let normalizedId = deviceId.lowercased()

        // Skip devices currently being set up via BLE to prevent them from
        // appearing in the configured devices list during the setup wizard
        if normalizedId == deviceInSetup {
            NSLog("[Registry] Skipping HTTP update for device %@ - currently in setup", normalizedId)
            return
        }

        if let existing = _devices[normalizedId] {
            // Update existing device with HTTP info
            existing.ipAddress = ipAddress
            existing.wifiConnected = true
            existing.lastSeen = Date()
            if !macAddress.isEmpty {
                existing.macAddress = macAddress
            }
            // Update name from HTTP (it's authoritative)
            existing.name = name
            NSLog("[Registry] Updated HTTP info for %@: IP=%@", normalizedId, ipAddress)
        } else {
            // Create new device from HTTP discovery
            let device = BlindDevice(name: name, ipAddress: ipAddress, deviceId: normalizedId)
            device.macAddress = macAddress
            _devices[normalizedId] = device
            NSLog("[Registry] Added new HTTP device %@: %@ at %@", normalizedId, name, ipAddress)
        }
        refreshList()
    }

    /// Mark a device as BLE connected (only during initial setup)
    func setBLEConnected(deviceId: String, connected: Bool) {
        guard let device = _devices[deviceId.lowercased()] else { return }
        device.bleConnected = connected
    }

    /// Mark a device as currently being set up via BLE.
    /// While in setup, HTTP discovery will not update this device to prevent
    /// it from appearing in the configured devices list prematurely.
    func markDeviceInSetup(deviceId: String?) {
        deviceInSetup = deviceId?.lowercased()
        if let id = deviceInSetup {
            NSLog("[Registry] Device %@ marked as in setup", id)
        } else {
            NSLog("[Registry] Device setup completed, clearing setup flag")
        }
    }

    /// Check if a device is currently being set up
    func isDeviceInSetup(deviceId: String) -> Bool {
        return deviceInSetup == deviceId.lowercased()
    }

    /// Get a device by its deviceId
    func device(for deviceId: String) -> BlindDevice? {
        return _devices[deviceId.lowercased()]
    }

    /// Clear all devices (for refresh)
    func clear() {
        _devices.removeAll()
        refreshList()
        NSLog("[Registry] Cleared all devices")
    }

    /// Clear only BLE-discovered devices (those without IP addresses).
    /// Used when starting a fresh BLE scan in SetupView without affecting mDNS-discovered devices.
    func clearBLEOnlyDevices() {
        let bleOnlyIds = _devices.filter { $0.value.ipAddress == nil && $0.value.peripheral != nil }
            .map { $0.key }

        for id in bleOnlyIds {
            _devices.removeValue(forKey: id)
        }

        if !bleOnlyIds.isEmpty {
            refreshList()
            NSLog("[Registry] Cleared %d BLE-only devices", bleOnlyIds.count)
        }
    }

    /// Remove a specific device by its deviceId
    func remove(deviceId: String) {
        let normalizedId = deviceId.lowercased()
        if let removed = _devices.removeValue(forKey: normalizedId) {
            NSLog("[Registry] Removed device: %@ (%@)", normalizedId, removed.name)
            refreshList()
        }
    }

    /// Remove stale devices not seen recently
    func removeStale(olderThan interval: TimeInterval = 300) {
        let cutoff = Date().addingTimeInterval(-interval)
        let staleIds = _devices.filter { $0.value.lastSeen < cutoff }.map { $0.key }
        for id in staleIds {
            _devices.removeValue(forKey: id)
            NSLog("[Registry] Removed stale device: %@", id)
        }
        if !staleIds.isEmpty {
            refreshList()
        }
    }
}

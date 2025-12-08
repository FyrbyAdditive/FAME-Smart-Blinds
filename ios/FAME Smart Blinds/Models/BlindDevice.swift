import Foundation
import CoreBluetooth

/// Represents a FAME Smart Blinds device.
///
/// Architecture note: BLE is only used during initial device setup (when device has no WiFi).
/// Once WiFi is configured, the device disables BLE advertising and all communication
/// (control, configuration, status) happens over HTTP via mDNS discovery.
@MainActor
class BlindDevice: Identifiable, ObservableObject {
    let id: UUID

    // BLE properties - only used during initial setup before WiFi is configured
    var peripheral: CBPeripheral?
    var rssi: Int

    // Device info
    @Published var name: String
    @Published var deviceId: String  // 8-char hex ID from MAC address, used as stable identifier
    @Published var macAddress: String

    // Configuration (readable via BLE during setup)
    @Published var wifiSsid: String
    @Published var mqttBroker: String

    // Connection state
    @Published var bleConnected: Bool = false  // Only true during setup
    @Published var wifiConnected: Bool = false
    @Published var mqttConnected: Bool = false

    // Network info (when WiFi connected - primary communication method post-setup)
    @Published var ipAddress: String?

    // Blind state
    @Published var state: BlindState = .unknown
    @Published var position: Int = 0

    // Calibration state
    @Published var isCalibrated: Bool = false
    @Published var cumulativePosition: Int = 0
    @Published var maxPosition: Int = 0
    @Published var calibrationState: String = "idle"

    // Status string from device
    @Published var statusString: String = ""

    // Timestamps
    var lastSeen: Date

    /// Initialize from BLE discovery - used during initial device setup only
    init(peripheral: CBPeripheral, rssi: Int = 0, advertisedName: String? = nil) {
        self.id = peripheral.identifier
        self.peripheral = peripheral
        self.rssi = rssi
        // Use advertised name if provided (not cached), otherwise fall back to peripheral.name
        let actualName = advertisedName ?? peripheral.name ?? "Unknown Device"
        self.name = actualName
        // Extract device ID from name (e.g., "FAMEBlinds_23c57e80" -> "23c57e80")
        self.deviceId = BlindDevice.extractDeviceId(from: actualName)
        self.macAddress = ""
        self.wifiSsid = ""
        self.mqttBroker = ""
        self.lastSeen = Date()
    }

    /// Extract device ID from a name like "FAMEBlinds_23c57e80"
    static func extractDeviceId(from name: String?) -> String {
        guard let name = name else { return "" }
        // Look for underscore followed by hex ID (8 characters)
        if let underscoreIndex = name.lastIndex(of: "_") {
            let suffix = String(name[name.index(after: underscoreIndex)...])
            // Validate it looks like a hex device ID (8 hex chars)
            if suffix.count == 8 && suffix.allSatisfy({ $0.isHexDigit }) {
                return suffix.lowercased()
            }
        }
        return ""
    }

    /// Initialize from mDNS/HTTP discovery - primary discovery method for configured devices
    init(name: String, ipAddress: String, deviceId: String = "") {
        self.id = UUID()
        self.peripheral = nil
        self.rssi = 0
        self.name = name
        self.deviceId = deviceId
        self.macAddress = ""
        self.wifiSsid = ""
        self.mqttBroker = ""
        self.ipAddress = ipAddress
        self.wifiConnected = true
        self.lastSeen = Date()
    }

    func updateFromStatus(_ status: String) {
        statusString = status

        // Parse status string like "wifi:192.168.1.100,mqtt:ok,servo:ok"
        let components = status.split(separator: ",")
        for component in components {
            let parts = component.split(separator: ":")
            guard parts.count >= 2 else { continue }

            let key = String(parts[0])
            let value = String(parts[1])

            switch key {
            case "wifi":
                if value == "connecting" {
                    wifiConnected = false
                } else if value == "disconnected" {
                    wifiConnected = false
                    ipAddress = nil
                } else {
                    wifiConnected = true
                    ipAddress = value
                }
            case "mqtt":
                mqttConnected = value == "ok"
            default:
                break
            }
        }
    }

    func updateFromDeviceStatus(_ deviceStatus: DeviceStatus) {
        self.state = BlindState(rawValue: deviceStatus.state) ?? .unknown
        self.position = deviceStatus.position

        if let calibration = deviceStatus.calibration {
            self.isCalibrated = calibration.calibrated
            self.cumulativePosition = calibration.cumulativePosition
            self.maxPosition = calibration.maxPosition
            self.calibrationState = calibration.state
        }
    }

    func updateFromCalibrationStatus(_ status: CalibrationStatusResponse) {
        self.isCalibrated = status.calibrated
        self.cumulativePosition = status.position
        self.maxPosition = status.maxPosition
        self.calibrationState = status.calibrationState
    }
}

extension BlindDevice: Equatable {
    nonisolated static func == (lhs: BlindDevice, rhs: BlindDevice) -> Bool {
        lhs.id == rhs.id
    }
}

extension BlindDevice: Hashable {
    nonisolated func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }
}

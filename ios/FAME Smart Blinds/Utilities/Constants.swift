import Foundation
import CoreBluetooth

enum Constants {
    // BLE UUIDs (must match firmware)
    enum BLE {
        static let serviceUUID = CBUUID(string: "4fafc201-1fb5-459e-8fcc-c5c9c331914b")

        static let wifiSsidUUID = CBUUID(string: "beb5483e-36e1-4688-b7f5-ea07361b26a8")
        static let wifiPasswordUUID = CBUUID(string: "beb5483e-36e1-4688-b7f5-ea07361b26a9")
        static let deviceNameUUID = CBUUID(string: "beb5483e-36e1-4688-b7f5-ea07361b26aa")
        static let mqttBrokerUUID = CBUUID(string: "beb5483e-36e1-4688-b7f5-ea07361b26ab")
        static let statusUUID = CBUUID(string: "beb5483e-36e1-4688-b7f5-ea07361b26ac")
        static let commandUUID = CBUUID(string: "beb5483e-36e1-4688-b7f5-ea07361b26ad")
        static let devicePasswordUUID = CBUUID(string: "beb5483e-36e1-4688-b7f5-ea07361b26ae")
        static let orientationUUID = CBUUID(string: "beb5483e-36e1-4688-b7f5-ea07361b26af")
        static let wifiScanTriggerUUID = CBUUID(string: "beb5483e-36e1-4688-b7f5-ea07361b26b0")
        static let wifiScanResultsUUID = CBUUID(string: "beb5483e-36e1-4688-b7f5-ea07361b26b1")

        static let allCharacteristicUUIDs: [CBUUID] = [
            wifiSsidUUID, wifiPasswordUUID, deviceNameUUID,
            mqttBrokerUUID, statusUUID, commandUUID, devicePasswordUUID, orientationUUID,
            wifiScanTriggerUUID, wifiScanResultsUUID
        ]
    }

    // HTTP endpoints
    enum HTTP {
        static let defaultPort = 80
        static let statusEndpoint = "/status"
        static let infoEndpoint = "/info"
        static let commandEndpoint = "/command"
        static let openEndpoint = "/open"
        static let closeEndpoint = "/close"
        static let stopEndpoint = "/stop"
    }

    // Device discovery
    enum Discovery {
        static let bonjourServiceType = "_famesmartblinds._tcp"
        static let bonjourDomain = "local."
        static let httpServiceType = "_http._tcp"
    }

    // Timeouts
    enum Timeout {
        static let bleConnection: TimeInterval = 10.0
        static let bleScan: TimeInterval = 15.0
        static let httpRequest: TimeInterval = 5.0
        static let wifiConnection: TimeInterval = 30.0
    }
}

// Blind commands
enum BlindCommand: String, CaseIterable {
    case open = "OPEN"
    case close = "CLOSE"
    case stop = "STOP"
    case restart = "RESTART"

    // Only show user-controllable commands
    static var userCommands: [BlindCommand] {
        [.open, .close, .stop]
    }

    var displayName: String {
        switch self {
        case .open: return "Open"
        case .close: return "Close"
        case .stop: return "Stop"
        case .restart: return "Restart"
        }
    }

    var icon: String {
        switch self {
        case .open: return "arrow.up"
        case .close: return "arrow.down"
        case .stop: return "stop.fill"
        case .restart: return "arrow.clockwise"
        }
    }
}

// Blind state
enum BlindState: String {
    case unknown
    case open
    case closed
    case opening
    case closing
    case stopped

    var displayName: String {
        rawValue.capitalized
    }

    var isMoving: Bool {
        self == .opening || self == .closing
    }
}

// Device orientation (mounting side)
enum DeviceOrientation: String, CaseIterable, Codable {
    case left
    case right

    var displayName: String {
        switch self {
        case .left: return "Left Side"
        case .right: return "Right Side"
        }
    }

    var description: String {
        switch self {
        case .left: return "Servo mounted on the left side of the window"
        case .right: return "Servo mounted on the right side of the window"
        }
    }
}

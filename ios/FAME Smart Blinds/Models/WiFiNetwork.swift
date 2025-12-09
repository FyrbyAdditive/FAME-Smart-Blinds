import Foundation

/// Signal strength classification for WiFi networks
enum SignalStrength: String {
    case excellent
    case good
    case fair
    case weak

    var iconName: String {
        switch self {
        case .excellent: return "wifi"
        case .good: return "wifi"
        case .fair: return "wifi"
        case .weak: return "wifi.exclamationmark"
        }
    }

    var barsCount: Int {
        switch self {
        case .excellent: return 3
        case .good: return 3
        case .fair: return 2
        case .weak: return 1
        }
    }
}

/// Represents a WiFi network discovered during BLE setup scanning
struct WiFiNetwork: Identifiable, Hashable {
    let ssid: String
    let rssi: Int
    let isSecured: Bool

    var id: String { ssid }

    var signalStrength: SignalStrength {
        switch rssi {
        case -50...0: return .excellent
        case -60...(-51): return .good
        case -70...(-61): return .fair
        default: return .weak
        }
    }
}

// MARK: - Codable conformance for JSON parsing

extension WiFiNetwork: Codable {
    enum CodingKeys: String, CodingKey {
        case ssid = "s"
        case rssi = "r"
        case isSecured = "e"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        ssid = try container.decode(String.self, forKey: .ssid)
        rssi = try container.decode(Int.self, forKey: .rssi)
        // Decode as Int (0 or 1) and convert to Bool
        let encrypted = try container.decode(Int.self, forKey: .isSecured)
        isSecured = encrypted == 1
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(ssid, forKey: .ssid)
        try container.encode(rssi, forKey: .rssi)
        try container.encode(isSecured ? 1 : 0, forKey: .isSecured)
    }
}

/// Response wrapper for WiFi scan results from firmware
struct WiFiScanResponse: Codable {
    let networks: [WiFiNetwork]

    enum CodingKeys: String, CodingKey {
        case networks = "n"
    }
}

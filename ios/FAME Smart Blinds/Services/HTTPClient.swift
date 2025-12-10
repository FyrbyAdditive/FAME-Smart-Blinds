import Foundation
import Combine

class HTTPClient: ObservableObject {
    static let shared = HTTPClient()

    @Published var isLoading = false
    @Published var lastError: String?

    private let session: URLSession

    init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = Constants.Timeout.httpRequest
        config.timeoutIntervalForResource = Constants.Timeout.httpRequest * 2
        session = URLSession(configuration: config)
    }

    // MARK: - Authentication Helpers

    /// Add authentication header to request if we have a valid session
    private func addAuthHeader(to request: inout URLRequest, forDeviceId deviceId: String) async {
        let password = await MainActor.run {
            AuthenticationManager.shared.getPassword(forDeviceId: deviceId)
        }
        if let password = password {
            request.setValue(password, forHTTPHeaderField: "X-Device-Password")
        }
    }

    /// Check response for 401 and handle auth failure
    private func handleAuthResponse(_ response: URLResponse?, data: Data, forDeviceId deviceId: String) throws {
        guard let httpResponse = response as? HTTPURLResponse else {
            throw HTTPError.invalidResponse
        }

        if httpResponse.statusCode == 401 {
            // Clear the invalid session and signal auth needed
            Task { @MainActor in
                AuthenticationManager.shared.handleAuthenticationRequired(forDeviceId: deviceId)
            }
            throw HTTPError.authenticationRequired(deviceId)
        }

        guard httpResponse.statusCode == 200 else {
            let errorBody = String(data: data, encoding: .utf8) ?? "Unknown error"
            throw HTTPError.serverError(httpResponse.statusCode, errorBody)
        }
    }

    // MARK: - Device Control

    func sendCommand(_ command: BlindCommand, to ipAddress: String, deviceId: String) async throws {
        // Use the simple endpoints which don't require a body
        // This avoids ESPAsyncWebServer body-handling race conditions
        let endpoint: String
        switch command {
        case .open:
            endpoint = Constants.HTTP.openEndpoint
        case .close:
            endpoint = Constants.HTTP.closeEndpoint
        case .stop:
            endpoint = Constants.HTTP.stopEndpoint
        case .restart:
            endpoint = "/restart"
        }

        let url = URL(string: "http://\(ipAddress)\(endpoint)")!

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        print("[HTTP] POST \(url)")

        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)

        print("[HTTP] Command sent successfully")
    }

    // Quick command methods
    @MainActor
    func open(device: BlindDevice) async throws {
        guard let ip = device.ipAddress else {
            throw HTTPError.noIPAddress
        }
        try await sendCommand(.open, to: ip, deviceId: device.deviceId)
    }

    @MainActor
    func close(device: BlindDevice) async throws {
        guard let ip = device.ipAddress else {
            throw HTTPError.noIPAddress
        }
        try await sendCommand(.close, to: ip, deviceId: device.deviceId)
    }

    @MainActor
    func stop(device: BlindDevice) async throws {
        guard let ip = device.ipAddress else {
            throw HTTPError.noIPAddress
        }
        try await sendCommand(.stop, to: ip, deviceId: device.deviceId)
    }

    // MARK: - Device Configuration

    func setDeviceName(_ name: String, at ipAddress: String, deviceId: String) async throws {
        let url = URL(string: "http://\(ipAddress)/name?name=\(name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? name)")!

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        print("[HTTP] POST \(url)")

        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)

        print("[HTTP] Device name set successfully")
    }

    func setDevicePassword(_ password: String, at ipAddress: String, deviceId: String) async throws {
        let encodedPassword = password.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? password
        let url = URL(string: "http://\(ipAddress)/password?password=\(encodedPassword)")!

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        print("[HTTP] POST /password")

        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)

        print("[HTTP] Device password set successfully")
    }

    func setMqttConfig(broker: String, port: Int = 1883, user: String = "", password: String = "", at ipAddress: String, deviceId: String) async throws {
        var urlString = "http://\(ipAddress)/mqtt?broker=\(broker.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? broker)&port=\(port)"
        if !user.isEmpty {
            urlString += "&user=\(user.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? user)"
        }
        if !password.isEmpty {
            urlString += "&password=\(password.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? password)"
        }

        let url = URL(string: urlString)!

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        print("[HTTP] POST /mqtt")

        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)

        print("[HTTP] MQTT config set successfully")
    }

    func setWifiCredentials(ssid: String, password: String, at ipAddress: String, deviceId: String) async throws {
        let encodedSsid = ssid.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ssid
        let encodedPassword = password.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? password
        let url = URL(string: "http://\(ipAddress)/wifi?ssid=\(encodedSsid)&password=\(encodedPassword)")!

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        print("[HTTP] POST /wifi")

        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)

        print("[HTTP] WiFi credentials set successfully")
    }

    func setOrientation(_ orientation: DeviceOrientation, at ipAddress: String, deviceId: String) async throws {
        let url = URL(string: "http://\(ipAddress)/orientation?orientation=\(orientation.rawValue)")!

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        print("[HTTP] POST /orientation: \(orientation.rawValue)")

        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)

        print("[HTTP] Orientation set successfully")
    }

    func setSpeed(_ speed: Int, at ipAddress: String, deviceId: String) async throws {
        let url = URL(string: "http://\(ipAddress)/speed")!

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = "value=\(speed)".data(using: .utf8)
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        print("[HTTP] POST /speed: \(speed)")

        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)

        print("[HTTP] Speed set successfully")
    }

    // MARK: - Device Status

    func getStatus(from ipAddress: String) async throws -> DeviceStatus {
        let url = URL(string: "http://\(ipAddress)\(Constants.HTTP.statusEndpoint)")!

        print("[HTTP] GET \(url)")

        let (data, response) = try await session.data(from: url)

        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw HTTPError.invalidResponse
        }

        let status = try JSONDecoder().decode(DeviceStatus.self, from: data)
        print("[HTTP] Status received: \(status)")
        return status
    }

    func getInfo(from ipAddress: String) async throws -> DeviceInfo {
        let url = URL(string: "http://\(ipAddress)\(Constants.HTTP.infoEndpoint)")!

        print("[HTTP] GET \(url)")

        let (data, response) = try await session.data(from: url)

        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw HTTPError.invalidResponse
        }

        let info = try JSONDecoder().decode(DeviceInfo.self, from: data)
        print("[HTTP] Info received: \(info)")
        return info
    }

    // MARK: - Device Discovery

    func checkDevice(at ipAddress: String) async -> Bool {
        do {
            _ = try await getInfo(from: ipAddress)
            return true
        } catch {
            return false
        }
    }

    // MARK: - Calibration

    func startCalibration(at ipAddress: String, deviceId: String) async throws {
        let url = URL(string: "http://\(ipAddress)/calibrate/start")!

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        print("[HTTP] POST /calibrate/start")

        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)

        print("[HTTP] Calibration started")
    }

    func setBottomPosition(at ipAddress: String, deviceId: String) async throws {
        let url = URL(string: "http://\(ipAddress)/calibrate/setbottom")!

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        print("[HTTP] POST /calibrate/setbottom")

        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)

        print("[HTTP] Bottom position set")
    }

    func cancelCalibration(at ipAddress: String, deviceId: String) async throws {
        let url = URL(string: "http://\(ipAddress)/calibrate/cancel")!

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        print("[HTTP] POST /calibrate/cancel")

        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)

        print("[HTTP] Calibration cancelled")
    }

    func getCalibrationStatus(from ipAddress: String, deviceId: String) async throws -> CalibrationStatusResponse {
        let url = URL(string: "http://\(ipAddress)/calibrate/status")!

        var request = URLRequest(url: url)
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        print("[HTTP] GET /calibrate/status")

        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)

        let status = try JSONDecoder().decode(CalibrationStatusResponse.self, from: data)
        print("[HTTP] Calibration status: \(status)")
        return status
    }

    func openForce(at ipAddress: String, deviceId: String) async throws {
        let url = URL(string: "http://\(ipAddress)/open/force")!

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        print("[HTTP] POST /open/force")

        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)

        print("[HTTP] Force open sent")
    }

    func closeForce(at ipAddress: String, deviceId: String) async throws {
        let url = URL(string: "http://\(ipAddress)/close/force")!

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        print("[HTTP] POST /close/force")

        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)

        print("[HTTP] Force close sent")
    }

    // MARK: - Firmware Update (Chunked Protocol)

    /// Upload firmware using chunked protocol for memory-constrained devices
    /// Protocol:
    /// 1. POST /ota/begin?size=TOTAL - Initialize update
    /// 2. POST /ota/chunk (body=chunk data) - Send each chunk
    /// 3. POST /ota/end - Finalize update
    func uploadFirmware(_ firmwareData: Data, to ipAddress: String, deviceId: String, progress: @escaping (Double) -> Void) async throws {
        let chunkSize = 8192  // 8KB chunks - fits easily in ESP32 RAM
        let totalSize = firmwareData.count
        let totalChunks = (totalSize + chunkSize - 1) / chunkSize

        print("[OTA] Starting chunked upload: \(totalSize) bytes in \(totalChunks) chunks")

        // Step 1: Initialize OTA
        let beginUrl = URL(string: "http://\(ipAddress)/ota/begin?size=\(totalSize)")!
        var beginRequest = URLRequest(url: beginUrl)
        beginRequest.httpMethod = "POST"
        beginRequest.timeoutInterval = 10
        await addAuthHeader(to: &beginRequest, forDeviceId: deviceId)

        let (beginData, beginResponse) = try await session.data(for: beginRequest)
        try handleAuthResponse(beginResponse, data: beginData, forDeviceId: deviceId)

        print("[OTA] Begin successful, sending chunks...")

        // Step 2: Send chunks (chunks use session established by begin)
        for chunkIndex in 0..<totalChunks {
            let startOffset = chunkIndex * chunkSize
            let endOffset = min(startOffset + chunkSize, totalSize)
            let chunkData = firmwareData.subdata(in: startOffset..<endOffset)

            // Calculate CRC32 for this chunk
            let crc = chunkData.crc32()

            let chunkUrl = URL(string: "http://\(ipAddress)/ota/chunk?index=\(chunkIndex)&crc=\(String(format: "%08x", crc))")!
            var chunkRequest = URLRequest(url: chunkUrl)
            chunkRequest.httpMethod = "POST"
            chunkRequest.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            chunkRequest.setValue("\(chunkData.count)", forHTTPHeaderField: "Content-Length")
            chunkRequest.timeoutInterval = 30
            await addAuthHeader(to: &chunkRequest, forDeviceId: deviceId)

            let (chunkResponseData, chunkResponse) = try await session.upload(for: chunkRequest, from: chunkData)

            guard let chunkHttpResponse = chunkResponse as? HTTPURLResponse,
                  chunkHttpResponse.statusCode == 200 else {
                let errorBody = String(data: chunkResponseData, encoding: .utf8) ?? "Unknown error"
                // Abort OTA on failure
                try? await abortOTA(at: ipAddress, deviceId: deviceId)
                throw HTTPError.serverError((chunkResponse as? HTTPURLResponse)?.statusCode ?? 0, "Chunk \(chunkIndex) failed: \(errorBody)")
            }

            // Update progress
            let currentProgress = Double(endOffset) / Double(totalSize)
            await MainActor.run {
                progress(currentProgress)
            }

            // Log every 10 chunks
            if chunkIndex % 10 == 0 || chunkIndex == totalChunks - 1 {
                print("[OTA] Chunk \(chunkIndex + 1)/\(totalChunks) sent (\(Int(currentProgress * 100))%)")
            }
        }

        // Step 3: Finalize OTA
        print("[OTA] All chunks sent, finalizing...")

        let endUrl = URL(string: "http://\(ipAddress)/ota/end")!
        var endRequest = URLRequest(url: endUrl)
        endRequest.httpMethod = "POST"
        endRequest.timeoutInterval = 30
        await addAuthHeader(to: &endRequest, forDeviceId: deviceId)

        let (endData, endResponse) = try await session.data(for: endRequest)
        try handleAuthResponse(endResponse, data: endData, forDeviceId: deviceId)

        print("[OTA] Firmware upload complete, device restarting...")
    }

    /// Abort an in-progress OTA update
    func abortOTA(at ipAddress: String, deviceId: String) async throws {
        let url = URL(string: "http://\(ipAddress)/ota/abort")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 5
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        _ = try? await session.data(for: request)
        print("[OTA] Aborted")
    }

    /// Get OTA status
    func getOTAStatus(from ipAddress: String, deviceId: String) async throws -> OTAStatusResponse {
        let url = URL(string: "http://\(ipAddress)/ota/status")!
        var request = URLRequest(url: url)
        await addAuthHeader(to: &request, forDeviceId: deviceId)
        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)
        return try JSONDecoder().decode(OTAStatusResponse.self, from: data)
    }

    // MARK: - Device Logs

    /// Fetch device logs from the ring buffer
    func getLogs(from ipAddress: String, deviceId: String) async throws -> [String] {
        let url = URL(string: "http://\(ipAddress)/logs")!

        var request = URLRequest(url: url)
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        print("[HTTP] GET /logs")

        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)

        let logsResponse = try JSONDecoder().decode(DeviceLogsResponse.self, from: data)
        print("[HTTP] Received \(logsResponse.logs.count) log entries")
        return logsResponse.logs
    }

    /// Clear device logs
    func clearLogs(at ipAddress: String, deviceId: String) async throws {
        let url = URL(string: "http://\(ipAddress)/logs")!

        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        print("[HTTP] DELETE /logs")

        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)

        print("[HTTP] Logs cleared")
    }

    // MARK: - Factory Reset

    func factoryReset(at ipAddress: String, deviceId: String) async throws {
        let url = URL(string: "http://\(ipAddress)/factory-reset")!

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 10
        await addAuthHeader(to: &request, forDeviceId: deviceId)

        print("[HTTP] POST /factory-reset")

        let (data, response) = try await session.data(for: request)
        try handleAuthResponse(response, data: data, forDeviceId: deviceId)

        print("[HTTP] Factory reset successful, device restarting...")
    }

    // MARK: - Test Authentication

    /// Test if a password is valid for a device (tries a protected endpoint)
    func testAuthentication(password: String, ipAddress: String, deviceId: String) async throws -> Bool {
        // Try to access a simple protected endpoint with the provided password
        let url = URL(string: "http://\(ipAddress)/calibrate/status")!

        var request = URLRequest(url: url)
        request.setValue(password, forHTTPHeaderField: "X-Device-Password")
        request.timeoutInterval = 5

        print("[HTTP] Testing authentication")

        let (_, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw HTTPError.invalidResponse
        }

        return httpResponse.statusCode == 200
    }
}

// MARK: - Response Models

struct DeviceStatus: Codable {
    let state: String
    let position: Int
    let wifi: WifiStatus
    let calibration: CalibrationStatus?
    let uptime: Int

    struct WifiStatus: Codable {
        let ssid: String
        let rssi: Int
        let ip: String
    }

    struct CalibrationStatus: Codable {
        let calibrated: Bool
        let cumulativePosition: Int
        let maxPosition: Int
        let state: String
    }
}

struct DeviceInfo: Codable {
    let device: String
    let version: String
    let mac: String
    let deviceId: String
    let hostname: String
    let orientation: String?  // "left" or "right"
    let speed: Int?  // 0-4095
    let wifiSsid: String?
    let mqttBroker: String?
    let mqttPort: Int?
    let mqttUser: String?
    let passwordRequired: Bool?  // true if device has a password set

    var deviceOrientation: DeviceOrientation {
        DeviceOrientation(rawValue: orientation ?? "left") ?? .left
    }

    var servoSpeed: Int {
        speed ?? 500  // Default speed
    }

    var requiresPassword: Bool {
        passwordRequired ?? false
    }
}

struct CalibrationStatusResponse: Codable {
    let calibrated: Bool
    let position: Int
    let maxPosition: Int
    let calibrationState: String
}

struct DeviceLogsResponse: Codable {
    let logs: [String]
}

// MARK: - Errors

struct OTAResponse: Codable {
    let success: Bool
    let message: String?
    let error: String?
}

struct OTAStatusResponse: Codable {
    let inProgress: Bool
    let received: Int
    let total: Int
    let progress: Int?
    let freeHeap: Int?
}

// MARK: - Upload Progress Delegate

class UploadProgressDelegate: NSObject, URLSessionTaskDelegate {
    let progressHandler: (Double) -> Void

    init(progressHandler: @escaping (Double) -> Void) {
        self.progressHandler = progressHandler
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        let progress = Double(totalBytesSent) / Double(totalBytesExpectedToSend)
        DispatchQueue.main.async {
            self.progressHandler(progress)
        }
    }
}

// MARK: - Errors

enum HTTPError: LocalizedError {
    case noIPAddress
    case invalidResponse
    case serverError(Int, String)
    case authenticationRequired(String)  // Device ID that needs auth

    var errorDescription: String? {
        switch self {
        case .noIPAddress:
            return "Device IP address not available"
        case .invalidResponse:
            return "Invalid response from device"
        case .serverError(let code, let message):
            return "Server error (\(code)): \(message)"
        case .authenticationRequired:
            return "Authentication required"
        }
    }

    var isAuthenticationError: Bool {
        if case .authenticationRequired = self { return true }
        if case .serverError(401, _) = self { return true }
        return false
    }
}

// MARK: - CRC32 Extension

extension Data {
    /// Calculate CRC32 checksum
    func crc32() -> UInt32 {
        let table: [UInt32] = (0..<256).map { i in
            (0..<8).reduce(UInt32(i)) { crc, _ in
                (crc & 1) == 1 ? 0xEDB88320 ^ (crc >> 1) : crc >> 1
            }
        }

        var crc: UInt32 = 0xFFFFFFFF
        for byte in self {
            let index = Int((crc ^ UInt32(byte)) & 0xFF)
            crc = table[index] ^ (crc >> 8)
        }
        return crc ^ 0xFFFFFFFF
    }
}

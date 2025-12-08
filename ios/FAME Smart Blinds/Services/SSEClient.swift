import Foundation
import Combine

/// SSE endpoint types
enum SSEEndpoint: String {
    case status = "/events"      // For device status updates
    case logs = "/events/logs"   // For log streaming
}

/// Server-Sent Events client for real-time device updates.
/// Connects to the device's /events or /events/logs endpoint.
class SSEClient: NSObject, ObservableObject {
    @Published var isConnected = false
    @Published var lastError: String?

    private var urlSession: URLSession?
    private var dataTask: URLSessionDataTask?
    private var ipAddress: String?
    private var endpoint: SSEEndpoint = .status
    private var buffer = Data()

    // Callback for status updates
    var onStatusUpdate: ((DeviceStatus) -> Void)?

    // Callback for log entries
    var onLogReceived: ((String) -> Void)?

    // Reconnection
    private var reconnectTask: Task<Void, Never>?
    private var reconnectAttempts = 0
    private let maxReconnectAttempts = 10
    private var shouldReconnect = false

    override init() {
        super.init()
    }

    /// Connect to a device's SSE endpoint
    /// - Parameters:
    ///   - ipAddress: The device IP address
    ///   - endpoint: Which SSE endpoint to connect to (.status for device control, .logs for log streaming)
    func connect(to ipAddress: String, endpoint: SSEEndpoint = .status) {
        disconnect()

        self.ipAddress = ipAddress
        self.endpoint = endpoint
        self.shouldReconnect = true
        self.reconnectAttempts = 0

        startConnection()
    }

    /// Disconnect from the SSE endpoint
    func disconnect() {
        shouldReconnect = false
        reconnectTask?.cancel()
        reconnectTask = nil
        dataTask?.cancel()
        dataTask = nil
        urlSession?.invalidateAndCancel()
        urlSession = nil
        buffer = Data()

        DispatchQueue.main.async {
            self.isConnected = false
        }
    }

    private func startConnection() {
        guard let ipAddress = ipAddress else { return }
        guard shouldReconnect else { return }

        let url = URL(string: "http://\(ipAddress)\(endpoint.rawValue)")!
        print("[SSE] Connecting to \(url)")

        // Create a session configuration for streaming
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = TimeInterval.infinity
        config.timeoutIntervalForResource = TimeInterval.infinity
        config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData

        urlSession = URLSession(configuration: config, delegate: self, delegateQueue: nil)

        var request = URLRequest(url: url)
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")

        dataTask = urlSession?.dataTask(with: request)
        dataTask?.resume()
    }

    private func scheduleReconnect() {
        guard shouldReconnect else { return }
        guard reconnectAttempts < maxReconnectAttempts else {
            print("[SSE] Max reconnection attempts reached")
            return
        }

        reconnectAttempts += 1
        let delay = min(pow(2.0, Double(reconnectAttempts)), 30.0)  // Exponential backoff, max 30s
        print("[SSE] Reconnecting in \(delay) seconds (attempt \(reconnectAttempts))")

        reconnectTask = Task {
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await MainActor.run {
                self.startConnection()
            }
        }
    }

    private func processBuffer() {
        guard let string = String(data: buffer, encoding: .utf8) else { return }

        // SSE format: lines ending with \n\n
        let messages = string.components(separatedBy: "\n\n")

        // Keep the last incomplete message in the buffer
        if !string.hasSuffix("\n\n"), let lastMessage = messages.last {
            buffer = lastMessage.data(using: .utf8) ?? Data()
        } else {
            buffer = Data()
        }

        // Process complete messages
        for message in messages.dropLast(string.hasSuffix("\n\n") ? 0 : 1) {
            processMessage(message)
        }
    }

    private func processMessage(_ message: String) {
        var eventType: String?
        var data: String?

        for line in message.components(separatedBy: "\n") {
            if line.hasPrefix("event:") {
                eventType = String(line.dropFirst(6)).trimmingCharacters(in: .whitespaces)
            } else if line.hasPrefix("data:") {
                data = String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces)
            }
        }

        // Handle different event types
        if eventType == "open" {
            print("[SSE] Connection opened")
            DispatchQueue.main.async {
                self.isConnected = true
                self.reconnectAttempts = 0
            }
            return
        }

        if eventType == "status", let jsonString = data {
            parseAndDeliverStatus(jsonString)
        }

        if eventType == "log", let logEntry = data {
            DispatchQueue.main.async {
                self.onLogReceived?(logEntry)
            }
        }
    }

    private func parseAndDeliverStatus(_ jsonString: String) {
        guard let jsonData = jsonString.data(using: .utf8) else {
            print("[SSE] Failed to convert status to data")
            return
        }

        do {
            let status = try JSONDecoder().decode(DeviceStatus.self, from: jsonData)
            print("[SSE] Received status: state=\(status.state), position=\(status.calibration?.cumulativePosition ?? -1)")

            DispatchQueue.main.async {
                self.onStatusUpdate?(status)
            }
        } catch {
            print("[SSE] Failed to parse status: \(error)")
        }
    }
}

// MARK: - URLSessionDataDelegate

extension SSEClient: URLSessionDataDelegate {
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        if let httpResponse = response as? HTTPURLResponse {
            print("[SSE] Response status: \(httpResponse.statusCode)")
            if httpResponse.statusCode == 200 {
                completionHandler(.allow)
            } else {
                completionHandler(.cancel)
            }
        } else {
            completionHandler(.allow)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        buffer.append(data)
        processBuffer()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = error {
            let nsError = error as NSError
            // Ignore cancellation errors
            if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled {
                print("[SSE] Connection cancelled")
            } else {
                print("[SSE] Connection error: \(error.localizedDescription)")
                DispatchQueue.main.async {
                    self.lastError = error.localizedDescription
                }
            }
        } else {
            print("[SSE] Connection closed")
        }

        DispatchQueue.main.async {
            self.isConnected = false
        }

        // Schedule reconnection
        scheduleReconnect()
    }
}

import Foundation
import CoreBluetooth
import Combine

class BLEManager: NSObject, ObservableObject {
    static let shared = BLEManager()

    // Published state - accessed on MainActor
    @MainActor @Published var isScanning = false
    @MainActor @Published var isPoweredOn = false
    @MainActor @Published var discoveredDevices: [BlindDevice] = []
    @MainActor @Published var connectedDevice: BlindDevice?
    @MainActor @Published var connectionState: ConnectionState = .disconnected
    @MainActor @Published var scannedWifiNetworks: [WiFiNetwork] = []
    @MainActor @Published var isWifiScanning = false

    enum ConnectionState: Sendable {
        case disconnected
        case connecting
        case connected
        case disconnecting
    }

    // Callbacks
    @MainActor var onStatusUpdate: ((String) -> Void)?
    @MainActor var onConfigurationComplete: (() -> Void)?

    private var centralManager: CBCentralManager!
    private var connectedPeripheral: CBPeripheral?
    private var characteristics: [CBUUID: CBCharacteristic] = [:]

    private var scanTimer: Timer?
    private var connectionTimer: Timer?
    private let bleQueue = DispatchQueue(label: "com.famesmartblinds.ble", qos: .userInitiated)

    // Track pending notification subscriptions - we wait for these before marking connected
    @MainActor private var pendingNotificationSubscriptions: Set<CBUUID> = []

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: bleQueue)
    }

    // MARK: - Scanning

    @MainActor
    func startScanning() {
        guard isPoweredOn else {
            print("[BLE] Cannot scan - Bluetooth not powered on")
            return
        }

        print("[BLE] Starting scan for FAME Smart Blinds devices")
        discoveredDevices.removeAll()
        isScanning = true

        centralManager.scanForPeripherals(
            withServices: [Constants.BLE.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )

        // Stop scanning after timeout
        scanTimer?.invalidate()
        scanTimer = Timer.scheduledTimer(withTimeInterval: Constants.Timeout.bleScan, repeats: false) { [weak self] _ in
            Task { @MainActor in
                self?.stopScanning()
            }
        }
    }

    /// Start a fresh BLE scan with cache mitigation.
    /// Used by SetupView to get fresh advertisement data and avoid stale cached entries.
    @MainActor
    func startFreshScan() {
        guard isPoweredOn else {
            NSLog("[BLE] Cannot scan - Bluetooth not powered on")
            return
        }

        // Stop any existing scan first
        if isScanning {
            centralManager.stopScan()
        }

        NSLog("[BLE] Starting FRESH scan for FAME Smart Blinds devices")
        discoveredDevices.removeAll()
        isScanning = true

        // Use AllowDuplicates:true initially to get fresh advertisement data
        // even for devices we've seen before (bypasses CoreBluetooth cache)
        centralManager.scanForPeripherals(
            withServices: [Constants.BLE.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )

        // After 2 seconds, switch to no-duplicates to reduce callback frequency
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
            guard let self = self, self.isScanning else { return }
            self.centralManager.stopScan()
            self.centralManager.scanForPeripherals(
                withServices: [Constants.BLE.serviceUUID],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
            )
        }

        // Stop scanning after timeout
        scanTimer?.invalidate()
        scanTimer = Timer.scheduledTimer(withTimeInterval: Constants.Timeout.bleScan, repeats: false) { [weak self] _ in
            Task { @MainActor in
                self?.stopScanning()
            }
        }
    }

    @MainActor
    func stopScanning() {
        print("[BLE] Stopping scan")
        isScanning = false
        centralManager.stopScan()
        scanTimer?.invalidate()
        scanTimer = nil
    }

    // MARK: - Connection

    @MainActor
    func connect(to device: BlindDevice) {
        guard let peripheral = device.peripheral else {
            print("[BLE] No peripheral to connect")
            return
        }

        print("[BLE] Connecting to \(device.name)")
        connectionState = .connecting
        connectedPeripheral = peripheral
        connectedDevice = device

        centralManager.connect(peripheral, options: nil)

        // Connection timeout
        connectionTimer?.invalidate()
        connectionTimer = Timer.scheduledTimer(withTimeInterval: Constants.Timeout.bleConnection, repeats: false) { [weak self] _ in
            Task { @MainActor in
                self?.handleConnectionTimeout()
            }
        }
    }

    @MainActor
    func disconnect() {
        guard let peripheral = connectedPeripheral else { return }

        print("[BLE] Disconnecting")
        connectionState = .disconnecting
        centralManager.cancelPeripheralConnection(peripheral)
    }

    @MainActor
    private func handleConnectionTimeout() {
        print("[BLE] Connection timeout")
        if let peripheral = connectedPeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        connectionState = .disconnected
        connectedDevice?.bleConnected = false
    }

    // MARK: - Read/Write Operations

    @MainActor
    func readCharacteristic(_ uuid: CBUUID) {
        guard let characteristic = characteristics[uuid] else {
            print("[BLE] Characteristic \(uuid) not found")
            return
        }
        connectedPeripheral?.readValue(for: characteristic)
    }

    @MainActor
    func writeCharacteristic(_ uuid: CBUUID, value: String) {
        guard let characteristic = characteristics[uuid] else {
            NSLog("[BLE] Cannot write to %@ - characteristic not found (have %d characteristics stored)",
                  uuid.uuidString, characteristics.count)
            return
        }
        guard let data = value.data(using: .utf8) else {
            NSLog("[BLE] Cannot write to %@ - failed to encode value", uuid.uuidString)
            return
        }

        let type: CBCharacteristicWriteType = characteristic.properties.contains(.writeWithoutResponse)
            ? .withoutResponse : .withResponse

        connectedPeripheral?.writeValue(data, for: characteristic, type: type)
        NSLog("[BLE] Wrote '%@' to %@", value, uuid.uuidString)
    }

    // MARK: - Configuration Methods

    @MainActor
    func configureWifi(ssid: String, password: String) {
        print("[BLE] Configuring WiFi - SSID: \(ssid)")
        writeCharacteristic(Constants.BLE.wifiSsidUUID, value: ssid)

        // Small delay before writing password
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 300_000_000) // 0.3 seconds
            self.writeCharacteristic(Constants.BLE.wifiPasswordUUID, value: password)
        }
    }

    @MainActor
    func configureMqtt(broker: String, port: Int = 1883) {
        let value = "\(broker):\(port)"
        print("[BLE] Configuring MQTT - \(value)")
        writeCharacteristic(Constants.BLE.mqttBrokerUUID, value: value)
    }

    @MainActor
    func configureDeviceName(_ name: String) {
        print("[BLE] Configuring device name - \(name)")
        writeCharacteristic(Constants.BLE.deviceNameUUID, value: name)
    }

    @MainActor
    func configureDevicePassword(_ password: String) {
        print("[BLE] Configuring device password")
        writeCharacteristic(Constants.BLE.devicePasswordUUID, value: password)
    }

    @MainActor
    func configureOrientation(_ orientation: DeviceOrientation) {
        print("[BLE] Configuring orientation - \(orientation.rawValue)")
        writeCharacteristic(Constants.BLE.orientationUUID, value: orientation.rawValue)
    }

    @MainActor
    func sendCommand(_ command: BlindCommand) {
        print("[BLE] Sending command - \(command.rawValue)")
        writeCharacteristic(Constants.BLE.commandUUID, value: command.rawValue)
    }

    // MARK: - WiFi Scanning

    private var wifiScanTimeoutTask: Task<Void, Never>?

    @MainActor
    func triggerWifiScan() {
        NSLog("[BLE-WIFI-SCAN] Triggering WiFi network scan via BLE (characteristics count: %d, connected: %@)",
              characteristics.count, connectionState == .connected ? "YES" : "NO")

        // Log all available characteristics for debugging
        let uuids = characteristics.keys.map { $0.uuidString }.sorted()
        NSLog("[BLE-WIFI-SCAN] Available characteristics: %@", uuids.joined(separator: ", "))

        // Check if the scan trigger characteristic exists
        let hasTrigger = characteristics[Constants.BLE.wifiScanTriggerUUID] != nil
        let hasResults = characteristics[Constants.BLE.wifiScanResultsUUID] != nil
        NSLog("[BLE-WIFI-SCAN] Has scan trigger: %@, Has scan results: %@",
              hasTrigger ? "YES" : "NO", hasResults ? "YES" : "NO")

        if !hasTrigger {
            NSLog("[BLE-WIFI-SCAN] ERROR: Scan trigger characteristic not found! Device may need firmware update.")
        }

        isWifiScanning = true
        scannedWifiNetworks = []

        // Cancel any previous timeout
        wifiScanTimeoutTask?.cancel()

        // This writes to the BLE characteristic to tell the ESP32 to scan for WiFi networks
        writeCharacteristic(Constants.BLE.wifiScanTriggerUUID, value: "SCAN")

        // Set a timeout - WiFi scan should complete within 20 seconds
        wifiScanTimeoutTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 20_000_000_000) // 20 seconds
            if !Task.isCancelled && isWifiScanning {
                NSLog("[BLE-WIFI-SCAN] Timeout - no WiFi networks received from device")
                isWifiScanning = false
            }
        }
    }

    @MainActor
    private func parseWifiScanResults(_ jsonString: String) {
        NSLog("[BLE] Parsing WiFi scan results: %@", jsonString)

        // Cancel timeout since we received results
        wifiScanTimeoutTask?.cancel()
        isWifiScanning = false

        guard let data = jsonString.data(using: .utf8) else {
            NSLog("[BLE] Failed to convert WiFi scan results to data")
            return
        }

        do {
            let response = try JSONDecoder().decode(WiFiScanResponse.self, from: data)
            // Sort by signal strength (strongest first)
            scannedWifiNetworks = response.networks.sorted { $0.rssi > $1.rssi }
            NSLog("[BLE] Parsed %d WiFi networks", scannedWifiNetworks.count)
        } catch {
            NSLog("[BLE] Failed to decode WiFi scan results: %@", error.localizedDescription)
        }
    }

    // MARK: - Helpers

    @MainActor
    func enableStatusNotifications() {
        guard let characteristic = characteristics[Constants.BLE.statusUUID] else { return }
        connectedPeripheral?.setNotifyValue(true, for: characteristic)
    }
}

// MARK: - CBCentralManagerDelegate

extension BLEManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let poweredOn = central.state == .poweredOn
        print("[BLE] State updated: \(central.state.rawValue), powered on: \(poweredOn)")

        Task { @MainActor in
            self.isPoweredOn = poweredOn
            if !poweredOn {
                self.isScanning = false
                self.connectionState = .disconnected
            }
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        // Use the LOCAL NAME from advertisement data - this is NOT cached by CoreBluetooth
        let advertisedName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let cachedName = peripheral.name

        print("[BLE] Discovered - advertised: '\(advertisedName ?? "nil")' cached: '\(cachedName ?? "nil")' RSSI: \(RSSI)")

        // ONLY use advertisedName - ignore cached entries entirely
        // This prevents stale CoreBluetooth cache entries from appearing
        guard let actualName = advertisedName, !actualName.isEmpty else {
            print("[BLE] Skipping - no advertised name (cached: \(cachedName ?? "nil"))")
            return
        }

        // Update the central device registry (single source of truth, keyed by deviceId)
        Task { @MainActor in
            DeviceRegistry.shared.updateFromBLE(
                peripheral: peripheral,
                advertisedName: actualName,
                rssi: RSSI.intValue
            )
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        print("[BLE] Connected to \(peripheral.name ?? "device") - starting service discovery")

        Task { @MainActor in
            self.connectionTimer?.invalidate()
            // Don't set connectionState to .connected yet - wait for characteristic discovery
            // This ensures characteristics are available before the WiFi config step triggers a scan
            self.connectedDevice?.bleConnected = true

            peripheral.delegate = self
            peripheral.discoverServices([Constants.BLE.serviceUUID])
        }
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        print("[BLE] Failed to connect: \(error?.localizedDescription ?? "unknown")")

        Task { @MainActor in
            self.connectionTimer?.invalidate()
            self.connectionState = .disconnected
            self.connectedDevice?.bleConnected = false
            self.connectedDevice = nil
            self.connectedPeripheral = nil
        }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        print("[BLE] Disconnected: \(error?.localizedDescription ?? "clean disconnect")")

        Task { @MainActor in
            self.connectionState = .disconnected
            self.connectedDevice?.bleConnected = false
            self.connectedDevice = nil
            self.connectedPeripheral = nil
            self.characteristics.removeAll()
            self.pendingNotificationSubscriptions.removeAll()
        }
    }
}

// MARK: - CBPeripheralDelegate

extension BLEManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error = error {
            print("[BLE] Error discovering services: \(error)")
            return
        }

        guard let services = peripheral.services else { return }

        for service in services {
            print("[BLE] Discovered service: \(service.uuid)")
            if service.uuid == Constants.BLE.serviceUUID {
                peripheral.discoverCharacteristics(Constants.BLE.allCharacteristicUUIDs, for: service)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error = error {
            print("[BLE] Error discovering characteristics: \(error)")
            return
        }

        guard let discoveredCharacteristics = service.characteristics else { return }

        // Store characteristics FIRST on MainActor, then enable notifications
        // This ensures characteristics are available before any writes are attempted
        Task { @MainActor in
            for characteristic in discoveredCharacteristics {
                NSLog("[BLE] Storing characteristic: %@", characteristic.uuid.uuidString)
                self.characteristics[characteristic.uuid] = characteristic
            }

            // Track which notifications we need to wait for before marking connected
            self.pendingNotificationSubscriptions.removeAll()

            // Now process characteristics (read initial values, enable notifications)
            // This runs on MainActor but CBPeripheral methods are safe to call from any queue
            for characteristic in discoveredCharacteristics {
                // Read initial values for readable characteristics
                if characteristic.properties.contains(.read) {
                    peripheral.readValue(for: characteristic)
                }

                // Enable notifications for status and WiFi scan results
                // We track WiFi scan results as a required subscription before marking connected
                if characteristic.properties.contains(.notify) {
                    if characteristic.uuid == Constants.BLE.statusUUID {
                        NSLog("[BLE] Subscribing to notifications for status characteristic")
                        peripheral.setNotifyValue(true, for: characteristic)
                    } else if characteristic.uuid == Constants.BLE.wifiScanResultsUUID {
                        NSLog("[BLE] Subscribing to notifications for WiFi scan results characteristic (REQUIRED)")
                        self.pendingNotificationSubscriptions.insert(characteristic.uuid)
                        peripheral.setNotifyValue(true, for: characteristic)
                    }
                }
            }

            // If no pending subscriptions, we can mark connected immediately
            // Otherwise, wait for didUpdateNotificationStateFor callbacks
            if self.pendingNotificationSubscriptions.isEmpty {
                NSLog("[BLE] No pending subscriptions - setting state to connected")
                self.connectionState = .connected
            } else {
                NSLog("[BLE] Waiting for %d notification subscription(s) to complete before marking connected",
                      self.pendingNotificationSubscriptions.count)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            NSLog("[BLE] Error reading characteristic %@: %@", characteristic.uuid.uuidString, error.localizedDescription)
            return
        }

        guard let data = characteristic.value,
              let value = String(data: data, encoding: .utf8) else {
            NSLog("[BLE] Could not decode value for characteristic: %@", characteristic.uuid.uuidString)
            return
        }

        NSLog("[BLE] didUpdateValueFor %@ = '%@'", characteristic.uuid.uuidString, value)

        Task { @MainActor in
            switch characteristic.uuid {
            case Constants.BLE.wifiSsidUUID:
                self.connectedDevice?.wifiSsid = value
            case Constants.BLE.deviceNameUUID:
                self.connectedDevice?.name = value
            case Constants.BLE.mqttBrokerUUID:
                self.connectedDevice?.mqttBroker = value
            case Constants.BLE.statusUUID:
                NSLog("[BLE] Status characteristic updated: '%@', has callback: %@", value, self.onStatusUpdate != nil ? "YES" : "NO")
                self.connectedDevice?.updateFromStatus(value)
                self.onStatusUpdate?(value)
            case Constants.BLE.wifiScanResultsUUID:
                NSLog("[BLE] WiFi scan results received")
                self.parseWifiScanResults(value)
            default:
                break
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            print("[BLE] Error writing characteristic: \(error)")
            return
        }
        print("[BLE] Successfully wrote to \(characteristic.uuid)")
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        // Check if this was a required subscription we were waiting for
        Task { @MainActor in
            if let error = error {
                NSLog("[BLE] Error updating notification state for %@: %@", characteristic.uuid.uuidString, error.localizedDescription)

                // If subscription failed for a required characteristic, remove it from pending
                // and proceed anyway - we'll fall back to polling
                if self.pendingNotificationSubscriptions.contains(characteristic.uuid) {
                    self.pendingNotificationSubscriptions.remove(characteristic.uuid)
                    NSLog("[BLE] Subscription failed for %@ - removing from pending (will use polling fallback), remaining: %d",
                          characteristic.uuid.uuidString,
                          self.pendingNotificationSubscriptions.count)

                    // If all pending subscriptions are resolved (success or failure), mark as connected
                    if self.pendingNotificationSubscriptions.isEmpty && self.connectionState != .connected {
                        NSLog("[BLE] All subscriptions resolved - setting state to connected (some may have failed)")
                        self.connectionState = .connected
                    }
                }
                return
            }

            NSLog("[BLE] Notification state updated for %@: isNotifying=%@",
                  characteristic.uuid.uuidString,
                  characteristic.isNotifying ? "true" : "false")

            if characteristic.isNotifying && self.pendingNotificationSubscriptions.contains(characteristic.uuid) {
                self.pendingNotificationSubscriptions.remove(characteristic.uuid)
                NSLog("[BLE] Required subscription confirmed for %@, remaining: %d",
                      characteristic.uuid.uuidString,
                      self.pendingNotificationSubscriptions.count)

                // If all required subscriptions are confirmed, mark as connected
                if self.pendingNotificationSubscriptions.isEmpty && self.connectionState != .connected {
                    NSLog("[BLE] All required subscriptions confirmed - setting state to connected")
                    self.connectionState = .connected
                }
            }
        }
    }
}

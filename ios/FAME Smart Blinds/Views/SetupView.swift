import SwiftUI

struct SetupView: View {
    @EnvironmentObject var bleManager: BLEManager
    @EnvironmentObject var discovery: DeviceDiscovery
    @EnvironmentObject var registry: DeviceRegistry
    @Environment(\.dismiss) private var dismiss

    @State private var selectedDevice: BlindDevice?
    @State private var setupStep: SetupStep = .selectDevice
    @State private var setupComplete = false

    enum SetupStep {
        case selectDevice
        case connectBLE
        case configureWifi
        case configureName
        case configureOrientation
        case configurePassword
        case configureMqtt
        case complete
    }

    var body: some View {
        NavigationStack {
            VStack {
                // Progress indicator
                ProgressView(value: progressValue)
                    .padding(.horizontal)

                switch setupStep {
                case .selectDevice:
                    SelectDeviceStep(
                        selectedDevice: $selectedDevice,
                        onContinue: {
                            if let device = selectedDevice {
                                // Mark device as in setup to prevent HTTP discovery from
                                // updating it and causing it to appear in configured devices
                                registry.markDeviceInSetup(deviceId: device.deviceId)
                                bleManager.connect(to: device)
                                setupStep = .connectBLE
                            }
                        }
                    )
                case .connectBLE:
                    ConnectingStep(device: selectedDevice)
                case .configureWifi:
                    WiFiConfigStep(
                        device: selectedDevice,
                        onContinue: { setupStep = .configureName }
                    )
                case .configureName:
                    DeviceNameConfigStep(
                        device: selectedDevice,
                        onContinue: { setupStep = .configureOrientation }
                    )
                case .configureOrientation:
                    OrientationConfigStep(
                        device: selectedDevice,
                        onContinue: { setupStep = .configurePassword }
                    )
                case .configurePassword:
                    DevicePasswordConfigStep(
                        device: selectedDevice,
                        onContinue: { setupStep = .configureMqtt },
                        onSkip: { setupStep = .configureMqtt }
                    )
                case .configureMqtt:
                    MqttConfigStep(
                        device: selectedDevice,
                        onContinue: { setupStep = .complete },
                        onSkip: { setupStep = .complete }
                    )
                case .complete:
                    SetupCompleteStep(
                        device: selectedDevice,
                        onDone: { finishSetup() }
                    )
                }
            }
            .navigationTitle("Device Setup")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if setupStep != .selectDevice {
                        Button("Cancel") {
                            resetSetup()
                        }
                    }
                }

                // Refresh button for BLE scanning (only on device selection step)
                ToolbarItem(placement: .primaryAction) {
                    if setupStep == .selectDevice {
                        Button {
                            registry.clearBLEOnlyDevices()
                            bleManager.startFreshScan()
                        } label: {
                            if bleManager.isScanning {
                                ProgressView()
                                    .controlSize(.small)
                            } else {
                                Image(systemName: "arrow.clockwise")
                            }
                        }
                        .disabled(bleManager.isScanning)
                    }
                }
            }
            .onChange(of: bleManager.connectionState) { oldValue, newValue in
                if newValue == .connected && setupStep == .connectBLE {
                    setupStep = .configureWifi
                } else if newValue == .disconnected && setupStep == .connectBLE {
                    setupStep = .selectDevice
                }
            }
        }
    }

    private var progressValue: Double {
        switch setupStep {
        case .selectDevice: return 0.0
        case .connectBLE: return 0.125
        case .configureWifi: return 0.25
        case .configureName: return 0.375
        case .configureOrientation: return 0.5
        case .configurePassword: return 0.625
        case .configureMqtt: return 0.75
        case .complete: return 1.0
        }
    }

    private func resetSetup() {
        // Clear setup flag so HTTP discovery can update this device again
        registry.markDeviceInSetup(deviceId: nil)
        bleManager.disconnect()
        selectedDevice = nil
        setupStep = .selectDevice
    }

    private func finishSetup() {
        // Send restart command to apply all settings (especially new BLE/mDNS name)
        bleManager.sendCommand(.restart)

        // Start cooldown to prevent premature scanning while device restarts
        registry.startScanCooldown(seconds: 6)

        // Disconnect BLE (connection will be lost anyway after restart)
        bleManager.disconnect()

        // Clear setup flag so HTTP discovery can update this device again
        registry.markDeviceInSetup(deviceId: nil)

        // Clear old discovered devices
        registry.clear()

        // Schedule mDNS discovery restart after 2 seconds to find the newly configured device
        discovery.triggerDelayedDiscovery(afterSeconds: 2)

        // Dismiss the setup view to return to device list
        dismiss()
    }
}

// MARK: - Select Device Step

struct SelectDeviceStep: View {
    @EnvironmentObject var bleManager: BLEManager
    @EnvironmentObject var registry: DeviceRegistry
    @Binding var selectedDevice: BlindDevice?
    let onContinue: () -> Void

    /// BLE-discoverable devices that need setup (have peripheral but no WiFi connection)
    var availableDevices: [BlindDevice] {
        registry.deviceList.filter { device in
            // Show devices found via BLE that don't have a working WiFi connection
            device.peripheral != nil && device.ipAddress == nil
        }
    }

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.system(size: 64))
                .foregroundColor(.accentColor)

            Text("Select a Device")
                .font(.title2)
                .fontWeight(.semibold)

            Text("Choose a FAME Smart Blinds device to set up")
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            if availableDevices.isEmpty {
                if bleManager.isScanning {
                    ProgressView("Scanning for devices...")
                } else {
                    VStack(spacing: 16) {
                        Text("No unconfigured devices found")
                            .foregroundColor(.secondary)
                        Button("Scan for Devices") {
                            registry.clearBLEOnlyDevices()
                            bleManager.startFreshScan()
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
            } else {
                List(availableDevices, selection: $selectedDevice) { device in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(device.name)
                                .font(.headline)
                            if device.rssi != 0 {
                                Text("Signal: \(device.rssi) dBm")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                        Spacer()
                        if selectedDevice?.id == device.id {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(.accentColor)
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        selectedDevice = device
                    }
                }
                #if os(iOS)
                .listStyle(.insetGrouped)
                #endif
                .frame(maxHeight: 300)
            }

            Spacer()

            Button("Continue") {
                onContinue()
            }
            .buttonStyle(.borderedProminent)
            .disabled(selectedDevice == nil)
        }
        .padding()
        .onAppear {
            // Clear previous BLE scan results and start fresh
            registry.clearBLEOnlyDevices()
            bleManager.startFreshScan()
        }
    }
}

// MARK: - Connecting Step

struct ConnectingStep: View {
    let device: BlindDevice?
    @EnvironmentObject var bleManager: BLEManager

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            ProgressView()
                .scaleEffect(2)

            Text("Connecting...")
                .font(.title2)
                .fontWeight(.semibold)

            Text("Connecting to \(device?.name ?? "device")")
                .foregroundColor(.secondary)

            Spacer()
        }
        .padding()
    }
}

// MARK: - WiFi Config Step

struct WiFiConfigStep: View {
    let device: BlindDevice?
    let onContinue: () -> Void

    @EnvironmentObject var bleManager: BLEManager
    @State private var ssid = ""
    @State private var password = ""
    @State private var isConfiguring = false
    @State private var configStatus = ""
    @State private var connectionFailed = false
    @State private var timeoutTask: DispatchWorkItem?
    @State private var pollingTimer: Timer?

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "wifi")
                .font(.system(size: 64))
                .foregroundColor(connectionFailed ? .red : .accentColor)

            Text(connectionFailed ? "Connection Failed" : "Configure WiFi")
                .font(.title2)
                .fontWeight(.semibold)

            Text(connectionFailed
                 ? "Could not connect to the WiFi network. Please check your credentials and try again."
                 : "Enter your WiFi credentials to connect the device to your network")
                .foregroundColor(connectionFailed ? .red : .secondary)
                .multilineTextAlignment(.center)

            VStack(spacing: 16) {
                TextField("WiFi Network Name (SSID)", text: $ssid)
                    .textFieldStyle(.roundedBorder)
                    #if os(iOS)
                    .autocapitalization(.none)
                    #endif
                    .disableAutocorrection(true)

                SecureField("Password", text: $password)
                    .textFieldStyle(.roundedBorder)
            }
            .padding(.horizontal)

            if !configStatus.isEmpty {
                HStack(spacing: 8) {
                    if isConfiguring {
                        ProgressView()
                            .controlSize(.small)
                    }
                    Text(configStatus)
                        .font(.caption)
                        .foregroundColor(connectionFailed ? .red : .secondary)
                }
            }

            Spacer()

            Button(isConfiguring ? "Connecting..." : (connectionFailed ? "Retry" : "Connect")) {
                configureWifi()
            }
            .buttonStyle(.borderedProminent)
            .disabled(ssid.isEmpty || isConfiguring)
        }
        .padding()
        .onAppear {
            // Pre-fill with existing SSID if available
            if let existingSsid = device?.wifiSsid, !existingSsid.isEmpty {
                ssid = existingSsid
            }
        }
        .onDisappear {
            // Cancel any pending timeout and polling
            timeoutTask?.cancel()
            pollingTimer?.invalidate()
            pollingTimer = nil
            bleManager.onStatusUpdate = nil
        }
    }

    private func configureWifi() {
        // Reset state
        connectionFailed = false
        isConfiguring = true
        configStatus = "Sending credentials..."

        // Cancel any previous timeout and polling
        timeoutTask?.cancel()
        pollingTimer?.invalidate()

        bleManager.configureWifi(ssid: ssid, password: password)

        // Handler for status updates (used by both notifications and polling)
        let handleStatus: (String) -> Void = { [self] status in
            NSLog("[WiFiConfig] Status update: '%@'", status)

            if status.contains("wifi_connected") || status.hasPrefix("wifi:1") || status.contains("wifi:172.") || status.contains("wifi:192.") || status.contains("wifi:10.") {
                // Successfully connected!
                NSLog("[WiFiConfig] WiFi connected! Proceeding to next step")
                cleanup()
                configStatus = "Connected!"
                isConfiguring = false
                onContinue()
            } else if status.contains("wifi_failed") {
                // Firmware explicitly reports connection failed
                NSLog("[WiFiConfig] WiFi connection failed")
                cleanup()
                configStatus = "Connection failed. Check your credentials."
                isConfiguring = false
                connectionFailed = true
            } else if status.contains("wifi_connecting") || status.contains("wifi:connecting") {
                configStatus = "Connecting to network..."
            }
        }

        // Set up notification callback (may not work due to iOS GATT cache issues)
        bleManager.onStatusUpdate = handleStatus

        // Also poll the status characteristic every second as a fallback
        // This handles the case where BLE notifications fail due to GATT cache
        pollingTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [self] _ in
            bleManager.readCharacteristic(Constants.BLE.statusUUID)
        }

        // Timeout after 20 seconds - covers the firmware's 15-second connection timeout
        let timeout = DispatchWorkItem { [self] in
            if isConfiguring {
                cleanup()
                configStatus = "Connection timed out. Check your credentials."
                isConfiguring = false
                connectionFailed = true
            }
        }
        timeoutTask = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 20, execute: timeout)
    }

    private func cleanup() {
        timeoutTask?.cancel()
        pollingTimer?.invalidate()
        pollingTimer = nil
        bleManager.onStatusUpdate = nil
    }
}

// MARK: - Device Name Config Step

struct DeviceNameConfigStep: View {
    let device: BlindDevice?
    let onContinue: () -> Void

    @EnvironmentObject var bleManager: BLEManager
    @State private var deviceName = ""
    @State private var isConfiguring = false

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "tag")
                .font(.system(size: 64))
                .foregroundColor(.accentColor)

            Text("Name Your Device")
                .font(.title2)
                .fontWeight(.semibold)

            Text("Give your blind controller a friendly name to identify it easily")
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            VStack(spacing: 16) {
                TextField("Device Name (e.g., Living Room Blinds)", text: $deviceName)
                    .textFieldStyle(.roundedBorder)
                    .disableAutocorrection(true)
            }
            .padding(.horizontal)

            Text("This name will be shown in the app and Home Assistant")
                .font(.caption)
                .foregroundColor(.secondary)

            Spacer()

            Button(isConfiguring ? "Saving..." : "Save & Continue") {
                configureDeviceName()
            }
            .buttonStyle(.borderedProminent)
            .disabled(deviceName.isEmpty || isConfiguring)
        }
        .padding()
        .onAppear {
            // Pre-fill with existing name or a default suggestion
            if let existingName = device?.name, !existingName.isEmpty {
                // If it's the default format like "FAMEBlinds_abc123", suggest a friendlier default
                if existingName.hasPrefix("FAMEBlinds_") {
                    deviceName = "My Blinds"
                } else {
                    // Strip the deviceId suffix if present for editing
                    deviceName = stripDeviceIdSuffix(from: existingName)
                }
            } else {
                deviceName = "My Blinds"
            }
        }
    }

    /// Strip the _deviceId suffix from a name for display in the text field
    private func stripDeviceIdSuffix(from name: String) -> String {
        // Look for underscore followed by 8 hex characters at the end
        if let underscoreIndex = name.lastIndex(of: "_") {
            let suffix = String(name[name.index(after: underscoreIndex)...])
            if suffix.count == 8 && suffix.allSatisfy({ $0.isHexDigit }) {
                return String(name[..<underscoreIndex])
            }
        }
        return name
    }

    private func configureDeviceName() {
        isConfiguring = true
        bleManager.configureDeviceName(deviceName)

        // Update local device model with full name including deviceId suffix
        // This matches what the firmware will use for BLE/mDNS advertising
        if let deviceId = device?.deviceId, !deviceId.isEmpty {
            device?.name = "\(deviceName)_\(deviceId)"
        } else {
            device?.name = deviceName
        }

        // Wait briefly then continue
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            isConfiguring = false
            onContinue()
        }
    }
}

// MARK: - Orientation Config Step

struct OrientationConfigStep: View {
    let device: BlindDevice?
    let onContinue: () -> Void

    @EnvironmentObject var bleManager: BLEManager
    @State private var selectedOrientation: DeviceOrientation = .left
    @State private var isConfiguring = false

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "arrow.left.and.right")
                .font(.system(size: 64))
                .foregroundColor(.accentColor)

            Text("Device Orientation")
                .font(.title2)
                .fontWeight(.semibold)

            Text("Which side of the window is the servo mounted on? This affects the direction of the open/close controls.")
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            VStack(spacing: 16) {
                ForEach(DeviceOrientation.allCases, id: \.self) { orientation in
                    Button {
                        selectedOrientation = orientation
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(orientation.displayName)
                                    .font(.headline)
                                Text(orientation.description)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            if selectedOrientation == orientation {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundColor(.accentColor)
                            } else {
                                Image(systemName: "circle")
                                    .foregroundColor(.secondary)
                            }
                        }
                        .padding()
                        .background(
                            RoundedRectangle(cornerRadius: 10)
                                .fill(selectedOrientation == orientation ?
                                      Color.accentColor.opacity(0.1) :
                                      Color(UIColor.systemGray6))
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 10)
                                .stroke(selectedOrientation == orientation ?
                                        Color.accentColor : Color.clear, lineWidth: 2)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal)

            Spacer()

            Button(isConfiguring ? "Saving..." : "Save & Continue") {
                configureOrientation()
            }
            .buttonStyle(.borderedProminent)
            .disabled(isConfiguring)
        }
        .padding()
    }

    private func configureOrientation() {
        isConfiguring = true
        bleManager.configureOrientation(selectedOrientation)

        // Wait briefly then continue
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            isConfiguring = false
            onContinue()
        }
    }
}

// MARK: - Device Password Config Step

struct DevicePasswordConfigStep: View {
    let device: BlindDevice?
    let onContinue: () -> Void
    let onSkip: () -> Void

    @EnvironmentObject var bleManager: BLEManager
    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var isConfiguring = false

    var passwordsMatch: Bool {
        !password.isEmpty && password == confirmPassword
    }

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "lock.shield")
                .font(.system(size: 64))
                .foregroundColor(.accentColor)

            Text("Set Device Password")
                .font(.title2)
                .fontWeight(.semibold)

            Text("Optionally set a password to secure access to your device")
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            VStack(spacing: 16) {
                SecureField("Password", text: $password)
                    .textFieldStyle(.roundedBorder)

                SecureField("Confirm Password", text: $confirmPassword)
                    .textFieldStyle(.roundedBorder)

                if !password.isEmpty && !confirmPassword.isEmpty && !passwordsMatch {
                    Text("Passwords don't match")
                        .font(.caption)
                        .foregroundColor(.red)
                }
            }
            .padding(.horizontal)

            Text("Leave blank to allow open access")
                .font(.caption)
                .foregroundColor(.secondary)

            Spacer()

            VStack(spacing: 12) {
                Button(isConfiguring ? "Saving..." : "Save & Continue") {
                    configurePassword()
                }
                .buttonStyle(.borderedProminent)
                .disabled(!password.isEmpty && !passwordsMatch || isConfiguring)

                Button("Skip - No Password") {
                    onSkip()
                }
                .foregroundColor(.secondary)
            }
        }
        .padding()
    }

    private func configurePassword() {
        guard password.isEmpty || passwordsMatch else { return }

        isConfiguring = true

        if !password.isEmpty {
            bleManager.configureDevicePassword(password)
        }

        // Wait briefly then continue
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            isConfiguring = false
            onContinue()
        }
    }
}

// MARK: - MQTT Config Step

struct MqttConfigStep: View {
    let device: BlindDevice?
    let onContinue: () -> Void
    let onSkip: () -> Void

    @EnvironmentObject var bleManager: BLEManager
    @State private var broker = ""
    @State private var port = "1883"
    @State private var isConfiguring = false

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "network")
                .font(.system(size: 64))
                .foregroundColor(.accentColor)

            Text("Configure Home Assistant")
                .font(.title2)
                .fontWeight(.semibold)

            Text("Enter your MQTT broker address to enable Home Assistant integration")
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            VStack(spacing: 16) {
                TextField("MQTT Broker (e.g., 192.168.1.50)", text: $broker)
                    .textFieldStyle(.roundedBorder)
                    #if os(iOS)
                    .autocapitalization(.none)
                    .keyboardType(.decimalPad)
                    #endif
                    .disableAutocorrection(true)

                TextField("Port", text: $port)
                    .textFieldStyle(.roundedBorder)
                    #if os(iOS)
                    .keyboardType(.numberPad)
                    #endif
            }
            .padding(.horizontal)

            Spacer()

            VStack(spacing: 12) {
                Button(isConfiguring ? "Configuring..." : "Save & Continue") {
                    configureMqtt()
                }
                .buttonStyle(.borderedProminent)
                .disabled(broker.isEmpty || isConfiguring)

                Button("Skip for Now") {
                    onSkip()
                }
                .foregroundColor(.secondary)
            }
        }
        .padding()
        .onAppear {
            if let existingBroker = device?.mqttBroker, !existingBroker.isEmpty {
                let parts = existingBroker.split(separator: ":")
                broker = String(parts.first ?? "")
                if parts.count > 1 {
                    port = String(parts[1])
                }
            }
        }
    }

    private func configureMqtt() {
        isConfiguring = true
        let portInt = Int(port) ?? 1883
        bleManager.configureMqtt(broker: broker, port: portInt)

        // Wait briefly then continue
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            isConfiguring = false
            onContinue()
        }
    }
}

// MARK: - Setup Complete Step

struct SetupCompleteStep: View {
    let device: BlindDevice?
    let onDone: () -> Void

    @State private var isFinishing = false

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            if isFinishing {
                ProgressView()
                    .scaleEffect(2)

                Text("Restarting Device...")
                    .font(.title2)
                    .fontWeight(.semibold)

                Text("The device is restarting with its new settings. It will appear in your device list shortly.")
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            } else {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 80))
                    .foregroundColor(.green)

                Text("Setup Complete!")
                    .font(.title)
                    .fontWeight(.bold)

                Text("Your FAME Smart Blinds device is now configured. Tap Done to restart the device and apply all settings.")
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)

                if let ip = device?.ipAddress {
                    VStack(spacing: 8) {
                        Text("Device IP Address")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text(ip)
                            .font(.headline)
                            .fontWeight(.semibold)
                    }
                    .padding()
                    .background(Color(UIColor.systemGray6))
                    .cornerRadius(8)
                }
            }

            Spacer()

            Button(isFinishing ? "Please wait..." : "Done") {
                isFinishing = true
                onDone()
            }
            .buttonStyle(.borderedProminent)
            .disabled(isFinishing)
        }
        .padding()
    }
}

#Preview {
    SetupView()
        .environmentObject(BLEManager.shared)
        .environmentObject(DeviceDiscovery.shared)
        .environmentObject(DeviceRegistry.shared)
}

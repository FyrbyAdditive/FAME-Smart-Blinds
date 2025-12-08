import SwiftUI

struct DeviceControlView: View {
    @ObservedObject var device: BlindDevice
    @EnvironmentObject var httpClient: HTTPClient
    @EnvironmentObject var discovery: DeviceDiscovery
    @Environment(\.dismiss) private var dismiss

    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var showingError = false
    @State private var showingRenameSheet = false
    @State private var newDeviceName = ""
    @State private var isRestarting = false
    @State private var showingCalibration = false
    @State private var calibrationNagDismissed = false
    @State private var showingSettings = false

    // SSE client for real-time updates
    @StateObject private var sseClient = SSEClient()

    // Fallback polling (only used if SSE fails)
    @State private var statusPollingTask: Task<Void, Never>?

    var canControlViaHTTP: Bool {
        device.ipAddress != nil
    }

    /// Device needs setup if it has no IP address (not yet connected via WiFi)
    var needsSetup: Bool {
        device.ipAddress == nil
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                // Setup required banner
                if needsSetup {
                    setupRequiredBanner
                }

                // Calibration nag banner
                if !needsSetup && !device.isCalibrated && !calibrationNagDismissed && canControlViaHTTP {
                    calibrationNagBanner
                }

                // Single column layout for all platforms
                VStack(spacing: 24) {
                    statusCard
                    controlButtons
                }
                .padding()
                #if targetEnvironment(macCatalyst)
                .frame(maxWidth: 500)
                .frame(maxWidth: .infinity)
                #endif
            }
        }
        #if targetEnvironment(macCatalyst)
        .navigationTitle("")
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(device.name)
                    .font(.headline)
            }
        }
        #else
        .navigationTitle(device.name)
        .navigationBarTitleDisplayMode(.large)
        #endif
        .alert("Error", isPresented: $showingError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "An unknown error occurred")
        }
        .sheet(isPresented: $showingRenameSheet) {
            RenameDeviceSheet(
                deviceName: $newDeviceName,
                onSave: { renameDevice() },
                onCancel: { showingRenameSheet = false }
            )
        }
        // Use fullScreenCover on Mac Catalyst for larger presentation
        #if targetEnvironment(macCatalyst)
        .fullScreenCover(isPresented: $showingCalibration) {
            CalibrationView(device: device)
                .environmentObject(httpClient)
        }
        .fullScreenCover(isPresented: $showingSettings) {
            DeviceSettingsView(device: device)
                .environmentObject(httpClient)
        }
        #else
        .sheet(isPresented: $showingCalibration) {
            CalibrationView(device: device)
                .environmentObject(httpClient)
        }
        .sheet(isPresented: $showingSettings) {
            DeviceSettingsView(device: device)
                .environmentObject(httpClient)
        }
        #endif
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button {
                        showingCalibration = true
                    } label: {
                        Label(device.isCalibrated ? "Recalibrate" : "Calibrate", systemImage: "ruler")
                    }
                    .disabled(!canControlViaHTTP)

                    Button {
                        newDeviceName = stripDeviceIdSuffix(from: device.name)
                        showingRenameSheet = true
                    } label: {
                        Label("Rename Device", systemImage: "pencil")
                    }

                    Button {
                        showingSettings = true
                    } label: {
                        Label("Settings", systemImage: "gear")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .onAppear {
            refreshStatus()
            // Reconnect SSE if not already connected (handles returning from full screen covers)
            if !sseClient.isConnected {
                startSSE()
            }
        }
        .onDisappear {
            // Only disconnect when truly leaving the view (not when showing full screen covers)
            // The full screen covers have their own SSE clients for logs
            stopSSE()
            stopPolling()
        }
        // Reconnect SSE when returning from full screen covers (settings, calibration)
        // This is needed because onAppear may not be called reliably on Mac Catalyst
        // Always force reconnect after settings/calibration because the device may have
        // restarted while we were away (e.g., firmware update, restart from settings)
        .onChange(of: showingSettings) { _, isShowing in
            if !isShowing {
                startSSE()  // Force reconnect - device may have restarted
            }
        }
        .onChange(of: showingCalibration) { _, isShowing in
            if !isShowing {
                startSSE()  // Force reconnect - device may have restarted
            }
        }
        .onChange(of: sseClient.isConnected) { _, isConnected in
            // Fall back to polling if SSE disconnects during movement
            if !isConnected && (device.state == .opening || device.state == .closing) {
                print("[SSE] Disconnected during movement, falling back to polling")
                startPollingIfNeeded()
            } else if isConnected {
                // SSE reconnected, stop polling
                stopPolling()
            }
        }
        .overlay {
            if isRestarting {
                ZStack {
                    Color.black.opacity(0.4)
                        .ignoresSafeArea()

                    VStack(spacing: 16) {
                        ProgressView()
                            .scaleEffect(1.5)
                            .tint(.white)

                        Text("Restarting Device...")
                            .font(.headline)
                            .foregroundColor(.white)

                        Text("Please wait while the device restarts with its new name")
                            .font(.subheadline)
                            .foregroundColor(.white.opacity(0.8))
                            .multilineTextAlignment(.center)
                    }
                    .padding(32)
                    .background(.ultraThinMaterial)
                    .cornerRadius(16)
                    .padding()
                }
            }
        }
    }

    private func renameDevice() {
        guard !newDeviceName.isEmpty, let ip = device.ipAddress else { return }

        showingRenameSheet = false
        isRestarting = true

        // Rename via HTTP (BLE is only used for initial setup)
        Task {
            do {
                try await httpClient.setDeviceName(newDeviceName, at: ip)
                // Restart to apply new name to mDNS
                try await httpClient.sendCommand(.restart, to: ip)
                await MainActor.run {
                    triggerRescanAfterRestart()
                }
            } catch {
                await MainActor.run {
                    isRestarting = false
                    errorMessage = "Failed to rename device: \(error.localizedDescription)"
                    showingError = true
                }
            }
        }
    }

    private func triggerRescanAfterRestart() {
        Task { @MainActor in
            // Wait for device to reboot
            try? await Task.sleep(nanoseconds: 5_000_000_000) // 5 seconds

            // Dismiss this view to go back to device list
            dismiss()

            // Clear registry to remove stale entries and rescan via mDNS
            // BLE scanning is only used during initial setup
            DeviceRegistry.shared.clear()
            discovery.startDiscovery()
        }
    }

    // MARK: - Status Card

    private var statusCard: some View {
        VStack(spacing: 0) {
            // WiFi status header
            HStack(spacing: 6) {
                Image(systemName: "wifi")
                    .font(.system(size: 14))
                    .foregroundColor(device.wifiConnected ? .green : .secondary)
                if let ip = device.ipAddress {
                    Text(ip)
                        .font(.caption)
                        .foregroundColor(.secondary)
                } else {
                    Text("Not connected")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 8)

            // Main content
            VStack(spacing: 16) {
                // Blind Icon with state
                ZStack {
                    Circle()
                        .fill(Color.accentColor.opacity(0.1))
                        .frame(width: 120, height: 120)

                    Image(systemName: blindIcon)
                        .font(.system(size: 48))
                        .foregroundColor(.accentColor)
                }

                // State
                Text(device.state.displayName)
                    .font(.title2)
                    .fontWeight(.semibold)

                // Position with progress bar (only if calibrated)
                if device.isCalibrated && device.maxPosition > 0 {
                    VStack(spacing: 8) {
                        HStack {
                            Text("Position")
                                .foregroundColor(.secondary)
                            Spacer()
                            Text("\(device.cumulativePosition) / \(device.maxPosition)")
                                .fontWeight(.medium)
                        }
                        .font(.subheadline)

                        // Progress bar
                        GeometryReader { geometry in
                            ZStack(alignment: .leading) {
                                RoundedRectangle(cornerRadius: 4)
                                    .fill(Color(.systemGray5))
                                    .frame(height: 8)

                                RoundedRectangle(cornerRadius: 4)
                                    .fill(Color.accentColor)
                                    .frame(width: progressWidth(in: geometry.size.width), height: 8)
                            }
                        }
                        .frame(height: 8)
                    }
                    .padding(.horizontal)
                }
            }
            .padding(.horizontal)
            .padding(.bottom)
        }
        .frame(maxWidth: .infinity)
        .background(cardBackground)
        .cornerRadius(16)
        .shadow(radius: 2)
    }

    private var blindIcon: String {
        switch device.state {
        case .open:
            return "blinds.horizontal.open"
        case .closed:
            return "blinds.horizontal.closed"
        case .opening, .closing:
            return "arrow.up.arrow.down"
        default:
            return "blinds.horizontal.closed"
        }
    }

    // MARK: - Control Buttons

    private var controlButtons: some View {
        VStack(spacing: 20) {
            // Up/Down arrow buttons stacked vertically
            VStack(spacing: 12) {
                // Open (Up) Button
                ArrowControlButton(
                    direction: .up,
                    isLoading: isLoading,
                    isActive: device.state == .opening
                ) {
                    sendCommand(.open)
                }

                // Close (Down) Button
                ArrowControlButton(
                    direction: .down,
                    isLoading: isLoading,
                    isActive: device.state == .closing
                ) {
                    sendCommand(.close)
                }
            }

            // Stop Button
            Button(action: { sendCommand(.stop) }) {
                HStack {
                    Image(systemName: "stop.fill")
                    Text("STOP")
                        .fontWeight(.semibold)
                }
                .font(.headline)
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 50)
                .background(Color.red)
                .cornerRadius(25)
            }
            .disabled(isLoading)
        }
        .padding(.horizontal)
        .disabled(!canControlViaHTTP)
    }

    // MARK: - Setup Required Banner

    private var setupRequiredBanner: some View {
        VStack(spacing: 16) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 48))
                .foregroundColor(.orange)

            Text("Setup Required")
                .font(.title2)
                .fontWeight(.semibold)

            Text("This device needs to be configured with WiFi credentials before it can be controlled.")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            NavigationLink {
                SetupView()
            } label: {
                Text("Go to Setup")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(Color.orange.opacity(0.1))
    }

    // MARK: - Calibration Nag Banner

    private var calibrationNagBanner: some View {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundColor(.orange)
                .font(.title2)

            VStack(alignment: .leading, spacing: 4) {
                Text("Calibration Required")
                    .font(.headline)
                Text("Calibrate your blind to enable position limits")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            Button("Calibrate") {
                showingCalibration = true
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)

            Button {
                withAnimation {
                    calibrationNagDismissed = true
                }
            } label: {
                Image(systemName: "xmark")
                    .foregroundColor(.secondary)
            }
            .buttonStyle(.plain)
        }
        .padding()
        .background(Color.orange.opacity(0.1))
        .overlay(
            Rectangle()
                .frame(height: 1)
                .foregroundColor(Color.orange.opacity(0.3)),
            alignment: .bottom
        )
    }

    private func progressWidth(in totalWidth: CGFloat) -> CGFloat {
        guard device.maxPosition > 0 else { return 0 }
        let progress = CGFloat(device.cumulativePosition) / CGFloat(device.maxPosition)
        return totalWidth * min(max(progress, 0), 1)
    }

    // MARK: - Helpers

    private var cardBackground: Color {
        Color(UIColor.systemBackground)
    }

    /// Strip the _deviceId suffix from a name for display in text fields
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

    // MARK: - Actions

    private func sendCommand(_ command: BlindCommand) {
        isLoading = true

        Task {
            do {
                if canControlViaHTTP {
                    try await httpClient.sendCommand(command, to: device.ipAddress!)

                    // Update local state optimistically
                    // SSE will provide real-time updates, no need to start polling
                    await MainActor.run {
                        switch command {
                        case .open:
                            device.state = .opening
                        case .close:
                            device.state = .closing
                        case .stop:
                            device.state = .stopped
                        case .restart:
                            break // No state change for restart
                        }
                    }
                }
                // BLE control removed - all device management is now HTTP-only after initial setup
            } catch {
                await MainActor.run {
                    errorMessage = error.localizedDescription
                    showingError = true
                }
            }

            await MainActor.run {
                isLoading = false
            }
        }
    }

    private func refreshStatus() {
        guard let ip = device.ipAddress else { return }

        Task {
            do {
                // Fetch both status and info in parallel
                async let statusTask = httpClient.getStatus(from: ip)
                async let infoTask = httpClient.getInfo(from: ip)

                let status = try await statusTask
                let info = try await infoTask

                await MainActor.run {
                    device.updateFromDeviceStatus(status)
                    device.wifiSsid = status.wifi.ssid
                    // Update the device name from the authoritative source (device info)
                    device.name = info.hostname
                }
            } catch {
                print("Failed to refresh status: \(error)")
            }
        }
    }

    // MARK: - SSE (Server-Sent Events)

    private func startSSE() {
        guard let ip = device.ipAddress else { return }

        // Set up status callback for real-time device updates
        sseClient.onStatusUpdate = { [weak device] status in
            guard let device = device else { return }
            device.updateFromDeviceStatus(status)
        }

        sseClient.connect(to: ip)
    }

    private func stopSSE() {
        sseClient.disconnect()
    }

    // MARK: - Fallback Polling

    private func startPollingIfNeeded(forState overrideState: BlindState? = nil) {
        // Only use polling as fallback when SSE is not connected
        guard !sseClient.isConnected else { return }
        // Only poll if device is moving
        let currentState = overrideState ?? device.state
        guard currentState == .opening || currentState == .closing else { return }
        guard statusPollingTask == nil else { return }  // Already polling

        statusPollingTask = Task {
            // Small delay to let the command propagate to device before first poll
            try? await Task.sleep(nanoseconds: 100_000_000)  // 100ms initial delay

            while !Task.isCancelled {
                await pollStatus()
                try? await Task.sleep(nanoseconds: 250_000_000)  // Poll every 250ms for smooth updates
            }
        }
    }

    private func stopPolling() {
        statusPollingTask?.cancel()
        statusPollingTask = nil
    }

    private func pollStatus() async {
        guard let ip = device.ipAddress else { return }

        do {
            let status = try await httpClient.getStatus(from: ip)
            print("[Poll] Received state: '\(status.state)', position: \(status.calibration?.cumulativePosition ?? -1)")
            await MainActor.run {
                let oldState = device.state
                device.updateFromDeviceStatus(status)
                print("[Poll] State changed: \(oldState) -> \(device.state)")
            }
        } catch {
            // Silently ignore polling errors - device might be temporarily unavailable
        }
    }
}

// MARK: - Arrow Control Button

struct ArrowControlButton: View {
    enum Direction {
        case up, down

        var icon: String {
            switch self {
            case .up: return "chevron.up"
            case .down: return "chevron.down"
            }
        }

        var color: Color {
            switch self {
            case .up: return .blue
            case .down: return .blue
            }
        }
    }

    let direction: Direction
    let isLoading: Bool
    let isActive: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                // Background
                RoundedRectangle(cornerRadius: 16)
                    .fill(isActive ? direction.color : Color(UIColor.secondarySystemBackground))
                    .frame(height: 80)

                // Arrow
                if isLoading && isActive {
                    ProgressView()
                        .tint(isActive ? .white : direction.color)
                        .scaleEffect(1.5)
                } else {
                    Image(systemName: direction.icon)
                        .font(.system(size: 36, weight: .bold))
                        .foregroundColor(isActive ? .white : direction.color)
                }
            }
        }
        .disabled(isLoading)
        .buttonStyle(.plain)
    }
}

// MARK: - Rename Device Sheet

struct RenameDeviceSheet: View {
    @Binding var deviceName: String
    let onSave: () -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Image(systemName: "tag")
                    .font(.system(size: 48))
                    .foregroundColor(.accentColor)

                Text("Rename Device")
                    .font(.title2)
                    .fontWeight(.semibold)

                TextField("Device Name", text: $deviceName)
                    .textFieldStyle(.roundedBorder)
                    .padding(.horizontal)

                Spacer()
            }
            .padding(.top, 32)
            .navigationTitle("Rename")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onCancel() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { onSave() }
                        .disabled(deviceName.isEmpty)
                }
            }
        }
    }
}

// MARK: - Connection Status Badge

struct ConnectionStatusBadge: View {
    let title: String
    let icon: String
    let connected: Bool
    var detail: String?

    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundColor(connected ? .green : .gray)

            Text(title)
                .font(.caption)
                .foregroundColor(.secondary)

            if let detail = detail {
                Text(detail)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
}

// MARK: - Info Row

struct InfoRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .fontWeight(.medium)
        }
        .font(.subheadline)
    }
}

#Preview {
    NavigationView {
        DeviceControlView(device: BlindDevice(name: "Test Blind", ipAddress: "192.168.1.100"))
            .environmentObject(BLEManager.shared)
            .environmentObject(HTTPClient.shared)
            .environmentObject(DeviceDiscovery.shared)
    }
}

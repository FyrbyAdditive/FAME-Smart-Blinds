import SwiftUI
import UniformTypeIdentifiers

// MARK: - Document Picker for Mac Catalyst
// SwiftUI's fileImporter is broken on Mac Catalyst - the callback never fires.
// This uses UIDocumentPickerViewController directly as a workaround.

class FirmwarePickerDelegate: NSObject, UIDocumentPickerDelegate {
    var onPick: ((URL) -> Void)?

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        print("[OTA] documentPicker didPickDocumentsAt: \(urls)")
        guard let url = urls.first else { return }
        onPick?(url)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        print("[OTA] documentPickerWasCancelled")
    }
}

struct DeviceSettingsView: View {
    @ObservedObject var device: BlindDevice
    @EnvironmentObject var httpClient: HTTPClient
    @Environment(\.dismiss) private var dismiss

    var onFactoryReset: (() -> Void)?

    @State private var firmwareVersion: String = "Unknown"
    @State private var currentOrientation: DeviceOrientation = .left
    @State private var isLoadingInfo = false
    @State private var isUploading = false
    @State private var uploadProgress: Double = 0
    @State private var uploadError: String?
    @State private var showingError = false
    @State private var showingSuccess = false
    @State private var successMessage: String = ""
    @State private var showingFactoryResetConfirmation = false
    @State private var isResetting = false
    @State private var isSavingOrientation = false
    @State private var orientationLoaded = false

    // Speed setting
    @State private var currentSpeed: Double = 500
    @State private var isSavingSpeed = false
    @State private var speedLoaded = false

    // Firmware update state
    @State private var availableUpdate: UpdateInfo?
    @State private var isCheckingUpdates = false
    @State private var isDownloading = false
    @State private var downloadProgress: Double = 0
    @State private var updateError: String?

    // Must keep delegate alive or callbacks won't fire
    @State private var pickerDelegate = FirmwarePickerDelegate()

    @State private var showingDeviceLogs = false

    // Use sheets for sub-views to avoid NavigationStack bugs on Mac Catalyst
    @State private var showingWifiConfig = false
    @State private var showingMqttConfig = false
    @State private var showingPasswordConfig = false

    var body: some View {
        NavigationStack {
            List {
                // Device Info Section
                Section("Device Information") {
                    HStack {
                        Text("Name")
                        Spacer()
                        Text(device.name)
                            .foregroundColor(.secondary)
                    }

                    HStack {
                        Text("IP Address")
                        Spacer()
                        Text(device.ipAddress ?? "Not connected")
                            .foregroundColor(.secondary)
                    }

                    HStack {
                        Text("Device ID")
                        Spacer()
                        Text(device.deviceId.isEmpty ? "Unknown" : device.deviceId)
                            .foregroundColor(.secondary)
                    }

                    if !device.macAddress.isEmpty {
                        HStack {
                            Text("MAC Address")
                            Spacer()
                            Text(device.macAddress)
                                .foregroundColor(.secondary)
                        }
                    }
                }

                // Orientation Section
                Section {
                    Picker("Mounting Side", selection: $currentOrientation) {
                        ForEach(DeviceOrientation.allCases, id: \.self) { orientation in
                            Text(orientation.displayName).tag(orientation)
                        }
                    }
                    .disabled(device.ipAddress == nil || isSavingOrientation)
                    .onChange(of: currentOrientation) { oldValue, newValue in
                        // Only save if orientation has been loaded and user actually changed it
                        if orientationLoaded && oldValue != newValue {
                            saveOrientation(newValue)
                        }
                    }
                } header: {
                    Text("Orientation")
                } footer: {
                    Text("Select which side of the window the servo is mounted on. This affects the direction of open/close controls.")
                }

                // Speed Section
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("Servo Speed")
                            Spacer()
                            if isSavingSpeed {
                                ProgressView()
                                    .scaleEffect(0.8)
                            } else {
                                Text("\(Int(currentSpeed))")
                                    .foregroundColor(.secondary)
                            }
                        }
                        Slider(value: $currentSpeed, in: 100...3400, step: 50)
                            .disabled(device.ipAddress == nil || isSavingSpeed)
                            .onChange(of: currentSpeed) { oldValue, newValue in
                                // Debounce: only save after user stops sliding
                                if speedLoaded && oldValue != newValue {
                                    saveSpeedDebounced(newValue)
                                }
                            }
                    }
                } header: {
                    Text("Speed")
                } footer: {
                    Text("Adjust how fast the blind moves. Lower values are slower, higher values are faster.")
                }

                // Configuration Section
                Section("Configuration") {
                    Button {
                        showingWifiConfig = true
                    } label: {
                        HStack {
                            Label("WiFi", systemImage: "wifi")
                                .foregroundColor(.primary)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.footnote)
                                .foregroundColor(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(device.ipAddress == nil)

                    Button {
                        showingMqttConfig = true
                    } label: {
                        HStack {
                            Label("MQTT", systemImage: "server.rack")
                                .foregroundColor(.primary)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.footnote)
                                .foregroundColor(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(device.ipAddress == nil)

                    Button {
                        showingPasswordConfig = true
                    } label: {
                        HStack {
                            Label("Password", systemImage: "lock")
                                .foregroundColor(.primary)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.footnote)
                                .foregroundColor(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(device.ipAddress == nil)
                }

                // Firmware Section
                Section("Firmware") {
                    HStack {
                        Text("Current Version")
                        Spacer()
                        if isLoadingInfo {
                            ProgressView()
                                .scaleEffect(0.8)
                        } else {
                            Text(firmwareVersion)
                                .foregroundColor(.secondary)
                        }
                    }

                    // Check for updates button
                    Button {
                        checkForUpdates()
                    } label: {
                        HStack {
                            Image(systemName: "icloud.and.arrow.down")
                            Text("Check for Updates")
                            Spacer()
                            if isCheckingUpdates {
                                ProgressView()
                                    .scaleEffect(0.8)
                            }
                        }
                    }
                    .disabled(device.ipAddress == nil || isCheckingUpdates || isDownloading || isUploading)

                    // Update error
                    if let error = updateError {
                        HStack {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundColor(.orange)
                            Text(error)
                                .font(.caption)
                                .foregroundColor(.secondary)
                            Spacer()
                            Button {
                                updateError = nil
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundColor(.secondary)
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    // Available update info
                    if let update = availableUpdate {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Image(systemName: "sparkles")
                                    .foregroundColor(.blue)
                                Text("Update Available: \(update.version)")
                                    .fontWeight(.medium)
                            }

                            if let notes = update.releaseNotes {
                                Text(notes)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }

                            // Warning if app update required
                            if update.requiresAppUpdate, let requiredVersion = update.requiredAppVersion {
                                HStack(spacing: 4) {
                                    Image(systemName: "exclamationmark.triangle.fill")
                                        .font(.caption)
                                        .foregroundColor(.orange)
                                    Text("App update to \(requiredVersion) required after install")
                                        .font(.caption)
                                        .foregroundColor(.orange)
                                }
                            }

                            // Warning if cannot flash due to app too old
                            if !update.canFlash && !update.appTooNewToFlash, let requiredFirmware = update.requiredFirmwareVersion {
                                HStack(spacing: 4) {
                                    Image(systemName: "xmark.circle.fill")
                                        .font(.caption)
                                        .foregroundColor(.red)
                                    Text("Requires firmware \(requiredFirmware) or later first")
                                        .font(.caption)
                                        .foregroundColor(.red)
                                }
                            }

                            // Warning if cannot flash due to app too new
                            if update.appTooNewToFlash, let maxVersion = update.maxAppVersionFlash {
                                HStack(spacing: 4) {
                                    Image(systemName: "xmark.circle.fill")
                                        .font(.caption)
                                        .foregroundColor(.red)
                                    Text("This app is too new (max \(maxVersion)). Downgrade required.")
                                        .font(.caption)
                                        .foregroundColor(.red)
                                }
                            }

                            // Download & Install button
                            Button {
                                downloadAndInstall()
                            } label: {
                                HStack {
                                    Image(systemName: "arrow.down.circle.fill")
                                    Text("Download & Install")
                                }
                                .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(!update.canFlash || isDownloading || isUploading)
                        }
                        .padding(.vertical, 4)
                    }

                    // Download/upload progress
                    if isDownloading || isUploading {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(isDownloading && !isUploading ? "Downloading firmware..." : "Uploading firmware...")
                                .font(.caption)
                                .foregroundColor(.secondary)

                            ProgressView(value: isDownloading ? downloadProgress : uploadProgress)
                                .progressViewStyle(.linear)

                            Text("\(Int((isDownloading ? downloadProgress : uploadProgress) * 100))%")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    }

                    // Manual update from file (fallback)
                    Button {
                        presentFilePicker()
                    } label: {
                        HStack {
                            Image(systemName: "folder")
                            Text("Update from File...")
                            Spacer()
                        }
                    }
                    .disabled(device.ipAddress == nil || isUploading || isDownloading)
                }

                // Actions Section
                Section("Actions") {
                    Button {
                        restartDevice()
                    } label: {
                        HStack {
                            Image(systemName: "arrow.clockwise")
                            Text("Restart Device")
                        }
                    }
                    .disabled(device.ipAddress == nil)
                }

                // Diagnostics Section
                Section("Diagnostics") {
                    Button {
                        showingDeviceLogs = true
                    } label: {
                        HStack {
                            Image(systemName: "doc.text.magnifyingglass")
                            Text("Device Logs")
                        }
                    }
                    .disabled(device.ipAddress == nil)
                }

                // Danger Zone Section
                Section {
                    Button(role: .destructive) {
                        showingFactoryResetConfirmation = true
                    } label: {
                        HStack {
                            Image(systemName: "exclamationmark.triangle.fill")
                            Text("Factory Reset")
                            Spacer()
                            if isResetting {
                                ProgressView()
                                    .scaleEffect(0.8)
                            }
                        }
                    }
                    .disabled(device.ipAddress == nil || isResetting)
                } header: {
                    Text("Danger Zone")
                } footer: {
                    Text("Factory reset will erase all settings including WiFi credentials, calibration data, and device name. The device will need to be set up again.")
                }
            }
            .navigationTitle("Settings")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
            .alert("Error", isPresented: $showingError) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(uploadError ?? "An unknown error occurred")
            }
            .alert("Success", isPresented: $showingSuccess) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(successMessage)
            }
            .alert("Factory Reset", isPresented: $showingFactoryResetConfirmation) {
                Button("Cancel", role: .cancel) {}
                Button("Reset", role: .destructive) {
                    performFactoryReset()
                }
            } message: {
                Text("Are you sure you want to factory reset this device?\n\nThis will erase ALL settings including:\n• WiFi credentials\n• Device name\n• Calibration data\n• MQTT configuration\n\nThe device will need to be set up again from scratch.")
            }
            .onAppear {
                loadDeviceInfo()
            }
            .sheet(isPresented: $showingDeviceLogs) {
                DeviceLogsView(device: device)
            }
            .sheet(isPresented: $showingWifiConfig) {
                NavigationStack {
                    WiFiConfigurationView(device: device)
                }
            }
            .sheet(isPresented: $showingMqttConfig) {
                NavigationStack {
                    MQTTConfigurationView(device: device)
                }
            }
            .sheet(isPresented: $showingPasswordConfig) {
                NavigationStack {
                    PasswordConfigurationView(device: device)
                }
            }
        }
    }

    private func loadDeviceInfo() {
        guard let ip = device.ipAddress else { return }

        isLoadingInfo = true
        Task {
            do {
                let info = try await httpClient.getInfo(from: ip)
                await MainActor.run {
                    firmwareVersion = info.version
                    currentOrientation = info.deviceOrientation
                    currentSpeed = Double(info.servoSpeed)
                    // Update device name from authoritative source
                    device.name = info.hostname
                    isLoadingInfo = false
                }
                // Delay marking as loaded so onChange doesn't fire for initial load
                try? await Task.sleep(nanoseconds: 100_000_000)  // 0.1 seconds
                await MainActor.run {
                    orientationLoaded = true
                    speedLoaded = true
                }
            } catch {
                await MainActor.run {
                    firmwareVersion = "Error loading"
                    isLoadingInfo = false
                }
                try? await Task.sleep(nanoseconds: 100_000_000)
                await MainActor.run {
                    orientationLoaded = true  // Still mark as loaded to allow manual changes
                    speedLoaded = true
                }
            }
        }
    }

    private func saveOrientation(_ orientation: DeviceOrientation) {
        guard let ip = device.ipAddress else { return }

        isSavingOrientation = true
        Task {
            do {
                try await httpClient.setOrientation(orientation, at: ip, deviceId: device.deviceId)
                await MainActor.run {
                    isSavingOrientation = false
                    successMessage = "Orientation updated to \(orientation.displayName)"
                    showingSuccess = true
                }
            } catch {
                await MainActor.run {
                    isSavingOrientation = false
                    uploadError = error.localizedDescription
                    showingError = true
                    // Revert the picker to the previous value
                    loadDeviceInfo()
                }
            }
        }
    }

    // Debounce timer for speed slider
    @State private var speedSaveTask: Task<Void, Never>?

    private func saveSpeedDebounced(_ speed: Double) {
        // Cancel any pending save
        speedSaveTask?.cancel()

        // Schedule a new save after a short delay
        speedSaveTask = Task {
            try? await Task.sleep(nanoseconds: 500_000_000)  // 0.5 seconds
            if !Task.isCancelled {
                await saveSpeed(Int(speed))
            }
        }
    }

    private func saveSpeed(_ speed: Int) async {
        guard let ip = device.ipAddress else { return }

        await MainActor.run {
            isSavingSpeed = true
        }

        do {
            try await httpClient.setSpeed(speed, at: ip, deviceId: device.deviceId)
            await MainActor.run {
                isSavingSpeed = false
            }
        } catch {
            await MainActor.run {
                isSavingSpeed = false
                uploadError = error.localizedDescription
                showingError = true
            }
        }
    }

    // MARK: - Firmware Update

    private func checkForUpdates() {
        guard firmwareVersion != "Unknown" && firmwareVersion != "Error loading" else {
            updateError = "Unable to check updates - device info not loaded"
            return
        }

        isCheckingUpdates = true
        updateError = nil
        availableUpdate = nil

        let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0"

        Task {
            do {
                let update = try await FirmwareUpdateService.shared.checkForUpdate(
                    currentFirmwareVersion: firmwareVersion,
                    currentAppVersion: appVersion
                )
                await MainActor.run {
                    availableUpdate = update
                    isCheckingUpdates = false
                    if update == nil {
                        successMessage = "Firmware is up to date"
                        showingSuccess = true
                    }
                }
            } catch {
                await MainActor.run {
                    updateError = "Unable to check for updates: \(error.localizedDescription)"
                    isCheckingUpdates = false
                }
            }
        }
    }

    private func downloadAndInstall() {
        guard let update = availableUpdate else { return }
        guard let ip = device.ipAddress else { return }

        // Check if the update can be flashed
        if !update.canFlash {
            updateError = "Cannot install this update. Your device needs firmware \(update.requiredFirmwareVersion ?? "newer") or later first."
            return
        }

        isDownloading = true
        downloadProgress = 0
        updateError = nil

        Task {
            do {
                // Download firmware from ZAP
                let firmwareData = try await FirmwareUpdateService.shared.downloadFirmware(
                    version: update.version
                ) { progress in
                    Task { @MainActor in
                        downloadProgress = progress * 0.5  // 0-50% for download
                    }
                }

                // Upload to device
                await MainActor.run {
                    isUploading = true
                }

                try await httpClient.uploadFirmware(firmwareData, to: ip, deviceId: device.deviceId) { progress in
                    Task { @MainActor in
                        downloadProgress = 0.5 + (progress * 0.5)  // 50-100% for upload
                    }
                }

                await MainActor.run {
                    isDownloading = false
                    isUploading = false
                    availableUpdate = nil

                    // Show success message with app update warning if needed
                    if update.requiresAppUpdate, let requiredVersion = update.requiredAppVersion {
                        successMessage = "Firmware updated to \(update.version). Device is restarting...\n\nIMPORTANT: Please update this app to version \(requiredVersion) or later."
                    } else {
                        successMessage = "Firmware updated to \(update.version). Device is restarting..."
                    }
                    showingSuccess = true
                }

                // Reload info after device restarts
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                await MainActor.run {
                    loadDeviceInfo()
                }
            } catch {
                await MainActor.run {
                    isDownloading = false
                    isUploading = false
                    updateError = "Update failed: \(error.localizedDescription)"
                }
            }
        }
    }

    private func presentFilePicker() {
        print("[OTA] presentFilePicker called")

        // Set up the callback before presenting
        pickerDelegate.onPick = { [self] url in
            print("[OTA] onPick callback fired with URL: \(url)")
            self.uploadFirmware(from: url)
        }

        let picker = UIDocumentPickerViewController(
            forOpeningContentTypes: [UTType(filenameExtension: "bin") ?? .data],
            asCopy: true  // Copy to app sandbox - avoids security scope issues
        )
        picker.delegate = pickerDelegate
        picker.allowsMultipleSelection = false

        // Find the root view controller and present from there
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let rootVC = windowScene.windows.first?.rootViewController else {
            print("[OTA] Could not find root view controller")
            uploadError = "Could not present file picker"
            showingError = true
            return
        }

        // Find the topmost presented controller
        var topVC = rootVC
        while let presented = topVC.presentedViewController {
            topVC = presented
        }

        print("[OTA] Presenting picker from: \(type(of: topVC))")
        topVC.present(picker, animated: true)
    }

    private func uploadFirmware(from fileURL: URL) {
        print("[OTA] uploadFirmware called with: \(fileURL)")
        guard let ip = device.ipAddress else {
            print("[OTA] No IP address")
            return
        }

        // With asCopy:true, file is already in our sandbox - no security scope needed
        let fileData: Data
        do {
            fileData = try Data(contentsOf: fileURL)
            print("[OTA] File size: \(fileData.count) bytes")
        } catch {
            print("[OTA] Failed to read file: \(error)")
            uploadError = "Failed to read file: \(error.localizedDescription)"
            showingError = true
            return
        }

        isUploading = true
        uploadProgress = 0

        Task {
            do {
                print("[OTA] Starting upload to \(ip)")
                try await httpClient.uploadFirmware(fileData, to: ip, deviceId: device.deviceId) { progress in
                    Task { @MainActor in
                        uploadProgress = progress
                    }
                }

                await MainActor.run {
                    print("[OTA] Upload complete!")
                    isUploading = false
                    successMessage = "Firmware updated successfully. Device is restarting..."
                    showingSuccess = true

                    // Reload info after a delay
                    DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
                        loadDeviceInfo()
                    }
                }
            } catch {
                await MainActor.run {
                    print("[OTA] Upload failed: \(error)")
                    isUploading = false
                    uploadError = error.localizedDescription
                    showingError = true
                }
            }
        }
    }

    private func restartDevice() {
        guard let ip = device.ipAddress else { return }

        Task {
            do {
                try await httpClient.sendCommand(.restart, to: ip, deviceId: device.deviceId)
                await MainActor.run {
                    successMessage = "Device is restarting..."
                    showingSuccess = true
                }
            } catch {
                await MainActor.run {
                    uploadError = error.localizedDescription
                    showingError = true
                }
            }
        }
    }

    private func performFactoryReset() {
        guard let ip = device.ipAddress else { return }

        isResetting = true

        Task {
            do {
                try await httpClient.factoryReset(at: ip, deviceId: device.deviceId)
                await MainActor.run {
                    isResetting = false
                    successMessage = "Factory reset complete. The device will restart and need to be set up again."
                    showingSuccess = true

                    // Remove the device from the registry so it can be rediscovered
                    DeviceRegistry.shared.remove(deviceId: device.deviceId)

                    // Dismiss and notify parent to also dismiss
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                        dismiss()
                        onFactoryReset?()
                    }
                }
            } catch {
                await MainActor.run {
                    isResetting = false
                    uploadError = error.localizedDescription
                    showingError = true
                }
            }
        }
    }
}

// MARK: - Device Logs View

struct DeviceLogsView: View {
    @ObservedObject var device: BlindDevice
    @EnvironmentObject var httpClient: HTTPClient
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    // Own SSE client for log streaming - connects to /events/logs endpoint
    @StateObject private var sseClient = SSEClient()

    @State private var logs: [String] = []
    @State private var isLoading = false
    @State private var error: String?


    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Toolbar
                HStack {
                    // SSE connection indicator
                    HStack(spacing: 4) {
                        Circle()
                            .fill(sseClient.isConnected ? Color.green : Color.red)
                            .frame(width: 8, height: 8)
                        Text(sseClient.isConnected ? "Live" : "Disconnected")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }

                    Spacer()

                    Button(role: .destructive) {
                        clearLogs()
                    } label: {
                        Label("Clear", systemImage: "trash")
                    }
                    .disabled(isLoading || logs.isEmpty)
                }
                .padding(.horizontal)
                .padding(.vertical, 8)
                .background(Color(uiColor: .systemBackground))

                Divider()

                // Log content
                if isLoading && logs.isEmpty {
                    Spacer()
                    ProgressView("Loading logs...")
                    Spacer()
                } else if let error = error {
                    Spacer()
                    VStack(spacing: 12) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.largeTitle)
                            .foregroundColor(.orange)
                        Text(error)
                            .foregroundColor(.secondary)
                        Button("Retry") {
                            loadLogs()
                        }
                        .buttonStyle(.bordered)
                    }
                    Spacer()
                } else if logs.isEmpty {
                    Spacer()
                    VStack(spacing: 12) {
                        Image(systemName: "doc.text")
                            .font(.largeTitle)
                            .foregroundColor(.secondary)
                        Text("No logs available")
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                } else {
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 2) {
                                ForEach(Array(logs.enumerated()), id: \.offset) { index, log in
                                    Text(log)
                                        .font(.system(.caption, design: .monospaced))
                                        .foregroundColor(logColor(for: log))
                                        .textSelection(.enabled)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .id(index)
                                }
                            }
                            .padding()
                        }
                        .background(Color(uiColor: .secondarySystemBackground))
                        .onChange(of: logs.count) { _, _ in
                            // Scroll to bottom when new logs arrive
                            if let lastIndex = logs.indices.last {
                                withAnimation {
                                    proxy.scrollTo(lastIndex, anchor: .bottom)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Device Logs")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
            .onAppear {
                loadLogs()
                startSSE()
            }
            .onDisappear {
                stopSSE()
            }
            // Reconnect SSE when app returns from background/sleep
            .onChange(of: scenePhase) { _, newPhase in
                if newPhase == .active && !sseClient.isConnected {
                    startSSE()
                }
            }
        }
        #if targetEnvironment(macCatalyst)
        .frame(minWidth: 600, minHeight: 400)
        #endif
    }

    private func logColor(for log: String) -> Color {
        if log.contains("[ERROR]") {
            return .red
        } else if log.contains("[WIFI]") {
            return .blue
        } else if log.contains("[BLE]") {
            return .purple
        } else if log.contains("[HTTP]") {
            return .green
        } else if log.contains("[SERVO]") {
            return .orange
        } else if log.contains("[HALL]") {
            return .cyan
        } else if log.contains("[BOOT]") {
            return .yellow
        }
        return .primary
    }

    private func startSSE() {
        guard let ip = device.ipAddress else { return }

        // Connect to the dedicated logs endpoint
        sseClient.onLogReceived = { logEntry in
            logs.append(logEntry)
        }
        sseClient.connect(to: ip, deviceId: device.deviceId, endpoint: .logs)
    }

    private func stopSSE() {
        // Disconnect from the logs endpoint
        sseClient.disconnect()
    }

    private func loadLogs() {
        guard let ip = device.ipAddress else {
            error = "Device not connected"
            return
        }

        isLoading = true
        error = nil

        Task {
            do {
                let fetchedLogs = try await httpClient.getLogs(from: ip, deviceId: device.deviceId)
                await MainActor.run {
                    logs = fetchedLogs
                    isLoading = false
                }
            } catch {
                await MainActor.run {
                    self.error = error.localizedDescription
                    isLoading = false
                }
            }
        }
    }

    private func clearLogs() {
        guard let ip = device.ipAddress else { return }

        Task {
            do {
                try await httpClient.clearLogs(at: ip, deviceId: device.deviceId)
                await MainActor.run {
                    logs = []
                }
            } catch {
                await MainActor.run {
                    self.error = error.localizedDescription
                }
            }
        }
    }
}

#Preview {
    DeviceSettingsView(device: BlindDevice(name: "Test Blind", ipAddress: "192.168.1.100"))
        .environmentObject(HTTPClient.shared)
}

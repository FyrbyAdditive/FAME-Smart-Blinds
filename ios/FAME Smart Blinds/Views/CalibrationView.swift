import SwiftUI

struct CalibrationView: View {
    @ObservedObject var device: BlindDevice
    @EnvironmentObject var httpClient: HTTPClient
    @Environment(\.dismiss) private var dismiss

    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var pollTimer: Timer?

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                // Status indicator (compact)
                calibrationStatusView

                // Instructions
                instructionsView

                Spacer(minLength: 8)

                // Position display (inline with controls when at_home)
                if device.calibrationState == "at_home" {
                    positionDisplay
                }

                // Controls based on state
                controlsView
            }
            .padding()
            .navigationTitle("Calibration")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        cancelAndDismiss()
                    }
                }
            }
            .alert("Error", isPresented: .constant(errorMessage != nil)) {
                Button("OK") {
                    errorMessage = nil
                }
            } message: {
                Text(errorMessage ?? "")
            }
            .onAppear {
                // Reset firmware state when opening for recalibration
                if device.calibrationState == "complete" {
                    Task {
                        if let ip = device.ipAddress {
                            do {
                                // Cancel on firmware to reset state from complete to idle
                                try await httpClient.cancelCalibration(at: ip, deviceId: device.deviceId)
                            } catch {
                                print("[CalibrationView] Failed to reset firmware state: \(error)")
                            }
                        }
                        device.calibrationState = "idle"
                        startPolling()
                    }
                } else {
                    startPolling()
                }
            }
            .onDisappear {
                stopPolling()
            }
        }
    }

    @ViewBuilder
    private var calibrationStatusView: some View {
        HStack(spacing: 16) {
            ZStack {
                Circle()
                    .fill(statusColor.opacity(0.2))
                    .frame(width: 56, height: 56)

                if isLoading || device.calibrationState == "finding_home" {
                    ProgressView()
                        .scaleEffect(1.2)
                } else {
                    Image(systemName: statusIcon)
                        .font(.system(size: 24))
                        .foregroundColor(statusColor)
                }
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(statusTitle)
                    .font(.headline)

                Text(statusSubtitle)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .lineLimit(2)
            }

            Spacer(minLength: 0)
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .cornerRadius(12)
    }

    @ViewBuilder
    private var instructionsView: some View {
        VStack(spacing: 10) {
            switch device.calibrationState {
            case "idle":
                InstructionRow(number: 1, text: "Press 'Start Calibration' to begin", isActive: true)
                InstructionRow(number: 2, text: "Blind will move up to find home", isActive: false)
                InstructionRow(number: 3, text: "Move blind to bottom position", isActive: false)
                InstructionRow(number: 4, text: "Press 'Set Bottom' to complete", isActive: false)

            case "finding_home":
                InstructionRow(number: 1, text: "Started calibration", isActive: false, isCompleted: true)
                InstructionRow(number: 2, text: "Finding home position...", isActive: true)
                InstructionRow(number: 3, text: "Move blind to bottom position", isActive: false)
                InstructionRow(number: 4, text: "Press 'Set Bottom' to complete", isActive: false)

            case "at_home":
                InstructionRow(number: 1, text: "Started calibration", isActive: false, isCompleted: true)
                InstructionRow(number: 2, text: "Home position found!", isActive: false, isCompleted: true)
                InstructionRow(number: 3, text: "Move blind to lowest point", isActive: true)
                InstructionRow(number: 4, text: "Press 'Set Bottom' when ready", isActive: false)

            case "complete":
                InstructionRow(number: 1, text: "Started calibration", isActive: false, isCompleted: true)
                InstructionRow(number: 2, text: "Home position found!", isActive: false, isCompleted: true)
                InstructionRow(number: 3, text: "Bottom position set", isActive: false, isCompleted: true)
                InstructionRow(number: 4, text: "Calibration complete!", isActive: false, isCompleted: true)

            default:
                Text("Unknown state: \(device.calibrationState)")
                    .foregroundColor(.secondary)
            }
        }
        .padding(12)
        .background(Color(.tertiarySystemBackground))
        .cornerRadius(10)
    }

    @ViewBuilder
    private var controlsView: some View {
        VStack(spacing: 12) {
            switch device.calibrationState {
            case "idle":
                Button(action: startCalibration) {
                    Label("Start Calibration", systemImage: "play.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(isLoading)

            case "finding_home":
                Button(action: cancelCalibration) {
                    Label("Cancel", systemImage: "xmark")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(isLoading)

            case "at_home":
                // Movement controls - more compact
                HStack(spacing: 12) {
                    Button(action: { moveOpen() }) {
                        Image(systemName: "chevron.up")
                            .font(.title2)
                            .frame(width: 50, height: 50)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isLoading)

                    Button(action: { stopMovement() }) {
                        Image(systemName: "stop.fill")
                            .font(.title2)
                            .frame(width: 50, height: 50)
                    }
                    .buttonStyle(.bordered)
                    .tint(.orange)
                    .disabled(isLoading)

                    Button(action: { moveClose() }) {
                        Image(systemName: "chevron.down")
                            .font(.title2)
                            .frame(width: 50, height: 50)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isLoading)
                }

                HStack(spacing: 12) {
                    Button(action: cancelCalibration) {
                        Label("Cancel", systemImage: "xmark")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isLoading)

                    Button(action: setBottom) {
                        Label("Set Bottom", systemImage: "checkmark.circle.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.green)
                    .disabled(isLoading)
                }

            case "complete":
                Button(action: { dismiss() }) {
                    Label("Done", systemImage: "checkmark")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)

            default:
                EmptyView()
            }
        }
    }

    @ViewBuilder
    private var positionDisplay: some View {
        HStack {
            Text("Position:")
                .font(.subheadline)
                .foregroundColor(.secondary)
            Text("\(device.cumulativePosition)")
                .font(.subheadline.monospacedDigit())
                .fontWeight(.semibold)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Color(.quaternarySystemFill))
        .cornerRadius(8)
    }

    // MARK: - Computed Properties

    private var statusColor: Color {
        switch device.calibrationState {
        case "idle": return .blue
        case "finding_home": return .orange
        case "at_home": return .green
        case "complete": return .green
        default: return .gray
        }
    }

    private var statusIcon: String {
        switch device.calibrationState {
        case "idle": return "gear"
        case "finding_home": return "arrow.up"
        case "at_home": return "house.fill"
        case "complete": return "checkmark.circle.fill"
        default: return "questionmark"
        }
    }

    private var statusTitle: String {
        switch device.calibrationState {
        case "idle": return "Ready to Calibrate"
        case "finding_home": return "Finding Home..."
        case "at_home": return "At Home Position"
        case "complete": return "Calibration Complete"
        default: return "Unknown"
        }
    }

    private var statusSubtitle: String {
        switch device.calibrationState {
        case "idle": return "Press Start to begin the calibration process"
        case "finding_home": return "Moving blind up to find the magnet sensor"
        case "at_home": return "Move the blind down to the lowest desired point"
        case "complete": return "Your blind is now calibrated and ready to use"
        default: return ""
        }
    }

    // MARK: - Actions

    private func startCalibration() {
        guard let ip = device.ipAddress else { return }
        isLoading = true

        Task {
            do {
                try await httpClient.startCalibration(at: ip, deviceId: device.deviceId)
                await refreshStatus()
            } catch {
                errorMessage = error.localizedDescription
            }
            isLoading = false
        }
    }

    private func setBottom() {
        guard let ip = device.ipAddress else { return }
        isLoading = true

        Task {
            do {
                try await httpClient.setBottomPosition(at: ip, deviceId: device.deviceId)
                await refreshStatus()
            } catch {
                errorMessage = error.localizedDescription
            }
            isLoading = false
        }
    }

    private func cancelCalibration() {
        guard let ip = device.ipAddress else { return }
        isLoading = true

        Task {
            do {
                try await httpClient.cancelCalibration(at: ip, deviceId: device.deviceId)
                await refreshStatus()
            } catch {
                errorMessage = error.localizedDescription
            }
            isLoading = false
        }
    }

    private func cancelAndDismiss() {
        if device.calibrationState != "idle" && device.calibrationState != "complete" {
            cancelCalibration()
        }
        dismiss()
    }

    private func moveOpen() {
        guard let ip = device.ipAddress else { return }
        Task {
            do {
                // During calibration, use force to bypass limits
                try await httpClient.openForce(at: ip, deviceId: device.deviceId)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func moveClose() {
        guard let ip = device.ipAddress else { return }
        Task {
            do {
                // During calibration, use force to bypass limits
                try await httpClient.closeForce(at: ip, deviceId: device.deviceId)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func stopMovement() {
        guard let ip = device.ipAddress else { return }
        Task {
            do {
                try await httpClient.sendCommand(.stop, to: ip, deviceId: device.deviceId)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    @MainActor
    private func refreshStatus() async {
        guard let ip = device.ipAddress else { return }
        do {
            let status = try await httpClient.getCalibrationStatus(from: ip, deviceId: device.deviceId)
            device.updateFromCalibrationStatus(status)
        } catch {
            print("[CalibrationView] Failed to refresh status: \(error)")
        }
    }

    private func startPolling() {
        pollTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
            Task { @MainActor in
                await refreshStatus()
            }
        }
    }

    private func stopPolling() {
        pollTimer?.invalidate()
        pollTimer = nil
    }
}

// MARK: - Helper Views

struct InstructionRow: View {
    let number: Int
    let text: String
    var isActive: Bool = false
    var isCompleted: Bool = false

    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                Circle()
                    .fill(circleColor)
                    .frame(width: 22, height: 22)

                if isCompleted {
                    Image(systemName: "checkmark")
                        .font(.caption2.bold())
                        .foregroundColor(.white)
                } else {
                    Text("\(number)")
                        .font(.caption2.bold())
                        .foregroundColor(isActive ? .white : .secondary)
                }
            }

            Text(text)
                .font(.footnote)
                .foregroundColor(textColor)

            Spacer()
        }
    }

    private var circleColor: Color {
        if isCompleted {
            return .green
        } else if isActive {
            return .blue
        } else {
            return Color(.tertiarySystemFill)
        }
    }

    private var textColor: Color {
        if isCompleted || isActive {
            return .primary
        } else {
            return .secondary
        }
    }
}

#Preview {
    CalibrationView(device: BlindDevice(name: "Test Device", ipAddress: "192.168.1.100"))
        .environmentObject(HTTPClient.shared)
}

import SwiftUI

struct PasswordConfigurationView: View {
    @ObservedObject var device: BlindDevice
    @EnvironmentObject var httpClient: HTTPClient
    @Environment(\.dismiss) private var dismiss

    @State private var devicePassword = ""
    @State private var confirmPassword = ""
    @State private var isSaving = false

    @State private var showingError = false
    @State private var errorMessage = ""
    @State private var showingSuccess = false
    @State private var successMessage = ""

    var passwordsMatch: Bool {
        devicePassword.isEmpty || devicePassword == confirmPassword
    }

    var body: some View {
        List {
            Section {
                SecureField("New Password", text: $devicePassword)

                SecureField("Confirm Password", text: $confirmPassword)

                if !devicePassword.isEmpty && !confirmPassword.isEmpty && !passwordsMatch {
                    Text("Passwords don't match")
                        .font(.caption)
                        .foregroundColor(.red)
                }

                Button {
                    saveDevicePassword()
                } label: {
                    HStack {
                        Text("Save Password")
                        Spacer()
                        if isSaving {
                            ProgressView()
                                .scaleEffect(0.8)
                        }
                    }
                }
                .disabled((!devicePassword.isEmpty && !passwordsMatch) || isSaving || device.ipAddress == nil)
            } header: {
                Text("Device Password")
            } footer: {
                Text("Set a password to secure access to this device. The password will be required when connecting to the device's web interface.\n\nLeave both fields empty and save to remove password protection.")
            }
        }
        .navigationTitle("Password")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") {
                    dismiss()
                }
            }
        }
        .alert("Error", isPresented: $showingError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage)
        }
        .alert("Success", isPresented: $showingSuccess) {
            Button("OK", role: .cancel) {
                dismiss()
            }
        } message: {
            Text(successMessage)
        }
    }

    private func saveDevicePassword() {
        guard let ip = device.ipAddress else { return }
        guard passwordsMatch else { return }

        isSaving = true
        Task {
            do {
                try await httpClient.setDevicePassword(devicePassword, at: ip)
                await MainActor.run {
                    isSaving = false
                    if devicePassword.isEmpty {
                        successMessage = "Password protection removed."
                    } else {
                        successMessage = "Device password updated successfully."
                    }
                    showingSuccess = true
                }
            } catch {
                await MainActor.run {
                    isSaving = false
                    errorMessage = error.localizedDescription
                    showingError = true
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        PasswordConfigurationView(device: BlindDevice(name: "Test Blind", ipAddress: "192.168.1.100"))
            .environmentObject(HTTPClient.shared)
    }
}

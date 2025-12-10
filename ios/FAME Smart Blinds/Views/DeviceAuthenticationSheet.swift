import SwiftUI

/// Sheet presented when device authentication is required
struct DeviceAuthenticationSheet: View {
    let deviceId: String
    let deviceName: String
    let ipAddress: String
    let onAuthenticated: () -> Void
    let onCancel: () -> Void

    @StateObject private var authManager = AuthenticationManager.shared
    @State private var password = ""
    @State private var isAuthenticating = false
    @State private var showError = false
    @State private var errorMessage = ""
    @State private var selectedExpiry: SessionExpiry = .week

    var body: some View {
        NavigationView {
            Form {
                Section {
                    Text("Device \"\(deviceName)\" requires authentication to continue.")
                        .foregroundColor(.secondary)
                }

                Section("Password") {
                    SecureField("Enter device password", text: $password)
                        .textContentType(.password)
                        .autocapitalization(.none)
                        .disabled(isAuthenticating)
                }

                Section("Session Duration") {
                    Picker("Keep me signed in for", selection: $selectedExpiry) {
                        ForEach(SessionExpiry.allCases) { expiry in
                            Text(expiry.rawValue).tag(expiry)
                        }
                    }
                }

                Section {
                    Button(action: authenticate) {
                        HStack {
                            Spacer()
                            if isAuthenticating {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle())
                            } else {
                                Text("Sign In")
                            }
                            Spacer()
                        }
                    }
                    .disabled(password.isEmpty || isAuthenticating)
                }
            }
            .navigationTitle("Authentication Required")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        onCancel()
                    }
                    .disabled(isAuthenticating)
                }
            }
            .alert("Authentication Failed", isPresented: $showError) {
                Button("OK") {}
            } message: {
                Text(errorMessage)
            }
        }
    }

    private func authenticate() {
        guard !password.isEmpty else { return }

        isAuthenticating = true
        showError = false

        Task {
            do {
                // Test the password against the device
                let isValid = try await HTTPClient.shared.testAuthentication(
                    password: password,
                    ipAddress: ipAddress,
                    deviceId: deviceId
                )

                await MainActor.run {
                    isAuthenticating = false

                    if isValid {
                        // Save the authenticated session
                        do {
                            try authManager.authenticate(
                                deviceId: deviceId,
                                password: password,
                                expiry: selectedExpiry
                            )
                            onAuthenticated()
                        } catch {
                            errorMessage = "Failed to save credentials: \(error.localizedDescription)"
                            showError = true
                        }
                    } else {
                        errorMessage = "Incorrect password"
                        showError = true
                    }
                }
            } catch {
                await MainActor.run {
                    isAuthenticating = false
                    errorMessage = "Connection failed: \(error.localizedDescription)"
                    showError = true
                }
            }
        }
    }
}

// MARK: - Preview

#Preview {
    DeviceAuthenticationSheet(
        deviceId: "abc12345",
        deviceName: "Living Room Blinds",
        ipAddress: "192.168.1.100",
        onAuthenticated: {},
        onCancel: {}
    )
}

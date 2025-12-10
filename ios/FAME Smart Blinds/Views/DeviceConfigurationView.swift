import SwiftUI

struct DeviceConfigurationView: View {
    @ObservedObject var device: BlindDevice
    @EnvironmentObject var httpClient: HTTPClient
    @Environment(\.dismiss) private var dismiss

    // WiFi Configuration
    @State private var wifiSsid = ""
    @State private var wifiPassword = ""
    @State private var isSavingWifi = false
    @State private var showingWifiRestartAlert = false

    // MQTT Configuration
    @State private var mqttBroker = ""
    @State private var mqttPort = "1883"
    @State private var mqttUser = ""
    @State private var mqttPassword = ""
    @State private var isSavingMqtt = false

    // Device Password
    @State private var devicePassword = ""
    @State private var confirmPassword = ""
    @State private var isSavingPassword = false

    // Alerts
    @State private var showingError = false
    @State private var errorMessage = ""
    @State private var showingSuccess = false
    @State private var successMessage = ""

    var passwordsMatch: Bool {
        devicePassword.isEmpty || devicePassword == confirmPassword
    }

    var body: some View {
        List {
            // WiFi Configuration Section
            Section {
                TextField("WiFi Network Name (SSID)", text: $wifiSsid)
                    #if os(iOS)
                    .autocapitalization(.none)
                    #endif
                    .disableAutocorrection(true)

                SecureField("WiFi Password", text: $wifiPassword)

                Button {
                    showingWifiRestartAlert = true
                } label: {
                    HStack {
                        Text("Save WiFi Settings")
                        Spacer()
                        if isSavingWifi {
                            ProgressView()
                                .scaleEffect(0.8)
                        }
                    }
                }
                .disabled(wifiSsid.isEmpty || isSavingWifi || device.ipAddress == nil)
            } header: {
                Text("WiFi Configuration")
            } footer: {
                Text("Changing WiFi settings will restart the device. Make sure the new credentials are correct or you may lose access to the device.")
            }

            // MQTT Configuration Section
            Section {
                TextField("Broker Address (e.g., 192.168.1.50)", text: $mqttBroker)
                    #if os(iOS)
                    .autocapitalization(.none)
                    .keyboardType(.decimalPad)
                    #endif
                    .disableAutocorrection(true)

                TextField("Port", text: $mqttPort)
                    #if os(iOS)
                    .keyboardType(.numberPad)
                    #endif

                TextField("Username (optional)", text: $mqttUser)
                    #if os(iOS)
                    .autocapitalization(.none)
                    #endif
                    .disableAutocorrection(true)

                SecureField("Password (optional)", text: $mqttPassword)

                Button {
                    saveMqttConfig()
                } label: {
                    HStack {
                        Text("Save MQTT Settings")
                        Spacer()
                        if isSavingMqtt {
                            ProgressView()
                                .scaleEffect(0.8)
                        }
                    }
                }
                .disabled(mqttBroker.isEmpty || isSavingMqtt || device.ipAddress == nil)
            } header: {
                Text("MQTT / Home Assistant")
            } footer: {
                Text("Configure MQTT to enable Home Assistant integration. Username and password are optional if your broker doesn't require authentication.")
            }

            // Device Password Section
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
                        if isSavingPassword {
                            ProgressView()
                                .scaleEffect(0.8)
                        }
                    }
                }
                .disabled((!devicePassword.isEmpty && !passwordsMatch) || isSavingPassword || device.ipAddress == nil)
            } header: {
                Text("Device Password")
            } footer: {
                Text("Set a password to secure access to this device. Leave empty to remove password protection.")
            }
        }
        .navigationTitle("Configuration")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .alert("Restart Required", isPresented: $showingWifiRestartAlert) {
            Button("Cancel", role: .cancel) {}
            Button("Save & Restart") {
                saveWifiConfig()
            }
        } message: {
            Text("Changing WiFi settings will restart the device. It will reconnect using the new WiFi credentials.\n\nMake sure you're connected to the same network, or you may lose access to the device.")
        }
        .alert("Error", isPresented: $showingError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage)
        }
        .alert("Success", isPresented: $showingSuccess) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(successMessage)
        }
    }

    // MARK: - Save Functions

    private func saveWifiConfig() {
        guard let ip = device.ipAddress else { return }

        isSavingWifi = true
        Task {
            do {
                try await httpClient.setWifiCredentials(ssid: wifiSsid, password: wifiPassword, at: ip, deviceId: device.deviceId)
                await MainActor.run {
                    isSavingWifi = false
                    successMessage = "WiFi settings saved. The device is restarting..."
                    showingSuccess = true
                    // Clear fields after success
                    wifiSsid = ""
                    wifiPassword = ""
                }
            } catch {
                await MainActor.run {
                    isSavingWifi = false
                    errorMessage = error.localizedDescription
                    showingError = true
                }
            }
        }
    }

    private func saveMqttConfig() {
        guard let ip = device.ipAddress else { return }

        isSavingMqtt = true
        let port = Int(mqttPort) ?? 1883

        Task {
            do {
                try await httpClient.setMqttConfig(
                    broker: mqttBroker,
                    port: port,
                    user: mqttUser,
                    password: mqttPassword,
                    at: ip,
                    deviceId: device.deviceId
                )
                await MainActor.run {
                    isSavingMqtt = false
                    successMessage = "MQTT settings saved successfully."
                    showingSuccess = true
                }
            } catch {
                await MainActor.run {
                    isSavingMqtt = false
                    errorMessage = error.localizedDescription
                    showingError = true
                }
            }
        }
    }

    private func saveDevicePassword() {
        guard let ip = device.ipAddress else { return }
        guard passwordsMatch else { return }

        isSavingPassword = true
        Task {
            do {
                try await httpClient.setDevicePassword(devicePassword, at: ip, deviceId: device.deviceId)
                await MainActor.run {
                    isSavingPassword = false
                    if devicePassword.isEmpty {
                        successMessage = "Password protection removed."
                    } else {
                        successMessage = "Device password updated successfully."
                    }
                    showingSuccess = true
                    // Clear fields after success
                    devicePassword = ""
                    confirmPassword = ""
                }
            } catch {
                await MainActor.run {
                    isSavingPassword = false
                    errorMessage = error.localizedDescription
                    showingError = true
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        DeviceConfigurationView(device: BlindDevice(name: "Test Blind", ipAddress: "192.168.1.100"))
            .environmentObject(HTTPClient.shared)
    }
}

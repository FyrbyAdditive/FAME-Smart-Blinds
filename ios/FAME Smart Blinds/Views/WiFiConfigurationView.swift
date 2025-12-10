import SwiftUI

struct WiFiConfigurationView: View {
    @ObservedObject var device: BlindDevice
    @EnvironmentObject var httpClient: HTTPClient
    @Environment(\.dismiss) private var dismiss

    @State private var wifiSsid = ""
    @State private var wifiPassword = ""
    @State private var isSaving = false
    @State private var isLoading = false
    @State private var showingRestartAlert = false

    @State private var showingError = false
    @State private var errorMessage = ""
    @State private var showingSuccess = false
    @State private var successMessage = ""

    var body: some View {
        List {
            Section {
                if isLoading {
                    HStack {
                        Spacer()
                        ProgressView("Loading...")
                        Spacer()
                    }
                } else {
                    TextField("WiFi Network Name (SSID)", text: $wifiSsid)
                        #if os(iOS)
                        .autocapitalization(.none)
                        #endif
                        .disableAutocorrection(true)

                    SecureField("WiFi Password", text: $wifiPassword)

                    Button {
                        showingRestartAlert = true
                    } label: {
                        HStack {
                            Text("Save WiFi Settings")
                            Spacer()
                            if isSaving {
                                ProgressView()
                                    .scaleEffect(0.8)
                            }
                        }
                    }
                    .disabled(wifiSsid.isEmpty || isSaving || device.ipAddress == nil)
                }
            } header: {
                Text("WiFi Configuration")
            } footer: {
                Text("Enter the WiFi network name and password. Changing WiFi settings will restart the device.\n\nMake sure the credentials are correct and you're connected to the same network, or you may lose access to the device.")
            }
        }
        .navigationTitle("WiFi")
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
        .onAppear {
            loadCurrentConfig()
        }
        .alert("Restart Required", isPresented: $showingRestartAlert) {
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
            Button("OK", role: .cancel) {
                dismiss()
            }
        } message: {
            Text(successMessage)
        }
    }

    private func loadCurrentConfig() {
        guard let ip = device.ipAddress else { return }

        isLoading = true
        Task {
            do {
                let info = try await httpClient.getInfo(from: ip)
                await MainActor.run {
                    wifiSsid = info.wifiSsid ?? ""
                    isLoading = false
                }
            } catch {
                await MainActor.run {
                    isLoading = false
                    // Don't show error, just leave fields empty
                }
            }
        }
    }

    private func saveWifiConfig() {
        guard let ip = device.ipAddress else { return }

        isSaving = true
        Task {
            do {
                try await httpClient.setWifiCredentials(ssid: wifiSsid, password: wifiPassword, at: ip, deviceId: device.deviceId)
                await MainActor.run {
                    isSaving = false
                    successMessage = "WiFi settings saved. The device is restarting..."
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
        WiFiConfigurationView(device: BlindDevice(name: "Test Blind", ipAddress: "192.168.1.100"))
            .environmentObject(HTTPClient.shared)
    }
}

import SwiftUI

struct MQTTConfigurationView: View {
    @ObservedObject var device: BlindDevice
    @EnvironmentObject var httpClient: HTTPClient
    @Environment(\.dismiss) private var dismiss

    @State private var mqttBroker = ""
    @State private var mqttPort = "1883"
    @State private var mqttUser = ""
    @State private var mqttPassword = ""
    @State private var isSaving = false
    @State private var isLoading = false

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
                            if isSaving {
                                ProgressView()
                                    .scaleEffect(0.8)
                            }
                        }
                    }
                    .disabled(isSaving || device.ipAddress == nil)
                }
            } header: {
                Text("MQTT / Home Assistant")
            } footer: {
                Text("Configure MQTT to enable Home Assistant integration.\n\nUsername and password are optional if your broker doesn't require authentication.\n\nTo disable MQTT, leave the broker address empty and save.")
            }
        }
        .navigationTitle("MQTT")
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
                    mqttBroker = info.mqttBroker ?? ""
                    if let port = info.mqttPort {
                        mqttPort = String(port)
                    }
                    mqttUser = info.mqttUser ?? ""
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

    private func saveMqttConfig() {
        guard let ip = device.ipAddress else { return }

        isSaving = true
        let port = Int(mqttPort) ?? 1883

        Task {
            do {
                try await httpClient.setMqttConfig(
                    broker: mqttBroker,
                    port: port,
                    user: mqttUser,
                    password: mqttPassword,
                    at: ip
                )
                await MainActor.run {
                    isSaving = false
                    if mqttBroker.isEmpty {
                        successMessage = "MQTT has been disabled."
                    } else {
                        successMessage = "MQTT settings saved successfully."
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
        MQTTConfigurationView(device: BlindDevice(name: "Test Blind", ipAddress: "192.168.1.100"))
            .environmentObject(HTTPClient.shared)
    }
}

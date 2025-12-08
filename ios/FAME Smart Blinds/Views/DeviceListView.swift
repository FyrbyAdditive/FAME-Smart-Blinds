import SwiftUI

struct DeviceListView: View {
    @EnvironmentObject var discovery: DeviceDiscovery
    @EnvironmentObject var registry: DeviceRegistry

    @State private var showingManualIP = false
    @State private var manualIP = ""
    @State private var showingAbout = false

    /// Only WiFi-configured devices (have IP address) - BLE-only devices appear in SetupView
    var configuredDevices: [BlindDevice] {
        registry.deviceList.filter { $0.ipAddress != nil }
    }

    var body: some View {
        NavigationStack {
            List {
                if !configuredDevices.isEmpty {
                    discoveredDevicesSection
                }

                if configuredDevices.isEmpty {
                    emptyStateSection
                }
            }
            .navigationTitle("FAME Smart Blinds")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.large)
            #endif
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showingAbout = true
                    } label: {
                        Image(systemName: "info.circle")
                    }
                }
            }
            .sheet(isPresented: $showingAbout) {
                AboutView()
            }
            .alert("Enter Device IP", isPresented: $showingManualIP) {
                TextField("192.168.1.x", text: $manualIP)
                #if os(iOS)
                    .keyboardType(.decimalPad)
                #endif
                Button("Cancel", role: .cancel) {}
                Button("Connect") {
                    Task {
                        await discovery.checkDeviceAt(ipAddress: manualIP)
                    }
                }
            }
        }
    }

    // MARK: - Sections

    private var discoveredDevicesSection: some View {
        Section("Discovered Devices") {
            ForEach(configuredDevices) { device in
                NavigationLink {
                    DeviceControlView(device: device)
                } label: {
                    DeviceRow(device: device)
                }
            }
        }
    }

    private var emptyStateSection: some View {
        Section {
            VStack(spacing: 16) {
                Image(systemName: "blinds.horizontal.closed")
                    .font(.system(size: 48))
                    .foregroundColor(.secondary)

                Text("No Devices Found")
                    .font(.headline)

                Text("Devices will appear automatically when detected on your network. Use the Setup tab to add new devices.")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)

                Button("Enter IP Manually") {
                    showingManualIP = true
                }
                .buttonStyle(.bordered)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 32)
        }
    }
}

// MARK: - Device Row

struct DeviceRow: View {
    @ObservedObject var device: BlindDevice

    var body: some View {
        HStack(spacing: 12) {
            // Icon
            Image(systemName: "blinds.horizontal.closed")
                .font(.title2)
                .foregroundColor(.accentColor)
                .frame(width: 40)

            // Info
            VStack(alignment: .leading, spacing: 4) {
                Text(device.name)
                    .font(.headline)

                HStack(spacing: 8) {
                    if let ip = device.ipAddress {
                        Label(ip, systemImage: "wifi")
                            .font(.caption)
                            .foregroundColor(.green)
                    }

                    if device.state != .unknown {
                        Text(device.state.displayName)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }

            Spacer()
        }
        .padding(.vertical, 4)
    }
}

#Preview {
    DeviceListView()
        .environmentObject(DeviceDiscovery.shared)
        .environmentObject(DeviceRegistry.shared)
}

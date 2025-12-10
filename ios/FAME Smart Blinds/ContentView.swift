import SwiftUI

struct ContentView: View {
    @EnvironmentObject var discovery: DeviceDiscovery
    @EnvironmentObject var registry: DeviceRegistry
    @StateObject private var authManager = AuthenticationManager.shared

    @State private var selectedTab = 0
    @State private var showingAuthSheet = false

    #if targetEnvironment(macCatalyst)
    @State private var selectedDevice: BlindDevice?
    @State private var showingSetup: Bool = false
    #endif

    /// Device that needs authentication (looked up from registry)
    private var deviceNeedingAuth: BlindDevice? {
        guard let deviceId = authManager.deviceNeedingAuth else { return nil }
        return registry.deviceList.first { $0.deviceId == deviceId }
    }

    var body: some View {
        #if targetEnvironment(macCatalyst)
        // Mac Catalyst: Use NavigationSplitView for sidebar layout
        NavigationSplitView(columnVisibility: .constant(.all)) {
            SidebarView(selectedDevice: $selectedDevice, showingSetup: $showingSetup)
                .navigationSplitViewColumnWidth(min: 320, ideal: 380, max: 500)
        } detail: {
            if showingSetup {
                SetupView()
            } else if let device = selectedDevice {
                DeviceControlView(device: device)
            } else {
                ContentUnavailableView(
                    "Select a Device",
                    systemImage: "blinds.horizontal.closed",
                    description: Text("Choose a device from the sidebar to control it")
                )
            }
        }
        .onChange(of: authManager.deviceNeedingAuth) { _, newValue in
            showingAuthSheet = newValue != nil
        }
        .sheet(isPresented: $showingAuthSheet) {
            if let device = deviceNeedingAuth, let ip = device.ipAddress {
                DeviceAuthenticationSheet(
                    deviceId: device.deviceId,
                    deviceName: device.name,
                    ipAddress: ip,
                    onAuthenticated: {
                        authManager.clearAuthenticationRequest()
                        showingAuthSheet = false
                    },
                    onCancel: {
                        authManager.clearAuthenticationRequest()
                        showingAuthSheet = false
                    }
                )
            }
        }
        #else
        // iOS/iPadOS: Use TabView for mobile experience
        TabView(selection: $selectedTab) {
            DeviceListView()
                .tabItem {
                    Label("Devices", systemImage: "blinds.horizontal.closed")
                }
                .tag(0)

            SetupView()
                .tabItem {
                    Label("Setup", systemImage: "gear")
                }
                .tag(1)
        }
        .onChange(of: authManager.deviceNeedingAuth) { _, newValue in
            showingAuthSheet = newValue != nil
        }
        .sheet(isPresented: $showingAuthSheet) {
            if let device = deviceNeedingAuth, let ip = device.ipAddress {
                DeviceAuthenticationSheet(
                    deviceId: device.deviceId,
                    deviceName: device.name,
                    ipAddress: ip,
                    onAuthenticated: {
                        authManager.clearAuthenticationRequest()
                        showingAuthSheet = false
                    },
                    onCancel: {
                        authManager.clearAuthenticationRequest()
                        showingAuthSheet = false
                    }
                )
            }
        }
        #endif
    }
}

#if targetEnvironment(macCatalyst)
/// Represents what is shown in the detail pane
enum DetailSelection: Hashable {
    case device(BlindDevice)
    case setup
}

struct SidebarView: View {
    @EnvironmentObject var registry: DeviceRegistry
    @Binding var selectedDevice: BlindDevice?
    @Binding var showingSetup: Bool

    /// Only WiFi-configured devices (have IP address) - BLE-only devices appear in SetupView
    var configuredDevices: [BlindDevice] {
        registry.deviceList.filter { $0.ipAddress != nil }
    }

    var body: some View {
        List {
            Section("Devices") {
                ForEach(configuredDevices) { device in
                    Button {
                        selectedDevice = device
                        showingSetup = false
                    } label: {
                        Label(device.name, systemImage: "blinds.horizontal.closed")
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(
                        selectedDevice?.deviceId == device.deviceId && !showingSetup
                            ? Color.accentColor.opacity(0.2)
                            : Color.clear
                    )
                }
            }

            Section("Setup") {
                Button {
                    showingSetup = true
                    selectedDevice = nil
                } label: {
                    Label("Add Device", systemImage: "plus.circle")
                }
                .buttonStyle(.plain)
                .listRowBackground(showingSetup ? Color.accentColor.opacity(0.2) : Color.clear)
            }
        }
        .navigationTitle("FAME Smart Blinds")
        .onChange(of: registry.deviceList) { oldValue, newValue in
            let configuredNew = newValue.filter { $0.ipAddress != nil }
            NSLog("[SidebarView] deviceList changed, configured count: \(configuredNew.count)")
            // Update selection to the new object from registry if device was refreshed
            // This handles the case where registry.clear() + rescan creates new BlindDevice objects
            if let selected = selectedDevice {
                if let updatedDevice = configuredNew.first(where: { $0.deviceId == selected.deviceId }) {
                    // Device still exists but may be a new object - update the reference
                    if updatedDevice !== selected {
                        NSLog("[SidebarView] Updating selected device reference for %@", selected.deviceId)
                        selectedDevice = updatedDevice
                    }
                } else {
                    // Device was removed from registry or no longer configured
                    NSLog("[SidebarView] Selected device was removed, clearing selection")
                    selectedDevice = nil
                }
            }
        }
    }
}
#endif

#Preview {
    ContentView()
        .environmentObject(HTTPClient.shared)
        .environmentObject(DeviceDiscovery.shared)
        .environmentObject(DeviceRegistry.shared)
}

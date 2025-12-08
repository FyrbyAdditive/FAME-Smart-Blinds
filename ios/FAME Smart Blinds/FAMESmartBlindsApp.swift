import SwiftUI

@main
struct FAMESmartBlindsApp: App {
    @StateObject private var bleManager = BLEManager.shared
    @StateObject private var httpClient = HTTPClient.shared
    @StateObject private var discovery = DeviceDiscovery.shared
    @StateObject private var registry = DeviceRegistry.shared

    @Environment(\.scenePhase) private var scenePhase

    #if targetEnvironment(macCatalyst)
    @State private var showingAbout = false
    #endif

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(bleManager)
                .environmentObject(httpClient)
                .environmentObject(discovery)
                .environmentObject(registry)
                #if targetEnvironment(macCatalyst)
                .sheet(isPresented: $showingAbout) {
                    AboutView()
                        .presentationSizing(.fitted)
                }
                #endif
        }
        #if targetEnvironment(macCatalyst)
        .defaultSize(width: 750, height: 750)
        .commands {
            CommandGroup(replacing: .newItem) { }
            CommandGroup(replacing: .appInfo) {
                Button("About FAME Smart Blinds") {
                    showingAbout = true
                }
            }
        }
        #endif
        .onChange(of: scenePhase) { _, newPhase in
            handleScenePhaseChange(newPhase)
        }
    }

    private func handleScenePhaseChange(_ phase: ScenePhase) {
        switch phase {
        case .active:
            // Start continuous mDNS discovery when app becomes active
            discovery.startContinuousDiscovery()
        case .background:
            // Stop discovery to save battery when app is backgrounded
            discovery.stopContinuousDiscovery()
        case .inactive:
            // Brief transition state - keep discovery running
            break
        @unknown default:
            break
        }
    }
}

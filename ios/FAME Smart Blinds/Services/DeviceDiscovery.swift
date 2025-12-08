import Foundation
import Network
import Combine

/// Handles mDNS/Bonjour discovery for WiFi-configured FAME Smart Blinds devices.
///
/// Supports two modes:
/// - **Continuous discovery**: Runs in background while app is active, automatically detecting devices
/// - **Manual refresh**: Immediately re-verifies all known devices when user taps refresh
///
/// BLE scanning is handled separately by BLEManager (only used in SetupView for unconfigured devices).
class DeviceDiscovery: NSObject, ObservableObject {
    static let shared = DeviceDiscovery()

    @Published var discoveredDevices: [BlindDevice] = []
    @Published var isSearching = false
    @Published private(set) var isContinuousDiscoveryActive = false

    private var browser: NWBrowser?
    private var netServiceBrowser: NetServiceBrowser?
    private var resolvedServices: [NetService] = []
    private var verificationTask: Task<Void, Never>?

    override init() {
        super.init()
    }

    // MARK: - Continuous Discovery (runs in background while app is active)

    /// Start continuous mDNS discovery. Called when app becomes active.
    /// Does NOT clear the device registry - adds/updates devices incrementally.
    func startContinuousDiscovery() {
        guard !isContinuousDiscoveryActive else {
            NSLog("[Discovery] Continuous discovery already active")
            return
        }

        NSLog("[Discovery] Starting continuous mDNS discovery")
        isContinuousDiscoveryActive = true

        // Start mDNS browser on main run loop for proper delegate callbacks
        startNetServiceBrowser()

        // Start periodic discovery cycle that restarts the browser
        // to catch devices that were already online before we started
        startPeriodicDiscovery()
    }

    private func startNetServiceBrowser() {
        netServiceBrowser?.stop()
        netServiceBrowser = NetServiceBrowser()
        netServiceBrowser?.delegate = self
        // Schedule on main run loop to ensure delegate callbacks work
        netServiceBrowser?.schedule(in: .main, forMode: .common)
        netServiceBrowser?.searchForServices(ofType: "_http._tcp.", inDomain: "local.")
        NSLog("[Discovery] NetServiceBrowser started")
    }

    /// Stop continuous discovery. Called when app goes to background.
    func stopContinuousDiscovery() {
        NSLog("[Discovery] Stopping continuous discovery")
        isContinuousDiscoveryActive = false
        isSearching = false
        netServiceBrowser?.stop()
        netServiceBrowser = nil
        browser?.cancel()
        browser = nil
        verificationTask?.cancel()
        verificationTask = nil
        resolvedServices.removeAll()
    }

    /// Trigger a manual refresh - immediately re-verifies all known devices.
    /// Called when user taps the refresh button in the sidebar/device list.
    func triggerManualRefresh() async {
        NSLog("[Discovery] Manual refresh triggered")

        await MainActor.run {
            isSearching = true
        }

        // Re-verify all known WiFi devices
        await verifyAllKnownDevices()

        // Restart the browser to catch any new announcements
        if isContinuousDiscoveryActive {
            netServiceBrowser?.stop()
            netServiceBrowser?.searchForServices(ofType: "_http._tcp.", inDomain: "local.")
        }

        await MainActor.run {
            isSearching = false
        }
    }

    // MARK: - Periodic Discovery

    private func startPeriodicDiscovery() {
        verificationTask?.cancel()
        verificationTask = Task { [weak self] in
            // Initial short delay, then restart browser to catch already-online devices
            try? await Task.sleep(nanoseconds: 2_000_000_000)

            while !Task.isCancelled {
                guard let self = self else { break }

                NSLog("[Discovery] Running periodic discovery cycle")

                // Restart the browser to trigger fresh mDNS queries
                // This catches devices that were already online and won't send new announcements
                await MainActor.run {
                    self.startNetServiceBrowser()
                }

                // Also verify any known devices directly via HTTP
                await self.verifyAllKnownDevices()

                // Wait 30 seconds before next cycle
                try? await Task.sleep(nanoseconds: 30_000_000_000)

                guard !Task.isCancelled else { break }
            }
        }
    }

    private func verifyAllKnownDevices() async {
        // Extract device info on MainActor to avoid cross-actor property access
        let deviceInfos: [(ip: String, deviceId: String)] = await MainActor.run {
            DeviceRegistry.shared.deviceList.compactMap { device in
                guard let ip = device.ipAddress else { return nil }
                return (ip: ip, deviceId: device.deviceId)
            }
        }

        guard !deviceInfos.isEmpty else { return }

        NSLog("[Discovery] Verifying %d known devices", deviceInfos.count)

        await withTaskGroup(of: Void.self) { group in
            for info in deviceInfos {
                group.addTask { [weak self] in
                    await self?.verifyDevice(at: info.ip, expectedDeviceId: info.deviceId)
                }
            }
        }
    }

    private func verifyDevice(at ipAddress: String, expectedDeviceId: String) async {
        do {
            let info = try await HTTPClient.shared.getInfo(from: ipAddress)

            guard info.device == "FAMESmartBlinds" else { return }

            // Update device in registry (refreshes lastSeen timestamp)
            await MainActor.run {
                DeviceRegistry.shared.updateFromHTTP(
                    name: info.hostname,
                    ipAddress: ipAddress,
                    deviceId: info.deviceId,
                    macAddress: info.mac
                )
            }
        } catch {
            // Device unreachable - could mark offline but we'll let it age out
            NSLog("[Discovery] Device at %@ unreachable: %@", ipAddress, error.localizedDescription)
        }
    }

    // MARK: - Legacy One-Shot Discovery (for SetupView finishSetup)

    func startDiscovery() {
        NSLog("[Discovery] Starting one-shot mDNS discovery")
        isSearching = true

        // Use the shared browser setup
        startNetServiceBrowser()

        // Stop after timeout, then ensure continuous mode is active
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
            self?.isSearching = false
            // Ensure continuous mode is running
            if !(self?.isContinuousDiscoveryActive ?? true) {
                self?.isContinuousDiscoveryActive = true
                self?.startPeriodicDiscovery()
            }
        }
    }

    func stopDiscovery() {
        NSLog("[Discovery] Stopping discovery")
        isSearching = false
        // Note: Don't stop continuous discovery here - only stopContinuousDiscovery() does that
    }

    /// Restart mDNS discovery from scratch after a delay.
    /// Used after device setup completes to quickly find the newly configured device.
    func triggerDelayedDiscovery(afterSeconds delay: TimeInterval) {
        NSLog("[Discovery] Scheduling mDNS discovery restart in %.1f seconds", delay)
        Task {
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))

            NSLog("[Discovery] Running post-setup mDNS discovery")

            // Restart the browser from scratch to find newly configured devices
            await MainActor.run {
                self.startNetServiceBrowser()
            }
        }
    }

    // MARK: - Manual IP Check

    func checkDeviceAt(ipAddress: String) async {
        print("[Discovery] Checking device at \(ipAddress)")

        do {
            let info = try await HTTPClient.shared.getInfo(from: ipAddress)

            // Verify this is a FAME Smart Blinds device by checking the device field
            guard info.device == "FAMESmartBlinds" else {
                print("[Discovery] Device at \(ipAddress) is not a FAME Smart Blinds device: \(info.device)")
                return
            }

            // Update the central device registry (single source of truth, keyed by deviceId)
            await MainActor.run {
                DeviceRegistry.shared.updateFromHTTP(
                    name: info.hostname,
                    ipAddress: ipAddress,
                    deviceId: info.deviceId,
                    macAddress: info.mac
                )
            }

            print("[Discovery] Found device: \(info.hostname) at \(ipAddress) (deviceId: \(info.deviceId))")
        } catch {
            print("[Discovery] No device at \(ipAddress): \(error.localizedDescription)")
        }
    }

    // MARK: - Network Scan

    func scanLocalNetwork() async {
        print("[Discovery] Scanning local network")

        // This is a simplified scan - in production you'd want to
        // determine the actual subnet from the device's network info
        let baseIP = "192.168.1."

        await withTaskGroup(of: Void.self) { group in
            for i in 1...254 {
                group.addTask { [weak self] in
                    let ip = "\(baseIP)\(i)"
                    await self?.checkDeviceAt(ipAddress: ip)
                }
            }
        }
    }
}

// MARK: - NetServiceBrowserDelegate

extension DeviceDiscovery: NetServiceBrowserDelegate {
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        print("[Discovery] Found service: \(service.name)")

        // Resolve all HTTP services - we'll verify if it's a FAME Smart Blinds device
        // when we query the /info endpoint. This allows renamed devices to be discovered.
        service.delegate = self
        resolvedServices.append(service)
        service.resolve(withTimeout: 5.0)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        print("[Discovery] Service removed: \(service.name)")
        resolvedServices.removeAll { $0 == service }
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        print("[Discovery] Search error: \(errorDict)")
        isSearching = false
    }

    func netServiceBrowserDidStopSearch(_ browser: NetServiceBrowser) {
        print("[Discovery] Search stopped")
        isSearching = false
    }
}

// MARK: - NetServiceDelegate

extension DeviceDiscovery: NetServiceDelegate {
    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let addresses = sender.addresses else { return }

        for addressData in addresses {
            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))

            addressData.withUnsafeBytes { ptr in
                guard let sockaddr = ptr.baseAddress?.assumingMemoryBound(to: sockaddr.self) else { return }

                if sockaddr.pointee.sa_family == UInt8(AF_INET) {
                    getnameinfo(sockaddr, socklen_t(addressData.count),
                                &hostname, socklen_t(hostname.count),
                                nil, 0, NI_NUMERICHOST)
                }
            }

            let ipAddress = String(cString: hostname)
            if !ipAddress.isEmpty && ipAddress != "0.0.0.0" {
                print("[Discovery] Resolved \(sender.name) to \(ipAddress)")

                Task {
                    await checkDeviceAt(ipAddress: ipAddress)
                }
                break
            }
        }
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        print("[Discovery] Failed to resolve \(sender.name): \(errorDict)")
    }
}

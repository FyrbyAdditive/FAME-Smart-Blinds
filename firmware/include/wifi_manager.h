#ifndef WIFI_MANAGER_H
#define WIFI_MANAGER_H

#include <Arduino.h>
#include <functional>

// WiFi connection state
enum class WifiState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    CONNECTION_FAILED
};

// Callback types
using WifiConnectedCallback = std::function<void(const String& ip)>;
using WifiDisconnectedCallback = std::function<void()>;
using WifiConnectionFailedCallback = std::function<void()>;

class WifiManager {
public:
    WifiManager();

    // Initialize WiFi (call once at startup)
    void init();

    // Connect to WiFi with stored or provided credentials
    // isInitial=true triggers onConnectionFailed callback on first failure
    bool connect(const String& ssid, const String& password, bool isInitial = false);
    bool connectWithStoredCredentials();

    // Disconnect from WiFi
    void disconnect();

    // Connection state
    WifiState getState() const;
    bool isConnected() const;

    // Network info
    String getIPAddress() const;
    String getSSID() const;
    int getRSSI() const;
    String getMacAddress() const;

    // Callbacks
    void onConnected(WifiConnectedCallback callback);
    void onDisconnected(WifiDisconnectedCallback callback);
    void onConnectionFailed(WifiConnectionFailedCallback callback);

    // Update loop (handles reconnection, call regularly)
    void update();

    // Get hostname for mDNS
    String getHostname() const;
    void setHostname(const String& hostname);

private:
    WifiState _state;
    String _ssid;
    String _password;
    String _hostname;

    unsigned long _connectStartTime;
    unsigned long _lastReconnectAttempt;
    int _reconnectAttempts;

    WifiConnectedCallback _onConnectedCallback;
    WifiDisconnectedCallback _onDisconnectedCallback;
    WifiConnectionFailedCallback _onConnectionFailedCallback;

    bool _isInitialConnection;  // True during first connection attempt after credentials set

    void handleConnectionResult();
    void startReconnect();
};

#endif // WIFI_MANAGER_H

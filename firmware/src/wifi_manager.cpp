#include "wifi_manager.h"
#include "config.h"
#include "logger.h"
#include "storage.h"
#include <WiFi.h>
#include <ESPmDNS.h>

extern Storage storage;

WifiManager::WifiManager()
    : _state(WifiState::DISCONNECTED)
    , _connectStartTime(0)
    , _lastReconnectAttempt(0)
    , _reconnectAttempts(0)
    , _onConnectedCallback(nullptr)
    , _onDisconnectedCallback(nullptr)
    , _onConnectionFailedCallback(nullptr)
    , _isInitialConnection(false)
{
}

void WifiManager::init() {
    LOG_WIFI("Initializing WiFi");

    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(false);  // We'll handle reconnection ourselves

    // Set hostname
    _hostname = storage.getDeviceName();
    _hostname.replace(" ", "-");
    _hostname.toLowerCase();

    WiFi.setHostname(_hostname.c_str());

    LOG_WIFI("WiFi initialized, hostname: %s", _hostname.c_str());
}

bool WifiManager::connect(const String& ssid, const String& password, bool isInitial) {
    if (ssid.isEmpty()) {
        LOG_WIFI("Cannot connect: SSID is empty");
        return false;
    }

    _ssid = ssid;
    _password = password;
    _state = WifiState::CONNECTING;
    _connectStartTime = millis();
    _reconnectAttempts = 0;
    _isInitialConnection = isInitial;

    LOG_WIFI("Connecting to WiFi: %s (initial: %s)", _ssid.c_str(), isInitial ? "yes" : "no");

    WiFi.begin(_ssid.c_str(), _password.c_str());

    return true;
}

bool WifiManager::connectWithStoredCredentials() {
    String ssid = storage.getWifiSsid();
    String password = storage.getWifiPassword();

    if (ssid.isEmpty()) {
        LOG_WIFI("No stored WiFi credentials");
        return false;
    }

    return connect(ssid, password, false);
}

void WifiManager::disconnect() {
    LOG_WIFI("Disconnecting from WiFi");
    WiFi.disconnect(true);
    _state = WifiState::DISCONNECTED;
}

WifiState WifiManager::getState() const {
    return _state;
}

bool WifiManager::isConnected() const {
    return _state == WifiState::CONNECTED && WiFi.status() == WL_CONNECTED;
}

String WifiManager::getIPAddress() const {
    if (!isConnected()) return "";
    return WiFi.localIP().toString();
}

String WifiManager::getSSID() const {
    return _ssid;
}

int WifiManager::getRSSI() const {
    if (!isConnected()) return 0;
    return WiFi.RSSI();
}

String WifiManager::getMacAddress() const {
    return WiFi.macAddress();
}

void WifiManager::onConnected(WifiConnectedCallback callback) {
    _onConnectedCallback = callback;
}

void WifiManager::onDisconnected(WifiDisconnectedCallback callback) {
    _onDisconnectedCallback = callback;
}

void WifiManager::onConnectionFailed(WifiConnectionFailedCallback callback) {
    _onConnectionFailedCallback = callback;
}

void WifiManager::update() {
    wl_status_t wifiStatus = WiFi.status();

    switch (_state) {
        case WifiState::CONNECTING:
            handleConnectionResult();
            break;

        case WifiState::CONNECTED:
            // Check if we've lost connection
            if (wifiStatus != WL_CONNECTED) {
                LOG_WIFI("WiFi connection lost");
                _state = WifiState::DISCONNECTED;
                if (_onDisconnectedCallback) {
                    _onDisconnectedCallback();
                }
                startReconnect();
            }
            break;

        case WifiState::DISCONNECTED:
        case WifiState::CONNECTION_FAILED:
            // Try to reconnect if we have credentials
            if (!_ssid.isEmpty()) {
                unsigned long now = millis();
                if (now - _lastReconnectAttempt >= WIFI_RECONNECT_INTERVAL_MS) {
                    startReconnect();
                }
            }
            break;
    }
}

void WifiManager::handleConnectionResult() {
    wl_status_t status = WiFi.status();

    if (status == WL_CONNECTED) {
        _state = WifiState::CONNECTED;
        _reconnectAttempts = 0;
        bool wasInitial = _isInitialConnection;
        _isInitialConnection = false;

        String ip = WiFi.localIP().toString();
        int rssi = WiFi.RSSI();

        LOG_WIFI("Connected! IP: %s, RSSI: %d dBm", ip.c_str(), rssi);

        // Start mDNS
        if (MDNS.begin(_hostname.c_str())) {
            MDNS.addService("http", "tcp", HTTP_PORT);
            MDNS.addService("famesmartblinds", "tcp", HTTP_PORT);
            LOG_WIFI("mDNS started: %s.local", _hostname.c_str());
        }

        if (_onConnectedCallback) {
            _onConnectedCallback(ip);
        }
    }
    else if (status == WL_CONNECT_FAILED ||
             status == WL_NO_SSID_AVAIL ||
             (millis() - _connectStartTime > WIFI_CONNECT_TIMEOUT_MS)) {

        LOG_WIFI("Connection failed (status: %d, initial: %s)", status, _isInitialConnection ? "yes" : "no");
        _state = WifiState::CONNECTION_FAILED;
        _lastReconnectAttempt = millis();
        _reconnectAttempts++;

        // On initial connection attempt failure, notify via callback immediately
        // This allows the app to show an error to the user right away
        if (_isInitialConnection) {
            _isInitialConnection = false;
            if (_onConnectionFailedCallback) {
                _onConnectionFailedCallback();
            }
        }

        if (_reconnectAttempts >= WIFI_MAX_RECONNECT_ATTEMPTS) {
            LOG_WIFI("Max reconnect attempts reached");
        }
    }
    // else still connecting, wait...
}

void WifiManager::startReconnect() {
    if (_reconnectAttempts >= WIFI_MAX_RECONNECT_ATTEMPTS) {
        return;  // Give up after max attempts
    }

    _lastReconnectAttempt = millis();
    _reconnectAttempts++;

    LOG_WIFI("Reconnection attempt %d/%d", _reconnectAttempts, WIFI_MAX_RECONNECT_ATTEMPTS);

    WiFi.disconnect();
    delay(100);
    _state = WifiState::CONNECTING;
    _connectStartTime = millis();
    WiFi.begin(_ssid.c_str(), _password.c_str());
}

String WifiManager::getHostname() const {
    return _hostname;
}

void WifiManager::setHostname(const String& hostname) {
    _hostname = hostname;
    _hostname.replace(" ", "-");
    _hostname.toLowerCase();
    WiFi.setHostname(_hostname.c_str());
}

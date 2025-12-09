#ifndef BLE_PROVISIONING_H
#define BLE_PROVISIONING_H

#include <Arduino.h>
#include <functional>

// Callback types
using BleWifiConfigCallback = std::function<void(const String& ssid, const String& password)>;
using BleMqttConfigCallback = std::function<void(const String& broker, uint16_t port)>;
using BleDeviceNameCallback = std::function<void(const String& name)>;
using BleDevicePasswordCallback = std::function<void(const String& password)>;
using BleOrientationCallback = std::function<void(const String& orientation)>;
using BleCommandCallback = std::function<void(const String& command)>;
using BleWifiScanCallback = std::function<void()>;

class BleProvisioning {
public:
    BleProvisioning();

    // Initialize BLE
    void init(const String& deviceName);

    // Start/stop advertising
    void startAdvertising();
    void stopAdvertising();

    // Check if a client is connected
    bool isClientConnected() const;

    // Check if advertising
    bool isAdvertising() const;

    // Update status characteristic (notifies connected clients)
    void updateStatus(const String& status);

    // Callbacks
    void onWifiConfig(BleWifiConfigCallback callback);
    void onMqttConfig(BleMqttConfigCallback callback);
    void onDeviceName(BleDeviceNameCallback callback);
    void onDevicePassword(BleDevicePasswordCallback callback);
    void onOrientation(BleOrientationCallback callback);
    void onCommand(BleCommandCallback callback);
    void onWifiScanRequest(BleWifiScanCallback callback);

    // Set WiFi scan results (notifies connected clients)
    void setWifiScanResults(const String& results);

    // Set current values (for read characteristics)
    void setCurrentSsid(const String& ssid);
    void setCurrentDeviceName(const String& name);
    void setCurrentMqttBroker(const String& broker);
    void setCurrentOrientation(const String& orientation);

private:
    String _deviceName;
    bool _initialized;
    bool _advertising;
    bool _clientConnected;

    String _currentSsid;
    String _currentDeviceName;
    String _currentMqttBroker;
    String _currentOrientation;

    BleWifiConfigCallback _wifiConfigCallback;
    BleMqttConfigCallback _mqttConfigCallback;
    BleDeviceNameCallback _deviceNameCallback;
    BleDevicePasswordCallback _devicePasswordCallback;
    BleOrientationCallback _orientationCallback;
    BleCommandCallback _commandCallback;
    BleWifiScanCallback _wifiScanCallback;

    void setupService();

    // Friend class for callbacks
    friend class CharacteristicCallbacks;
};

#endif // BLE_PROVISIONING_H

#include "ble_provisioning.h"
#include "config.h"
#include "logger.h"
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <esp_gap_ble_api.h>

// Global BLE objects
static BLEServer* pServer = nullptr;
static BLEService* pService = nullptr;
static BLECharacteristic* pCharSsid = nullptr;
static BLECharacteristic* pCharPassword = nullptr;
static BLECharacteristic* pCharDeviceName = nullptr;
static BLECharacteristic* pCharDevicePassword = nullptr;
static BLECharacteristic* pCharMqttBroker = nullptr;
static BLECharacteristic* pCharOrientation = nullptr;
static BLECharacteristic* pCharStatus = nullptr;
static BLECharacteristic* pCharCommand = nullptr;
static BLECharacteristic* pCharWifiScanTrigger = nullptr;
static BLECharacteristic* pCharWifiScanResults = nullptr;

// Static pointer to provisioning instance for callbacks
static BleProvisioning* bleInstance = nullptr;

// Server callbacks
class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) override {
        LOG_BLE("Client connected");
        if (bleInstance) {
            // Update internal state through friend access or public method
        }
    }

    void onDisconnect(BLEServer* pServer) override {
        LOG_BLE("Client disconnected");
        // Restart advertising
        if (bleInstance && bleInstance->isAdvertising()) {
            pServer->startAdvertising();
        }
    }
};

// Characteristic callbacks
class CharacteristicCallbacks : public BLECharacteristicCallbacks {
public:
    enum CharType {
        WIFI_SSID,
        WIFI_PASSWORD,
        DEVICE_NAME,
        DEVICE_PASSWORD,
        MQTT_BROKER,
        ORIENTATION,
        COMMAND,
        WIFI_SCAN_TRIGGER
    };

    CharacteristicCallbacks(CharType type) : _type(type) {}

    void onWrite(BLECharacteristic* pCharacteristic) override {
        String value = pCharacteristic->getValue().c_str();

        switch (_type) {
            case WIFI_SSID:
                LOG_BLE("WiFi SSID received: %s", value.c_str());
                _pendingSsid = value;
                break;

            case WIFI_PASSWORD:
                LOG_BLE("WiFi password received (length: %d)", value.length());
                // When password is written, trigger the callback with both SSID and password
                if (bleInstance && !_pendingSsid.isEmpty()) {
                    if (bleInstance->_wifiConfigCallback) {
                        bleInstance->_wifiConfigCallback(_pendingSsid, value);
                    }
                }
                break;

            case DEVICE_NAME:
                LOG_BLE("Device name received: %s", value.c_str());
                if (bleInstance && bleInstance->_deviceNameCallback) {
                    bleInstance->_deviceNameCallback(value);
                }
                break;

            case DEVICE_PASSWORD:
                LOG_BLE("Device password received (length: %d)", value.length());
                if (bleInstance && bleInstance->_devicePasswordCallback) {
                    bleInstance->_devicePasswordCallback(value);
                }
                break;

            case MQTT_BROKER:
                LOG_BLE("MQTT broker received: %s", value.c_str());
                if (bleInstance && bleInstance->_mqttConfigCallback) {
                    // Parse broker:port format
                    int colonPos = value.indexOf(':');
                    String host = value;
                    uint16_t port = MQTT_PORT;
                    if (colonPos > 0) {
                        host = value.substring(0, colonPos);
                        port = value.substring(colonPos + 1).toInt();
                    }
                    bleInstance->_mqttConfigCallback(host, port);
                }
                break;

            case ORIENTATION:
                LOG_BLE("Orientation received: %s", value.c_str());
                if (bleInstance && bleInstance->_orientationCallback) {
                    bleInstance->_orientationCallback(value);
                }
                break;

            case COMMAND:
                LOG_BLE("Command received: %s", value.c_str());
                if (bleInstance && bleInstance->_commandCallback) {
                    bleInstance->_commandCallback(value);
                }
                break;

            case WIFI_SCAN_TRIGGER:
                LOG_BLE("WiFi scan trigger received: %s", value.c_str());
                if (value == "SCAN" && bleInstance && bleInstance->_wifiScanCallback) {
                    bleInstance->_wifiScanCallback();
                }
                break;
        }
    }

private:
    CharType _type;
    static String _pendingSsid;
};

String CharacteristicCallbacks::_pendingSsid = "";

BleProvisioning::BleProvisioning()
    : _initialized(false)
    , _advertising(false)
    , _clientConnected(false)
    , _wifiConfigCallback(nullptr)
    , _mqttConfigCallback(nullptr)
    , _deviceNameCallback(nullptr)
    , _devicePasswordCallback(nullptr)
    , _orientationCallback(nullptr)
    , _commandCallback(nullptr)
{
    bleInstance = this;
}

void BleProvisioning::init(const String& deviceName) {
    _deviceName = deviceName;

    // Use the provided device name which includes the unique ID suffix
    // e.g., "FAMEBlinds_23c57e80" - this matches the mDNS hostname
    // BLE advertising name max is ~29 bytes, our format is ~22 bytes so it fits
    String bleName = deviceName;

    LOG_BLE("Initializing BLE with name: %s", bleName.c_str());

    // Initialize BLE with the correct name from the start
    // No deinit needed - just init with the right name
    BLEDevice::init(bleName.c_str());

    // Force set the device name again to override any cached value
    esp_ble_gap_set_device_name(bleName.c_str());

    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    setupService();

    _initialized = true;
    LOG_BLE("BLE initialized");
}

void BleProvisioning::setupService() {
    // Create service with enough handles for 10 characteristics
    // Each characteristic needs ~3 handles (decl + value + optional descriptor)
    // 10 characteristics * 3 = 30, plus 1 for service = 31, round up to 35
    pService = pServer->createService(BLEUUID(BLE_SERVICE_UUID), 35);

    // WiFi SSID characteristic (read/write)
    pCharSsid = pService->createCharacteristic(
        BLE_CHAR_WIFI_SSID_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE
    );
    pCharSsid->setCallbacks(new CharacteristicCallbacks(CharacteristicCallbacks::WIFI_SSID));
    pCharSsid->setValue(_currentSsid.c_str());

    // WiFi Password characteristic (write only)
    pCharPassword = pService->createCharacteristic(
        BLE_CHAR_WIFI_PASS_UUID,
        BLECharacteristic::PROPERTY_WRITE
    );
    pCharPassword->setCallbacks(new CharacteristicCallbacks(CharacteristicCallbacks::WIFI_PASSWORD));

    // Device Name characteristic (read/write)
    pCharDeviceName = pService->createCharacteristic(
        BLE_CHAR_DEVICE_NAME_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE
    );
    pCharDeviceName->setCallbacks(new CharacteristicCallbacks(CharacteristicCallbacks::DEVICE_NAME));
    pCharDeviceName->setValue(_currentDeviceName.c_str());

    // Device Password characteristic (write only - never readable for security)
    pCharDevicePassword = pService->createCharacteristic(
        BLE_CHAR_DEVICE_PASS_UUID,
        BLECharacteristic::PROPERTY_WRITE
    );
    pCharDevicePassword->setCallbacks(new CharacteristicCallbacks(CharacteristicCallbacks::DEVICE_PASSWORD));

    // MQTT Broker characteristic (read/write)
    pCharMqttBroker = pService->createCharacteristic(
        BLE_CHAR_MQTT_BROKER_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE
    );
    pCharMqttBroker->setCallbacks(new CharacteristicCallbacks(CharacteristicCallbacks::MQTT_BROKER));
    pCharMqttBroker->setValue(_currentMqttBroker.c_str());

    // Orientation characteristic (read/write) - "left" or "right"
    pCharOrientation = pService->createCharacteristic(
        BLE_CHAR_ORIENTATION_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE
    );
    pCharOrientation->setCallbacks(new CharacteristicCallbacks(CharacteristicCallbacks::ORIENTATION));
    pCharOrientation->setValue(_currentOrientation.c_str());

    // Status characteristic (read/notify)
    pCharStatus = pService->createCharacteristic(
        BLE_CHAR_STATUS_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
    );
    pCharStatus->addDescriptor(new BLE2902());
    pCharStatus->setValue("initialized");

    // Command characteristic (write)
    pCharCommand = pService->createCharacteristic(
        BLE_CHAR_COMMAND_UUID,
        BLECharacteristic::PROPERTY_WRITE
    );
    pCharCommand->setCallbacks(new CharacteristicCallbacks(CharacteristicCallbacks::COMMAND));

    // WiFi Scan Trigger characteristic (write)
    pCharWifiScanTrigger = pService->createCharacteristic(
        BLE_CHAR_WIFI_SCAN_TRIGGER_UUID,
        BLECharacteristic::PROPERTY_WRITE
    );
    pCharWifiScanTrigger->setCallbacks(new CharacteristicCallbacks(CharacteristicCallbacks::WIFI_SCAN_TRIGGER));

    // WiFi Scan Results characteristic (read/notify)
    pCharWifiScanResults = pService->createCharacteristic(
        BLE_CHAR_WIFI_SCAN_RESULTS_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
    );
    pCharWifiScanResults->addDescriptor(new BLE2902());
    pCharWifiScanResults->setValue("");

    // Start service
    pService->start();

    LOG_BLE("BLE service created with 10 characteristics");
}

void BleProvisioning::startAdvertising() {
    if (!_initialized) {
        LOG_ERROR("BLE not initialized");
        return;
    }

    // Simple advertising setup - known to work on ESP32-C3
    BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(BLE_SERVICE_UUID);
    pAdvertising->setScanResponse(true);

    // Start advertising
    BLEDevice::startAdvertising();
    _advertising = true;

    LOG_BLE("BLE advertising started");
}

void BleProvisioning::stopAdvertising() {
    if (!_initialized) return;

    BLEDevice::stopAdvertising();
    _advertising = false;

    LOG_BLE("BLE advertising stopped");
}

bool BleProvisioning::isClientConnected() const {
    if (!pServer) return false;
    return pServer->getConnectedCount() > 0;
}

bool BleProvisioning::isAdvertising() const {
    return _advertising;
}

void BleProvisioning::updateStatus(const String& status) {
    if (!_initialized || !pCharStatus) return;

    LOG_BLE("Updating status: %s", status.c_str());
    pCharStatus->setValue(status.c_str());
    pCharStatus->notify();
}

void BleProvisioning::onWifiConfig(BleWifiConfigCallback callback) {
    _wifiConfigCallback = callback;
}

void BleProvisioning::onMqttConfig(BleMqttConfigCallback callback) {
    _mqttConfigCallback = callback;
}

void BleProvisioning::onDeviceName(BleDeviceNameCallback callback) {
    _deviceNameCallback = callback;
}

void BleProvisioning::onDevicePassword(BleDevicePasswordCallback callback) {
    _devicePasswordCallback = callback;
}

void BleProvisioning::onOrientation(BleOrientationCallback callback) {
    _orientationCallback = callback;
}

void BleProvisioning::onCommand(BleCommandCallback callback) {
    _commandCallback = callback;
}

void BleProvisioning::setCurrentSsid(const String& ssid) {
    _currentSsid = ssid;
    if (pCharSsid) {
        pCharSsid->setValue(ssid.c_str());
    }
}

void BleProvisioning::setCurrentDeviceName(const String& name) {
    _currentDeviceName = name;
    if (pCharDeviceName) {
        pCharDeviceName->setValue(name.c_str());
    }
}

void BleProvisioning::setCurrentMqttBroker(const String& broker) {
    _currentMqttBroker = broker;
    if (pCharMqttBroker) {
        pCharMqttBroker->setValue(broker.c_str());
    }
}

void BleProvisioning::setCurrentOrientation(const String& orientation) {
    _currentOrientation = orientation;
    if (pCharOrientation) {
        pCharOrientation->setValue(orientation.c_str());
    }
}

void BleProvisioning::onWifiScanRequest(BleWifiScanCallback callback) {
    _wifiScanCallback = callback;
}

void BleProvisioning::setWifiScanResults(const String& results) {
    if (!_initialized || !pCharWifiScanResults) return;

    LOG_BLE("Setting WiFi scan results: %s", results.c_str());
    pCharWifiScanResults->setValue(results.c_str());
    pCharWifiScanResults->notify();
}

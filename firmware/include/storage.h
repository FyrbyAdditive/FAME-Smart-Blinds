#ifndef STORAGE_H
#define STORAGE_H

#include <Arduino.h>

// Configuration structure stored in NVS
struct DeviceConfig {
    char wifiSsid[64];
    char wifiPassword[64];
    char deviceName[32];
    char mqttBroker[64];
    char mqttUser[32];
    char mqttPassword[64];
    uint16_t mqttPort;
    uint8_t servoId;

    // Default constructor
    DeviceConfig() {
        memset(wifiSsid, 0, sizeof(wifiSsid));
        memset(wifiPassword, 0, sizeof(wifiPassword));
        memset(deviceName, 0, sizeof(deviceName));
        memset(mqttBroker, 0, sizeof(mqttBroker));
        memset(mqttUser, 0, sizeof(mqttUser));
        memset(mqttPassword, 0, sizeof(mqttPassword));
        mqttPort = 1883;
        servoId = 1;
    }

    bool hasWifiCredentials() const {
        return strlen(wifiSsid) > 0;
    }

    bool hasMqttConfig() const {
        return strlen(mqttBroker) > 0;
    }
};

class Storage {
public:
    Storage();

    // Initialize NVS storage
    bool init();

    // Load/save entire config
    bool loadConfig(DeviceConfig& config);
    bool saveConfig(const DeviceConfig& config);

    // Individual value getters/setters
    String getWifiSsid();
    String getWifiPassword();
    String getDeviceName();
    String getMqttBroker();
    String getMqttUser();
    String getMqttPassword();
    uint16_t getMqttPort();
    uint8_t getServoId();

    bool setWifiCredentials(const String& ssid, const String& password);
    bool setDeviceName(const String& name);
    bool setDevicePassword(const String& password);
    String getDevicePassword();
    bool setMqttConfig(const String& broker, uint16_t port = 1883,
                       const String& user = "", const String& password = "");
    bool setServoId(uint8_t id);

    // Calibration data
    int32_t getMaxPosition();
    bool setMaxPosition(int32_t pos);
    int32_t getCurrentPosition();
    bool setCurrentPosition(int32_t pos);
    bool isCalibrated();
    bool setCalibrated(bool cal);
    bool getAutoHome();
    bool setAutoHome(bool val);

    // Power outage recovery
    bool getWasMoving();
    bool setWasMoving(bool moving);
    int32_t getTargetPosition();
    bool setTargetPosition(int32_t pos);

    // Device orientation (for servo direction)
    String getOrientation();  // Returns "left" or "right"
    bool setOrientation(const String& orientation);
    bool isRightMount();  // Convenience method

    // Servo speed (0-4095)
    uint16_t getServoSpeed();
    bool setServoSpeed(uint16_t speed);

    // Setup state (BLE is only enabled until setup is complete)
    bool isSetupComplete();
    bool setSetupComplete(bool complete);

    // Clear all stored data
    bool clearAll();

    // Factory reset - clear all settings
    bool factoryReset();

    // Get device MAC address as string (for unique IDs)
    static String getMacAddress();
    static String getDeviceId();  // Short form for topics

private:
    bool _initialized;

    // Internal helper methods
    String getString(const char* key, const char* defaultValue = "");
    bool setString(const char* key, const String& value);
    uint16_t getUInt16(const char* key, uint16_t defaultValue = 0);
    bool setUInt16(const char* key, uint16_t value);
    uint8_t getUInt8(const char* key, uint8_t defaultValue = 0);
    bool setUInt8(const char* key, uint8_t value);
    int32_t getInt32(const char* key, int32_t defaultValue = 0);
    bool setInt32(const char* key, int32_t value);
    bool getBool(const char* key, bool defaultValue = false);
    bool setBool(const char* key, bool value);
};

#endif // STORAGE_H

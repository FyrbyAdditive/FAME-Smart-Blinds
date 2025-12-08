#include "storage.h"
#include "config.h"
#include "logger.h"
#include <Preferences.h>
#include <WiFi.h>

static Preferences preferences;

Storage::Storage() : _initialized(false) {
}

bool Storage::init() {
    LOG_NVS("Initializing NVS storage");

    if (!preferences.begin(NVS_NAMESPACE, false)) {
        LOG_ERROR("Failed to initialize NVS namespace: %s", NVS_NAMESPACE);
        return false;
    }

    _initialized = true;
    LOG_NVS("NVS initialized successfully");
    return true;
}

bool Storage::loadConfig(DeviceConfig& config) {
    if (!_initialized) {
        LOG_ERROR("NVS not initialized");
        return false;
    }

    LOG_NVS("Loading configuration from NVS");

    String ssid = getString(NVS_KEY_WIFI_SSID);
    String pass = getString(NVS_KEY_WIFI_PASS);
    // Use empty string as default - main.cpp will handle the deviceId suffix
    String name = getString(NVS_KEY_DEVICE_NAME, "");
    String broker = getString(NVS_KEY_MQTT_BROKER);
    String mqttUser = getString(NVS_KEY_MQTT_USER);
    String mqttPass = getString(NVS_KEY_MQTT_PASS);

    strncpy(config.wifiSsid, ssid.c_str(), sizeof(config.wifiSsid) - 1);
    strncpy(config.wifiPassword, pass.c_str(), sizeof(config.wifiPassword) - 1);
    strncpy(config.deviceName, name.c_str(), sizeof(config.deviceName) - 1);
    strncpy(config.mqttBroker, broker.c_str(), sizeof(config.mqttBroker) - 1);
    strncpy(config.mqttUser, mqttUser.c_str(), sizeof(config.mqttUser) - 1);
    strncpy(config.mqttPassword, mqttPass.c_str(), sizeof(config.mqttPassword) - 1);

    config.mqttPort = getUInt16("mqtt_port", MQTT_PORT);
    config.servoId = getUInt8(NVS_KEY_SERVO_ID, DEFAULT_SERVO_ID);

    LOG_NVS("Config loaded - WiFi SSID: %s, Device: %s, MQTT: %s:%d",
            config.wifiSsid, config.deviceName, config.mqttBroker, config.mqttPort);

    return true;
}

bool Storage::saveConfig(const DeviceConfig& config) {
    if (!_initialized) {
        LOG_ERROR("NVS not initialized");
        return false;
    }

    LOG_NVS("Saving configuration to NVS");

    bool success = true;
    success &= setString(NVS_KEY_WIFI_SSID, config.wifiSsid);
    success &= setString(NVS_KEY_WIFI_PASS, config.wifiPassword);
    success &= setString(NVS_KEY_DEVICE_NAME, config.deviceName);
    success &= setString(NVS_KEY_MQTT_BROKER, config.mqttBroker);
    success &= setString(NVS_KEY_MQTT_USER, config.mqttUser);
    success &= setString(NVS_KEY_MQTT_PASS, config.mqttPassword);
    success &= setUInt16("mqtt_port", config.mqttPort);
    success &= setUInt8(NVS_KEY_SERVO_ID, config.servoId);

    if (success) {
        LOG_NVS("Configuration saved successfully");
    } else {
        LOG_ERROR("Failed to save some configuration values");
    }

    return success;
}

String Storage::getWifiSsid() {
    return getString(NVS_KEY_WIFI_SSID);
}

String Storage::getWifiPassword() {
    return getString(NVS_KEY_WIFI_PASS);
}

String Storage::getDeviceName() {
    String name = getString(NVS_KEY_DEVICE_NAME);
    String baseName = name.isEmpty() ? String(DEVICE_NAME_PREFIX) : name;
    // Always include the deviceId suffix for consistent identification across BLE/WiFi
    return baseName + "_" + getDeviceId();
}

String Storage::getMqttBroker() {
    return getString(NVS_KEY_MQTT_BROKER);
}

String Storage::getMqttUser() {
    return getString(NVS_KEY_MQTT_USER);
}

String Storage::getMqttPassword() {
    return getString(NVS_KEY_MQTT_PASS);
}

uint16_t Storage::getMqttPort() {
    return getUInt16("mqtt_port", MQTT_PORT);
}

uint8_t Storage::getServoId() {
    return getUInt8(NVS_KEY_SERVO_ID, DEFAULT_SERVO_ID);
}

bool Storage::setWifiCredentials(const String& ssid, const String& password) {
    LOG_NVS("Setting WiFi credentials for SSID: %s", ssid.c_str());
    bool success = setString(NVS_KEY_WIFI_SSID, ssid);
    success &= setString(NVS_KEY_WIFI_PASS, password);
    return success;
}

bool Storage::setDeviceName(const String& name) {
    LOG_NVS("Setting device name: %s", name.c_str());
    return setString(NVS_KEY_DEVICE_NAME, name);
}

bool Storage::setDevicePassword(const String& password) {
    LOG_NVS("Setting device password (length: %d)", password.length());
    return setString(NVS_KEY_DEVICE_PASS, password);
}

String Storage::getDevicePassword() {
    return getString(NVS_KEY_DEVICE_PASS);
}

bool Storage::setMqttConfig(const String& broker, uint16_t port,
                            const String& user, const String& password) {
    LOG_NVS("Setting MQTT config - broker: %s:%d", broker.c_str(), port);
    bool success = setString(NVS_KEY_MQTT_BROKER, broker);
    success &= setUInt16("mqtt_port", port);
    success &= setString(NVS_KEY_MQTT_USER, user);
    success &= setString(NVS_KEY_MQTT_PASS, password);
    return success;
}

bool Storage::setServoId(uint8_t id) {
    LOG_NVS("Setting servo ID: %d", id);
    return setUInt8(NVS_KEY_SERVO_ID, id);
}

// Calibration methods
int32_t Storage::getMaxPosition() {
    return getInt32(NVS_KEY_MAX_POSITION, 0);
}

bool Storage::setMaxPosition(int32_t pos) {
    LOG_NVS("Setting max position: %d", pos);
    return setInt32(NVS_KEY_MAX_POSITION, pos);
}

int32_t Storage::getCurrentPosition() {
    return getInt32(NVS_KEY_CURRENT_POSITION, 0);
}

bool Storage::setCurrentPosition(int32_t pos) {
    // Don't log every position save to avoid spam
    return setInt32(NVS_KEY_CURRENT_POSITION, pos);
}

bool Storage::isCalibrated() {
    return getBool(NVS_KEY_CALIBRATED, false);
}

bool Storage::setCalibrated(bool cal) {
    LOG_NVS("Setting calibrated: %s", cal ? "true" : "false");
    return setBool(NVS_KEY_CALIBRATED, cal);
}

bool Storage::getAutoHome() {
    return getBool(NVS_KEY_AUTO_HOME, false);
}

bool Storage::setAutoHome(bool val) {
    LOG_NVS("Setting auto-home: %s", val ? "true" : "false");
    return setBool(NVS_KEY_AUTO_HOME, val);
}

// Power outage recovery methods
bool Storage::getWasMoving() {
    return getBool(NVS_KEY_WAS_MOVING, false);
}

bool Storage::setWasMoving(bool moving) {
    return setBool(NVS_KEY_WAS_MOVING, moving);
}

int32_t Storage::getTargetPosition() {
    return getInt32(NVS_KEY_TARGET_POSITION, 0);
}

bool Storage::setTargetPosition(int32_t pos) {
    return setInt32(NVS_KEY_TARGET_POSITION, pos);
}

String Storage::getOrientation() {
    return getString(NVS_KEY_ORIENTATION, "left");  // Default to left mount
}

bool Storage::setOrientation(const String& orientation) {
    // Validate - only allow "left" or "right"
    if (orientation != "left" && orientation != "right") {
        LOG_ERROR("Invalid orientation: %s (must be 'left' or 'right')", orientation.c_str());
        return false;
    }
    LOG_NVS("Setting orientation: %s", orientation.c_str());
    return setString(NVS_KEY_ORIENTATION, orientation);
}

bool Storage::isRightMount() {
    return getOrientation() == "right";
}

uint16_t Storage::getServoSpeed() {
    return getUInt16(NVS_KEY_SERVO_SPEED, SERVO_SPEED);  // Default from config.h
}

bool Storage::setServoSpeed(uint16_t speed) {
    LOG_NVS("Setting servo speed: %d", speed);
    return setUInt16(NVS_KEY_SERVO_SPEED, speed);
}

bool Storage::isSetupComplete() {
    return getBool(NVS_KEY_SETUP_COMPLETE, false);
}

bool Storage::setSetupComplete(bool complete) {
    LOG_NVS("Setting setup complete: %s", complete ? "true" : "false");
    return setBool(NVS_KEY_SETUP_COMPLETE, complete);
}

bool Storage::clearAll() {
    if (!_initialized) {
        LOG_ERROR("NVS not initialized");
        return false;
    }

    LOG_NVS("Clearing all stored data");
    bool success = preferences.clear();
    if (success) {
        LOG_NVS("All data cleared");
    } else {
        LOG_ERROR("Failed to clear NVS");
    }
    return success;
}

bool Storage::factoryReset() {
    LOG_NVS("=== FACTORY RESET ===");
    LOG_NVS("Erasing all configuration...");

    bool success = clearAll();

    if (success) {
        LOG_NVS("Factory reset complete - all settings erased");
    } else {
        LOG_ERROR("Factory reset failed");
    }

    return success;
}

String Storage::getMacAddress() {
    uint8_t mac[6];
    WiFi.macAddress(mac);

    char macStr[18];
    snprintf(macStr, sizeof(macStr), "%02X:%02X:%02X:%02X:%02X:%02X",
             mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);

    return String(macStr);
}

String Storage::getDeviceId() {
    uint8_t mac[6];
    WiFi.macAddress(mac);

    char idStr[9];
    snprintf(idStr, sizeof(idStr), "%02x%02x%02x%02x",
             mac[2], mac[3], mac[4], mac[5]);

    return String(idStr);
}

// Private helper methods
String Storage::getString(const char* key, const char* defaultValue) {
    if (!_initialized) return String(defaultValue);
    return preferences.getString(key, defaultValue);
}

bool Storage::setString(const char* key, const String& value) {
    if (!_initialized) return false;
    return preferences.putString(key, value) > 0;
}

uint16_t Storage::getUInt16(const char* key, uint16_t defaultValue) {
    if (!_initialized) return defaultValue;
    return preferences.getUShort(key, defaultValue);
}

bool Storage::setUInt16(const char* key, uint16_t value) {
    if (!_initialized) return false;
    return preferences.putUShort(key, value) > 0;
}

uint8_t Storage::getUInt8(const char* key, uint8_t defaultValue) {
    if (!_initialized) return defaultValue;
    return preferences.getUChar(key, defaultValue);
}

bool Storage::setUInt8(const char* key, uint8_t value) {
    if (!_initialized) return false;
    return preferences.putUChar(key, value) > 0;
}

int32_t Storage::getInt32(const char* key, int32_t defaultValue) {
    if (!_initialized) return defaultValue;
    return preferences.getInt(key, defaultValue);
}

bool Storage::setInt32(const char* key, int32_t value) {
    if (!_initialized) return false;
    return preferences.putInt(key, value) > 0;
}

bool Storage::getBool(const char* key, bool defaultValue) {
    if (!_initialized) return defaultValue;
    return preferences.getBool(key, defaultValue);
}

bool Storage::setBool(const char* key, bool value) {
    if (!_initialized) return false;
    return preferences.putBool(key, value);
}

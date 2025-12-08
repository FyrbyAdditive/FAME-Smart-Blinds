#include <Arduino.h>
#include "config.h"
#include "logger.h"
#include "storage.h"
#include "servo_controller.h"
#include "hall_sensor.h"
#include "wifi_manager.h"
#include "http_server.h"
#include "mqtt_client.h"
#include "ble_provisioning.h"

// Global instances
Storage storage;
ServoController servo;
HallSensor hallSensor;
WifiManager wifi;
HttpServer httpServer;
MqttClient mqtt;
BleProvisioning ble;

// Device configuration
DeviceConfig config;

// State tracking
bool wifiWasConnected = false;

// Forward declarations
void handleCommand(const String& command);
void onWifiConnected(const String& ip);
void onWifiDisconnected();
void onWifiConnectionFailed();
void onBleWifiConfig(const String& ssid, const String& password);
void onBleMqttConfig(const String& broker, uint16_t port);
void onBleDeviceName(const String& name);
void onBleDevicePassword(const String& password);
void onBleOrientation(const String& orientation);
void onBleCommand(const String& command);
void updateBleStatus();

void setup() {
    // Initialize USB serial for debugging
    Logger::init(115200);
    Logger::waitForSerial(3000);

    LOG_BOOT("========================================");
    LOG_BOOT("FAME Smart Blinds v%s starting...", FIRMWARE_VERSION);
    LOG_BOOT("========================================");

    // Initialize storage
    if (!storage.init()) {
        LOG_ERROR("Failed to initialize storage!");
    }

    // Load configuration
    storage.loadConfig(config);

    LOG_BOOT("Device ID: %s", Storage::getDeviceId().c_str());
    LOG_BOOT("MAC Address: %s", Storage::getMacAddress().c_str());
    LOG_BOOT("Stored device name: '%s' (first char: %d)", config.deviceName, (int)config.deviceName[0]);

    // Initialize hall sensor for home position detection
    hallSensor.init(HALL_SENSOR_PIN);
    LOG_BOOT("Hall sensor initialized on pin %d", HALL_SENSOR_PIN);

    // Initialize servo controller
    uint8_t servoId = config.servoId > 0 ? config.servoId : DEFAULT_SERVO_ID;
    if (servo.init(servoId, SERVO_RX_PIN, SERVO_TX_PIN)) {
        LOG_SERVO("Servo initialized successfully");
    } else {
        LOG_ERROR("Servo initialization failed - will retry");
    }

    // Wire up hall sensor and storage to servo controller
    servo.setHallSensor(&hallSensor);
    servo.setStorage(&storage);

    // Check for and start power outage recovery if needed
    if (servo.needsRecovery()) {
        LOG_BOOT("Power outage recovery needed - will start after init complete");
    }

    // Load orientation setting for direction inversion
    bool isRightMount = storage.isRightMount();
    servo.setInvertDirection(isRightMount);
    LOG_BOOT("Orientation: %s mount", isRightMount ? "right" : "left");

    // Load speed setting
    uint16_t speed = storage.getServoSpeed();
    servo.setSpeed(speed);
    LOG_BOOT("Servo speed: %d", speed);

    // BLE is only used for initial setup - disabled after WiFi is configured
    // This simplifies the architecture and reduces power consumption
    bool setupComplete = storage.isSetupComplete();
    LOG_BLE("Setup complete flag: %s", setupComplete ? "true" : "false");

    // IMPORTANT: Initialize BLE BEFORE WiFi on ESP32-C3
    // Initialize BLE provisioning (needed even if not advertising, for potential factory reset)
    String fullDeviceName = storage.getDeviceName();
    LOG_BLE("Using BLE name: '%s'", fullDeviceName.c_str());
    ble.init(fullDeviceName);
    ble.setCurrentSsid(config.wifiSsid);
    // Extract base name (without suffix) for display in BLE characteristic
    String baseName = config.deviceName[0] ? config.deviceName : DEVICE_NAME_PREFIX;
    ble.setCurrentDeviceName(baseName);
    ble.setCurrentMqttBroker(config.mqttBroker);
    ble.setCurrentOrientation(storage.getOrientation());

    // Set BLE callbacks
    ble.onWifiConfig(onBleWifiConfig);
    ble.onMqttConfig(onBleMqttConfig);
    ble.onDeviceName(onBleDeviceName);
    ble.onDevicePassword(onBleDevicePassword);
    ble.onOrientation(onBleOrientation);
    ble.onCommand(onBleCommand);

    // Only start BLE advertising if setup is not complete
    // After initial setup, all device management is done over WiFi/HTTP
    if (!setupComplete) {
        ble.startAdvertising();
        LOG_BLE("BLE advertising started - device in setup mode");
    } else {
        LOG_BLE("BLE disabled - setup complete, use WiFi for management");
    }

    // Now initialize WiFi manager (after BLE is running)
    wifi.init();
    wifi.onConnected(onWifiConnected);
    wifi.onDisconnected(onWifiDisconnected);
    wifi.onConnectionFailed(onWifiConnectionFailed);

    // Set HTTP command callback
    httpServer.onCommand(handleCommand);

    // Set up log broadcast callback (for SSE log streaming)
    Logger::setLogBroadcastCallback([](const char* logEntry) {
        httpServer.broadcastLog(logEntry);
    });

    // Set MQTT command callback
    mqtt.onCommand(handleCommand);

    // Try to connect to WiFi if credentials are stored
    if (config.hasWifiCredentials()) {
        LOG_WIFI("Found stored credentials, attempting connection...");
        wifi.connect(config.wifiSsid, config.wifiPassword);
    } else {
        LOG_WIFI("No WiFi credentials stored");
    }

    // Start power outage recovery if needed (after all initialization is complete)
    if (servo.needsRecovery()) {
        LOG_BOOT("Starting power outage recovery...");
        servo.startRecovery();
    }

    LOG_BOOT("Setup complete");
    LOG_BOOT("----------------------------------------");
}

void loop() {
    static unsigned long lastStatusUpdate = 0;
    unsigned long now = millis();

    // Check for pending restart (from HTTP request - allows response to be sent first)
    if (httpServer.isRestartPending()) {
        LOG_BOOT("Restart pending - restarting in 500ms...");
        delay(500);  // Allow HTTP response to be fully sent
        ESP.restart();
    }

    // Update all managers
    wifi.update();
    hallSensor.update();
    servo.update();

    // Update MQTT if we have config
    if (config.hasMqttConfig()) {
        mqtt.update();
    }

    // Update HTTP server with current state every loop (for responsive polling)
    if (wifi.isConnected()) {
        httpServer.updateState(servo.getStateString());
        httpServer.updatePosition(servo.getCurrentPosition());
        httpServer.updateWifiInfo(wifi.getSSID(), wifi.getRSSI(), wifi.getIPAddress());
        httpServer.updateCalibration(servo.isCalibrated(), servo.getCumulativePosition(),
                                    servo.getMaxPosition(), servo.getCalibrationStateString());
        httpServer.updateHallSensor(hallSensor.getRawState(), hallSensor.isTriggered(),
                                   hallSensor.getTriggerCount());

        // Broadcast state changes to SSE clients (for cross-device sync)
        httpServer.broadcastStateIfChanged();
    }

    // Periodic status logging
    if (now - lastStatusUpdate >= STATUS_UPDATE_INTERVAL_MS) {
        lastStatusUpdate = now;

        // Log hall sensor state during calibration for debugging
        // Note: magnet_now = current state (is magnet present right now?)
        //       trigger_latched = edge-detected latch (did magnet arrive at some point?)
        if (servo.isCalibrating()) {
            LOG_SERVO("Calibration: state=%s, magnet_now=%s, trigger_latched=%s, count=%u",
                      servo.getCalibrationStateString(),
                      hallSensor.getRawState() == LOW ? "YES" : "NO",
                      hallSensor.isTriggered() ? "YES" : "NO",
                      hallSensor.getTriggerCount());
        }

        // Log recovery state for debugging
        if (servo.isRecovering()) {
            LOG_SERVO("Recovery: state=%s, pos=%d, magnet_now=%s, trigger_latched=%s",
                      servo.getStateString(),
                      servo.getCumulativePosition(),
                      hallSensor.getRawState() == LOW ? "YES" : "NO",
                      hallSensor.isTriggered() ? "YES" : "NO");
        }

        // Update BLE status
        updateBleStatus();
    }

    delay(LOOP_INTERVAL_MS);
}

void handleCommand(const String& command) {
    LOG_SERVO("Handling command: %s", command.c_str());

    String cmd = command;
    cmd.toUpperCase();

    if (cmd == "OPEN") {
        servo.open();
        // Immediately update HTTP server state so polling gets current state
        httpServer.updateState(servo.getStateString());
        mqtt.publishState(servo.getStateString());
    } else if (cmd == "CLOSE") {
        servo.close();
        httpServer.updateState(servo.getStateString());
        mqtt.publishState(servo.getStateString());
    } else if (cmd == "STOP") {
        servo.stop();
        httpServer.updateState(servo.getStateString());
        mqtt.publishState(servo.getStateString());
    } else if (cmd == "OPEN_FORCE") {
        servo.open(true);
        httpServer.updateState(servo.getStateString());
        mqtt.publishState(servo.getStateString());
    } else if (cmd == "CLOSE_FORCE") {
        servo.close(true);
        httpServer.updateState(servo.getStateString());
        mqtt.publishState(servo.getStateString());
    } else if (cmd == "CALIBRATE_START") {
        servo.startCalibration();
        httpServer.updateState(servo.getStateString());
    } else if (cmd == "CALIBRATE_SETBOTTOM") {
        servo.setBottomPosition();
    } else if (cmd == "CALIBRATE_CANCEL") {
        servo.cancelCalibration();
        httpServer.updateState(servo.getStateString());
    } else if (cmd == "RESTART") {
        LOG_BOOT("Restart command received - restarting in 2 seconds...");
        ble.updateStatus("restarting");
        delay(2000);
        ESP.restart();
    } else {
        LOG_ERROR("Unknown command: %s", command.c_str());
    }
}

void onWifiConnected(const String& ip) {
    LOG_WIFI("WiFi connected callback - IP: %s", ip.c_str());

    // Start HTTP server
    httpServer.begin();

    // Initialize and connect MQTT if configured
    if (config.hasMqttConfig()) {
        mqtt.init(config.mqttBroker, config.mqttPort, config.mqttUser, config.mqttPassword);
        mqtt.connect();
    }

    wifiWasConnected = true;

    // Update BLE status FIRST, before stopping advertising
    // This ensures the app receives the wifi_connected notification
    ble.updateStatus("wifi_connected");

    // Mark setup as complete and disable BLE advertising
    // After this, all device management is done over WiFi/HTTP
    if (!storage.isSetupComplete()) {
        // Give time for the BLE notification to be sent before stopping
        delay(100);
        storage.setSetupComplete(true);
        ble.stopAdvertising();
        LOG_BLE("Setup complete - BLE advertising stopped");
    }
}

void onWifiDisconnected() {
    LOG_WIFI("WiFi disconnected callback");

    if (wifiWasConnected) {
        // Don't stop HTTP server - it will just not respond until reconnected
        LOG_WIFI("WiFi lost, will attempt reconnection");
    }

    // Update BLE status
    ble.updateStatus("wifi_disconnected");
}

void onWifiConnectionFailed() {
    LOG_WIFI("WiFi initial connection failed callback");

    // Send explicit failure status via BLE so app knows credentials are wrong
    ble.updateStatus("wifi_failed");
}

void onBleWifiConfig(const String& ssid, const String& password) {
    LOG_BLE("Received WiFi config - SSID: %s", ssid.c_str());

    // Save to storage (will be overwritten if user retries with correct password)
    storage.setWifiCredentials(ssid, password);

    // Update config struct
    strncpy(config.wifiSsid, ssid.c_str(), sizeof(config.wifiSsid) - 1);
    strncpy(config.wifiPassword, password.c_str(), sizeof(config.wifiPassword) - 1);

    // Update BLE readable value
    ble.setCurrentSsid(ssid);

    // Notify via BLE that we're attempting connection
    ble.updateStatus("wifi_connecting");

    // Try to connect with isInitial=true to trigger failure callback if it fails
    LOG_WIFI("Attempting connection with new credentials...");
    wifi.connect(ssid, password, true);
}

void onBleMqttConfig(const String& broker, uint16_t port) {
    LOG_BLE("Received MQTT config - Broker: %s:%d", broker.c_str(), port);

    // Save to storage
    storage.setMqttConfig(broker, port);

    // Update config struct
    strncpy(config.mqttBroker, broker.c_str(), sizeof(config.mqttBroker) - 1);
    config.mqttPort = port;

    // Update BLE readable value
    ble.setCurrentMqttBroker(broker + ":" + String(port));

    // Notify via BLE
    ble.updateStatus("mqtt_saved");

    // If WiFi is connected, try to connect to MQTT
    if (wifi.isConnected()) {
        mqtt.init(broker, port, config.mqttUser, config.mqttPassword);
        mqtt.connect();
    }
}

void onBleDeviceName(const String& name) {
    LOG_BLE("Received device name: %s", name.c_str());

    // Save to storage
    storage.setDeviceName(name);

    // Update config struct
    strncpy(config.deviceName, name.c_str(), sizeof(config.deviceName) - 1);

    // Update BLE readable value
    ble.setCurrentDeviceName(name);

    // Notify via BLE
    ble.updateStatus("name_saved");
}

void onBleDevicePassword(const String& password) {
    LOG_BLE("Received device password (length: %d)", password.length());

    // Save to storage
    storage.setDevicePassword(password);

    // Notify via BLE
    ble.updateStatus("password_saved");
}

void onBleOrientation(const String& orientation) {
    LOG_BLE("Received orientation: %s", orientation.c_str());

    // Validate and save to storage
    String orient = orientation;
    orient.toLowerCase();

    if (orient != "left" && orient != "right") {
        LOG_ERROR("Invalid orientation: %s (must be 'left' or 'right')", orientation.c_str());
        ble.updateStatus("orientation_error");
        return;
    }

    storage.setOrientation(orient);

    // Update servo controller direction immediately
    servo.setInvertDirection(orient == "right");

    // Update BLE readable value
    ble.setCurrentOrientation(orient);

    // Notify via BLE
    ble.updateStatus("orientation_saved");
}

void onBleCommand(const String& command) {
    LOG_BLE("Received BLE command: %s", command.c_str());
    handleCommand(command);
}

void updateBleStatus() {
    String status;

    if (wifi.isConnected()) {
        status = "wifi:" + wifi.getIPAddress();
        if (mqtt.isConnected()) {
            status += ",mqtt:ok";
        } else if (config.hasMqttConfig()) {
            status += ",mqtt:disconnected";
        }
    } else if (wifi.getState() == WifiState::CONNECTING) {
        status = "wifi:connecting";
    } else {
        status = "wifi:disconnected";
    }

    if (servo.isConnected()) {
        status += ",servo:ok";
    } else {
        status += ",servo:error";
    }

    // Only update if BLE client is connected (to avoid unnecessary operations)
    if (ble.isClientConnected()) {
        ble.updateStatus(status);
    }
}

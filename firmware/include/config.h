#ifndef CONFIG_H
#define CONFIG_H

// ============================================================================
// FAME Smart Blinds Configuration
// ============================================================================

// Firmware version
#define FIRMWARE_VERSION "1.0.6"
#define DEVICE_NAME_PREFIX "FAMEBlinds"

// ============================================================================
// Pin Definitions (XIAO ESP32-C3 + Bus Servo Adapter)
// ============================================================================

// Servo serial pins (connected to Bus Servo Adapter)
#define SERVO_TX_PIN 21  // D6 on XIAO
#define SERVO_RX_PIN 20  // D7 on XIAO

// Hall sensor pin for home position detection
#define HALL_SENSOR_PIN 4  // D2 on XIAO (GPIO4)

// ============================================================================
// Servo Configuration
// ============================================================================

#define SERVO_BAUD_RATE 1000000  // 1Mbaud for STS series
#define DEFAULT_SERVO_ID 1
#define SERVO_MIN_POSITION 0
#define SERVO_MAX_POSITION 4095
#define SERVO_CENTER_POSITION 2048

// Servo speed (0-4095, lower = slower)
#define SERVO_SPEED 500
#define SERVO_ACCELERATION 50

// ============================================================================
// WiFi Configuration
// ============================================================================

#define WIFI_CONNECT_TIMEOUT_MS 15000
#define WIFI_RECONNECT_INTERVAL_MS 5000
#define WIFI_MAX_RECONNECT_ATTEMPTS 10

// ============================================================================
// MQTT Configuration
// ============================================================================

#define MQTT_PORT 1883
#define MQTT_RECONNECT_INTERVAL_MS 5000
#define MQTT_KEEPALIVE_SECONDS 60

// MQTT topic prefixes
#define MQTT_TOPIC_PREFIX "famesmartblinds"
#define MQTT_DISCOVERY_PREFIX "homeassistant"

// ============================================================================
// HTTP Server Configuration
// ============================================================================

#define HTTP_PORT 80

// ============================================================================
// BLE Configuration
// ============================================================================

// Service UUID
#define BLE_SERVICE_UUID "4fafc201-1fb5-459e-8fcc-c5c9c331914b"

// Characteristic UUIDs
#define BLE_CHAR_WIFI_SSID_UUID     "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define BLE_CHAR_WIFI_PASS_UUID     "beb5483e-36e1-4688-b7f5-ea07361b26a9"
#define BLE_CHAR_DEVICE_NAME_UUID   "beb5483e-36e1-4688-b7f5-ea07361b26aa"
#define BLE_CHAR_MQTT_BROKER_UUID   "beb5483e-36e1-4688-b7f5-ea07361b26ab"
#define BLE_CHAR_STATUS_UUID        "beb5483e-36e1-4688-b7f5-ea07361b26ac"
#define BLE_CHAR_COMMAND_UUID       "beb5483e-36e1-4688-b7f5-ea07361b26ad"
#define BLE_CHAR_DEVICE_PASS_UUID   "beb5483e-36e1-4688-b7f5-ea07361b26ae"
#define BLE_CHAR_ORIENTATION_UUID   "beb5483e-36e1-4688-b7f5-ea07361b26af"
#define BLE_CHAR_WIFI_SCAN_TRIGGER_UUID "beb5483e-36e1-4688-b7f5-ea07361b26b0"
#define BLE_CHAR_WIFI_SCAN_RESULTS_UUID "beb5483e-36e1-4688-b7f5-ea07361b26b1"

// ============================================================================
// NVS Storage Keys
// ============================================================================

#define NVS_NAMESPACE "fameblinds"
#define NVS_KEY_WIFI_SSID "wifi_ssid"
#define NVS_KEY_WIFI_PASS "wifi_pass"
#define NVS_KEY_DEVICE_NAME "dev_name"
#define NVS_KEY_DEVICE_PASS "dev_pass"
#define NVS_KEY_MQTT_BROKER "mqtt_host"
#define NVS_KEY_MQTT_USER "mqtt_user"
#define NVS_KEY_MQTT_PASS "mqtt_pass"
#define NVS_KEY_SERVO_ID "servo_id"

// Calibration NVS keys
#define NVS_KEY_MAX_POSITION "max_pos"
#define NVS_KEY_CURRENT_POSITION "cur_pos"
#define NVS_KEY_CALIBRATED "calibrated"
#define NVS_KEY_AUTO_HOME "auto_home"
#define NVS_KEY_SERVO_SPEED "servo_spd"

// Power outage recovery keys
#define NVS_KEY_WAS_MOVING "was_moving"
#define NVS_KEY_TARGET_POSITION "target_pos"

// Setup state
#define NVS_KEY_SETUP_COMPLETE "setup_done"
#define NVS_KEY_ORIENTATION "orientation"

// ============================================================================
// Timing Configuration
// ============================================================================

#define LOOP_INTERVAL_MS 10
#define STATUS_UPDATE_INTERVAL_MS 1000
#define HEARTBEAT_INTERVAL_MS 30000

#endif // CONFIG_H

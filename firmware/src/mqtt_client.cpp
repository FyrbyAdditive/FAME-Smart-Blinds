#include "mqtt_client.h"
#include "config.h"
#include "logger.h"
#include "storage.h"
#include <WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>

extern Storage storage;

// WiFi client for MQTT
static WiFiClient wifiClient;
static PubSubClient mqttClient(wifiClient);

// Static instance pointer for callback
MqttClient* MqttClient::_instance = nullptr;

MqttClient::MqttClient()
    : _port(MQTT_PORT)
    , _initialized(false)
    , _discoveryPublished(false)
    , _lastReconnectAttempt(0)
    , _lastHeartbeat(0)
    , _commandCallback(nullptr)
{
    _instance = this;
}

void MqttClient::init(const String& broker, uint16_t port,
                      const String& user, const String& password) {
    _broker = broker;
    _port = port;
    _user = user;
    _password = password;

    _deviceId = Storage::getDeviceId();
    _deviceName = storage.getDeviceName();

    buildTopics();

    LOG_MQTT("Initializing MQTT - broker: %s:%d", _broker.c_str(), _port);
    LOG_MQTT("Device ID: %s, Name: %s", _deviceId.c_str(), _deviceName.c_str());
    LOG_MQTT("Command topic: %s", _commandTopic.c_str());
    LOG_MQTT("State topic: %s", _stateTopic.c_str());

    mqttClient.setServer(_broker.c_str(), _port);
    mqttClient.setCallback(messageCallback);
    mqttClient.setKeepAlive(MQTT_KEEPALIVE_SECONDS);

    _initialized = true;
}

void MqttClient::buildTopics() {
    String prefix = String(MQTT_TOPIC_PREFIX) + "/" + _deviceId;

    _commandTopic = prefix + "/command";
    _stateTopic = prefix + "/state";
    _availabilityTopic = prefix + "/availability";
    _discoveryTopic = String(MQTT_DISCOVERY_PREFIX) + "/cover/famesmartblinds_" + _deviceId + "/config";
}

bool MqttClient::connect() {
    if (!_initialized || _broker.isEmpty()) {
        LOG_MQTT("Cannot connect: not initialized or no broker configured");
        return false;
    }

    if (mqttClient.connected()) {
        return true;
    }

    LOG_MQTT("Connecting to MQTT broker: %s:%d", _broker.c_str(), _port);

    String clientId = "famesmartblinds_" + _deviceId;

    bool connected = false;
    if (_user.isEmpty()) {
        connected = mqttClient.connect(clientId.c_str(),
                                        _availabilityTopic.c_str(), 0, true, "offline");
    } else {
        connected = mqttClient.connect(clientId.c_str(),
                                        _user.c_str(), _password.c_str(),
                                        _availabilityTopic.c_str(), 0, true, "offline");
    }

    if (connected) {
        LOG_MQTT("Connected to MQTT broker");

        // Publish availability
        publishAvailability(true);

        // Subscribe to command topic
        if (mqttClient.subscribe(_commandTopic.c_str())) {
            LOG_MQTT("Subscribed to: %s", _commandTopic.c_str());
        } else {
            LOG_ERROR("Failed to subscribe to command topic");
        }

        // Publish Home Assistant discovery
        if (!_discoveryPublished) {
            publishDiscovery();
            _discoveryPublished = true;
        }

        return true;
    } else {
        LOG_ERROR("MQTT connection failed, state: %d", mqttClient.state());
        return false;
    }
}

void MqttClient::disconnect() {
    if (mqttClient.connected()) {
        publishAvailability(false);
        mqttClient.disconnect();
        LOG_MQTT("Disconnected from MQTT broker");
    }
}

bool MqttClient::isConnected() {
    return mqttClient.connected();
}

void MqttClient::update() {
    if (!_initialized || _broker.isEmpty()) {
        return;
    }

    if (!mqttClient.connected()) {
        unsigned long now = millis();
        if (now - _lastReconnectAttempt >= MQTT_RECONNECT_INTERVAL_MS) {
            _lastReconnectAttempt = now;
            connect();
        }
    } else {
        mqttClient.loop();

        // Send periodic heartbeat
        unsigned long now = millis();
        if (now - _lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
            _lastHeartbeat = now;
            publishAvailability(true);
        }
    }
}

void MqttClient::onCommand(MqttCommandCallback callback) {
    _commandCallback = callback;
}

void MqttClient::publishState(const char* state) {
    if (!mqttClient.connected()) {
        return;
    }

    LOG_MQTT("Publishing state: %s", state);
    mqttClient.publish(_stateTopic.c_str(), state, true);
}

void MqttClient::publishAvailability(bool online) {
    if (!mqttClient.connected() && online) {
        return;  // Can't publish online if not connected
    }

    const char* payload = online ? "online" : "offline";
    LOG_MQTT("Publishing availability: %s", payload);
    mqttClient.publish(_availabilityTopic.c_str(), payload, true);
}

void MqttClient::publishDiscovery() {
    if (!mqttClient.connected()) {
        return;
    }

    String payload = buildDiscoveryPayload();

    LOG_MQTT("Publishing HA discovery to: %s", _discoveryTopic.c_str());
    LOG_MQTT("Payload size: %d bytes", payload.length());

    if (mqttClient.publish(_discoveryTopic.c_str(), payload.c_str(), true)) {
        LOG_MQTT("HA discovery published successfully");
    } else {
        LOG_ERROR("Failed to publish HA discovery");
    }
}

String MqttClient::buildDiscoveryPayload() {
    JsonDocument doc;

    // Basic config
    doc["name"] = _deviceName;
    doc["unique_id"] = "famesmartblinds_" + Storage::getMacAddress();
    doc["device_class"] = "blind";

    // Topics
    doc["command_topic"] = _commandTopic;
    doc["state_topic"] = _stateTopic;
    doc["availability_topic"] = _availabilityTopic;

    // Payloads
    doc["payload_open"] = "OPEN";
    doc["payload_close"] = "CLOSE";
    doc["payload_stop"] = "STOP";

    // States
    doc["state_open"] = "open";
    doc["state_opening"] = "opening";
    doc["state_closed"] = "closed";
    doc["state_closing"] = "closing";
    doc["state_stopped"] = "stopped";

    // Device info
    JsonObject device = doc["device"].to<JsonObject>();
    device["identifiers"][0] = "famesmartblinds_" + _deviceId;
    device["name"] = _deviceName;
    device["manufacturer"] = "FAME Smart Blinds";
    device["model"] = "Smart Blind Controller";
    device["sw_version"] = FIRMWARE_VERSION;

    // Origin info (recommended by HA)
    JsonObject origin = doc["origin"].to<JsonObject>();
    origin["name"] = "FAME Smart Blinds";
    origin["sw_version"] = FIRMWARE_VERSION;

    String output;
    serializeJson(doc, output);
    return output;
}

String MqttClient::getCommandTopic() const {
    return _commandTopic;
}

String MqttClient::getStateTopic() const {
    return _stateTopic;
}

String MqttClient::getAvailabilityTopic() const {
    return _availabilityTopic;
}

void MqttClient::onMessage(const char* topic, const uint8_t* payload, unsigned int length) {
    String message;
    message.reserve(length + 1);
    for (unsigned int i = 0; i < length; i++) {
        message += (char)payload[i];
    }

    LOG_MQTT("Received on %s: %s", topic, message.c_str());

    if (String(topic) == _commandTopic) {
        message.toUpperCase();
        if (message == "OPEN" || message == "CLOSE" || message == "STOP") {
            if (_commandCallback) {
                _commandCallback(message);
            }
        } else {
            LOG_MQTT("Unknown command: %s", message.c_str());
        }
    }
}

// Static callback wrapper
void MqttClient::messageCallback(char* topic, uint8_t* payload, unsigned int length) {
    if (_instance) {
        _instance->onMessage(topic, payload, length);
    }
}

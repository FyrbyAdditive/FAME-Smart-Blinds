#ifndef MQTT_CLIENT_H
#define MQTT_CLIENT_H

#include <Arduino.h>
#include <functional>

// Callback for received commands
using MqttCommandCallback = std::function<void(const String& command)>;

class MqttClient {
public:
    MqttClient();

    // Initialize with broker info
    void init(const String& broker, uint16_t port = 1883,
              const String& user = "", const String& password = "");

    // Connection management
    bool connect();
    void disconnect();
    void disable();  // Disconnect and clear config (disables MQTT)
    bool isConnected();
    bool isEnabled() const;  // Returns true if broker is configured

    // Update loop (handles reconnection, message processing)
    void update();

    // Set command callback
    void onCommand(MqttCommandCallback callback);

    // Publish state updates
    void publishState(const char* state);
    void publishAvailability(bool online);

    // Publish Home Assistant discovery config
    void publishDiscovery();

    // Get topic names (for external use)
    String getCommandTopic() const;
    String getStateTopic() const;
    String getAvailabilityTopic() const;

private:
    String _broker;
    uint16_t _port;
    String _user;
    String _password;

    String _deviceId;
    String _deviceName;

    String _commandTopic;
    String _stateTopic;
    String _availabilityTopic;
    String _discoveryTopic;

    bool _initialized;
    bool _discoveryPublished;

    unsigned long _lastReconnectAttempt;
    unsigned long _lastHeartbeat;

    MqttCommandCallback _commandCallback;

    void buildTopics();
    String buildDiscoveryPayload();
    void onMessage(const char* topic, const uint8_t* payload, unsigned int length);

    static MqttClient* _instance;
    static void messageCallback(char* topic, uint8_t* payload, unsigned int length);
};

#endif // MQTT_CLIENT_H

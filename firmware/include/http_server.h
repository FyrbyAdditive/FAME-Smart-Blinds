#ifndef HTTP_SERVER_H
#define HTTP_SERVER_H

#include <Arduino.h>
#include <functional>
#include <Update.h>

// Command callback type
using HttpCommandCallback = std::function<void(const String& action)>;

// MQTT config callback type (broker, port, user, password)
using HttpMqttConfigCallback = std::function<void(const String& broker, uint16_t port,
                                                   const String& user, const String& password)>;

class HttpServer {
public:
    HttpServer();

    // Initialize and start the server
    void begin();

    // Stop the server
    void stop();

    // Check if running
    bool isRunning() const;

    // Check if restart is pending (call from main loop)
    bool isRestartPending() const;

    // Set callback for blind commands
    void onCommand(HttpCommandCallback callback);

    // Set callback for MQTT configuration changes
    void onMqttConfig(HttpMqttConfigCallback callback);

    // Update device state (for status endpoint)
    void updateState(const char* state);
    void updatePosition(int position);
    void updateWifiInfo(const String& ssid, int rssi, const String& ip);
    void updateCalibration(bool calibrated, int32_t cumulativePosition, int32_t maxPosition,
                          const char* calibrationState);
    void updateHallSensor(bool rawState, bool triggered, uint32_t triggerCount);

    // SSE: Broadcast state to all connected clients (call from main loop when state changes)
    void broadcastStateIfChanged();

    // SSE: Broadcast a log entry to all connected clients
    void broadcastLog(const char* logEntry);

    // SSE: Get number of connected event clients (status stream)
    int getEventClientCount() const;

    // SSE: Get number of connected log clients
    int getLogClientCount() const;

private:
    bool _running;
    bool _pendingRestart = false;
    HttpCommandCallback _commandCallback;
    HttpMqttConfigCallback _mqttConfigCallback;

    // Current state for status endpoint
    String _currentState;
    int _currentPosition;
    String _wifiSsid;
    int _wifiRssi;
    String _wifiIp;

    // Calibration state
    bool _calibrated = false;
    int32_t _cumulativePosition = 0;
    int32_t _maxPosition = 0;
    String _calibrationState = "idle";

    // Hall sensor state
    bool _hallRawState = true;  // HIGH = no magnet
    bool _hallTriggered = false;
    uint32_t _hallTriggerCount = 0;

    // OTA update state
    bool _otaInProgress = false;
    size_t _otaReceived = 0;
    size_t _otaTotal = 0;

    // SSE state tracking (for change detection)
    String _lastBroadcastState;
    int32_t _lastBroadcastPosition = -1;
    String _lastBroadcastCalibrationState;
    unsigned long _lastBroadcastTime = 0;

    void setupRoutes();
    void setupOTARoutes();
    void setupSSE();
    String buildStatusJson();
    String buildInfoJson();
};

#endif // HTTP_SERVER_H

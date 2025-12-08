#include "http_server.h"
#include "config.h"
#include "logger.h"
#include "storage.h"
#include "servo_controller.h"
#include <ESPAsyncWebServer.h>
#include <ArduinoJson.h>
#include <Update.h>

// Global server instance
static AsyncWebServer server(HTTP_PORT);

// SSE event source for real-time status updates
static AsyncEventSource events("/events");

// Separate SSE event source for log streaming (to avoid overwhelming device)
static AsyncEventSource logEvents("/events/logs");

extern Storage storage;

HttpServer::HttpServer()
    : _running(false)
    , _commandCallback(nullptr)
    , _currentState("unknown")
    , _currentPosition(0)
    , _wifiRssi(0)
{
}

void HttpServer::begin() {
    if (_running) {
        LOG_HTTP("Server already running");
        return;
    }

    LOG_HTTP("Starting HTTP server on port %d", HTTP_PORT);
    setupRoutes();
    setupOTARoutes();
    setupSSE();
    server.begin();
    _running = true;
    LOG_HTTP("HTTP server started");
}

void HttpServer::stop() {
    if (!_running) return;

    LOG_HTTP("Stopping HTTP server");
    server.end();
    _running = false;
}

bool HttpServer::isRunning() const {
    return _running;
}

bool HttpServer::isRestartPending() const {
    return _pendingRestart;
}

void HttpServer::onCommand(HttpCommandCallback callback) {
    _commandCallback = callback;
}

void HttpServer::updateState(const char* state) {
    _currentState = state;
}

void HttpServer::updatePosition(int position) {
    _currentPosition = position;
}

void HttpServer::updateWifiInfo(const String& ssid, int rssi, const String& ip) {
    _wifiSsid = ssid;
    _wifiRssi = rssi;
    _wifiIp = ip;
}

void HttpServer::updateCalibration(bool calibrated, int32_t cumulativePosition, int32_t maxPosition,
                                   const char* calibrationState) {
    _calibrated = calibrated;
    _cumulativePosition = cumulativePosition;
    _maxPosition = maxPosition;
    _calibrationState = calibrationState;
}

void HttpServer::updateHallSensor(bool rawState, bool triggered, uint32_t triggerCount) {
    _hallRawState = rawState;
    _hallTriggered = triggered;
    _hallTriggerCount = triggerCount;
}

void HttpServer::setupRoutes() {
    // CORS headers for all responses
    DefaultHeaders::Instance().addHeader("Access-Control-Allow-Origin", "*");
    DefaultHeaders::Instance().addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    DefaultHeaders::Instance().addHeader("Access-Control-Allow-Headers", "Content-Type");

    // Handle preflight requests
    server.onNotFound([](AsyncWebServerRequest *request) {
        if (request->method() == HTTP_OPTIONS) {
            request->send(200);
        } else {
            request->send(404, "application/json", "{\"error\":\"Not found\"}");
        }
    });

    // GET /status - Device status
    server.on("/status", HTTP_GET, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("GET /status");
        request->send(200, "application/json", buildStatusJson());
    });

    // GET /info - Device info
    server.on("/info", HTTP_GET, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("GET /info");
        request->send(200, "application/json", buildInfoJson());
    });

    // POST /command - Control the blind
    server.on("/command", HTTP_POST,
        [](AsyncWebServerRequest *request) {
            // This handler is for when there's no body
            request->send(400, "application/json", "{\"error\":\"No body\"}");
        },
        NULL,  // No upload handler
        [this](AsyncWebServerRequest *request, uint8_t *data, size_t len, size_t index, size_t total) {
            // Body handler
            String body = String((char*)data).substring(0, len);
            LOG_HTTP("POST /command: %s", body.c_str());

            JsonDocument doc;
            DeserializationError error = deserializeJson(doc, body);

            if (error) {
                LOG_HTTP("JSON parse error: %s", error.c_str());
                request->send(400, "application/json", "{\"error\":\"Invalid JSON\"}");
                return;
            }

            String action = doc["action"] | "";
            action.toUpperCase();

            if (action != "OPEN" && action != "CLOSE" && action != "STOP") {
                request->send(400, "application/json", "{\"error\":\"Invalid action. Use OPEN, CLOSE, or STOP\"}");
                return;
            }

            LOG_HTTP("Executing command: %s", action.c_str());

            if (_commandCallback) {
                _commandCallback(action);
            }

            JsonDocument response;
            response["success"] = true;
            response["action"] = action;

            String responseStr;
            serializeJson(response, responseStr);
            request->send(200, "application/json", responseStr);
        }
    );

    // GET / - Simple health check
    server.on("/", HTTP_GET, [](AsyncWebServerRequest *request) {
        request->send(200, "application/json", "{\"status\":\"ok\",\"device\":\"FAMESmartBlinds\"}");
    });

    // POST /open - Quick open command
    server.on("/open", HTTP_POST, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("POST /open");
        if (_commandCallback) {
            _commandCallback("OPEN");
        }
        request->send(200, "application/json", "{\"success\":true,\"action\":\"OPEN\"}");
    });

    // POST /close - Quick close command
    server.on("/close", HTTP_POST, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("POST /close");
        if (_commandCallback) {
            _commandCallback("CLOSE");
        }
        request->send(200, "application/json", "{\"success\":true,\"action\":\"CLOSE\"}");
    });

    // POST /stop - Quick stop command
    server.on("/stop", HTTP_POST, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("POST /stop");
        if (_commandCallback) {
            _commandCallback("STOP");
        }
        request->send(200, "application/json", "{\"success\":true,\"action\":\"STOP\"}");
    });

    // =====================
    // Calibration Endpoints
    // =====================

    // POST /calibrate/start - Begin calibration (find home)
    server.on("/calibrate/start", HTTP_POST, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("POST /calibrate/start");
        if (_commandCallback) {
            _commandCallback("CALIBRATE_START");
        }
        request->send(200, "application/json", "{\"success\":true,\"action\":\"CALIBRATE_START\"}");
    });

    // POST /calibrate/setbottom - Confirm bottom position
    server.on("/calibrate/setbottom", HTTP_POST, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("POST /calibrate/setbottom");
        if (_commandCallback) {
            _commandCallback("CALIBRATE_SETBOTTOM");
        }
        request->send(200, "application/json", "{\"success\":true,\"action\":\"CALIBRATE_SETBOTTOM\"}");
    });

    // POST /calibrate/cancel - Cancel calibration
    server.on("/calibrate/cancel", HTTP_POST, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("POST /calibrate/cancel");
        if (_commandCallback) {
            _commandCallback("CALIBRATE_CANCEL");
        }
        request->send(200, "application/json", "{\"success\":true,\"action\":\"CALIBRATE_CANCEL\"}");
    });

    // GET /calibrate/status - Get calibration state
    server.on("/calibrate/status", HTTP_GET, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("GET /calibrate/status");
        JsonDocument doc;
        doc["calibrated"] = _calibrated;
        doc["position"] = _cumulativePosition;
        doc["maxPosition"] = _maxPosition;
        doc["calibrationState"] = _calibrationState;

        String output;
        serializeJson(doc, output);
        request->send(200, "application/json", output);
    });

    // POST /open/force - Force open (bypass limits)
    server.on("/open/force", HTTP_POST, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("POST /open/force");
        if (_commandCallback) {
            _commandCallback("OPEN_FORCE");
        }
        request->send(200, "application/json", "{\"success\":true,\"action\":\"OPEN_FORCE\"}");
    });

    // POST /close/force - Force close (bypass limits)
    server.on("/close/force", HTTP_POST, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("POST /close/force");
        if (_commandCallback) {
            _commandCallback("CLOSE_FORCE");
        }
        request->send(200, "application/json", "{\"success\":true,\"action\":\"CLOSE_FORCE\"}");
    });

    // GET /hall - Get hall sensor debug info
    server.on("/hall", HTTP_GET, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("GET /hall");
        JsonDocument doc;
        doc["rawState"] = _hallRawState ? "HIGH" : "LOW";
        doc["rawStateNote"] = _hallRawState ? "no magnet" : "magnet detected";
        doc["triggered"] = _hallTriggered;
        doc["triggerCount"] = _hallTriggerCount;

        String output;
        serializeJson(doc, output);
        request->send(200, "application/json", output);
    });

    // POST /restart - Restart the device
    server.on("/restart", HTTP_POST, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("POST /restart");
        request->send(200, "application/json", "{\"success\":true,\"action\":\"RESTART\"}");
        // Set flag to restart after response is sent (handled in main loop)
        _pendingRestart = true;
    });

    // POST /name - Set device name
    server.on("/name", HTTP_POST, [this](AsyncWebServerRequest *request) {
        String name = "";
        if (request->hasParam("name", false)) {
            name = request->getParam("name", false)->value();
        } else if (request->hasParam("name")) {
            name = request->getParam("name")->value();
        }

        if (name.isEmpty()) {
            LOG_HTTP("POST /name - missing name parameter");
            request->send(400, "application/json", "{\"error\":\"Missing name parameter\"}");
            return;
        }

        LOG_HTTP("POST /name: %s", name.c_str());
        storage.setDeviceName(name);

        JsonDocument response;
        response["success"] = true;
        response["name"] = name;

        String responseStr;
        serializeJson(response, responseStr);
        request->send(200, "application/json", responseStr);
    });

    // POST /password - Set device password
    server.on("/password", HTTP_POST, [this](AsyncWebServerRequest *request) {
        String password = "";
        if (request->hasParam("password", false)) {
            password = request->getParam("password", false)->value();
        } else if (request->hasParam("password")) {
            password = request->getParam("password")->value();
        }

        // Empty password is allowed (disables auth)
        LOG_HTTP("POST /password (length: %d)", password.length());
        storage.setDevicePassword(password);

        JsonDocument response;
        response["success"] = true;

        String responseStr;
        serializeJson(response, responseStr);
        request->send(200, "application/json", responseStr);
    });

    // POST /mqtt - Set MQTT configuration
    server.on("/mqtt", HTTP_POST, [this](AsyncWebServerRequest *request) {
        String broker = "";
        uint16_t port = 1883;
        String user = "";
        String password = "";

        if (request->hasParam("broker", false)) {
            broker = request->getParam("broker", false)->value();
        } else if (request->hasParam("broker")) {
            broker = request->getParam("broker")->value();
        }

        if (request->hasParam("port", false)) {
            port = request->getParam("port", false)->value().toInt();
        } else if (request->hasParam("port")) {
            port = request->getParam("port")->value().toInt();
        }

        if (request->hasParam("user", false)) {
            user = request->getParam("user", false)->value();
        } else if (request->hasParam("user")) {
            user = request->getParam("user")->value();
        }

        if (request->hasParam("password", false)) {
            password = request->getParam("password", false)->value();
        } else if (request->hasParam("password")) {
            password = request->getParam("password")->value();
        }

        if (broker.isEmpty()) {
            LOG_HTTP("POST /mqtt - missing broker parameter");
            request->send(400, "application/json", "{\"error\":\"Missing broker parameter\"}");
            return;
        }

        LOG_HTTP("POST /mqtt: %s:%d", broker.c_str(), port);
        storage.setMqttConfig(broker, port, user, password);

        JsonDocument response;
        response["success"] = true;
        response["broker"] = broker;
        response["port"] = port;

        String responseStr;
        serializeJson(response, responseStr);
        request->send(200, "application/json", responseStr);
    });

    // POST /factory-reset - Factory reset the device (clear all settings)
    server.on("/factory-reset", HTTP_POST, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("POST /factory-reset - Erasing all settings");

        // Clear all NVS storage
        storage.factoryReset();

        request->send(200, "application/json",
            "{\"success\":true,\"message\":\"Factory reset complete. Device will restart.\"}");

        // Restart after response is sent
        _pendingRestart = true;
    });

    // GET /logs - Get device logs (ring buffer)
    server.on("/logs", HTTP_GET, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("GET /logs");
        String logsJson = Logger::getLogsJson();
        request->send(200, "application/json", "{\"logs\":" + logsJson + "}");
    });

    // DELETE /logs - Clear device logs
    server.on("/logs", HTTP_DELETE, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("DELETE /logs");
        Logger::clearBuffer();
        request->send(200, "application/json", "{\"success\":true,\"message\":\"Logs cleared\"}");
    });

    // POST /wifi - Set WiFi configuration (for reconfiguration)
    server.on("/wifi", HTTP_POST, [this](AsyncWebServerRequest *request) {
        String ssid = "";
        String password = "";

        if (request->hasParam("ssid", false)) {
            ssid = request->getParam("ssid", false)->value();
        } else if (request->hasParam("ssid")) {
            ssid = request->getParam("ssid")->value();
        }

        if (request->hasParam("password", false)) {
            password = request->getParam("password", false)->value();
        } else if (request->hasParam("password")) {
            password = request->getParam("password")->value();
        }

        if (ssid.isEmpty()) {
            LOG_HTTP("POST /wifi - missing ssid parameter");
            request->send(400, "application/json", "{\"error\":\"Missing ssid parameter\"}");
            return;
        }

        LOG_HTTP("POST /wifi: %s", ssid.c_str());
        storage.setWifiCredentials(ssid, password);

        JsonDocument response;
        response["success"] = true;
        response["ssid"] = ssid;
        response["message"] = "WiFi credentials saved. Restart device to apply.";

        String responseStr;
        serializeJson(response, responseStr);
        request->send(200, "application/json", responseStr);
    });

    // POST /orientation - Set device orientation (left or right)
    server.on("/orientation", HTTP_POST, [this](AsyncWebServerRequest *request) {
        String orientation = "";
        if (request->hasParam("orientation", false)) {
            orientation = request->getParam("orientation", false)->value();
        } else if (request->hasParam("orientation")) {
            orientation = request->getParam("orientation")->value();
        }

        orientation.toLowerCase();

        if (orientation != "left" && orientation != "right") {
            LOG_HTTP("POST /orientation - invalid value: %s", orientation.c_str());
            request->send(400, "application/json", "{\"error\":\"Invalid orientation. Use 'left' or 'right'\"}");
            return;
        }

        LOG_HTTP("POST /orientation: %s", orientation.c_str());
        storage.setOrientation(orientation);

        // Update servo controller immediately
        extern ServoController servo;
        servo.setInvertDirection(orientation == "right");

        JsonDocument response;
        response["success"] = true;
        response["orientation"] = orientation;
        response["message"] = "Orientation updated. Servo direction adjusted.";

        String responseStr;
        serializeJson(response, responseStr);
        request->send(200, "application/json", responseStr);
    });

    // GET /orientation - Get current device orientation
    server.on("/orientation", HTTP_GET, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("GET /orientation");
        String orientation = storage.getOrientation();

        JsonDocument response;
        response["orientation"] = orientation;

        String responseStr;
        serializeJson(response, responseStr);
        request->send(200, "application/json", responseStr);
    });

    // POST /speed - Set servo speed (0-4095)
    server.on("/speed", HTTP_POST, [this](AsyncWebServerRequest *request) {
        if (!request->hasParam("value", true)) {
            request->send(400, "application/json", "{\"error\":\"Missing 'value' parameter\"}");
            return;
        }

        int speed = request->getParam("value", true)->value().toInt();

        // Validate range (0-4095, but practical range is ~100-2000)
        if (speed < 0 || speed > 4095) {
            LOG_HTTP("POST /speed - invalid value: %d", speed);
            request->send(400, "application/json", "{\"error\":\"Speed must be 0-4095\"}");
            return;
        }

        LOG_HTTP("POST /speed: %d", speed);
        storage.setServoSpeed(speed);
        // Speed will be applied on next movement command or after restart

        JsonDocument response;
        response["success"] = true;
        response["speed"] = speed;

        String responseStr;
        serializeJson(response, responseStr);
        request->send(200, "application/json", responseStr);
    });

    // GET /speed - Get current servo speed
    server.on("/speed", HTTP_GET, [this](AsyncWebServerRequest *request) {
        LOG_HTTP("GET /speed");
        uint16_t speed = storage.getServoSpeed();

        JsonDocument response;
        response["speed"] = speed;

        String responseStr;
        serializeJson(response, responseStr);
        request->send(200, "application/json", responseStr);
    });
}

String HttpServer::buildStatusJson() {
    JsonDocument doc;

    doc["state"] = _currentState;
    doc["position"] = _currentPosition;

    JsonObject wifi = doc["wifi"].to<JsonObject>();
    wifi["ssid"] = _wifiSsid;
    wifi["rssi"] = _wifiRssi;
    wifi["ip"] = _wifiIp;

    // Calibration info
    JsonObject calibration = doc["calibration"].to<JsonObject>();
    calibration["calibrated"] = _calibrated;
    calibration["cumulativePosition"] = _cumulativePosition;
    calibration["maxPosition"] = _maxPosition;
    calibration["state"] = _calibrationState;

    doc["uptime"] = millis() / 1000;

    String output;
    serializeJson(doc, output);
    return output;
}

String HttpServer::buildInfoJson() {
    JsonDocument doc;

    doc["device"] = "FAMESmartBlinds";
    doc["version"] = FIRMWARE_VERSION;
    doc["mac"] = Storage::getMacAddress();
    doc["deviceId"] = Storage::getDeviceId();
    doc["hostname"] = storage.getDeviceName();
    doc["orientation"] = storage.getOrientation();
    doc["speed"] = storage.getServoSpeed();

    JsonObject endpoints = doc["endpoints"].to<JsonObject>();
    endpoints["status"] = "GET /status";
    endpoints["info"] = "GET /info";
    endpoints["command"] = "POST /command {action: OPEN|CLOSE|STOP}";
    endpoints["open"] = "POST /open";
    endpoints["close"] = "POST /close";
    endpoints["stop"] = "POST /stop";
    endpoints["update"] = "POST /update (multipart firmware binary)";

    String output;
    serializeJson(doc, output);
    return output;
}

void HttpServer::setupOTARoutes() {
    // POST /update - OTA firmware update (multipart file upload)
    server.on("/update", HTTP_POST,
        // Response handler (called after upload completes)
        [this](AsyncWebServerRequest *request) {
            _otaInProgress = false;

            if (Update.hasError()) {
                LOG_HTTP("OTA update failed: %s", Update.errorString());
                request->send(500, "application/json",
                    "{\"success\":false,\"error\":\"" + String(Update.errorString()) + "\"}");
            } else {
                LOG_HTTP("OTA update successful, restarting...");
                request->send(200, "application/json",
                    "{\"success\":true,\"message\":\"Update successful, restarting...\"}");
                _pendingRestart = true;
            }
        },
        // File upload handler (called for multipart file uploads)
        [this](AsyncWebServerRequest *request, String filename, size_t index, uint8_t *data, size_t len, bool final) {
            if (index == 0) {
                // First chunk - initialize update
                LOG_HTTP("OTA update starting: %s (content-length header may include multipart overhead)", filename.c_str());
                _otaInProgress = true;
                _otaReceived = 0;
                _otaTotal = request->contentLength();

                LOG_HTTP("OTA content length: %d", _otaTotal);

                // Begin update - use sketch partition
                if (!Update.begin(UPDATE_SIZE_UNKNOWN, U_FLASH)) {
                    LOG_HTTP("OTA begin failed: %s", Update.errorString());
                    return;
                }
                LOG_HTTP("OTA Update.begin() successful");
            }

            if (len > 0) {
                // Write chunk
                size_t written = Update.write(data, len);
                if (written != len) {
                    LOG_HTTP("OTA write failed: wrote %d of %d bytes, error: %s", written, len, Update.errorString());
                    return;
                }

                _otaReceived += len;

                // Log progress every 64KB
                if (_otaReceived % 65536 < len) {
                    LOG_HTTP("OTA progress: %d bytes received", _otaReceived);
                }
            }

            if (final) {
                // Last chunk - finish update
                LOG_HTTP("OTA final chunk received, total: %d bytes", _otaReceived);
                if (!Update.end(true)) {
                    LOG_HTTP("OTA end failed: %s", Update.errorString());
                } else {
                    LOG_HTTP("OTA Update.end() successful - firmware ready");
                }
            }
        }
    );

    // GET /update/status - Check OTA status
    server.on("/update/status", HTTP_GET, [this](AsyncWebServerRequest *request) {
        JsonDocument doc;
        doc["inProgress"] = _otaInProgress;
        doc["received"] = _otaReceived;
        doc["total"] = _otaTotal;
        if (_otaTotal > 0 && _otaInProgress) {
            doc["progress"] = (_otaReceived * 100) / _otaTotal;
        }

        String output;
        serializeJson(doc, output);
        request->send(200, "application/json", output);
    });

    // ========================================
    // Chunked OTA Update Protocol
    // ========================================
    // 1. POST /ota/begin?size=TOTAL_SIZE - Initialize update
    // 2. POST /ota/chunk?index=N&crc=CRC32 - Send chunk (body is raw binary)
    // 3. POST /ota/end - Finalize and verify
    // 4. GET /ota/status - Check progress
    // ========================================

    // POST /ota/begin - Initialize chunked OTA update
    server.on("/ota/begin", HTTP_POST, [this](AsyncWebServerRequest *request) {
        if (!request->hasParam("size")) {
            request->send(400, "application/json", "{\"success\":false,\"error\":\"Missing size parameter\"}");
            return;
        }

        size_t totalSize = request->getParam("size")->value().toInt();
        LOG_HTTP("OTA begin: total size = %d bytes, free heap = %d", totalSize, ESP.getFreeHeap());

        if (totalSize == 0 || totalSize > 2000000) {  // Max 2MB for OTA partition
            request->send(400, "application/json", "{\"success\":false,\"error\":\"Invalid firmware size\"}");
            return;
        }

        _otaInProgress = true;
        _otaReceived = 0;
        _otaTotal = totalSize;

        if (!Update.begin(totalSize, U_FLASH)) {
            LOG_HTTP("OTA begin failed: %s", Update.errorString());
            _otaInProgress = false;
            request->send(500, "application/json",
                "{\"success\":false,\"error\":\"" + String(Update.errorString()) + "\"}");
            return;
        }

        LOG_HTTP("OTA Update.begin() successful");

        JsonDocument doc;
        doc["success"] = true;
        doc["message"] = "OTA initialized";
        doc["totalSize"] = totalSize;
        doc["chunkSize"] = 8192;  // Recommended chunk size (8KB fits in RAM easily)

        String output;
        serializeJson(doc, output);
        request->send(200, "application/json", output);
    });

    // POST /ota/chunk - Receive a chunk of firmware data
    server.on("/ota/chunk", HTTP_POST,
        [this](AsyncWebServerRequest *request) {
            // Response sent after body is processed
        },
        nullptr,
        [this](AsyncWebServerRequest *request, uint8_t *data, size_t len, size_t index, size_t total) {
            if (!_otaInProgress) {
                return;
            }

            // For chunked uploads, each request is one chunk
            // index is offset within THIS request's body
            if (index == 0) {
                // Get expected CRC from query param
                uint32_t expectedCrc = 0;
                if (request->hasParam("crc")) {
                    expectedCrc = strtoul(request->getParam("crc")->value().c_str(), NULL, 16);
                }

                // Store for verification after all data received
                request->_tempObject = new uint32_t(expectedCrc);
            }

            // Write data directly to flash
            if (len > 0) {
                size_t written = Update.write(data, len);
                if (written != len) {
                    LOG_HTTP("OTA chunk write failed: %s", Update.errorString());
                    JsonDocument doc;
                    doc["success"] = false;
                    doc["error"] = Update.errorString();
                    String output;
                    serializeJson(doc, output);
                    request->send(500, "application/json", output);
                    return;
                }
                _otaReceived += len;
            }

            // When full chunk received
            if (index + len >= total) {
                // Clean up temp object
                if (request->_tempObject) {
                    delete (uint32_t*)request->_tempObject;
                    request->_tempObject = nullptr;
                }

                LOG_HTTP("OTA chunk received: %d/%d bytes (%d%%)",
                    _otaReceived, _otaTotal, (_otaReceived * 100) / _otaTotal);

                JsonDocument doc;
                doc["success"] = true;
                doc["received"] = _otaReceived;
                doc["total"] = _otaTotal;
                doc["progress"] = (_otaReceived * 100) / _otaTotal;

                String output;
                serializeJson(doc, output);
                request->send(200, "application/json", output);
            }
        }
    );

    // POST /ota/end - Finalize OTA update
    server.on("/ota/end", HTTP_POST, [this](AsyncWebServerRequest *request) {
        if (!_otaInProgress) {
            request->send(400, "application/json", "{\"success\":false,\"error\":\"No OTA in progress\"}");
            return;
        }

        LOG_HTTP("OTA end: received %d of %d bytes", _otaReceived, _otaTotal);

        _otaInProgress = false;

        if (_otaReceived != _otaTotal) {
            Update.abort();
            LOG_HTTP("OTA aborted: incomplete upload");
            request->send(400, "application/json", "{\"success\":false,\"error\":\"Incomplete upload\"}");
            return;
        }

        if (!Update.end(true)) {
            LOG_HTTP("OTA end failed: %s", Update.errorString());
            request->send(500, "application/json",
                "{\"success\":false,\"error\":\"" + String(Update.errorString()) + "\"}");
            return;
        }

        LOG_HTTP("OTA Update.end() successful - firmware ready, restarting...");
        request->send(200, "application/json",
            "{\"success\":true,\"message\":\"Update successful, restarting...\"}");
        _pendingRestart = true;
    });

    // POST /ota/abort - Cancel OTA update
    server.on("/ota/abort", HTTP_POST, [this](AsyncWebServerRequest *request) {
        if (_otaInProgress) {
            Update.abort();
            _otaInProgress = false;
            _otaReceived = 0;
            _otaTotal = 0;
            LOG_HTTP("OTA aborted by user");
        }
        request->send(200, "application/json", "{\"success\":true,\"message\":\"OTA aborted\"}");
    });

    // GET /ota/status - Get current OTA status
    server.on("/ota/status", HTTP_GET, [this](AsyncWebServerRequest *request) {
        JsonDocument doc;
        doc["inProgress"] = _otaInProgress;
        doc["received"] = _otaReceived;
        doc["total"] = _otaTotal;
        if (_otaTotal > 0) {
            doc["progress"] = (_otaReceived * 100) / _otaTotal;
        }
        doc["freeHeap"] = ESP.getFreeHeap();

        String output;
        serializeJson(doc, output);
        request->send(200, "application/json", output);
    });

    LOG_HTTP("OTA routes configured (chunked protocol)");
}

void HttpServer::setupSSE() {
    // Configure SSE event source for status updates
    events.onConnect([](AsyncEventSourceClient *client) {
        if (client->lastId()) {
            LOG_HTTP("SSE status client reconnected, last ID: %u", client->lastId());
        } else {
            LOG_HTTP("SSE status client connected");
        }
        // Send initial state on connect
        client->send("connected", "open", millis());
    });

    // Configure separate SSE event source for log streaming
    logEvents.onConnect([](AsyncEventSourceClient *client) {
        if (client->lastId()) {
            LOG_HTTP("SSE log client reconnected, last ID: %u", client->lastId());
        } else {
            LOG_HTTP("SSE log client connected");
        }
        client->send("connected", "open", millis());
    });

    // Add both event sources to server
    server.addHandler(&events);
    server.addHandler(&logEvents);

    LOG_HTTP("SSE endpoints configured: /events (status), /events/logs (logs)");
}

void HttpServer::broadcastStateIfChanged() {
    // Only broadcast if there are connected clients
    if (events.count() == 0) return;

    // Check if anything changed
    bool stateChanged = (_currentState != _lastBroadcastState);
    bool positionChanged = (_cumulativePosition != _lastBroadcastPosition);
    bool calibrationChanged = (_calibrationState != _lastBroadcastCalibrationState);

    if (!stateChanged && !positionChanged && !calibrationChanged) {
        return;  // Nothing changed, don't broadcast
    }

    // Rate limit broadcasts to prevent overwhelming multiple clients
    // State changes (open/close/stop) are sent immediately
    // Position-only changes during movement are throttled to 50ms min interval
    unsigned long now = millis();
    if (!stateChanged && !calibrationChanged && positionChanged) {
        // Position-only change - throttle to avoid flooding
        if (now - _lastBroadcastTime < 50) {
            return;  // Skip this update, next one will include the change
        }
    }

    // Update tracking
    _lastBroadcastState = _currentState;
    _lastBroadcastPosition = _cumulativePosition;
    _lastBroadcastCalibrationState = _calibrationState;
    _lastBroadcastTime = now;

    // Build and send status JSON to all clients
    String json = buildStatusJson();
    events.send(json.c_str(), "status", millis());
}

void HttpServer::broadcastLog(const char* logEntry) {
    // Only broadcast if there are connected log clients
    if (logEvents.count() == 0) return;

    // Send log entry to log stream clients only
    logEvents.send(logEntry, "log", millis());
}

int HttpServer::getEventClientCount() const {
    return events.count();
}

int HttpServer::getLogClientCount() const {
    return logEvents.count();
}

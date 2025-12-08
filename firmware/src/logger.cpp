#include "logger.h"
#include <stdarg.h>

bool Logger::_enabled = true;
char Logger::_buffer[256];

// Ring buffer storage
char Logger::_logBuffer[LOG_BUFFER_SIZE][LOG_ENTRY_SIZE];
int Logger::_logHead = 0;
int Logger::_logCount = 0;

// SSE broadcast callback
LogBroadcastCallback Logger::_broadcastCallback = nullptr;

void Logger::setLogBroadcastCallback(LogBroadcastCallback callback) {
    _broadcastCallback = callback;
}

void Logger::init(unsigned long baudRate) {
    Serial.begin(baudRate);
    clearBuffer();
}

void Logger::waitForSerial(unsigned long timeoutMs) {
    unsigned long start = millis();
    while (!Serial && (millis() - start) < timeoutMs) {
        delay(10);
    }
    // Small delay to ensure serial is fully ready
    delay(100);
}

void Logger::setEnabled(bool enabled) {
    _enabled = enabled;
}

bool Logger::isEnabled() {
    return _enabled;
}

const char* Logger::getCategoryPrefix(LogCategory category) {
    switch (category) {
        case LogCategory::BOOT:  return "[BOOT]";
        case LogCategory::WIFI:  return "[WIFI]";
        case LogCategory::BLE:   return "[BLE]";
        case LogCategory::MQTT:  return "[MQTT]";
        case LogCategory::SERVO: return "[SERVO]";
        case LogCategory::HTTP:  return "[HTTP]";
        case LogCategory::NVS:   return "[NVS]";
        case LogCategory::HALL:  return "[HALL]";
        case LogCategory::ERROR: return "[ERROR]";
        default:                 return "[???]";
    }
}

void Logger::addToBuffer(const char* entry) {
    strncpy(_logBuffer[_logHead], entry, LOG_ENTRY_SIZE - 1);
    _logBuffer[_logHead][LOG_ENTRY_SIZE - 1] = '\0';
    _logHead = (_logHead + 1) % LOG_BUFFER_SIZE;
    if (_logCount < LOG_BUFFER_SIZE) {
        _logCount++;
    }
}

void Logger::clearBuffer() {
    _logHead = 0;
    _logCount = 0;
    memset(_logBuffer, 0, sizeof(_logBuffer));
}

String Logger::getLogsJson() {
    String json = "[";

    // Calculate start position for reading (oldest entry)
    int start = (_logCount < LOG_BUFFER_SIZE) ? 0 : _logHead;

    for (int i = 0; i < _logCount; i++) {
        int idx = (start + i) % LOG_BUFFER_SIZE;
        if (i > 0) json += ",";

        // Escape the log entry for JSON
        json += "\"";
        const char* entry = _logBuffer[idx];
        while (*entry) {
            char c = *entry++;
            if (c == '"') json += "\\\"";
            else if (c == '\\') json += "\\\\";
            else if (c == '\n') json += "\\n";
            else if (c == '\r') json += "\\r";
            else if (c == '\t') json += "\\t";
            else if (c >= 32 && c < 127) json += c;
            // Skip other control characters
        }
        json += "\"";
    }

    json += "]";
    return json;
}

void Logger::logVa(LogCategory category, const char* format, va_list args) {
    vsnprintf(_buffer, sizeof(_buffer), format, args);

    unsigned long timestamp = millis();

    // Format the full log entry
    char fullEntry[LOG_ENTRY_SIZE];
    snprintf(fullEntry, sizeof(fullEntry), "%8lu %s %s",
             timestamp, getCategoryPrefix(category), _buffer);

    // Add to ring buffer
    addToBuffer(fullEntry);

    // Also output to serial if enabled
    if (_enabled && Serial) {
        Serial.println(fullEntry);
    }

    // Broadcast via SSE if callback is set
    // Skip HTTP logs to avoid recursive feedback (HTTP handlers log, which would broadcast, etc.)
    if (_broadcastCallback && category != LogCategory::HTTP) {
        _broadcastCallback(fullEntry);
    }
}

void Logger::log(LogCategory category, const char* format, ...) {
    va_list args;
    va_start(args, format);
    logVa(category, format, args);
    va_end(args);
}

void Logger::log(LogCategory category, const String& message) {
    log(category, "%s", message.c_str());
}

void Logger::boot(const char* format, ...) {
    va_list args;
    va_start(args, format);
    logVa(LogCategory::BOOT, format, args);
    va_end(args);
}

void Logger::wifi(const char* format, ...) {
    va_list args;
    va_start(args, format);
    logVa(LogCategory::WIFI, format, args);
    va_end(args);
}

void Logger::ble(const char* format, ...) {
    va_list args;
    va_start(args, format);
    logVa(LogCategory::BLE, format, args);
    va_end(args);
}

void Logger::mqtt(const char* format, ...) {
    va_list args;
    va_start(args, format);
    logVa(LogCategory::MQTT, format, args);
    va_end(args);
}

void Logger::servo(const char* format, ...) {
    va_list args;
    va_start(args, format);
    logVa(LogCategory::SERVO, format, args);
    va_end(args);
}

void Logger::http(const char* format, ...) {
    va_list args;
    va_start(args, format);
    logVa(LogCategory::HTTP, format, args);
    va_end(args);
}

void Logger::nvs(const char* format, ...) {
    va_list args;
    va_start(args, format);
    logVa(LogCategory::NVS, format, args);
    va_end(args);
}

void Logger::hall(const char* format, ...) {
    va_list args;
    va_start(args, format);
    logVa(LogCategory::HALL, format, args);
    va_end(args);
}

void Logger::error(const char* format, ...) {
    va_list args;
    va_start(args, format);
    logVa(LogCategory::ERROR, format, args);
    va_end(args);
}

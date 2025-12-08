#ifndef LOGGER_H
#define LOGGER_H

#include <Arduino.h>
#include <functional>

// Log buffer configuration
#define LOG_BUFFER_SIZE 50      // Number of log entries to keep
#define LOG_ENTRY_SIZE 128      // Max size of each log entry

// Callback type for SSE log broadcasting
using LogBroadcastCallback = std::function<void(const char*)>;

// Log categories
enum class LogCategory {
    BOOT,
    WIFI,
    BLE,
    MQTT,
    SERVO,
    HTTP,
    NVS,
    HALL,
    ERROR
};

class Logger {
public:
    static void init(unsigned long baudRate = 115200);

    // Set callback for broadcasting logs via SSE
    static void setLogBroadcastCallback(LogBroadcastCallback callback);

    // Main logging functions
    static void log(LogCategory category, const char* format, ...);
    static void log(LogCategory category, const String& message);

    // Convenience methods for each category
    static void boot(const char* format, ...);
    static void wifi(const char* format, ...);
    static void ble(const char* format, ...);
    static void mqtt(const char* format, ...);
    static void servo(const char* format, ...);
    static void http(const char* format, ...);
    static void nvs(const char* format, ...);
    static void hall(const char* format, ...);
    static void error(const char* format, ...);

    // Enable/disable logging
    static void setEnabled(bool enabled);
    static bool isEnabled();

    // Wait for serial connection (useful during development)
    static void waitForSerial(unsigned long timeoutMs = 3000);

    // Get buffered logs as JSON array string
    static String getLogsJson();

    // Clear the log buffer
    static void clearBuffer();

private:
    static bool _enabled;
    static char _buffer[256];

    // Ring buffer for log entries
    static char _logBuffer[LOG_BUFFER_SIZE][LOG_ENTRY_SIZE];
    static int _logHead;        // Next write position
    static int _logCount;       // Number of entries in buffer

    // Callback for SSE broadcasting
    static LogBroadcastCallback _broadcastCallback;

    static const char* getCategoryPrefix(LogCategory category);
    static void logVa(LogCategory category, const char* format, va_list args);
    static void addToBuffer(const char* entry);
};

// Macros for easy logging
#define LOG_BOOT(...)  Logger::boot(__VA_ARGS__)
#define LOG_WIFI(...)  Logger::wifi(__VA_ARGS__)
#define LOG_BLE(...)   Logger::ble(__VA_ARGS__)
#define LOG_MQTT(...)  Logger::mqtt(__VA_ARGS__)
#define LOG_SERVO(...) Logger::servo(__VA_ARGS__)
#define LOG_HTTP(...)  Logger::http(__VA_ARGS__)
#define LOG_NVS(...)   Logger::nvs(__VA_ARGS__)
#define LOG_HALL(...)  Logger::hall(__VA_ARGS__)
#define LOG_ERROR(...) Logger::error(__VA_ARGS__)

#endif // LOGGER_H

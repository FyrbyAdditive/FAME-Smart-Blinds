#ifndef SERVO_CONTROLLER_H
#define SERVO_CONTROLLER_H

#include <Arduino.h>

// Forward declaration
class HallSensor;
class Storage;

// Blind state enumeration
enum class BlindState {
    UNKNOWN,
    OPEN,
    CLOSED,
    OPENING,
    CLOSING,
    STOPPED,
    RECOVERING   // Re-homing after power outage
};

// Blind command enumeration
enum class BlindCommand {
    OPEN,
    CLOSE,
    STOP
};

// Calibration state enumeration
enum class CalibrationState {
    IDLE,           // Not calibrating
    FINDING_HOME,   // Moving up, waiting for hall sensor
    AT_HOME,        // Hall triggered, waiting for user to set bottom
    COMPLETE        // Calibration just completed
};

class ServoController {
public:
    ServoController();

    // Initialize the servo controller
    bool init(uint8_t servoId = 1, int rxPin = 20, int txPin = 21);

    // Set hall sensor and storage references (must be called after init)
    void setHallSensor(HallSensor* sensor);
    void setStorage(Storage* storage);

    // Basic commands (force bypasses calibration limits)
    void open(bool force = false);
    void close(bool force = false);
    void stop();

    // Execute a command
    void execute(BlindCommand command);

    // State getters
    BlindState getState() const;
    const char* getStateString() const;

    // Servo status
    bool isConnected() const;
    int getCurrentPosition() const;  // Raw servo position 0-4095
    int getLoad() const;
    int getVoltage() const;
    int getTemperature() const;

    // Calibration
    void startCalibration();
    void setBottomPosition();
    void cancelCalibration();
    bool isCalibrating() const;
    CalibrationState getCalibrationState() const;
    const char* getCalibrationStateString() const;

    // Position tracking (cumulative across rotations)
    int32_t getCumulativePosition() const;
    int32_t getMaxPosition() const;
    bool isCalibrated() const;

    // Update loop (call regularly)
    void update();

    // Set servo ID (for multi-servo setups)
    void setServoId(uint8_t id);
    uint8_t getServoId() const;

    // Configuration
    void setSpeed(uint16_t speed);
    void setAcceleration(uint8_t acc);

    // Orientation (for direction inversion)
    void setInvertDirection(bool invert);
    bool getInvertDirection() const;

    // Power outage recovery
    bool needsRecovery() const;
    void startRecovery();
    bool isRecovering() const;

private:
    uint8_t _servoId;
    BlindState _state;
    bool _initialized;
    bool _connected;

    uint16_t _speed;
    uint8_t _acceleration;
    bool _invertDirection;     // True for right-hand mount

    int _currentPosition;      // Raw servo position 0-4095
    int _targetPosition;

    unsigned long _lastUpdateTime;
    unsigned long _movementStartTime;

    // Calibration
    HallSensor* _hallSensor;
    Storage* _storage;
    CalibrationState _calibrationState;
    bool _calibrated;
    int32_t _maxPosition;           // Maximum position (bottom limit)
    int32_t _cumulativePosition;    // Tracked position across rotations
    int _lastRawPosition;           // For detecting wrap-around
    unsigned long _lastPositionSaveTime;

    // Movement timeout (stop if no position change detected)
    static const unsigned long MOVEMENT_TIMEOUT_MS = 30000;
    // Position save interval while moving
    static const unsigned long POSITION_SAVE_INTERVAL_MS = 3000;

    // Power outage recovery
    bool _needsRecovery;                // Flag set on boot if recovery needed
    int32_t _recoveryTargetPosition;    // Position to return to after re-homing
    bool _recoveryReturning;            // True when returning to target after home

    // Internal methods
    void checkPowerOutageRecovery();    // Called during setStorage()
    void updateState();
    bool pingServo();
    void readServoStatus();
    void updateCumulativePosition();
    void checkCalibrationLimits();
    void savePositionIfNeeded();
};

// Helper function to convert state to string
const char* blindStateToString(BlindState state);
const char* blindCommandToString(BlindCommand command);

#endif // SERVO_CONTROLLER_H

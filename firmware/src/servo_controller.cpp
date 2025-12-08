#include "servo_controller.h"
#include "config.h"
#include "logger.h"
#include "hall_sensor.h"
#include "storage.h"
#include <SCServo.h>

// Global servo instance (SCServo library uses global serial)
static SMS_STS servo;

// For ESP32-C3/C6/S3 with XIAO Bus Servo Adapter, use Serial0 (hardware UART on D6/D7)
// For other boards, use Serial1
#define SERVO_SERIAL Serial0
#define SERVO_SERIAL_NAME "Serial0"
// XIAO ESP32-C3 Bus Servo Adapter pins: D6=TX (GPIO21), D7=RX (GPIO20)
#define S_RXD 20  // D7
#define S_TXD 21  // D6

ServoController::ServoController()
    : _servoId(DEFAULT_SERVO_ID)
    , _state(BlindState::UNKNOWN)
    , _initialized(false)
    , _connected(false)
    , _speed(SERVO_SPEED)
    , _acceleration(SERVO_ACCELERATION)
    , _invertDirection(false)
    , _currentPosition(0)
    , _targetPosition(0)
    , _lastUpdateTime(0)
    , _movementStartTime(0)
    , _hallSensor(nullptr)
    , _storage(nullptr)
    , _calibrationState(CalibrationState::IDLE)
    , _calibrated(false)
    , _maxPosition(0)
    , _cumulativePosition(0)
    , _lastRawPosition(0)
    , _lastPositionSaveTime(0)
    , _needsRecovery(false)
    , _recoveryTargetPosition(0)
    , _recoveryReturning(false)
{
}

void ServoController::setHallSensor(HallSensor* sensor) {
    _hallSensor = sensor;
}

void ServoController::setStorage(Storage* storage) {
    _storage = storage;
    if (_storage) {
        // Load calibration data from storage
        _calibrated = _storage->isCalibrated();
        _maxPosition = _storage->getMaxPosition();
        _cumulativePosition = _storage->getCurrentPosition();
        LOG_SERVO("Loaded calibration: calibrated=%s, maxPos=%d, curPos=%d",
                  _calibrated ? "true" : "false", _maxPosition, _cumulativePosition);

        // Check for power outage recovery
        checkPowerOutageRecovery();
    }
}

bool ServoController::init(uint8_t servoId, int rxPin, int txPin) {
    _servoId = servoId;

    LOG_SERVO("Initializing servo ID %d using %s at %d baud", servoId, SERVO_SERIAL_NAME, SERVO_BAUD_RATE);
    LOG_SERVO("Speed: %d, Acceleration: %d", _speed, _acceleration);

    // Initialize serial for servo communication
    // For XIAO ESP32-C3 with Bus Servo Adapter:
    // - Use Serial0 with explicit pins D6=TX (GPIO21), D7=RX (GPIO20)
    // - 1Mbaud, 8N1
    // ESP32 variant: specify RX/TX pins explicitly
    SERVO_SERIAL.begin(SERVO_BAUD_RATE, SERIAL_8N1);
    servo.pSerial = &SERVO_SERIAL;

    delay(1000);  // Give serial time to initialize (per FTServo examples)

    LOG_SERVO("%s initialized, attempting to ping servo...", SERVO_SERIAL_NAME);

    // Try to ping the servo using Ping() + getLastError() like the SDK example
    for (int attempt = 0; attempt < 3; attempt++) {
        //int lastError = servo.getLastError();
        //LOG_SERVO("Ping(%d) returned %d, getLastError()=%d", _servoId, pingResult, lastError);

        if (servo.Ping(_servoId) != -1) {
            // No error - servo responded
            _initialized = true;
            _connected = true;
            LOG_SERVO("Servo ID %d connected on attempt %d", _servoId, attempt + 1);

            // Put servo in wheel mode for continuous rotation (per WriteSpe example)
            servo.WheelMode(_servoId);
            LOG_SERVO("WheelMode(%d) called", _servoId);

            _state = BlindState::STOPPED;
            return true;
        }
        LOG_SERVO("Ping attempt %d failed, retrying...", attempt + 1);
        delay(500);
    }

    LOG_ERROR("Failed to communicate with servo ID %d after 3 attempts", _servoId);
    _initialized = true;  // Still mark as initialized, we'll retry later
    _connected = false;
    return false;
}

bool ServoController::pingServo() {
    // Use Ping() + getLastError() like the SDK example
    servo.Ping(_servoId);
    if (!servo.getLastError()) {
        // Servo responded, try to read position
        int pos = servo.ReadPos(_servoId);
        if (pos >= 0 && pos <= 4095) {
            _currentPosition = pos;
        }
        return true;
    }
    return false;
}

void ServoController::readServoStatus() {
    int pos = servo.ReadPos(_servoId);
    LOG_SERVO("readServoStatus: pos=%d for ID %d", pos, _servoId);
    if (pos != -1 && pos >= 0 && pos <= 4095) {
        _currentPosition = pos;
        _connected = true;
    } else {
        _connected = false;
    }
}

void ServoController::open(bool force) {
    LOG_SERVO("open() called: calibrated=%s, force=%s, cumPos=%d",
              _calibrated ? "true" : "false", force ? "true" : "false", _cumulativePosition);

    if (!_initialized) {
        LOG_ERROR("Servo not initialized");
        return;
    }

    // Check calibration limits (OPEN = toward home, position decreasing toward 0)
    if (_calibrated && !force && _cumulativePosition <= 0) {
        LOG_SERVO("BLOCKED: Already at home position, ignoring OPEN command (cumPos=%d)", _cumulativePosition);
        _state = BlindState::STOPPED;
        return;
    }

    // Reload speed from storage in case it was changed
    if (_storage) {
        _speed = _storage->getServoSpeed();
    }

    LOG_SERVO("Opening blind (servo ID %d, connected: %s, force: %s, speed: %d)",
              _servoId, _connected ? "yes" : "no", force ? "yes" : "no", _speed);

    _state = BlindState::OPENING;
    _movementStartTime = millis();

    // Persist moving state for power outage recovery (target = home = 0)
    if (_storage && _calibrated && !force) {
        _storage->setTargetPosition(0);
        _storage->setWasMoving(true);
    }

    // Use wheel mode for continuous rotation
    // OPEN = move toward home
    // Direction is inverted for right-hand mount
    int16_t actualSpeed = _invertDirection ? -_speed : _speed;
    int result = servo.WriteSpe(_servoId, actualSpeed, _acceleration);
    LOG_SERVO("WriteSpe(%d, %d, %d) returned %d (invert=%s)", _servoId, actualSpeed, _acceleration, result, _invertDirection ? "yes" : "no");
}

void ServoController::close(bool force) {
    if (!_initialized) {
        LOG_ERROR("Servo not initialized");
        return;
    }

    // Check calibration limits (CLOSE = toward bottom, position increasing toward maxPosition)
    if (_calibrated && !force && _cumulativePosition >= _maxPosition) {
        LOG_SERVO("Already at max position, ignoring CLOSE command");
        _state = BlindState::STOPPED;
        return;
    }

    // Reload speed from storage in case it was changed
    if (_storage) {
        _speed = _storage->getServoSpeed();
    }

    LOG_SERVO("Closing blind (servo ID %d, connected: %s, force: %s, speed: %d)",
              _servoId, _connected ? "yes" : "no", force ? "yes" : "no", _speed);

    _state = BlindState::CLOSING;
    _movementStartTime = millis();

    // Persist moving state for power outage recovery (target = bottom = maxPosition)
    if (_storage && _calibrated && !force) {
        _storage->setTargetPosition(_maxPosition);
        _storage->setWasMoving(true);
    }

    // CLOSE = move toward bottom (opposite direction from open)
    // Direction is inverted for right-hand mount
    int16_t actualSpeed = _invertDirection ? _speed : -_speed;
    int result = servo.WriteSpe(_servoId, actualSpeed, _acceleration);
    LOG_SERVO("WriteSpe(%d, %d, %d) returned %d (invert=%s)", _servoId, actualSpeed, _acceleration, result, _invertDirection ? "yes" : "no");
}

void ServoController::stop() {
    if (!_initialized) {
        LOG_ERROR("Servo not initialized");
        return;
    }

    LOG_SERVO("Stopping blind (servo ID %d, connected: %s)", _servoId, _connected ? "yes" : "no");

    // Stop the servo by setting speed to 0
    int result = servo.WriteSpe(_servoId, 0, _acceleration);
    LOG_SERVO("WriteSpe(%d, 0, %d) returned %d", _servoId, _acceleration, result);

    _state = BlindState::STOPPED;
    readServoStatus();

    // Save position and clear moving flag on stop
    if (_storage && _calibrated) {
        _storage->setCurrentPosition(_cumulativePosition);
        _storage->setWasMoving(false);
    }
}

void ServoController::execute(BlindCommand command) {
    switch (command) {
        case BlindCommand::OPEN:
            open();
            break;
        case BlindCommand::CLOSE:
            close();
            break;
        case BlindCommand::STOP:
            stop();
            break;
    }
}

BlindState ServoController::getState() const {
    return _state;
}

const char* ServoController::getStateString() const {
    return blindStateToString(_state);
}

bool ServoController::isConnected() const {
    return _connected;
}

int ServoController::getCurrentPosition() const {
    return _currentPosition;
}

int ServoController::getLoad() const {
    if (!_connected) return 0;
    return servo.ReadLoad(_servoId);
}

int ServoController::getVoltage() const {
    if (!_connected) return 0;
    return servo.ReadVoltage(_servoId);
}

int ServoController::getTemperature() const {
    if (!_connected) return 0;
    return servo.ReadTemper(_servoId);
}

void ServoController::update() {
    unsigned long now = millis();

    // Update every 100ms when moving/recovering, 1000ms when stopped
    bool isMoving = (_state == BlindState::OPENING || _state == BlindState::CLOSING || _state == BlindState::RECOVERING);
    unsigned long updateInterval = isMoving ? 100 : 1000;

    if (now - _lastUpdateTime < updateInterval) {
        return;
    }
    _lastUpdateTime = now;

    // Re-check connection periodically
    if (!pingServo()) {
        if (_connected) {
            LOG_ERROR("Lost connection to servo ID %d", _servoId);
            _connected = false;
        }
        return;
    }

    if (!_connected) {
        LOG_SERVO("Reconnected to servo ID %d", _servoId);
        _connected = true;
    }

    readServoStatus();

    // Update cumulative position tracking
    updateCumulativePosition();

    // Check calibration during FINDING_HOME state
    if (_calibrationState == CalibrationState::FINDING_HOME && _hallSensor) {
        if (_hallSensor->isTriggered()) {
            LOG_SERVO("Hall sensor triggered - HOME position found!");
            stop();
            _cumulativePosition = 0;  // This is now home
            _lastRawPosition = _currentPosition;
            _calibrationState = CalibrationState::AT_HOME;

            // Save home position
            if (_storage) {
                _storage->setCurrentPosition(0);
            }
        }
    }

    // Handle power outage recovery state machine
    if (_state == BlindState::RECOVERING && _hallSensor) {
        if (!_recoveryReturning) {
            // Phase 1: Moving toward home, waiting for hall sensor
            if (_hallSensor->isTriggered()) {
                LOG_SERVO("Recovery: HOME position found!");
                // Stop and reset position
                servo.WriteSpe(_servoId, 0, _acceleration);
                _cumulativePosition = 0;
                _lastRawPosition = _currentPosition;

                if (_storage) {
                    _storage->setCurrentPosition(0);
                }

                // Now return to target position
                if (_recoveryTargetPosition > 0) {
                    LOG_SERVO("Recovery: Returning to position %d", _recoveryTargetPosition);
                    _recoveryReturning = true;
                    // Start closing toward target
                    int16_t actualSpeed = _invertDirection ? _speed : -_speed;
                    servo.WriteSpe(_servoId, actualSpeed, _acceleration);
                    _movementStartTime = millis();
                } else {
                    // Target was home, we're done
                    LOG_SERVO("Recovery: Complete (target was home)");
                    _state = BlindState::OPEN;
                    _needsRecovery = false;
                    _recoveryReturning = false;
                    if (_storage) {
                        _storage->setWasMoving(false);
                    }
                }
            }
        } else {
            // Phase 2: Returning to target position
            if (_cumulativePosition >= _recoveryTargetPosition) {
                LOG_SERVO("Recovery: Reached target position %d", _recoveryTargetPosition);
                servo.WriteSpe(_servoId, 0, _acceleration);
                _cumulativePosition = _recoveryTargetPosition;  // Clamp
                _state = BlindState::CLOSED;
                _needsRecovery = false;
                _recoveryReturning = false;

                if (_storage) {
                    _storage->setCurrentPosition(_cumulativePosition);
                    _storage->setWasMoving(false);
                }
            }
        }
    }

    // Check calibration limits during normal operation
    checkCalibrationLimits();

    // Save position periodically
    savePositionIfNeeded();

    // Check for movement timeout (only for uncalibrated devices or recovery)
    // Calibrated devices rely on position limits instead of timeout
    // Skip timeout during active calibration - user controls when to stop
    if (_state == BlindState::OPENING || _state == BlindState::CLOSING || _state == BlindState::RECOVERING) {
        bool isCalibrating = (_calibrationState == CalibrationState::FINDING_HOME ||
                              _calibrationState == CalibrationState::AT_HOME);
        bool shouldTimeout = (!_calibrated || _state == BlindState::RECOVERING) && !isCalibrating;
        if (shouldTimeout && (now - _movementStartTime > MOVEMENT_TIMEOUT_MS)) {
            LOG_SERVO("Movement timeout, stopping");
            stop();

            // Clear recovery state on timeout
            if (_state == BlindState::RECOVERING) {
                _needsRecovery = false;
                _recoveryReturning = false;
            }

            // Save final position on stop
            if (_storage && _calibrated) {
                _storage->setCurrentPosition(_cumulativePosition);
            }
        }
    }
}

void ServoController::setServoId(uint8_t id) {
    _servoId = id;
    LOG_SERVO("Servo ID changed to %d", _servoId);
}

uint8_t ServoController::getServoId() const {
    return _servoId;
}

void ServoController::setSpeed(uint16_t speed) {
    _speed = speed;
}

void ServoController::setAcceleration(uint8_t acc) {
    _acceleration = acc;
}

void ServoController::setInvertDirection(bool invert) {
    _invertDirection = invert;
    LOG_SERVO("Direction inversion set to: %s", invert ? "true (right mount)" : "false (left mount)");
}

bool ServoController::getInvertDirection() const {
    return _invertDirection;
}

// Calibration methods
void ServoController::startCalibration() {
    if (!_hallSensor) {
        LOG_ERROR("Cannot calibrate: Hall sensor not set");
        return;
    }

    LOG_SERVO("Starting calibration - finding home position");

    // Reset calibration state completely
    _calibrationState = CalibrationState::FINDING_HOME;
    _calibrated = false;
    _maxPosition = 0;

    // Clear any existing hall sensor trigger so we detect a NEW trigger
    _hallSensor->clearTriggered();

    LOG_SERVO("Hall sensor cleared, raw state: %s",
              _hallSensor->getRawState() == LOW ? "LOW (magnet)" : "HIGH (no magnet)");

    // Move toward home (open direction)
    open(true);  // Force to bypass limits
}

void ServoController::setBottomPosition() {
    if (_calibrationState != CalibrationState::AT_HOME) {
        LOG_ERROR("Cannot set bottom: not in AT_HOME state");
        return;
    }

    _maxPosition = _cumulativePosition;
    _calibrated = true;
    _calibrationState = CalibrationState::COMPLETE;

    // Save to storage
    if (_storage) {
        _storage->setMaxPosition(_maxPosition);
        _storage->setCalibrated(true);
        _storage->setCurrentPosition(_cumulativePosition);
    }

    LOG_SERVO("Calibration complete - maxPosition=%d", _maxPosition);
}

void ServoController::cancelCalibration() {
    if (_calibrationState != CalibrationState::IDLE) {
        LOG_SERVO("Cancelling calibration");
        stop();
        _calibrationState = CalibrationState::IDLE;
    }
}

bool ServoController::isCalibrating() const {
    return _calibrationState != CalibrationState::IDLE &&
           _calibrationState != CalibrationState::COMPLETE;
}

CalibrationState ServoController::getCalibrationState() const {
    return _calibrationState;
}

const char* ServoController::getCalibrationStateString() const {
    switch (_calibrationState) {
        case CalibrationState::IDLE:         return "idle";
        case CalibrationState::FINDING_HOME: return "finding_home";
        case CalibrationState::AT_HOME:      return "at_home";
        case CalibrationState::COMPLETE:     return "complete";
        default:                             return "unknown";
    }
}

int32_t ServoController::getCumulativePosition() const {
    return _cumulativePosition;
}

int32_t ServoController::getMaxPosition() const {
    return _maxPosition;
}

bool ServoController::isCalibrated() const {
    return _calibrated;
}

// Power outage recovery methods
void ServoController::checkPowerOutageRecovery() {
    if (!_storage || !_calibrated) {
        return;  // Can't recover if not calibrated
    }

    bool wasMoving = _storage->getWasMoving();
    if (wasMoving) {
        _recoveryTargetPosition = _storage->getTargetPosition();
        _needsRecovery = true;
        LOG_SERVO("Power outage detected! Was moving to position %d, will re-home first", _recoveryTargetPosition);
    }
}

bool ServoController::needsRecovery() const {
    return _needsRecovery;
}

void ServoController::startRecovery() {
    if (!_needsRecovery || !_hallSensor) {
        LOG_SERVO("startRecovery called but recovery not needed or no hall sensor");
        return;
    }

    LOG_SERVO("Starting power outage recovery - moving to home first");

    _state = BlindState::RECOVERING;
    _recoveryReturning = false;
    _movementStartTime = millis();

    // Clear hall sensor trigger so we detect fresh
    _hallSensor->clearTriggered();

    // Move toward home (open direction)
    int16_t actualSpeed = _invertDirection ? -_speed : _speed;
    servo.WriteSpe(_servoId, actualSpeed, _acceleration);
}

bool ServoController::isRecovering() const {
    return _state == BlindState::RECOVERING;
}

void ServoController::updateCumulativePosition() {
    int rawPos = _currentPosition;

    // Calculate delta with wrap-around handling
    int delta = rawPos - _lastRawPosition;

    // Handle wrap-around (4095 -> 0 or 0 -> 4095)
    if (delta > 2048) delta -= 4096;
    if (delta < -2048) delta += 4096;

    _cumulativePosition += delta;
    _lastRawPosition = rawPos;
}

void ServoController::checkCalibrationLimits() {
    if (!_calibrated) return;

    // Skip limit checks during calibration - we need unrestricted movement
    if (_calibrationState != CalibrationState::IDLE &&
        _calibrationState != CalibrationState::COMPLETE) {
        return;
    }

    // Check if we've hit the limits and auto-stop
    if (_state == BlindState::OPENING && _cumulativePosition <= 0) {
        LOG_SERVO("LIMIT HIT: Reached home position (0), stopping. cumPos=%d", _cumulativePosition);
        _cumulativePosition = 0;  // Clamp to exactly 0
        stop();
        _state = BlindState::OPEN;
    } else if (_state == BlindState::CLOSING && _cumulativePosition >= _maxPosition) {
        LOG_SERVO("LIMIT HIT: Reached max position (%d), stopping. cumPos=%d", _maxPosition, _cumulativePosition);
        _cumulativePosition = _maxPosition;  // Clamp to max
        stop();
        _state = BlindState::CLOSED;
    }
}

void ServoController::savePositionIfNeeded() {
    if (!_storage || !_calibrated) return;

    unsigned long now = millis();

    // Save position periodically while moving
    if (_state == BlindState::OPENING || _state == BlindState::CLOSING) {
        if (now - _lastPositionSaveTime >= POSITION_SAVE_INTERVAL_MS) {
            _storage->setCurrentPosition(_cumulativePosition);
            _lastPositionSaveTime = now;
        }
    }
}

// Helper functions
const char* blindStateToString(BlindState state) {
    switch (state) {
        case BlindState::UNKNOWN:    return "unknown";
        case BlindState::OPEN:       return "open";
        case BlindState::CLOSED:     return "closed";
        case BlindState::OPENING:    return "opening";
        case BlindState::CLOSING:    return "closing";
        case BlindState::STOPPED:    return "stopped";
        case BlindState::RECOVERING: return "recovering";
        default:                     return "unknown";
    }
}

const char* blindCommandToString(BlindCommand command) {
    switch (command) {
        case BlindCommand::OPEN:  return "OPEN";
        case BlindCommand::CLOSE: return "CLOSE";
        case BlindCommand::STOP:  return "STOP";
        default:                  return "UNKNOWN";
    }
}

#include "hall_sensor.h"
#include "logger.h"

HallSensor* HallSensor::_instance = nullptr;

HallSensor::HallSensor()
    : _pin(0)
    , _triggered(false)
    , _interruptFlag(false)
    , _triggerCount(0)
    , _lastTriggerTime(0)
    , _initialized(false)
    , _pendingTrigger(false)
    , _pendingTriggerTime(0)
{
}

void IRAM_ATTR HallSensor::isrHandler() {
    if (_instance) {
        // FALLING edge = HIGH -> LOW transition = magnet arriving
        // Don't set _triggered here - let update() handle debouncing
        _instance->_interruptFlag = true;
    }
}

void HallSensor::init(uint8_t pin) {
    _pin = pin;
    _instance = this;

    pinMode(_pin, INPUT);

    delay(10);

    // LOW = magnet present (per datasheet)
    // Check if magnet is already present at startup
    int initialReading = digitalRead(_pin);
    if (initialReading == LOW) {
        // Magnet already present - start pending trigger for debounce
        _pendingTrigger = true;
        _pendingTriggerTime = millis();
    }

    // FALLING = HIGH -> LOW = magnet arriving
    attachInterrupt(digitalPinToInterrupt(_pin), isrHandler, FALLING);

    _initialized = true;

    LOG_BOOT("Hall sensor initialized on pin %d (FALLING interrupt, initial: %s, raw=%d, debounce=%lums)",
             _pin, initialReading == LOW ? "MAGNET PRESENT" : "no magnet", initialReading, DEBOUNCE_MS);
}

bool HallSensor::isTriggered() const {
    return _triggered;
}

void HallSensor::clearTriggered() {
    _triggered = false;
    _interruptFlag = false;
    _pendingTrigger = false;
    _pendingTriggerTime = 0;
}

bool HallSensor::getRawState() const {
    if (!_initialized) return HIGH;
    return digitalRead(_pin);
}

uint32_t HallSensor::getTriggerCount() const {
    return _triggerCount;
}

void HallSensor::update() {
    if (!_initialized) return;

    unsigned long now = millis();
    bool currentState = digitalRead(_pin);  // LOW = magnet present

    // Handle new interrupt - start debounce period
    if (_interruptFlag) {
        _interruptFlag = false;
        if (!_pendingTrigger && !_triggered) {
            // Start a new pending trigger
            _pendingTrigger = true;
            _pendingTriggerTime = now;
            LOG_SERVO("Hall sensor: potential trigger detected, starting debounce...");
        }
    }

    // Process pending trigger with debounce
    if (_pendingTrigger && !_triggered) {
        if (currentState == LOW) {
            // Magnet still present - check if debounce period has elapsed
            if (now - _pendingTriggerTime >= DEBOUNCE_MS) {
                // Signal has been stable for debounce period - confirm trigger
                _triggered = true;
                _triggerCount++;
                _lastTriggerTime = now;
                _pendingTrigger = false;
                LOG_SERVO("Hall sensor TRIGGERED (confirmed after %lums debounce, count: %u)",
                         DEBOUNCE_MS, _triggerCount);
            }
        } else {
            // Magnet no longer present - this was a false trigger, cancel it
            LOG_SERVO("Hall sensor: false trigger rejected (signal unstable after %lums)",
                     now - _pendingTriggerTime);
            _pendingTrigger = false;
            _pendingTriggerTime = 0;
        }
    }
}

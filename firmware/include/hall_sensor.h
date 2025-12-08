#ifndef HALL_SENSOR_H
#define HALL_SENSOR_H

#include <Arduino.h>

class HallSensor {
public:
    HallSensor();

    // Initialize the hall sensor on the specified pin with interrupt
    void init(uint8_t pin);

    // Returns true when magnet is detected (sensor reads LOW)
    bool isTriggered() const;

    // Clear the triggered flag (call after handling the trigger)
    void clearTriggered();

    // Update sensor state (processes interrupt flags with debouncing)
    void update();

    // Get raw pin state (for debugging)
    bool getRawState() const;

    // Get trigger count (for debugging)
    uint32_t getTriggerCount() const;

private:
    uint8_t _pin;
    volatile bool _triggered;           // Confirmed trigger (after debounce)
    volatile bool _interruptFlag;       // Raw interrupt received
    volatile uint32_t _triggerCount;
    unsigned long _lastTriggerTime;
    bool _initialized;

    // Debounce state machine
    bool _pendingTrigger;               // Interrupt received, waiting for debounce
    unsigned long _pendingTriggerTime;  // When the pending trigger started

    // Debounce time in milliseconds - signal must be stable for this duration
    static const unsigned long DEBOUNCE_MS = 100;

    // ISR handler - must be static
    static void IRAM_ATTR isrHandler();

    // Pointer to the singleton instance for ISR access
    static HallSensor* _instance;
};

#endif // HALL_SENSOR_H

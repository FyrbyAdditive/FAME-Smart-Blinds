package com.fyrbyadditive.famesmartblinds.data.model

/**
 * Device mounting orientation (which side of the window the servo is on)
 */
enum class DeviceOrientation(val value: String) {
    LEFT("left"),
    RIGHT("right");

    val displayName: String
        get() = when (this) {
            LEFT -> "Left Side"
            RIGHT -> "Right Side"
        }

    val description: String
        get() = when (this) {
            LEFT -> "Servo mounted on the left side of the window"
            RIGHT -> "Servo mounted on the right side of the window"
        }

    companion object {
        fun fromString(value: String?): DeviceOrientation {
            return entries.find { it.value.equals(value, ignoreCase = true) } ?: LEFT
        }
    }
}

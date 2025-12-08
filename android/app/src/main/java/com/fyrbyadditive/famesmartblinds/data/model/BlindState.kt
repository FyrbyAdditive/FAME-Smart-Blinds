package com.fyrbyadditive.famesmartblinds.data.model

/**
 * Represents the current state of a blind device
 */
enum class BlindState(val value: String) {
    UNKNOWN("unknown"),
    OPEN("open"),
    CLOSED("closed"),
    OPENING("opening"),
    CLOSING("closing"),
    STOPPED("stopped");

    val displayName: String
        get() = value.replaceFirstChar { it.uppercase() }

    val isMoving: Boolean
        get() = this == OPENING || this == CLOSING

    companion object {
        fun fromString(value: String): BlindState {
            return entries.find { it.value.equals(value, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

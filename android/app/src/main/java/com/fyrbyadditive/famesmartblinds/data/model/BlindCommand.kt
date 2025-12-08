package com.fyrbyadditive.famesmartblinds.data.model

/**
 * Commands that can be sent to a blind device
 */
enum class BlindCommand(val value: String) {
    OPEN("OPEN"),
    CLOSE("CLOSE"),
    STOP("STOP"),
    RESTART("RESTART");

    val displayName: String
        get() = value.lowercase().replaceFirstChar { it.uppercase() }

    val icon: String
        get() = when (this) {
            OPEN -> "arrow_upward"
            CLOSE -> "arrow_downward"
            STOP -> "stop"
            RESTART -> "refresh"
        }

    companion object {
        val userCommands = listOf(OPEN, CLOSE, STOP)
    }
}

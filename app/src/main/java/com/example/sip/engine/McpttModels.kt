package com.example.sip.engine

enum class RegistrationState {
    UNREGISTERED,
    MCPTT_APN_BOUND,
    REGISTERING,
    AUTHENTICATING,
    REGISTERED,
    REGISTRATION_FAILED,
    NETWORK_UNAVAILABLE;

    val isRegistered: Boolean
        get() = this == REGISTERED
}

enum class CallSessionState {
    IDLE,
    CALLING,
    CONNECTED,
    DISCONNECTING
}

enum class FloorState {
    IDLE,
    REQUESTING,
    GRANTED,
    LISTENING,
    RELEASING
}

data class NegotiatedMedia(
    val host: String,
    val rtpPort: Int,
    val rtcpPort: Int = rtpPort + 1,
    val codec: String = "PCMU/8000"
)

data class IncomingChatMessage(
    val from: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

package me.kavishdevar.librepods.data.handoff

enum class HandoffOwner { NONE, MAC, PHONE }

fun interface HandoffListener {
    fun onClaim(owner: HandoffOwner, revision: Long)
}

data class HandoffDevice(
    val id: String,
    val name: String,
)

enum class HandoffStatus {
    DISABLED,
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    PAIRING,
    AUTHENTICATING,
    READY,
    ERROR,
}

enum class HandoffIssue {
    BLUETOOTH_UNAVAILABLE,
    PERMISSION_REQUIRED,
    CONNECTION_FAILED,
    HANDSHAKE_TIMED_OUT,
    PROTOCOL_ERROR,
    IDENTITY_UNAVAILABLE,
}

data class MacHandoffState(
    val status: HandoffStatus = HandoffStatus.DISABLED,
    val enabled: Boolean = false,
    val paired: Boolean = false,
    val savedConnection: Boolean = false,
    val pairedName: String? = null,
    val owner: HandoffOwner = HandoffOwner.NONE,
    val revision: Long = -1L,
    val devices: List<HandoffDevice> = emptyList(),
    val pairingCode: String? = null,
    val pairingName: String? = null,
    val issue: HandoffIssue? = null,
    val transitionMessage: String? = null,
    val transitionSucceeded: Boolean? = null,
)

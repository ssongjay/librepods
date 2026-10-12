package me.kavishdevar.librepods.bluetooth

/** Connect only after an observed out-of-ear to in-ear transition, never on rediscovery alone. */
class WearConnectionPolicy {
    private var worn: Boolean? = null
    private var session = 0L
    private var attempts = 0
    private var completed = false
    private var inFlight: Long? = null
    private var nextAttemptAt = 0L

    @Synchronized
    @JvmOverloads
    fun observe(
        enabled: Boolean, isWorn: Boolean, audioConnected: Boolean, nowMs: Long,
        otherDeviceActive: Boolean = false
    ): Long? {
        if (!enabled) {
            forgetObservation()
            return null
        }
        if (!isWorn) {
            if (worn != false) session++
            worn = false
            attempts = 0
            completed = false
            nextAttemptAt = 0L
            return null
        }
        if (worn == null) {
            // Startup or a BLE gap is not evidence that the user just put the earbuds in.
            worn = true
            completed = true
            return null
        }
        if (worn == false) {
            worn = true
            session++
        }
        if (audioConnected || otherDeviceActive) completed = true
        if (completed || inFlight != null || attempts >= MAX_ATTEMPTS || nowMs < nextAttemptAt) {
            return null
        }
        attempts++
        inFlight = session
        return session
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = worn == true && !completed && session == token && inFlight == token

    @Synchronized
    fun forgetObservation() {
        if (worn != null) session++
        worn = null
        attempts = 0
        completed = false
        nextAttemptAt = 0L
    }

    @Synchronized
    fun finish(token: Long, nowMs: Long, audioConnected: Boolean) {
        if (inFlight != token) return
        inFlight = null
        if (session == token && worn == true) {
            if (audioConnected) completed = true
            nextAttemptAt = nowMs + RETRY_DELAY_MS
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2_000L
    }
}

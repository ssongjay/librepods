package me.kavishdevar.librepods.bluetooth

/** One bounded connection sequence per wear session, independent of rotating BLE addresses. */
class WearConnectionPolicy {
    private var worn = false
    private var session = 0L
    private var attempts = 0
    private var completed = false
    private var inFlight: Long? = null
    private var nextAttemptAt = 0L

    @Synchronized
    fun observe(enabled: Boolean, isWorn: Boolean, audioConnected: Boolean, nowMs: Long): Long? {
        if (!enabled || !isWorn) {
            if (worn) session++
            worn = false
            attempts = 0
            completed = false
            nextAttemptAt = 0L
            return null
        }
        if (!worn) {
            worn = true
            session++
        }
        if (audioConnected) completed = true
        if (completed || inFlight != null || attempts >= MAX_ATTEMPTS || nowMs < nextAttemptAt) {
            return null
        }
        attempts++
        inFlight = session
        return session
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = worn && !completed && session == token && inFlight == token

    @Synchronized
    fun finish(token: Long, nowMs: Long, audioConnected: Boolean) {
        if (inFlight != token) return
        inFlight = null
        if (session == token && worn) {
            if (audioConnected) completed = true
            nextAttemptAt = nowMs + RETRY_DELAY_MS
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2_000L
    }
}

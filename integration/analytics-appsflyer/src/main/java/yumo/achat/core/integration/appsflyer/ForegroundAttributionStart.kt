package yumo.achat.core.integration.appsflyer

import java.lang.ref.WeakReference

/** Main-thread owner of the current foreground host and two independent SDK in-flight guards. */
internal class ForegroundAttributionStart<T : Any>(
    private val valid: (T) -> Boolean,
    private val start: (T, Long) -> Boolean,
    private val timeoutMillis: Long = 15_000,
    private val minIntervalMillis: Long = 1_000,
    private val nowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private var host = WeakReference<T>(null)
    private var requested = false
    private var retryPending = false
    private var nextAttempt = 0L
    @Volatile private var activeAttempt = 0L
    private var launchPending = false
    private var conversionPending = false
    private var startedAt = 0L
    private var lastStartedAt: Long? = null

    val latestAttempt: Long get() = activeAttempt

    fun attach(value: T) { host = WeakReference(value); dispatch() }
    fun detach(value: T) { if (host.get() === value) host.clear() }
    fun request(retry: Boolean = false) {
        requested = true
        retryPending = retryPending || retry
        dispatch()
    }
    fun dropPendingRetry() { retryPending = false }
    fun revokeAccess() {
        host.clear()
        requested = false
        retryPending = false
    }
    fun launchAccepted(attempt: Long) { if (attempt == activeAttempt) { launchPending = false; dispatch() } }
    fun launchFailed(attempt: Long) {
        if (attempt == activeAttempt) {
            launchPending = false
            conversionPending = false
            dispatch()
        }
    }
    fun conversionFinished(attempt: Long = activeAttempt) {
        if (attempt == activeAttempt) { conversionPending = false; dispatch() }
    }
    private fun dispatch() {
        if (!requested || (activeAttempt > 0 && !retryPending)) return
        val current = host.get()?.takeIf(valid) ?: return
        val now = nowMillis()
        if ((launchPending || conversionPending) && now - startedAt < timeoutMillis) return
        if (lastStartedAt?.let { now - it < minIntervalMillis } == true) return
        retryPending = false
        val attempt = ++nextAttempt
        activeAttempt = attempt
        launchPending = true
        conversionPending = true
        startedAt = now
        lastStartedAt = now
        if (!start(current, attempt)) {
            // A synchronous init/start exception cannot leave a conversion wait behind.
            if (attempt == activeAttempt) {
                launchPending = false
                conversionPending = false
            }
        }
    }
}

enum class AttributionStartStage { NOT_REQUESTED, WAITING_FOR_ACTIVITY, START_CALLED, REQUEST_ACCEPTED, REQUEST_FAILED, INITIALIZATION_FAILED, DISABLED }
/** Only retains the SDK numeric error code; callback text may contain URLs or credentials. */
data class AttributionStartStatus(val stage: AttributionStartStage, val errorCode: Int? = null)

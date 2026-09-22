package yumo.achat.core.integration.appsflyer

import java.lang.ref.WeakReference

internal class ForegroundAttributionStart<T : Any>(
    private val valid: (T) -> Boolean,
    private val start: (T) -> Boolean,
) {
    private var host = WeakReference<T>(null)
    private var requested = false
    private var submitted = false
    private var retryPending = false
    private var starting = false
    private var redispatchPending = false

    fun attach(value: T) {
        synchronized(this) {
            host = WeakReference(value)
            if (starting) redispatchPending = true
        }
        dispatch()
    }

    fun detach(value: T) {
        synchronized(this) { if (host.get() === value) host.clear() }
    }

    fun request(retry: Boolean = false) {
        synchronized(this) {
            requested = true
            retryPending = retryPending || retry
            if (starting && retry) redispatchPending = true
        }
        dispatch()
    }

    private fun dispatch() {
        val current = synchronized(this) {
            if (!requested || starting || (submitted && !retryPending)) return
            val available = host.get()?.takeIf(valid) ?: return
            retryPending = false
            starting = true
            available
        }
        val succeeded = start(current)
        val redispatch = synchronized(this) {
            submitted = succeeded
            starting = false
            redispatchPending.also { redispatchPending = false }
        }
        if (redispatch) dispatch()
    }
}

package yumo.achat.core.integration.appsflyer

import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import yumo.achat.core.attribution.isMediaAttribution

/** Process-owned GCD slot. Call its entry points from the owner's main-thread scope. */
internal class GcdRecheckCoordinator<H : Any>(
    private val scope: CoroutineScope,
    private val minIntervalMillis: Long,
    private val timeoutMillis: Long,
    private val nowNanos: () -> Long,
    private val uid: () -> String,
    private val eligible: () -> Boolean,
    private val validHost: (H) -> Boolean,
    private val fetch: suspend (String) -> JsonObject?,
    private val onMedia: (JsonObject) -> Unit,
) {
    private var host = WeakReference<H>(null)
    private var allowed = false
    private var organicSeen = false
    private var mediaKnown = false
    private var inFlight: Job? = null
    private var lastStartedNanos: Long? = null

    init {
        require(minIntervalMillis > 0)
        require(timeoutMillis > 0)
    }

    fun attach(value: H) {
        allowed = true
        host = WeakReference(value)
        request()
    }

    fun detach(value: H) {
        if (host.get() === value) {
            host.clear()
            inFlight?.cancel()
        }
    }

    fun revokeAccess() {
        allowed = false
        host.clear()
        inFlight?.cancel()
    }

    fun sdkOrganic() {
        if (mediaKnown) return
        organicSeen = true
        request()
    }

    fun sdkMedia() {
        mediaKnown = true
        inFlight?.cancel()
    }

    fun request() {
        if (!organicSeen || mediaKnown || !allowed || !eligible()) return
        if (host.get()?.takeIf(validHost) == null || inFlight != null) return
        val requestUid = uid().takeIf(String::isNotBlank) ?: return
        val now = nowNanos()
        if (lastStartedNanos?.let { now - it < minIntervalMillis * 1_000_000L } == true) return

        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (!allowed || !eligible() || mediaKnown || host.get()?.takeIf(validHost) == null ||
                uid() != requestUid) return@launch
            lastStartedNanos = nowNanos()
            val result = try {
                withTimeout(timeoutMillis) { fetch(requestUid) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (result?.isMediaAttribution() == true && allowed && eligible() && !mediaKnown &&
                host.get()?.takeIf(validHost) != null && uid() == requestUid) {
                val boundResult = JsonObject(result + ("af_uid" to JsonPrimitive(requestUid)))
                onMedia(boundResult)
                mediaKnown = true
            }
        }
        inFlight = job
        job.invokeOnCompletion {
            scope.launch {
                if (inFlight === job) {
                    inFlight = null
                    request()
                }
            }
        }
        job.start()
    }
}

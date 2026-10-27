package yumo.achat.core.integration.appsflyer

import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import yumo.achat.core.attribution.AttributionResultSource
import yumo.achat.core.attribution.isMediaAttribution

/** Two finite process opportunities: one after SDK Organic and one after the first home frame. */
internal class GcdRecheckCoordinator<H : Any>(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long,
    private val uid: () -> String,
    private val eligible: () -> Boolean,
    private val validHost: (H) -> Boolean,
    private val fetch: suspend (String) -> JsonObject?,
    private val referrer: suspend (String) -> JsonObject?,
    private val onResult: (AttributionResultSource, JsonObject) -> Unit,
) {
    private var host = WeakReference<H>(null)
    private var allowed = false
    private var organicSeen = false
    private var mediaKnown = false
    private var preAttempted = false
    private var homeAttempted = false
    private var inFlight: Job? = null
    private val mutablePreHomeComplete = MutableStateFlow(false)
    val preHomeComplete = mutablePreHomeComplete.asStateFlow()

    init { require(timeoutMillis > 0) }

    fun attach(value: H) {
        allowed = true
        host = WeakReference(value)
        startPreHomeIfReady()
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
        startPreHomeIfReady()
    }

    fun sdkMedia() {
        mediaKnown = true
        mutablePreHomeComplete.value = true
        inFlight?.cancel()
    }

    fun resetForNewIdentity() {
        inFlight?.cancel()
        inFlight = null
        organicSeen = false
        mediaKnown = false
        preAttempted = false
        homeAttempted = false
        mutablePreHomeComplete.value = false
    }

    private fun startPreHomeIfReady() {
        if (!organicSeen || preAttempted || mediaKnown || inFlight != null) return
        if (!requestAllowed()) return
        val requestUid = uid().takeIf(String::isNotBlank) ?: return
        preAttempted = true
        launch(Opportunity.PRE_HOME, requestUid)
    }

    suspend fun verifyHome() {
        inFlight?.join()
        if (mediaKnown || homeAttempted) return
        homeAttempted = true
        if (!requestAllowed()) return
        val requestUid = uid().takeIf(String::isNotBlank) ?: return
        val job = launch(Opportunity.HOME, requestUid)
        job.join()
    }

    private fun launch(opportunity: Opportunity, requestUid: String): Job = scope.launch {
        try {
            val result = withTimeoutOrNull(timeoutMillis) { fetch(requestUid) } ?: return@launch
            if (!responseAllowed(requestUid)) return@launch
            val bound = JsonObject(result + ("af_uid" to JsonPrimitive(requestUid)))
            onResult(AttributionResultSource.GCD_HTTP, bound)
            if (bound.isMediaAttribution()) {
                mediaKnown = true
                return@launch
            }
            val supplement = withTimeoutOrNull(timeoutMillis) { referrer(requestUid) }
            if (supplement != null && responseAllowed(requestUid)) {
                onResult(AttributionResultSource.GOOGLE_INSTALL_REFERRER, supplement)
                mediaKnown = supplement.isMediaAttribution()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // This opportunity is finite; preserve prior evidence and let the caller continue.
        } finally {
            if (opportunity == Opportunity.PRE_HOME) mutablePreHomeComplete.value = true
        }
    }.also { job ->
        inFlight = job
        job.invokeOnCompletion { if (inFlight === job) inFlight = null }
    }

    private fun requestAllowed(): Boolean =
        allowed && eligible() && host.get()?.takeIf(validHost) != null

    private fun responseAllowed(requestUid: String): Boolean =
        !mediaKnown && requestAllowed() && uid() == requestUid

    private enum class Opportunity { PRE_HOME, HOME }
}

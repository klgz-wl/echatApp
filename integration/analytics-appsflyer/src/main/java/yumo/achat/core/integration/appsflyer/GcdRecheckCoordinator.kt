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
import yumo.achat.core.attribution.hasValidAttribution
import yumo.achat.core.attribution.withoutAdvertisingIdentifiers

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
    private val persistResult: suspend (AttributionResultSource, JsonObject) -> Unit = { _, _ -> },
) {
    private var host = WeakReference<H>(null)
    private var allowed = false
    private var organicSeen = false
    private var mediaKnown = false
    private var preAttempted = false
    private var homeAttempted = false
    private var inFlight: Job? = null
    private var identityGeneration = 0L
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
        identityGeneration++
        inFlight?.cancel()
        organicSeen = false
        mediaKnown = false
        mutablePreHomeComplete.value = preAttempted
    }

    private fun startPreHomeIfReady() {
        if (!organicSeen || preAttempted || mediaKnown || inFlight != null) return
        if (!requestAllowed()) return
        val requestUid = uid().takeIf(String::isNotBlank) ?: return
        preAttempted = true
        launch(Opportunity.PRE_HOME, requestUid)
    }

    suspend fun verifyHome() {
        startPreHomeIfReady()
        awaitInFlightChain()
        if (mediaKnown || homeAttempted) return
        homeAttempted = true
        if (!requestAllowed()) return
        val requestUid = uid().takeIf(String::isNotBlank) ?: return
        launch(Opportunity.HOME, requestUid)
        awaitInFlightChain()
    }

    private suspend fun awaitInFlightChain() {
        // Completion can hand ownership to a pending pre-home confirmation.
        while (true) {
            val pending = inFlight ?: return
            pending.join()
        }
    }

    private fun launch(opportunity: Opportunity, requestUid: String): Job {
        val generation = identityGeneration
        return scope.launch {
        try {
            val result = withTimeoutOrNull(timeoutMillis) { fetch(requestUid) } ?: return@launch
            if (!result.hasValidAttribution() || !responseAllowed(requestUid, generation)) return@launch
            val bound = JsonObject(result + ("af_uid" to JsonPrimitive(requestUid))).withoutAdvertisingIdentifiers()
            persistResult(AttributionResultSource.GCD_HTTP, bound)
            if (!responseAllowed(requestUid, generation)) return@launch
            onResult(AttributionResultSource.GCD_HTTP, bound)
            if (bound.isMediaAttribution()) {
                mediaKnown = true
                return@launch
            }
            if (!(bound["af_status"] as? JsonPrimitive)?.content.equals("Organic", ignoreCase = true)) return@launch
            val supplement = withTimeoutOrNull(timeoutMillis) { referrer(requestUid) }?.withoutAdvertisingIdentifiers()
            if (supplement?.isMediaAttribution() == true && responseAllowed(requestUid, generation)) {
                persistResult(AttributionResultSource.GOOGLE_INSTALL_REFERRER, supplement)
                if (!responseAllowed(requestUid, generation)) return@launch
                onResult(AttributionResultSource.GOOGLE_INSTALL_REFERRER, supplement)
                mediaKnown = supplement.isMediaAttribution()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // This opportunity is finite; preserve prior evidence and let the caller continue.
        } finally {
            if (opportunity == Opportunity.PRE_HOME && generation == identityGeneration) mutablePreHomeComplete.value = true
        }
    }.also { job ->
        inFlight = job
        job.invokeOnCompletion {
            if (inFlight === job) {
                inFlight = null
                startPreHomeIfReady()
            }
        }
    }
    }

    private fun requestAllowed(): Boolean =
        allowed && eligible() && host.get()?.takeIf(validHost) != null

    private fun responseAllowed(requestUid: String, generation: Long): Boolean =
        generation == identityGeneration && !mediaKnown && requestAllowed() && uid() == requestUid

    private enum class Opportunity { PRE_HOME, HOME }
}

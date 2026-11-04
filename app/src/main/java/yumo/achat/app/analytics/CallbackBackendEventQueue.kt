package yumo.achat.app.analytics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import yumo.achat.core.analytics.AnalyticsPlatform
import yumo.achat.core.analytics.EventApi
import yumo.achat.core.analytics.ReportEventRequest
import yumo.achat.core.analytics.analyticsParameters
import yumo.achat.core.attribution.AdvertisingIdProvider
import yumo.achat.core.attribution.readOrNull
import yumo.achat.core.config.ClientIdentity
import yumo.achat.core.integration.appsflyer.AppsFlyerCallbackOwner
import java.util.concurrent.atomic.AtomicBoolean

/** Conversion diagnostics are best effort and never enter the durable business outbox. */
internal class CallbackBackendEventQueue(
    private val api: EventApi,
    private val identity: ClientIdentity,
    capacity: Int = 256,
    private val advertisingIds: AdvertisingIdProvider = AdvertisingIdProvider { null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val requests = Channel<ReportEventRequest>(capacity.also { require(it > 0) })
    private val initialized = AtomicBoolean(false)

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        scope.launch {
            for (request in requests) {
                try {
                    api.reportEvent(request.copy(gaid = advertisingIds.readOrNull()))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed event is dropped; the next callback remains independently deliverable.
                }
            }
        }
    }

    fun enqueue(name: String, parameters: Map<String, Any>, owner: AppsFlyerCallbackOwner): Boolean {
        if (owner.deviceId.isBlank()) return false
        return requests.trySend(ReportEventRequest(
            deviceId = owner.deviceId,
            userId = owner.userId,
            eventTime = owner.eventTime,
            eventType = AnalyticsPlatform.BACKEND.eventName(name),
            packageName = identity.packageName,
            appVersion = identity.versionName,
            platform = "android",
            parameters = analyticsParameters(parameters).mapValues { it.value.toString() },
        )).isSuccess
    }
}

package yumo.achat.app.analytics

import android.content.Context
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import yumo.achat.core.attribution.AdvertisingIdProvider

internal data class AdvertisingIdSnapshot(val id: String?, val limited: Boolean)

private val ADVERTISING_ID_FORMAT = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
private const val ZERO_ADVERTISING_ID = "00000000-0000-0000-0000-000000000000"

internal fun usableAdvertisingId(snapshot: AdvertisingIdSnapshot?): String? = snapshot
    ?.takeUnless { it.limited }
    ?.id?.takeIf { ADVERTISING_ID_FORMAT.matches(it) && !it.equals(ZERO_ADVERTISING_ID, ignoreCase = true) }

/** A caller times out without cancelling a lookup shared by other callers. */
internal class GoogleAdvertisingIdProvider(
    private val lookup: () -> AdvertisingIdSnapshot?,
    private val timeoutMillis: Long = 1_200L,
) : AdvertisingIdProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var inFlight: Deferred<String?>? = null

    init { require(timeoutMillis > 0) }

    override suspend fun current(): String? {
        val pending = synchronized(lock) {
            inFlight?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
                usableAdvertisingId(lookup())
            }.also { created ->
                inFlight = created
                created.invokeOnCompletion { synchronized(lock) { if (inFlight === created) inFlight = null } }
                created.start()
            }
        }
        return try {
            val result = withTimeoutOrNull(timeoutMillis) { pending.await() }
            if (result == null && pending.isActive) synchronized(lock) {
                if (inFlight === pending) inFlight = null
            }
            result
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}

/** Shared by the handwritten host and Hilt bindings; no completed GAID is retained. */
internal object AdvertisingIdRuntime {
    @Volatile private var instance: AdvertisingIdProvider? = null

    fun get(context: Context): AdvertisingIdProvider = instance ?: synchronized(this) {
        instance ?: GoogleAdvertisingIdProvider(lookup = {
            if (!AnalyticsConsent.granted(context)) null else {
                AdvertisingIdClient.getAdvertisingIdInfo(context.applicationContext).let { info ->
                    AdvertisingIdSnapshot(info.id, info.isLimitAdTrackingEnabled)
                }
            }
        }).also { instance = it }
    }
}

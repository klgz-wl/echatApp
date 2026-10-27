package yumo.achat.core.attribution

/** A fresh system read per completed query; concurrent callers may share one in-flight read. */
fun interface AdvertisingIdProvider {
    suspend fun current(): String?
}

suspend fun AdvertisingIdProvider.readOrNull(): String? = try { current() }
catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
catch (_: Exception) { null }

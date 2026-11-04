package yumo.achat.core.integration.appsflyer

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.*

internal interface InstallReferrerConnection {
    fun start(onSetup: (Boolean) -> Unit, onDisconnected: () -> Unit)
    fun read(): InstallReferrerRaw
    fun close()
}

internal interface InstallReferrerCache {
    fun firstInstallTime(): Long?
    fun read(installTime: Long, isActive: () -> Boolean): InstallReferrerRaw?
    // Check isActive at the actual synchronous commit boundary, including legacy migration.
    fun commit(installTime: Long, raw: InstallReferrerRaw, isActive: () -> Boolean): Boolean
}

internal class InstallReferrerReadCoordinator(
    private val cache: InstallReferrerCache,
    private val connect: () -> InstallReferrerConnection,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = 10_000,
) {
    suspend fun read(): InstallReferrerRaw? = withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            val terminal = AtomicBoolean()
            val cancelled = AtomicBoolean()
            val setupReceived = AtomicBoolean()
            val connection = AtomicReference<InstallReferrerConnection?>()
            // An uninterruptible Binder call must not keep the caller's cancellation waiting.
            val workers = CoroutineScope(SupervisorJob() + io)
            fun active() = !terminal.get() && continuation.isActive
            fun close() { connection.getAndSet(null)?.let { runCatching { it.close() } } }
            fun finish(raw: InstallReferrerRaw?) {
                if (!terminal.compareAndSet(false, true)) return
                close()
                workers.cancel()
                continuation.resume(raw)
            }
            continuation.invokeOnCancellation {
                cancelled.set(true)
                terminal.set(true)
                close()
                workers.cancel()
            }
            workers.launch {
                try {
                    if (!active()) return@launch
                    val installTime = cache.firstInstallTime()
                    if (installTime == null || installTime <= 0) { finish(null); return@launch }
                    val cached = cache.read(installTime, ::active)
                    if (!active()) return@launch
                    if (cached != null) { finish(cached); return@launch }
                    val client = connect()
                    connection.set(client)
                    if (!active()) { close(); return@launch }
                    client.start(onSetup = { ok ->
                        if (active() && setupReceived.compareAndSet(false, true)) {
                            if (!ok) finish(null)
                            else workers.launch {
                                try {
                                    if (!active()) return@launch
                                    val raw = client.read()
                                    if (!active()) return@launch
                                    val stored = cache.firstInstallTime() == installTime && active() &&
                                        cache.commit(installTime, raw, ::active)
                                    if (active()) finish(raw.takeIf { stored })
                                } catch (_: Exception) { finish(null) }
                            }
                        }
                    }, onDisconnected = { finish(null) })
                    // Cancellation can race a still-starting connection. Close a late bind too.
                    if (cancelled.get()) runCatching { client.close() }
                } catch (_: Exception) { finish(null) }
            }
        }
    }
}

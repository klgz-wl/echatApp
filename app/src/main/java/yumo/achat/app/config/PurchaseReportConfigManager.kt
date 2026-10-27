package yumo.achat.app.config

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import yumo.achat.app.ui.imagevideo.achatBackendConfiguration
import yumo.achat.core.backend.AchatBackendClient
import yumo.achat.core.backend.AchatBackendHttpException
import yumo.achat.core.backend.AchatSessionStore
import yumo.achat.core.backend.ClientConfigKeys
import yumo.achat.core.backend.ClientConfigs

internal interface PurchaseReportCache {
    fun read(): Boolean?
    fun write(value: Boolean?)
}

internal class PurchaseReportConfigManager(
    private val source: suspend () -> ClientConfigs,
    private val cache: PurchaseReportCache,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var started = false
    private var networkRevision = 0L
    private var inFlight: Job? = null
    @Volatile private var allowed = false

    fun enabled(): Boolean = allowed

    fun start() {
        val shouldStart = synchronized(lock) {
            if (started) false else { started = true; true }
        }
        if (!shouldStart) return
        val initialRevision = synchronized(lock) { networkRevision }
        scope.launch {
            val cached = try { cache.read() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            synchronized(lock) {
                if (networkRevision == initialRevision) allowed = cached == true
            }
        }
        refresh()
    }

    fun refresh(): Job {
        val job = synchronized(lock) {
            inFlight?.takeUnless(Job::isCompleted) ?: scope.launch(start = CoroutineStart.LAZY) {
                val fresh = try { source().boolean(ClientConfigKeys.PURCHASE_REPORT) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return@launch }
                synchronized(lock) {
                    networkRevision++
                    allowed = fresh == true
                    try { cache.write(fresh) } catch (_: Exception) { /* 内存仍采用最新有效响应。 */ }
                }
            }.also { created ->
                inFlight = created
                created.invokeOnCompletion { synchronized(lock) { if (inFlight === created) inFlight = null } }
            }
        }
        job.start()
        return job
    }
}

private class SharedPreferencesPurchaseReportCache(context: Context) : PurchaseReportCache {
    private val preferences = context.getSharedPreferences("achat_client_configs", Context.MODE_PRIVATE)
    override fun read(): Boolean? = if (!preferences.contains(ClientConfigKeys.PURCHASE_REPORT)) null
        else runCatching { preferences.getBoolean(ClientConfigKeys.PURCHASE_REPORT, false) }.getOrNull()

    override fun write(value: Boolean?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(ClientConfigKeys.PURCHASE_REPORT)
        else editor.putBoolean(ClientConfigKeys.PURCHASE_REPORT, value)
        check(editor.commit()) { "Client config cache persistence failed" }
    }
}

/** Created without touching authentication or attribution startup. */
internal object AppConfigRuntime {
    @Volatile private var instance: PurchaseReportConfigManager? = null

    fun get(context: Context): PurchaseReportConfigManager = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(context: Context): PurchaseReportConfigManager {
        val sessions = AchatSessionStore(context)
        val client = AchatBackendClient(achatBackendConfiguration())
        val existingKeys = emptyList<String>() // 当前 Android 端没有其他远端 AppConfig 消费者。
        return PurchaseReportConfigManager(
            source = {
                val token = sessions.readSession()?.token
                val deviceId = sessions.deviceId()
                try { client.clientConfigs(existingKeys, token, deviceId) }
                catch (error: AchatBackendHttpException) {
                    if (error.statusCode != 401 || token == null) throw error
                    client.clientConfigs(existingKeys, token = null, deviceId = deviceId)
                }
            },
            cache = SharedPreferencesPurchaseReportCache(context),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}

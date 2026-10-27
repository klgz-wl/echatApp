package yumo.achat.app.config

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.backend.ClientConfigs
import yumo.achat.core.backend.parseClientConfigs

class PurchaseReportConfigManagerTest {
    @Test fun `startup does not wait for AppConfig network response`() = runBlocking {
        val pending = kotlinx.coroutines.CompletableDeferred<ClientConfigs>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = PurchaseReportConfigManager(source = { pending.await() }, cache = Cache(), scope = scope)
            manager.start()
            assertFalse(manager.enabled())
            pending.complete(response("true"))
            withTimeout(1_000) { while (!manager.enabled()) delay(5) }
        } finally { scope.cancel() }
    }
    private fun response(raw: String? = "true"): ClientConfigs = parseClientConfigs(
        """{"code":0,"data":{"package_name":"test.app","platform":"android","config_keys":[],"configs":{${raw?.let { "\"client.appsflyer.purchase.report\":$it" }.orEmpty()}}}}""",
        "test.app", "android",
    )

    private class Cache(@Volatile var value: Boolean? = null) : PurchaseReportCache {
        override fun read(): Boolean? = value
        override fun write(value: Boolean?) { this.value = value }
    }

    @Test fun `default is off and failed request may restore only a valid cached true`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cached = PurchaseReportConfigManager(source = { error("offline") }, cache = Cache(true), scope = scope)
            assertFalse(cached.enabled())
            cached.start()
            withTimeout(1_000) { while (!cached.enabled()) delay(5) }
            val empty = PurchaseReportConfigManager(source = { error("offline") }, cache = Cache(null), scope = scope)
            empty.start()
            empty.refresh().join()
            assertFalse(empty.enabled())
        } finally { scope.cancel() }
    }

    @Test fun `late old cache cannot overwrite fresh network true`() = runBlocking {
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val cache = object : PurchaseReportCache {
            @Volatile var stored: Boolean? = false
            override fun read(): Boolean? {
                val old = stored
                readEntered.countDown()
                releaseRead.await(2, TimeUnit.SECONDS)
                return old
            }
            override fun write(value: Boolean?) { stored = value }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = PurchaseReportConfigManager(source = { response("true") }, cache = cache, scope = scope)
            manager.start()
            assertTrue(readEntered.await(1, TimeUnit.SECONDS))
            withTimeout(1_000) { while (!manager.enabled()) delay(5) }
            releaseRead.countDown()
            delay(30)
            assertTrue(manager.enabled())
            assertEquals(true, cache.stored)
        } finally { releaseRead.countDown(); scope.cancel() }
    }

    @Test fun `successful missing null or malformed value removes stale true and restart stays off`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            for (raw in listOf(null, "null", "\"true\"", "1", "{}")) {
                val cache = Cache(true)
                val manager = PurchaseReportConfigManager(source = { response(raw) }, cache = cache, scope = scope)
                manager.start()
                withTimeout(1_000) { while (cache.value != null) delay(5) }
                assertFalse(manager.enabled())
                val restarted = PurchaseReportConfigManager(source = { error("offline") }, cache = cache, scope = scope)
                restarted.start()
                restarted.refresh().join()
                assertFalse(restarted.enabled())
            }
        } finally { scope.cancel() }
    }

    @Test fun `explicit false replaces cached true and is retained after failed restart fetch`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cache = Cache(true)
            val manager = PurchaseReportConfigManager(source = { response("false") }, cache = cache, scope = scope)
            manager.start()
            withTimeout(1_000) { while (cache.value != false) delay(5) }
            assertFalse(manager.enabled())
            val restored = PurchaseReportConfigManager(source = { error("offline") }, cache = cache, scope = scope)
            restored.start()
            restored.refresh().join()
            assertFalse(restored.enabled())
        } finally { scope.cancel() }
    }

    @Test fun `later response updates next orders without process restart`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val current = java.util.concurrent.atomic.AtomicReference(response("false"))
            val cache = Cache()
            val manager = PurchaseReportConfigManager(source = { current.get() }, cache = cache, scope = scope)
            manager.start()
            manager.refresh().join()
            assertFalse(manager.enabled())
            current.set(response("true"))
            manager.refresh().join()
            assertTrue(manager.enabled())
            current.set(response("false"))
            manager.refresh().join()
            assertFalse(manager.enabled())
            assertEquals(false, cache.value)
        } finally { scope.cancel() }
    }
}

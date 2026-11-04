package yumo.achat.core.integration.appsflyer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class InstallReferrerPersistentCacheTest {
    private val raw = InstallReferrerRaw("gclid=test", 1, 2)
    private val canonical = encodeInstallReferrerCache(123, raw)
    private val legacy = """{"installReferrer":"gclid=test","referrerClickTimestampSeconds":1,"installBeginTimestampSeconds":2}"""

    @Test fun `canonical memory from failed fresh commit requires a later successful commit`() = runBlocking {
        val storage = Storage()
        val cache = InstallReferrerPersistentCache(storage) { 123L }
        storage.succeeds = { false }
        assertFalse(cache.commit(123, raw) { true })
        assertEquals(canonical, storage.memory)
        assertNull(storage.disk)
        assertNull(noConnection(cache).read())
        assertEquals(2, storage.attempts.get())
        storage.succeeds = { true }
        assertEquals(raw, noConnection(cache).read())
        assertEquals(canonical, storage.disk)
        assertEquals(3, storage.attempts.get())
    }

    @Test fun `failed legacy migration cannot make its canonical memory a durable hit`() = runBlocking {
        val storage = Storage().apply {
            memory = legacy
            legacyInstallTime = 123
            succeeds = { false }
        }
        val cache = InstallReferrerPersistentCache(storage) { 123L }
        assertNull(noConnection(cache).read())
        assertEquals(canonical, storage.memory)
        assertNull(storage.legacyInstallTime)
        assertNull(storage.disk)
        assertNull(noConnection(cache).read())
        assertEquals(2, storage.attempts.get())
        storage.succeeds = { true }
        assertEquals(raw, noConnection(cache).read())
        assertEquals(canonical, storage.disk)
    }

    @Test fun `cancelled pending fresh write cannot be used until cache confirmation succeeds`() = runBlocking {
        val storage = Storage()
        val cache = InstallReferrerPersistentCache(storage) { 123L }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicInteger()
        storage.beforeDisk = { attempt -> if (attempt == 1) { entered.countDown(); release.await() } }
        storage.succeeds = { attempt -> attempt != 1 }
        val pending = async {
            InstallReferrerReadCoordinator(cache, {
                object : InstallReferrerConnection {
                    override fun start(onSetup: (Boolean) -> Unit, onDisconnected: () -> Unit) = onSetup(true)
                    override fun read() = raw
                    override fun close() { closed.incrementAndGet() }
                }
            }).read()
        }
        var confirmation: Deferred<InstallReferrerRaw?>? = null
        try {
            await { entered.count == 0L }
            withTimeout(1_000) { pending.cancelAndJoin() }
            assertEquals(1, closed.get())
            assertEquals(canonical, storage.memory)
            assertNull(storage.disk)
            confirmation = async { noConnection(cache).read() }
            await { storage.attempts.get() >= 2 || confirmation.isCompleted }
            assertFalse("cache must wait for a successful synchronous commit", confirmation.isCompleted)
        } finally { release.countDown() }
        assertEquals(raw, withTimeout(2_000) { confirmation.await() })
        assertEquals(canonical, storage.disk)
    }

    @Test fun `cancelled legacy migration does not deliver its late failed write and later confirms canonical memory`() = runBlocking {
        val storage = Storage().apply {
            memory = legacy
            legacyInstallTime = 123
            succeeds = { attempt -> attempt != 1 }
        }
        val cache = InstallReferrerPersistentCache(storage) { 123L }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        storage.beforeDisk = { entered.countDown(); release.await() }
        storage.afterDisk = { finished.countDown() }
        val pending = async { noConnection(cache).read() }
        try {
            await { entered.count == 0L || pending.isCompleted }
            assertFalse("canonical memory still requires confirmation", pending.isCompleted)
            withTimeout(1_000) { pending.cancelAndJoin() }
        } finally { release.countDown() }
        await { finished.count == 0L }
        assertTrue(pending.isCancelled)
        assertEquals(canonical, storage.memory)
        assertNull(storage.disk)
        storage.beforeDisk = {}
        assertEquals(raw, noConnection(cache).read())
        assertEquals(canonical, storage.disk)
        assertEquals(2, storage.attempts.get())
    }

    @Test fun `canonical confirmation runs on IO and checks installation and cancellation at commit`() = runBlocking {
        val caller = Thread.currentThread()
        val storage = Storage().apply { memory = canonical }
        val cache = InstallReferrerPersistentCache(storage) { 123L }
        storage.checkThread = { assertNotSame(caller, Thread.currentThread()) }
        assertEquals(raw, noConnection(cache).read())
        storage.checkThread = {}
        var active = true
        storage.beforeBoundary = { active = false }
        assertNull(cache.read(123) { active })
        assertEquals(1, storage.attempts.get())
        assertFalse(InstallReferrerPersistentCache(storage) { 456L }.commit(123, raw) { true })
        assertEquals(1, storage.attempts.get())
    }

    private fun noConnection(cache: InstallReferrerCache) = InstallReferrerReadCoordinator(
        cache, { throw AssertionError("cached evidence must not reconnect to Play") },
    )

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(2_000) { while (!condition()) delay(1) }
    }

    private class Storage : InstallReferrerCacheStorage {
        @Volatile var memory: String? = null
        @Volatile var disk: String? = null
        var legacyInstallTime: Long? = null
        val attempts = AtomicInteger()
        var succeeds: (Int) -> Boolean = { true }
        var beforeBoundary: () -> Unit = {}
        var beforeDisk: (Int) -> Unit = {}
        var afterDisk: () -> Unit = {}
        var checkThread: () -> Unit = {}
        private val diskLock = Any()
        override fun successfulResult(): String? { checkThread(); return memory }
        override fun legacyFirstInstallTime(): Long? { checkThread(); return legacyInstallTime }
        override fun commitCanonical(encoded: String, isActive: () -> Boolean): Boolean {
            checkThread()
            beforeBoundary()
            if (!isActive()) return false
            // SharedPreferences updates memory before serializing disk writes, even on failure.
            memory = encoded
            legacyInstallTime = null
            val attempt = attempts.incrementAndGet()
            return synchronized(diskLock) {
                beforeDisk(attempt)
                succeeds(attempt).also { success ->
                    if (success) disk = encoded
                    afterDisk()
                }
            }
        }
    }
}

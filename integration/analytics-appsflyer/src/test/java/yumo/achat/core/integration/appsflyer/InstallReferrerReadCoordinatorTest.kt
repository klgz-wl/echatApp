package yumo.achat.core.integration.appsflyer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class InstallReferrerReadCoordinatorTest {
    private val raw = InstallReferrerRaw("gclid=test", 1, 2)

    @Test fun `main style callback returns while binder blocks and cancellation closes before binder returns`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val connection = Connection { entered.countDown(); release.await(); finished.countDown(); raw }
        val cache = Cache()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val pending = async { InstallReferrerReadCoordinator(cache, { connection }, io).read() }
            await(connection.started)
            // Invoke on the caller thread, as Install Referrer 2.2 does on Android main.
            connection.ready(true)
            await(entered)
            try {
                withTimeout(1_000) { pending.cancelAndJoin() }
                assertEquals(1, connection.closed.get())
                assertEquals(0, cache.commits.get())
            } finally { release.countDown() }
            await(finished)
        }
        assertEquals(0, cache.commits.get())
    }

    @Test fun `timeout closes immediately and late binder result cannot commit`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val connection = Connection { entered.countDown(); release.await(); finished.countDown(); raw }
        val cache = Cache()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val pending = async { InstallReferrerReadCoordinator(cache, { connection }, io, 300).read() }
            await(connection.started)
            connection.ready(true)
            await(entered)
            try {
                assertNull(withTimeout(1_000) { pending.await() })
                assertEquals(1, connection.closed.get())
            } finally { release.countDown() }
            await(finished)
        }
        assertEquals(0, cache.commits.get())
    }

    @Test fun `success uses IO for cache and binder and commits before returning`() = runBlocking {
        val caller = Thread.currentThread()
        val cache = Cache()
        val connection = Connection { assertNotSame(caller, Thread.currentThread()); raw }
        cache.checkThread = { assertNotSame(caller, Thread.currentThread()) }
        val pending = async { InstallReferrerReadCoordinator(cache, { connection }).read() }
        await(connection.started)
        connection.ready(true)
        connection.ready(true)
        assertEquals(raw, pending.await())
        assertEquals(1, cache.commits.get())
        assertEquals(1, connection.closed.get())
    }

    @Test fun `failure disconnect read exception and failed commit all close and return null`() = runBlocking {
        for (mode in listOf("failure", "disconnect", "read", "commit", "start", "installation")) {
            val cache = Cache().apply { stored = mode != "commit" }
            val connection = Connection { if (mode == "read") error("binder"); raw }
            connection.startFailure = mode == "start"
            val pending = async { InstallReferrerReadCoordinator(cache, { connection }).read() }
            await(connection.started)
            when (mode) {
                "disconnect" -> connection.disconnected()
                "installation" -> { cache.installTime = 456; connection.ready(true) }
                "start" -> Unit
                else -> connection.ready(mode != "failure")
            }
            assertNull(mode, pending.await())
            assertEquals(mode, 1, connection.closed.get())
        }
    }

    @Test fun `cache hit avoids connection and cache failures are not successful reads`() = runBlocking {
        val cache = Cache().apply { cached = raw }
        assertEquals(raw, InstallReferrerReadCoordinator(cache, { error("must not connect") }).read())
        cache.installTime = null
        assertNull(InstallReferrerReadCoordinator(cache, { error("must not connect") }).read())
    }

    @Test fun `cancellation before setup closes and ignores a late callback`() = runBlocking {
        val connection = Connection { error("late read") }
        val pending = async { InstallReferrerReadCoordinator(Cache(), { connection }).read() }
        await(connection.started)
        pending.cancelAndJoin()
        connection.ready(true)
        assertEquals(1, connection.closed.get())
    }

    @Test fun `synchronous unsuccessful setup closes only once`() = runBlocking {
        val connection = Connection { error("must not read") }.apply { immediateSetup = false }
        assertNull(InstallReferrerReadCoordinator(Cache(), { connection }).read())
        assertEquals(1, connection.closed.get())
    }

    @Test fun `cancellation during cache read returns promptly and prevents migration commit`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val cache = Cache().apply {
            beforeRead = { entered.countDown(); release.await(); finished.countDown() }
            cached = raw
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val pending = async { InstallReferrerReadCoordinator(cache, { error("must not connect") }, io).read() }
            await(entered)
            try { withTimeout(1_000) { pending.cancelAndJoin() } }
            finally { release.countDown() }
            await(finished)
        }
        assertEquals(0, cache.migrations.get())
    }

    @Test fun `disconnect during binder read discards late result`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val cache = Cache()
        val connection = Connection { entered.countDown(); release.await(); finished.countDown(); raw }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val pending = async { InstallReferrerReadCoordinator(cache, { connection }, io).read() }
            await(connection.started)
            connection.ready(true)
            await(entered)
            try {
                connection.disconnected()
                assertNull(withTimeout(1_000) { pending.await() })
                assertEquals(1, connection.closed.get())
            } finally { release.countDown() }
            await(finished)
        }
        assertEquals(0, cache.commits.get())
    }

    @Test fun `cancellation at cache boundary prevents a stale commit`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val cache = Cache().apply {
            beforeCommit = { entered.countDown(); release.await(); finished.countDown() }
        }
        val connection = Connection { raw }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val pending = async { InstallReferrerReadCoordinator(cache, { connection }, io).read() }
            await(connection.started)
            connection.ready(true)
            await(entered)
            try {
                withTimeout(1_000) { pending.cancelAndJoin() }
                assertEquals(1, connection.closed.get())
            } finally { release.countDown() }
            await(finished)
        }
        assertEquals(0, cache.commits.get())
    }

    private suspend fun await(latch: CountDownLatch) {
        withTimeout(2_000) { while (latch.count != 0L) delay(1) }
    }

    private class Connection(val reader: () -> InstallReferrerRaw) : InstallReferrerConnection {
        val started = CountDownLatch(1)
        val closed = AtomicInteger()
        lateinit var ready: (Boolean) -> Unit
        lateinit var disconnected: () -> Unit
        var startFailure = false
        var immediateSetup: Boolean? = null
        override fun start(onSetup: (Boolean) -> Unit, onDisconnected: () -> Unit) {
            ready = onSetup
            disconnected = onDisconnected
            started.countDown()
            if (startFailure) error("start")
            immediateSetup?.let(onSetup)
        }
        override fun read() = reader()
        override fun close() { closed.incrementAndGet() }
    }

    private class Cache : InstallReferrerCache {
        @Volatile var installTime: Long? = 123
        var cached: InstallReferrerRaw? = null
        var stored = true
        var checkThread: () -> Unit = {}
        var beforeRead: () -> Unit = {}
        var beforeCommit: () -> Unit = {}
        val commits = AtomicInteger()
        val migrations = AtomicInteger()
        override fun firstInstallTime(): Long? { checkThread(); return installTime }
        override fun read(installTime: Long, isActive: () -> Boolean): InstallReferrerRaw? {
            checkThread()
            beforeRead()
            if (cached != null && isActive()) migrations.incrementAndGet()
            return cached.takeIf { isActive() }
        }
        override fun commit(installTime: Long, raw: InstallReferrerRaw, isActive: () -> Boolean): Boolean {
            checkThread()
            beforeCommit()
            if (!isActive()) return false
            commits.incrementAndGet()
            return stored
        }
    }
}

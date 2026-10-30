package yumo.achat.core.backend

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StableInstallIdTest {
    @Test
    fun `missing Android ID does not persist fallback identity`() {
        val stored = AtomicReference<String?>()
        assertThrows(IllegalStateException::class.java) {
            StableInstallId.resolve(
                read = stored::get,
                persist = { stored.set(it); true },
                generate = { error("Android ID unavailable") },
            )
        }
        assertEquals(null, stored.get())
    }

    @Test
    fun `concurrent stores generate and persist one install id`() {
        val start = CountDownLatch(1)
        val generated = AtomicInteger()
        val stored = AtomicReference<String?>()
        val results = java.util.Collections.synchronizedList(mutableListOf<String>())
        val threads = List(32) {
            Thread {
                start.await()
                results += StableInstallId.resolve(
                    read = stored::get,
                    persist = { stored.set(it); true },
                    generate = { "device-${generated.incrementAndGet()}" },
                )
            }.apply { start() }
        }

        start.countDown()
        threads.forEach(Thread::join)

        assertEquals(1, generated.get())
        assertEquals(setOf("device-1"), results.toSet())
    }
}

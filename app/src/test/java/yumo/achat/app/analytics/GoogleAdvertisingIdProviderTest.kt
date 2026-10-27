package yumo.achat.app.analytics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GoogleAdvertisingIdProviderTest {
    private val first = "12345678-1234-4234-8234-123456789abc"
    private val second = "87654321-4321-4321-8321-cba987654321"

    @Test fun `limited deleted blank and malformed IDs are unavailable`() {
        assertEquals(first, usableAdvertisingId(AdvertisingIdSnapshot(first, false)))
        for (snapshot in listOf(
            AdvertisingIdSnapshot(first, true), AdvertisingIdSnapshot(null, false),
            AdvertisingIdSnapshot("", false),
            AdvertisingIdSnapshot("00000000-0000-0000-0000-000000000000", false),
            AdvertisingIdSnapshot("not-a-uuid", false),
            AdvertisingIdSnapshot("123-123-123-123-123", false),
        )) assertNull(usableAdvertisingId(snapshot))
    }

    @Test fun `concurrent callers share one read and later call reads current system value`() = runBlocking {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = GoogleAdvertisingIdProvider(lookup = {
            val number = calls.incrementAndGet()
            if (number == 1) { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
            AdvertisingIdSnapshot(if (number == 1) first else second, false)
        }, timeoutMillis = 2_000)
        val one = async(start = CoroutineStart.UNDISPATCHED) { provider.current() }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val two = async(start = CoroutineStart.UNDISPATCHED) { provider.current() }
        release.countDown()
        assertEquals(first, one.await())
        assertEquals(first, two.await())
        assertEquals(1, calls.get())
        assertEquals(second, provider.current())
        assertEquals(2, calls.get())
    }

    @Test fun `timed out stuck read is retired and late completion cannot replace newer read`() = runBlocking {
        val calls = AtomicInteger()
        val releaseFirst = CountDownLatch(1)
        val enteredSecond = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val provider = GoogleAdvertisingIdProvider(lookup = {
            when (calls.incrementAndGet()) {
                1 -> { releaseFirst.await(2, TimeUnit.SECONDS); AdvertisingIdSnapshot(first, false) }
                else -> { enteredSecond.countDown(); releaseSecond.await(2, TimeUnit.SECONDS); AdvertisingIdSnapshot(second, false) }
            }
        }, timeoutMillis = 200)
        assertNull(provider.current())
        val retry = async(start = CoroutineStart.UNDISPATCHED) { provider.current() }
        assertTrue(enteredSecond.await(1, TimeUnit.SECONDS))
        releaseFirst.countDown()
        val overlapping = async(start = CoroutineStart.UNDISPATCHED) { provider.current() }
        releaseSecond.countDown()
        assertEquals(second, retry.await())
        assertEquals(second, overlapping.await())
        assertEquals(2, calls.get())
    }

    @Test fun `timeout and lookup error do not block later request while caller cancellation propagates`() = runBlocking {
        val release = CountDownLatch(1)
        val provider = GoogleAdvertisingIdProvider(lookup = {
            release.await(2, TimeUnit.SECONDS)
            AdvertisingIdSnapshot(first, false)
        }, timeoutMillis = 40)
        assertNull(provider.current())
        val waiter = async(Dispatchers.Default) { provider.current() }
        waiter.cancelAndJoin()
        assertTrue(waiter.isCancelled)
        release.countDown()
        val failing = GoogleAdvertisingIdProvider(lookup = { error("unavailable") }, timeoutMillis = 100)
        assertNull(failing.current())
    }
}

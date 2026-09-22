package yumo.achat.core.integration.appsflyer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ForegroundAttributionStartTest {
    private class Host(var valid: Boolean = true)
    private class Fixture {
        val started = mutableListOf<Host>()
        var succeeds = true
        val start = ForegroundAttributionStart<Host>({ it.valid }) { started += it; succeeds }
    }

    @Test fun `request waits for a valid foreground host`() {
        val fixture = Fixture()
        fixture.start.request()
        assertTrue(fixture.started.isEmpty())
        val host = Host()
        fixture.start.attach(host)
        assertSame(host, fixture.started.single())
    }

    @Test fun `duplicate requests do not start twice`() {
        val fixture = Fixture()
        val host = Host()
        fixture.start.attach(host)
        fixture.start.request()
        fixture.start.request()
        assertEquals(1, fixture.started.size)
    }

    @Test fun `retry is delivered to the next valid foreground host`() {
        val fixture = Fixture()
        val first = Host()
        val second = Host()
        fixture.start.attach(first)
        fixture.start.request()
        fixture.start.detach(first)
        fixture.start.request(retry = true)
        fixture.start.attach(second)
        assertEquals(listOf(first, second), fixture.started)
    }

    @Test fun `concurrent request and attach start at most once`() {
        val starts = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = ForegroundAttributionStart<Host>({ it.valid }) {
            starts.incrementAndGet()
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
        }
        val host = Host()
        val requestThread = Thread { coordinator.request() }
        val attachThread = Thread { coordinator.attach(host) }

        requestThread.start()
        attachThread.start()
        entered.await(2, TimeUnit.SECONDS)
        release.countDown()
        requestThread.join(2_000)
        attachThread.join(2_000)

        assertEquals(1, starts.get())
        assertTrue(!requestThread.isAlive && !attachThread.isAlive)
    }

    @Test fun `retry arriving during start is dispatched after the active start`() {
        val starts = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = ForegroundAttributionStart<Host>({ it.valid }) {
            if (starts.incrementAndGet() == 1) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            true
        }
        val host = Host()
        coordinator.attach(host)
        val requestThread = Thread { coordinator.request() }
        requestThread.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        coordinator.request(retry = true)
        release.countDown()
        requestThread.join(2_000)

        assertEquals(2, starts.get())
        assertTrue(!requestThread.isAlive)
    }
}

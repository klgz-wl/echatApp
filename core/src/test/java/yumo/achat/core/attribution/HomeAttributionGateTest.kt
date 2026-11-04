package yumo.achat.core.attribution

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HomeAttributionGateTest {
    @Test fun `failed auxiliary acquisition ends finitely without consuming a second opportunity`() = runBlocking {
        var calls = 0
        val gate = HomeAttributionGate(this, { false }) { calls++; error("network unavailable") }
        gate.enterAndVerify()
        gate.awaitVerification()
        gate.enterAndVerify()
        assertEquals(1, calls)
    }

    @Test fun `profile consumer waits for first frame and all auxiliary work`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val gate = HomeAttributionGate(this, { false }) { calls++; entered.complete(Unit); release.await() }
        val profile = async { gate.awaitVerification() }
        yield()
        assertFalse(profile.isCompleted)
        val home = async { gate.enterAndVerify() }
        entered.await()
        assertFalse(profile.isCompleted)
        release.complete(Unit)
        home.await(); profile.await()
        gate.enterAndVerify()
        assertEquals(1, calls)
    }

    @Test fun `restored final success does not wait for first frame`() = runBlocking {
        val gate = HomeAttributionGate(this, { true }) { error("must not acquire") }
        withTimeout(100) { gate.awaitVerification(); gate.enterAndVerify() }
    }

    @Test fun `cancelled page does not cancel process verification`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val gate = HomeAttributionGate(this, { false }) { calls++; entered.complete(Unit); release.await() }
        val oldPage = launch { gate.enterAndVerify() }
        entered.await(); oldPage.cancelAndJoin()
        val newPage = async { gate.enterAndVerify() }
        release.complete(Unit); newPage.await()
        assertEquals(1, calls)
    }
}

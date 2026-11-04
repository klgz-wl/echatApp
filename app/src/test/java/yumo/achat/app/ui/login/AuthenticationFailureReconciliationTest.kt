package yumo.achat.app.ui.login

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.app.ui.imagevideo.StartupOperationTimeout
import yumo.achat.core.backend.AuthSession

class AuthenticationFailureReconciliationTest {
    private val saved = AuthSession("user", "token", "refresh", "session", true)

    @Test fun `owned timeout or error recovers durable session using only snapshot read`() = runBlocking {
        for (failure in listOf(StartupOperationTimeout(), IllegalStateException("post-auth failure"))) {
            val calls = mutableListOf<String>()
            val session = reconcileAuthenticationFailure(failure,
                checkOperation = { calls += "owner" },
                savedSession = { calls += "snapshot"; saved },
            )
            assertEquals(saved, session)
            assertEquals(listOf("owner", "snapshot", "owner"), calls)
        }
    }

    @Test fun `preauth failure without durable session remains login error`() = runBlocking {
        assertNull(reconcileAuthenticationFailure(StartupOperationTimeout(), {}, { null }))
    }

    @Test fun `snapshot failure retains original failure diagnostics`() = runBlocking {
        val failure = runCatching {
            reconcileAuthenticationFailure(StartupOperationTimeout(), {}, { error("snapshot unavailable") })
        }.exceptionOrNull()
        assertTrue(failure is StartupOperationTimeout)
        assertEquals("snapshot unavailable", failure?.suppressed?.single()?.message)
    }

    @Test fun `external cancellation never reads saved session`() = runBlocking {
        val failure = runCatching {
            reconcileAuthenticationFailure(CancellationException("disposed"),
                checkOperation = { error("must not reconcile") },
                savedSession = { error("must not read") },
            )
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("disposed", failure?.message)
    }

    @Test fun `generation change during snapshot read rejects reconciled session`() = runBlocking {
        var current = true
        val failure = runCatching {
            reconcileAuthenticationFailure(StartupOperationTimeout(),
                checkOperation = { if (!current) throw CancellationException("owner changed") },
                savedSession = { current = false; saved },
            )
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun `changed generation before reconciliation never reads session`() = runBlocking {
        val failure = runCatching {
            reconcileAuthenticationFailure(StartupOperationTimeout(),
                checkOperation = { throw CancellationException("owner changed") },
                savedSession = { error("must not read") },
            )
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun `caller cancellation while reading snapshot cannot publish recovered session`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var published = false
        val job = launch {
            reconcileAuthenticationFailure(StartupOperationTimeout(), {}, {
                entered.complete(Unit)
                awaitCancellation()
            })
            published = true
        }
        withTimeout(1_000) { entered.await(); job.cancelAndJoin() }
        assertFalse(published)
    }
}

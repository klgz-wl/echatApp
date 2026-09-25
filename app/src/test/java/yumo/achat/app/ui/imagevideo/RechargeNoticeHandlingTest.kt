package yumo.achat.app.ui.imagevideo

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import yumo.achat.core.payment.RechargeNotificationRoute

class RechargeNoticeHandlingTest {
    @Test
    fun `notice from another session is ignored`() {
        assertEquals(
            RechargeNoticeAction.Ignore,
            rechargeNoticeAction(
                isCurrentSession = false,
                route = RechargeNotificationRoute.LegacyFirst,
            ),
        )
    }

    @Test
    fun `first matching legacy notice refreshes balance and emits success`() {
        assertEquals(
            RechargeNoticeAction.LegacySuccess,
            rechargeNoticeAction(
                isCurrentSession = true,
                route = RechargeNotificationRoute.LegacyFirst,
            ),
        )
    }

    @Test
    fun `service or duplicate notice only reconciles visible balance`() {
        assertEquals(
            RechargeNoticeAction.RefreshBalance,
            rechargeNoticeAction(
                isCurrentSession = true,
                route = RechargeNotificationRoute.Handled,
            ),
        )
    }

    @Test
    fun `unknown or missing order only refreshes balance`() {
        assertEquals(
            RechargeNoticeAction.RefreshBalance,
            rechargeNoticeAction(
                isCurrentSession = true,
                route = RechargeNotificationRoute.Unknown,
            ),
        )
    }

    @Test
    fun `foreground retry only replays an unacknowledged legacy success`() {
        assertTrue(shouldRetryLegacySuccess(hasCoreRecord = false, checkoutSucceeded = true))
        assertFalse(shouldRetryLegacySuccess(hasCoreRecord = true, checkoutSucceeded = true))
        assertFalse(shouldRetryLegacySuccess(hasCoreRecord = false, checkoutSucceeded = false))
    }

    @Test
    fun `recharge balance load retries transient failures with bounded delays`() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()

        val balance = loadRechargeBalanceWithRetry(
            maxAttempts = 4,
            waitBeforeRetry = { waits.add(it) },
            loadBalance = {
                attempts += 1
                if (attempts < 3) throw IOException("temporary")
                640
            },
        )

        assertEquals(640, balance)
        assertEquals(3, attempts)
        assertEquals(listOf(500L, 1_000L), waits)
    }

    @Test
    fun `recharge balance load stops after maximum attempts`() = runBlocking {
        var attempts = 0

        val balance = loadRechargeBalanceWithRetry(
            maxAttempts = 3,
            waitBeforeRetry = {},
            loadBalance = {
                attempts += 1
                throw IOException("offline")
            },
        )

        assertNull(balance)
        assertEquals(3, attempts)
    }
}

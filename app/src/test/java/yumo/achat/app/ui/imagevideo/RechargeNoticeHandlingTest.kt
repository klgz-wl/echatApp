package yumo.achat.app.ui.imagevideo

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import yumo.achat.core.backend.WalletTransaction
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
    fun `wallet fallback only uses purchase transaction business order ids`() {
        val transactions = listOf(
            walletTransaction("recharge", category = "purchase", relatedId = "order-1", relatedType = "payment_order"),
            walletTransaction("refund", category = "refund", relatedId = "order-2", relatedType = "payment_order"),
            walletTransaction("reward", category = "reward", relatedId = "order-3", relatedType = "task"),
            walletTransaction(
                "negative",
                category = "purchase",
                relatedId = "order-4",
                relatedType = "payment_order",
                type = "expense",
                amount = -100,
            ),
        )

        assertEquals(setOf("order-1"), legacyPaymentOrderIds(transactions))
    }

    @Test
    fun `pending legacy wallet reconciliation retries until its order transaction appears`() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()

        val reconciled = reconcileLegacyWalletWithRetry(
            maxAttempts = 4,
            waitBeforeRetry = { waits += it },
            loadTransactions = {
                attempts += 1
                if (attempts < 3) {
                    listOf(walletTransaction("reward", "reward", "task-1", "task"))
                } else {
                    listOf(walletTransaction("recharge", "purchase", "order-1", "payment_order"))
                }
            },
            reconcileOrderIds = { "order-1" in it },
        )

        assertTrue(reconciled)
        assertEquals(3, attempts)
        assertEquals(listOf(3_000L, 3_000L), waits)
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

    private fun walletTransaction(
        id: String,
        category: String,
        relatedId: String,
        relatedType: String,
        type: String = "income",
        amount: Int = 100,
    ) = WalletTransaction(
        id = id,
        type = type,
        amount = amount,
        description = id,
        category = category,
        createdAt = "2026-09-25T00:00:00Z",
        balanceAfter = 100,
        relatedId = relatedId,
        relatedType = relatedType,
    )
}

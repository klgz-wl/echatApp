package yumo.achat.app.ui.imagevideo

import kotlinx.coroutines.CancellationException
import yumo.achat.core.backend.WalletTransaction
import yumo.achat.core.payment.RechargeNotificationRoute

internal enum class RechargeNoticeAction {
    Ignore,
    RefreshBalance,
    LegacySuccess,
}

internal fun rechargeNoticeAction(
    isCurrentSession: Boolean,
    route: RechargeNotificationRoute,
): RechargeNoticeAction = when {
    !isCurrentSession -> RechargeNoticeAction.Ignore
    route == RechargeNotificationRoute.LegacyFirst -> RechargeNoticeAction.LegacySuccess
    else -> RechargeNoticeAction.RefreshBalance
}

internal fun shouldRetryLegacySuccess(
    hasCoreRecord: Boolean,
    checkoutSucceeded: Boolean,
): Boolean = !hasCoreRecord && checkoutSucceeded

internal fun legacyPaymentOrderIds(transactions: List<WalletTransaction>): Set<String> =
    transactions.asSequence()
        .filter {
            it.type == "income" && it.amount > 0 &&
                it.category == "purchase" && it.relatedType == "payment_order"
        }
        .mapNotNull { it.relatedId?.takeIf(String::isNotBlank) }
        .toSet()

internal suspend fun reconcileLegacyWalletWithRetry(
    maxAttempts: Int,
    waitBeforeRetry: suspend (Long) -> Unit,
    loadTransactions: suspend () -> List<WalletTransaction>,
    reconcileOrderIds: suspend (Set<String>) -> Boolean,
): Boolean {
    require(maxAttempts > 0)
    repeat(maxAttempts) { attempt ->
        val reconciled = try {
            reconcileOrderIds(legacyPaymentOrderIds(loadTransactions()))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (reconciled) return true
        if (attempt < maxAttempts - 1) waitBeforeRetry(3_000L)
    }
    return false
}

internal suspend fun loadRechargeBalanceWithRetry(
    maxAttempts: Int,
    waitBeforeRetry: suspend (Long) -> Unit,
    loadBalance: suspend () -> Int,
): Int? {
    require(maxAttempts > 0)
    repeat(maxAttempts) { attempt ->
        try {
            return loadBalance()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (attempt == maxAttempts - 1) return null
            waitBeforeRetry((500L shl attempt).coerceAtMost(4_000L))
        }
    }
    return null
}

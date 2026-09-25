package yumo.achat.app.ui.imagevideo

import kotlinx.coroutines.CancellationException
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

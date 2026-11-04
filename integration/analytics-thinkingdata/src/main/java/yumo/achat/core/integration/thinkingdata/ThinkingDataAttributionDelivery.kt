package yumo.achat.core.integration.thinkingdata

import java.util.Date

/** The caller serializes identify + track with ordinary events using the same sink lock. */
internal class ThinkingDataAttributionDelivery(
    private val identify: (String?) -> Unit,
    private val track: (ThinkingDataAttributionEvent, Date) -> Unit,
) {
    fun deliver(result: ThinkingDataAttributionResult, frozenUserId: String?, eventTime: Long) {
        val event = result.toEvent()
        identify(frozenUserId)
        track(event, Date(eventTime))
    }
}

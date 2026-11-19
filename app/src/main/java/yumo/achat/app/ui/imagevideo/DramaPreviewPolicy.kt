package yumo.achat.app.ui.imagevideo

import yumo.achat.core.backend.ShortDramaEpisode

internal const val DramaPreviewLimitMs = 10_000L

/** Receives media positions only, so paused/buffering/background wall time cannot count. */
internal class DramaPreviewProgress(private val restricted: Boolean) {
    var finished = false
        private set
    fun update(positionMs: Long): Boolean = restricted && positionMs >= DramaPreviewLimitMs && ended()
    fun ended(): Boolean {
        if (finished) return false
        finished = true
        return true
    }
}

internal enum class DramaPreviewGate { Watch, Subscribe, Unlock, Unavailable }
internal fun previewShouldStop(positionMs: Long, episode: ShortDramaEpisode?): Boolean =
    positionMs >= DramaPreviewLimitMs && episode != null && !episode.canPlay && (episode.vip || (episode.amount ?: 0)>0)
internal fun previewGate(finished: Boolean, episode: ShortDramaEpisode?, isVip: Boolean?): DramaPreviewGate {
    if(!finished || episode==null || episode.canPlay)return DramaPreviewGate.Watch
    if(episode.vip && isVip!=true)return DramaPreviewGate.Subscribe
    val amount=episode.amount
    if(amount==null || episode.accessReason=="PRICE_NOT_CONFIGURED")return DramaPreviewGate.Unavailable
    if(amount>0)return DramaPreviewGate.Unlock
    return if(episode.accessReason=="VIP_REQUIRED")DramaPreviewGate.Subscribe else DramaPreviewGate.Watch
}

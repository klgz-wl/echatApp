package yumo.achat.app.ui.imagevideo

import yumo.achat.core.backend.ShortDramaEpisode

internal enum class DramaPreviewGate { Watch, Subscribe, Unlock, Unavailable }
internal fun previewShouldStop(positionMs: Long, episode: ShortDramaEpisode?): Boolean =
    positionMs >= 10_000 && episode != null && !episode.canPlay && (episode.vip || (episode.amount ?: 0)>0)
internal fun previewGate(finished: Boolean, episode: ShortDramaEpisode?, isVip: Boolean?): DramaPreviewGate {
    if(!finished || episode==null || episode.canPlay)return DramaPreviewGate.Watch
    return when(episode.accessReason) {
        "VIP_REQUIRED" -> DramaPreviewGate.Subscribe
        "EPISODE_LOCKED" -> if(isVip==true) DramaPreviewGate.Unlock else if(episode.vip) DramaPreviewGate.Subscribe else DramaPreviewGate.Unavailable
        else -> DramaPreviewGate.Unavailable
    }
}

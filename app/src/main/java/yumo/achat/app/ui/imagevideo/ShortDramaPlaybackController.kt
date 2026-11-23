package yumo.achat.app.ui.imagevideo

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import yumo.achat.core.backend.*

data class DramaUnlockIntent(val dramaId: String, val episodeId: String, val amount: Int, val idempotencyKey: String)
interface DramaUnlockStore {
    fun pending(dramaId: String, episodeId: String, amount: Int): DramaUnlockIntent
    fun clear(intent: DramaUnlockIntent)
}
enum class DramaPlaybackAction { None, VipRequired, UnlockRequired, TopUpRequired, Unavailable, Retry }

/** Owned by an account-scoped ViewModel. Grants and resolved URLs are memory-only. */
class ShortDramaPlaybackController(
    private val gateway: ShortDramaPlaybackGateway,
    private val scope: CoroutineScope,
    private val unlockStore: DramaUnlockStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    var detail by mutableStateOf<ShortDramaDetail?>(null); private set
    var episode by mutableStateOf<ShortDramaEpisode?>(null); private set
    var grant by mutableStateOf<DramaPlaybackGrant?>(null); private set
    var media by mutableStateOf<ResolvedDramaPreview?>(null); private set
    var subtitleMedia by mutableStateOf<ResolvedDramaPreview?>(null); private set
    var selectedSubtitleId by mutableStateOf<String?>(null); private set
    var completed by mutableStateOf(false); private set
    var seriesCompleted by mutableStateOf(false); private set
    var replaySerial by mutableStateOf(0L); private set
    var loading by mutableStateOf(false); private set
    var errorCode by mutableStateOf<String?>(null); private set
    var action by mutableStateOf(DramaPlaybackAction.None); private set
    var positionMs by mutableStateOf(0L); private set
    var durationMs by mutableStateOf(0L); private set
    var playing by mutableStateOf(true); private set
    var muted by mutableStateOf(false); private set
    var speed by mutableStateOf(1f); private set
    var unlockSuccessSerial by mutableStateOf(0L); private set
    private var visible = true
    private var closed = false
    private var generation = 0L
    private var work: Job? = null
    private var renewal: Job? = null
    private var subtitleJob: Job? = null
    private var subtitleVersion = 0L
    private var dramaId: String? = null
    private var pendingUnlock: DramaUnlockIntent? = null
    private var resumeAfterMediaError = false
    private var deferredPlay = false

    fun start(dramaId: String, preferredEpisodeId: String? = null, restartCompleted: Boolean = false) {
        if (closed) return
        if (this.dramaId == dramaId && (loading || detail != null)) {
            if (preferredEpisodeId != null && preferredEpisodeId != episode?.episodeId) selectEpisode(preferredEpisodeId)
            else if (restartCompleted && completed && !loading) {
                completed = false; seriesCompleted = false; playing = true
                positionMs = 0; durationMs = 0; replaySerial++
                media = null; subtitleMedia = null
                launch { if (grant != null) refresh() else load(episode?.episodeId) }
            }
            return
        }
        this.dramaId = dramaId
        detail = null; episode = null
        switch { load(preferredEpisodeId) }
    }
    /** One explicit Home CTA authorizes only this episode and this quoted amount. */
    fun startConfirmedUnlock(dramaId: String, episodeId: String, confirmedAmount: Int) {
        if (closed || loading) return
        this.dramaId = dramaId
        switch {
            fresh(episodeId)
            val e = episode
            when {
                e == null -> action = DramaPlaybackAction.Unavailable
                e.canPlay -> playIfAllowed()
                accessAction(e.accessReason) == DramaPlaybackAction.VipRequired -> action = DramaPlaybackAction.VipRequired
                e.amount != confirmedAmount -> {
                    action = accessAction(e.accessReason)
                    errorCode = "PRICE_CHANGED"
                }
                confirmedAmount <= 0 -> action = DramaPlaybackAction.Unavailable
                accessAction(e.accessReason) != DramaPlaybackAction.UnlockRequired -> action = accessAction(e.accessReason)
                else -> {
                    val intent = unlockStore.pending(dramaId, episodeId, confirmedAmount)
                    pendingUnlock = intent
                    unlock(intent)
                }
            }
        }
    }
    private data class RechargeEpisode(val dramaId:String,val episodeId:String,val amount:Int)
    private var rechargeEpisode:RechargeEpisode?=null
    private var rechargeConfirmed=false

    /** Returns true only when this explicit episode purchase needs the Top Up route. */
    fun watchPaidEpisode(balance:Int):Boolean {
        if(closed || loading || action !in setOf(DramaPlaybackAction.UnlockRequired,DramaPlaybackAction.TopUpRequired))return false
        val e=episode ?: return false
        val amount=e.amount?.takeIf {it>0} ?: return false
        if(accessAction(e.accessReason)==DramaPlaybackAction.VipRequired)return false
        if(balance<amount || action==DramaPlaybackAction.TopUpRequired) {
            rechargeEpisode=RechargeEpisode(requireNotNull(dramaId),e.episodeId,amount)
            action=DramaPlaybackAction.TopUpRequired
            return true
        }
        startConfirmedUnlock(requireNotNull(dramaId),e.episodeId,amount)
        return false
    }
    fun rechargeSucceeded() {
        if(closed || rechargeEpisode==null)return
        rechargeConfirmed=true
        continueAfterRecharge()
    }
    private fun continueAfterRecharge() {
        if(!rechargeConfirmed || loading || closed)return
        val target=rechargeEpisode ?: return
        rechargeConfirmed=false;rechargeEpisode=null
        if(dramaId==target.dramaId && episode?.episodeId==target.episodeId)
            startConfirmedUnlock(target.dramaId,target.episodeId,target.amount)
    }
    fun selectEpisode(id: String) {
        if (closed || episode?.episodeId == id) return
        episode = detail?.episodes?.firstOrNull { it.episodeId == id }
        playing = true
        switch { load(id) }
    }
    private fun switch(block: suspend () -> Unit) {
        rechargeEpisode=null;rechargeConfirmed=false
        generation++; work?.cancel(); renewal?.cancel(); subtitleJob?.cancel(); subtitleVersion++
        grant = null; media = null; subtitleMedia = null; selectedSubtitleId = null
        resumeAfterMediaError = false; deferredPlay = false
        completed = false; seriesCompleted = false
        pendingUnlock = null; positionMs = 0; durationMs = 0; loading = false
        launch(block)
    }
    private fun launch(block: suspend () -> Unit) {
        if (closed || loading) return
        val version = generation
        loading = true; errorCode = null; action = DramaPlaybackAction.None
        work = scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (version == generation && !closed) fail(e) }
            finally { if (version == generation && !closed) { loading = false; scheduleRenewal(); continueAfterRecharge() } }
        }
    }
    private suspend fun fresh(id: String?) {
        val version = generation
        val result = gateway.shortDramaDetail(requireNotNull(dramaId))
        ensureCurrent(version)
        detail = result
        episode = result.episodes.firstOrNull { it.episodeId == id } ?: if (id == null) result.episodes.minByOrNull { it.episodeNumber } else null
    }
    private suspend fun load(id: String?) { fresh(id); playIfAllowed() }
    private suspend fun playIfAllowed() {
        val e = episode ?: run { action = DramaPlaybackAction.Unavailable; return }
        if (!e.canPlay) { action = accessAction(e.accessReason); return }
        if (!visible) { deferredPlay = true; return }
        deferredPlay = false
        if (grant != null) { refresh(); return }
        val version = generation
        val result = gateway.playDramaEpisode(requireNotNull(dramaId),e.episodeId)
        ensureCurrent(version); validateExpiry(result.expiresAt); grant = result
        resolve(result)
    }
    private suspend fun resolve(value: DramaPlaybackGrant) {
        val version = generation
        val resolved = gateway.resolveDramaMedia(value.mediaResolver)
        ensureCurrent(version); validateExpiry(resolved.expiresAt); media = resolved
        action = DramaPlaybackAction.None
        val track = value.subtitles.firstOrNull { it.trackId == selectedSubtitleId }
        if (track == null) { selectedSubtitleId = null; subtitleMedia = null }
        else {
            val subtitleSerial = subtitleVersion
            try {
                val resolvedSubtitle = gateway.resolveDramaSubtitle(track.resolverUrl)
                ensureCurrent(version)
                if (subtitleSerial == subtitleVersion) { validateExpiry(resolvedSubtitle.expiresAt); subtitleMedia = resolvedSubtitle }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (version == generation && subtitleSerial == subtitleVersion) subtitleFailed() }
        }
    }
    private suspend fun refresh() {
        val version = generation
        val previous = requireNotNull(grant)
        val previousExpiry = listOfNotNull(previous.expiresAt, media?.expiresAt, subtitleMedia?.expiresAt).minOf { validateExpiry(it, allowExpired = true) }
        val result = gateway.refreshDramaGrant(previous.grant)
        ensureCurrent(version)
        val expiry = validateExpiry(result.expiresAt)
        if (expiry <= validateExpiry(previous.expiresAt, allowExpired = true) && expiry - now() <= 20_000) {
            throw HomeFeedServiceException("DRAMA_REFRESH_NOT_EXTENDED")
        }
        grant = result; resolve(result)
        val effectiveExpiry = listOfNotNull(result.expiresAt, media?.expiresAt, subtitleMedia?.expiresAt).minOf { validateExpiry(it) }
        if (effectiveExpiry <= previousExpiry && effectiveExpiry - now() <= 20_000) {
            throw HomeFeedServiceException("DRAMA_REFRESH_NOT_EXTENDED")
        }
    }
    fun retry() {
        if (closed || loading) return
        val intent = pendingUnlock
        launch {
            when { intent != null -> unlock(intent); grant != null -> refresh(); else -> load(episode?.episodeId) }
            if (resumeAfterMediaError && media != null && action == DramaPlaybackAction.None) {
                playing = true; resumeAfterMediaError = false
            }
        }
    }
    fun confirmUnlock() {
        if (closed || loading || action != DramaPlaybackAction.UnlockRequired) return
        val e = episode ?: return
        val amount = e.amount ?: return
        val intent = try { unlockStore.pending(requireNotNull(dramaId),e.episodeId,amount) }
        catch (e: Exception) { fail(e); return }
        pendingUnlock = intent
        launch { unlock(intent) }
    }
    private suspend fun unlock(intent: DramaUnlockIntent) {
        val version = generation
        try { gateway.unlockDramaEpisode(intent.dramaId,intent.episodeId,intent.amount,intent.idempotencyKey) }
        catch (e: HomeFeedServiceException) {
            ensureCurrent(version)
            if (e.code == "PRICE_CHANGED" || e.code == "EPISODE_FREE") {
                unlockStore.clear(intent); pendingUnlock = null; fresh(intent.episodeId)
                if (e.code == "EPISODE_FREE" || episode?.canPlay == true) playIfAllowed()
                else { errorCode = e.code; action = accessAction(episode?.accessReason.orEmpty()) }
                return
            }
            throw e
        }
        ensureCurrent(version); unlockSuccessSerial++
        unlockStore.clear(intent); pendingUnlock = null
        fresh(intent.episodeId); playIfAllowed()
    }
    fun selectSubtitle(id: String?) {
        if (closed) return
        subtitleJob?.cancel(); val serial = ++subtitleVersion; val version = generation
        selectedSubtitleId = id; subtitleMedia = null
        if (errorCode == "DRAMA_SUBTITLE_ERROR") { errorCode = null; action = DramaPlaybackAction.None }
        scheduleRenewal()
        val track = grant?.subtitles?.firstOrNull { it.trackId == id } ?: return
        subtitleJob = scope.launch {
            try {
                val result = gateway.resolveDramaSubtitle(track.resolverUrl)
                ensureCurrent(version); if (serial == subtitleVersion) { validateExpiry(result.expiresAt); subtitleMedia = result }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (version == generation && serial == subtitleVersion) {
                    subtitleFailed()
                }
            } finally {
                if (version == generation && serial == subtitleVersion && !closed) scheduleRenewal()
            }
        }
    }
    fun setVisible(value: Boolean) {
        if (closed || visible == value) return
        visible = value; renewal?.cancel()
        if (!value) return
        if (loading) return
        if (action in setOf(DramaPlaybackAction.VipRequired, DramaPlaybackAction.TopUpRequired, DramaPlaybackAction.UnlockRequired) && episode != null) {
            media = null; subtitleMedia = null
            launch { load(episode?.episodeId) }
        } else if (grant != null && errorCode == null && playing && !completed) launch { refresh() }
        else if (grant == null && errorCode == null && deferredPlay && episode != null) launch { playIfAllowed() }
    }
    private fun scheduleRenewal() {
        renewal?.cancel()
        if (!visible || !playing || completed || closed || (errorCode != null && errorCode != "DRAMA_SUBTITLE_ERROR") || grant == null) return
        val version = generation
        val expires = listOfNotNull(grant?.expiresAt,media?.expiresAt,subtitleMedia?.expiresAt).mapNotNull { previewExpiryMillis(it) }.minOrNull() ?: return
        renewal = scope.launch {
            wait((expires - now() - 20_000).coerceAtLeast(1_000))
            if (visible && playing && !completed && !closed && version == generation && !loading) this@ShortDramaPlaybackController.launch { refresh() }
        }
    }
    private fun ensureCurrent(version: Long) { if (closed || version != generation) throw CancellationException() }
    private fun validateExpiry(value: String, allowExpired: Boolean = false): Long {
        val expiry = previewExpiryMillis(value)
            ?: throw HomeFeedServiceException("DRAMA_INVALID_EXPIRY")
        if (!allowExpired && expiry <= now()) throw HomeFeedServiceException("DRAMA_EXPIRED")
        return expiry
    }
    private fun subtitleFailed() {
        subtitleMedia = null; selectedSubtitleId = null
        errorCode = "DRAMA_SUBTITLE_ERROR"; action = DramaPlaybackAction.Retry
    }
    private fun fail(e: Exception) {
        media = null; subtitleMedia = null
        errorCode = (e as? HomeFeedServiceException)?.code ?: "HOME_NETWORK_ERROR"
        if (errorCode in setOf("GRANT_DENIED", "CONTENT_UNAVAILABLE", "MEDIA_UNAVAILABLE", "DRAMA_UNAVAILABLE", "EPISODE_UNAVAILABLE", "NOT_FOUND")) {
            grant = null; deferredPlay = false
        }
        action = when (errorCode) {
            "INSUFFICIENT_BALANCE" -> {
                pendingUnlock?.let {rechargeEpisode=RechargeEpisode(it.dramaId,it.episodeId,it.amount)}
                DramaPlaybackAction.TopUpRequired
            }
            "VIP_REQUIRED", "VIP_EXPIRED" -> DramaPlaybackAction.VipRequired
            "EPISODE_LOCKED", "PURCHASE_REQUIRED", "UNLOCK_REQUIRED" -> if (episode?.amount != null) DramaPlaybackAction.UnlockRequired else DramaPlaybackAction.Unavailable
            "GRANT_DENIED", "CONTENT_UNAVAILABLE", "MEDIA_UNAVAILABLE", "DRAMA_UNAVAILABLE", "EPISODE_UNAVAILABLE", "NOT_FOUND" -> DramaPlaybackAction.Unavailable
            else -> DramaPlaybackAction.Retry
        }
    }
    private fun accessAction(reason: String) = when (reason.uppercase()) {
        "VIP_REQUIRED", "VIP_EXPIRED" -> DramaPlaybackAction.VipRequired
        "EPISODE_LOCKED", "PURCHASE_REQUIRED", "UNLOCK_REQUIRED" -> if (episode?.amount != null) DramaPlaybackAction.UnlockRequired else DramaPlaybackAction.Unavailable
        else -> DramaPlaybackAction.Unavailable
    }
    fun reportMediaError() {
        if (closed) return
        generation++; work?.cancel(); renewal?.cancel(); subtitleJob?.cancel(); subtitleVersion++
        loading = false; playing = false; resumeAfterMediaError = true
        fail(HomeFeedServiceException("DRAMA_MEDIA_ERROR"))
    }
    fun updatePosition(positionMs: Long, durationMs: Long) { this.positionMs = positionMs.coerceAtLeast(0); this.durationMs = durationMs.coerceAtLeast(0) }
    @JvmName("changePlaying")
    fun setPlaying(value: Boolean) {
        if (closed || completed || playing == value) return
        playing = value
        renewal?.cancel()
        if (value && visible && grant != null && !loading) {
            val expires = listOfNotNull(grant?.expiresAt, media?.expiresAt).mapNotNull { previewExpiryMillis(it) }.minOrNull() ?: 0L
            if (expires - now() <= 20_000) launch { refresh() } else scheduleRenewal()
        }
    }
    fun episodeEnded(episodeId: String, authorizationId: String, advance: Boolean = true) {
        if (closed || loading || completed || episode?.episodeId != episodeId || grant?.authorizationId != authorizationId) return
        renewal?.cancel()
        if (!advance) { completed = true; seriesCompleted = false; playing = false; return }
        val next = detail?.episodes?.sortedBy { it.episodeNumber }?.let { list ->
            val index=list.indexOfFirst { it.episodeId==episodeId }; if(index>=0) list.getOrNull(index+1) else null
        }
        if (next == null) { completed=true; seriesCompleted=true; playing=false; return }
        selectEpisode(next.episodeId)
    }
    @JvmName("changeMuted")
    fun setMuted(value: Boolean) { muted = value }
    @JvmName("changeSpeed")
    fun setSpeed(value: Float) { if (value.isFinite() && value in 0.25f..3f) speed = value }
    fun close() { closed = true; generation++; work?.cancel(); renewal?.cancel(); subtitleJob?.cancel(); grant = null; media = null; subtitleMedia = null }
}

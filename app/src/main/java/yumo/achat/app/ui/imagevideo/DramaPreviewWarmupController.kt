package yumo.achat.app.ui.imagevideo

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import yumo.achat.core.backend.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class DramaPreviewWarmupState(val entry: HomeFeedEntry, val position: Int, private val fullPlaybackAvailable:Boolean=true) {
    var detail by mutableStateOf<ShortDramaDetail?>(null)
    var media by mutableStateOf<ResolvedDramaPreview?>(null)
    var detailFailed by mutableStateOf(false)
    var mediaFailed by mutableStateOf(false)
    var expired by mutableStateOf(false)
    var detailLoading by mutableStateOf(true)
    var mediaLoading by mutableStateOf(true)
    var expiresAtMs by mutableStateOf(0L)
    internal var work: Job? = null
    internal var mediaWork: Job? = null
    val firstEpisode get() = detail?.episodes?.minByOrNull { it.episodeNumber }
    val usesFullPlayback get()=fullPlaybackAvailable && firstEpisode?.canPlay==true
    val restricted get() = firstEpisode?.let { !it.canPlay && (it.vip || (it.amount ?: 0) > 0) } == true
}

/** RAM-only, bounded to actual Feed neighbors; never requests formal play, purchases or ACKs. */
internal class DramaPreviewWarmupController(private val gateway: HomeFeedGateway, private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis, private val fullPlaybackAvailable:Boolean=true) {
    val states = mutableStateMapOf<String, DramaPreviewWarmupState>()
    private var closed = false
    private var activeKey: String? = null
    private val metadataPermits=Semaphore(2)
    private var mediaAllowed=emptySet<String>()
    fun setWindow(entries: List<HomeFeedEntry>, current: Int, allowNextMedia:Boolean=true) {
        if (closed) return
        val indices = listOf(current, current + 1).filter { it in entries.indices }
        val wanted = indices.filter { entries[it].item.kind == "short_drama" }.map { entries[it].key }.toSet()
        mediaAllowed=indices.filter {it==current || allowNextMedia}.map {entries[it].key}.toSet()
        states.values.filter {it.entry.key !in mediaAllowed && it.media==null}.forEach {it.mediaWork?.cancel();it.mediaWork=null}
        states.keys.toList().filter { it !in wanted }.forEach(::evict)
        val newActive = entries.getOrNull(current)?.key
        indices.filter { entries[it].key in wanted }.forEach { index ->
            val entry = entries[index]
            val old = states[entry.key]
            if(old?.media!=null && old.expiresAtMs<=now()){old.media=null;old.expired=true}
            val retryOnEntry = entry.key == newActive && newActive != activeKey &&
                old?.let { it.detailFailed || it.mediaFailed } == true
            if (old == null || old.entry != entry || retryOnEntry) {
                evict(entry.key)
                load(entry,index)
            }
        }
        states.values.forEach(::startMedia)
        activeKey = newActive
    }
    fun retry(key: String) { states[key]?.let { val entry=it.entry;val position=it.position;evict(key);load(entry,position) } }
    private fun owns(state: DramaPreviewWarmupState) = !closed && states[state.entry.key] === state
    private fun load(entry: HomeFeedEntry, position: Int) {
        val state=DramaPreviewWarmupState(entry,position,fullPlaybackAvailable)
        states[entry.key]=state
        state.work=scope.launch {
            try {
                metadataPermits.withPermit {
                    val detail=HomeLoadDiagnostics.timed("drama_detail") {gateway.shortDramaDetail(entry.item.resourceId)}
                    ensureActive()
                    if(owns(state)){state.detail=detail;startMedia(state)}
                }
            } catch(e:CancellationException){throw e}
            catch(_:Exception){if(owns(state))state.detailFailed=true}
            finally {if(owns(state))state.detailLoading=false}
        }
    }
    private fun startMedia(state:DramaPreviewWarmupState) {
        if(!owns(state) || state.detail==null)return
        if(state.usesFullPlayback || state.entry.item.preview==null) {
            state.mediaLoading=false;return
        }
        if(state.entry.key !in mediaAllowed || state.mediaWork?.isActive==true || state.media!=null || state.mediaFailed || state.expired)return
        state.mediaLoading=true
        state.mediaWork=scope.launch {
            try {
                val preview=state.entry.item.preview ?: return@launch
                val grantExpiry=previewExpiryMillis(preview.expiresAt) ?: 0L
                if(grantExpiry<=now()){if(owns(state))state.expired=true;return@launch}
                val resolved=HomeLoadDiagnostics.timed("preview_resolver") {resolveDramaPreviewWithRetry(gateway,preview.resolverUrl)}
                ensureActive()
                if(!owns(state))return@launch
                val until=minOf(grantExpiry,previewExpiryMillis(resolved.expiresAt) ?: 0L)
                if(until<=now()){state.expired=true;return@launch}
                state.expiresAtMs=until;state.media=resolved;state.mediaLoading=false
                delay((until-now()).coerceAtLeast(1))
                if(owns(state)){state.media=null;state.expired=true}
            } catch(e:CancellationException){throw e}
            catch(e:Exception){if(owns(state)){
                state.expired=e is HomeFeedServiceException && e.code=="GRANT_EXPIRED"
                state.mediaFailed=!state.expired
            }} finally {if(owns(state))state.mediaLoading=false}
        }
    }
    private fun evict(key:String){states.remove(key)?.let {it.work?.cancel();it.mediaWork?.cancel();it.media=null;it.detail=null}}
    fun clear(){states.keys.toList().forEach(::evict);activeKey=null;mediaAllowed=emptySet()}
    fun close(){closed=true;clear()}
}

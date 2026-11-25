package yumo.achat.app.ui.imagevideo

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import yumo.achat.core.backend.ShortDramaDetail

internal class DramaBannerPoster {
    var detail by mutableStateOf<ShortDramaDetail?>(null)
    var loading by mutableStateOf(true)
    var failed by mutableStateOf(false)
    var revision by mutableIntStateOf(0)
    internal var job: Job? = null
}

/** Banner-session metadata cache; adjacent pages prepare images through Coil. */
internal class DramaBannerLoader(private val scope:CoroutineScope,private val fetch:suspend (String)->ShortDramaDetail) {
    val states=mutableStateMapOf<String,DramaBannerPoster>()
    private val permits=Semaphore(2)
    private var closed=false
    fun load(ids:List<String>) {
        if(closed)return
        ids.distinct().forEach { id -> if(id !in states) {
            val state=DramaBannerPoster();states[id]=state;start(id,state)
        } }
    }
    fun retry(id:String) {
        val state=states[id] ?: return
        if(closed || state.job?.isActive==true)return
        state.revision++;state.failed=false
        // Image failures reuse the successful metadata; changing revision recreates Coil's request.
        if(state.detail?.posterUrl?.isNotBlank()==true)return
        start(id,state)
    }
    private fun start(id:String,state:DramaBannerPoster) {
        state.loading=true;state.failed=false
        state.job=scope.launch {
            try {
                permits.withPermit {
                    val result=fetch(id)
                    ensureActive()
                    if(!closed){state.detail=result;state.failed=result.posterUrl.isBlank()}
                }
            } catch(e:CancellationException){throw e}
            catch(_:Exception){if(!closed)state.failed=true}
            finally {if(!closed)state.loading=false}
        }
    }
    fun close(){closed=true;states.values.forEach {it.job?.cancel()};states.clear()}
}

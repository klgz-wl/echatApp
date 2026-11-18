package yumo.achat.app.ui.imagevideo

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import yumo.achat.core.backend.DramaPage

internal fun <T> visibleRanking(items: List<T>, expanded: Boolean): List<T> = items.take(if(expanded) 10 else 4)

/** One session/filter owns this controller. Reload and disposal invalidate even uncancellable responses. */
internal class DramaPageController<T>(private val scope: CoroutineScope, private val identity: (T) -> String,
    private val fetch: suspend (Int) -> DramaPage<T>) {
    var items by mutableStateOf(emptyList<T>()); private set
    var loading by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    var hasMore by mutableStateOf(false); private set
    private var nextPage=1
    private var revision=0L
    private var closed=false
    private var work: Job?=null
    fun reload() { if(closed)return; revision++; work?.cancel(); items=emptyList(); nextPage=1; hasMore=true; loading=false; more() }
    fun more() {
        if(closed || loading || !hasMore)return
        val version=revision; val requested=nextPage
        loading=true; failed=false
        work=scope.launch {
            try {
                val response=fetch(requested)
                if(closed || version!=revision)return@launch
                require(response.page==requested && response.pageSize>0 && response.total>=0)
                items=(items+response.items).distinctBy(identity)
                hasMore=response.hasMore && response.items.isNotEmpty(); nextPage=requested+1
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { if(!closed && version==revision) failed=true }
            finally { if(!closed && version==revision)loading=false }
        }
    }
    fun close() { closed=true; revision++; work?.cancel() }
}

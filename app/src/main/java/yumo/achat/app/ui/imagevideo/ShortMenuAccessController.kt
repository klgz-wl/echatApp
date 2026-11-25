package yumo.achat.app.ui.imagevideo

import android.os.SystemClock
import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** One authenticated session owns one bounded polling window; no navigation or feed reloads. */
internal class ShortMenuAccessController(
    private val scope:CoroutineScope,
    private val loadIsB:suspend (String)->Boolean?,
    private val now:()->Long={SystemClock.elapsedRealtime()},
    private val wait:suspend (Long)->Unit={delay(it)},
) {
    var isB by mutableStateOf(false);private set
    var revision=0L;private set
    private var owner:String?=null
    private var generation=0L
    private var foreground=false
    private var work:Job?=null

    fun bind(owner:String,initialIsB:Boolean) {
        if(this.owner==owner)return
        clear()
        this.owner=owner;isB=initialIsB;revision++
        if(initialIsB)return
        val version=generation
        val started=now()
        val deadline=started+60_000L
        work=scope.launch {
            var scheduled=started+3_000L
            while(scheduled<deadline && version==generation && this@ShortMenuAccessController.owner==owner && !isB) {
                wait((scheduled-now()).coerceAtLeast(0L))
                if(now()>=deadline || version!=generation || this@ShortMenuAccessController.owner!=owner || isB)return@launch
                if(foreground) {
                    val expected=revision
                    val value=try {withTimeoutOrNull(minOf(3_000L,deadline-now()).coerceAtLeast(1L)){loadIsB(owner)}}
                    catch(e:CancellationException){throw e}
                    catch(_:Exception){null}
                    if(version!=generation || this@ShortMenuAccessController.owner!=owner || now()>=deadline)return@launch
                    accept(owner,value,expected)
                    if(isB)return@launch
                }
                scheduled+=3_000L
                if(scheduled<now())scheduled=now()+3_000L
            }
        }
    }
    fun accept(owner:String,value:Boolean?,expectedRevision:Long?=null) {
        if(this.owner!=owner || value==null || (expectedRevision!=null && revision!=expectedRevision))return
        isB=value;revision++
        if(value){work?.cancel();work=null}
    }
    fun setForeground(value:Boolean) {foreground=value}
    fun clear() {
        generation++;revision++;work?.cancel();work=null;owner=null;isB=false
    }
}

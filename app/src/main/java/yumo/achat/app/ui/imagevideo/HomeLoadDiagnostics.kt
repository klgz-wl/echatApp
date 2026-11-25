package yumo.achat.app.ui.imagevideo

import yumo.achat.app.BuildConfig

/** Fixed stage names and elapsed durations only; never log media URLs or authorization data. */
internal object HomeLoadDiagnostics {
    fun record(stage:String,startedNanos:Long,outcome:String="ok") {
        if(BuildConfig.DEBUG) runCatching {
            android.util.Log.d("HomeLoadTiming","stage=$stage elapsed_ms=${(System.nanoTime()-startedNanos)/1_000_000} outcome=$outcome")
        }
    }
    suspend fun <T> timed(stage:String,block:suspend ()->T):T {
        val started=System.nanoTime()
        try {return block().also {record(stage,started)}}
        catch(e:kotlinx.coroutines.CancellationException){record(stage,started,"cancelled");throw e}
        catch(e:Exception){record(stage,started,"failed");throw e}
    }
}

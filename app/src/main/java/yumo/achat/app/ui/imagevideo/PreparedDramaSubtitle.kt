package yumo.achat.app.ui.imagevideo

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.FilterInputStream
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class DramaSubtitleTimingSummary(val cueCount:Int,val firstCueMs:Long?,val lastCueEndMs:Long?)
internal data class PreparedDramaSubtitle(val url:String,val bytes:ByteArray,val summary:DramaSubtitleTimingSummary)
private const val MaximumDramaSubtitleBytes=8*1024*1024

/** Numeric timing metadata only. The parser and renderer still interpret the original VTT bytes. */
internal fun summarizeDramaWebVtt(bytes:ByteArray):DramaSubtitleTimingSummary {
    fun timestamp(raw:String):Long? {
        val parts=raw.split(':')
        if(parts.size !in 2..3)return null
        val seconds=parts.last().split('.')
        if(seconds.size!=2 || seconds[1].length!=3)return null
        val ss=seconds[0].toLongOrNull()?.takeIf {it in 0..59} ?: return null
        val ms=seconds[1].toLongOrNull() ?: return null
        val mm=parts[parts.size-2].toLongOrNull()?.takeIf {it in 0..59} ?: return null
        val hh=if(parts.size==3)parts[0].toLongOrNull() ?: return null else 0L
        if(hh !in 0..2_000_000_000L)return null
        return ((hh*60+mm)*60+ss)*1000+ms
    }
    val pattern=Regex("(?m)^\\s*((?:\\d{2,}:)?\\d{2}:\\d{2}\\.\\d{3})[ \\t]+-->[ \\t]+((?:\\d{2,}:)?\\d{2}:\\d{2}\\.\\d{3})(?:[ \\t].*)?\\r?$")
    var count=0;var first:Long?=null;var last:Long?=null
    pattern.findAll(bytes.toString(Charsets.UTF_8)).forEach {match->
        val start=timestamp(match.groupValues[1]);val end=timestamp(match.groupValues[2])
        if(start!=null && end!=null && end>=start){count++;first=first?.let {minOf(it,start)} ?: start;last=last?.let {maxOf(it,end)} ?: end}
    }
    return DramaSubtitleTimingSummary(count,first,last)
}

/** Cancellation actively closes the input, so blocking socket reads do not hold up a new selection. */
internal suspend fun readPreparedSubtitleBytes(input:InputStream,maxBytes:Int=MaximumDramaSubtitleBytes):ByteArray {
    require(maxBytes>0)
    return suspendCancellableCoroutine {continuation->
        val work=CoroutineScope(continuation.context+Dispatchers.IO).launch {
            try {
                val output=ByteArrayOutputStream(minOf(maxBytes,16*1024));val buffer=ByteArray(8192)
                while(true){
                    ensureActive()
                    val length=input.read(buffer,0,minOf(buffer.size,maxBytes-output.size()+1))
                    ensureActive()
                    if(length<0)break
                    if(length==0){yield();continue}
                    if(length>maxBytes-output.size())throw IOException("Subtitle exceeds size limit")
                    output.write(buffer,0,length)
                }
                val bytes=output.toByteArray()
                ensureActive()
                input.close()
                continuation.resume(bytes)
            } catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
            finally {runCatching {input.close()}}
        }
        continuation.invokeOnCancellation {runCatching {input.close()};work.cancel()}
    }
}

@OptIn(UnstableApi::class)
private fun dramaNetworkDataSourceFactory(context:Context):DataSource.Factory=
    DefaultDataSource.Factory(context.applicationContext,DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(15_000).setReadTimeoutMs(15_000).setUserAgent("Achat-Drama/1.0"))

/** Bounded RAM preparation only: signed media URLs and subtitle bytes never enter persistent cache. */
@OptIn(UnstableApi::class)
internal suspend fun prepareDramaSubtitle(context:Context,url:String):PreparedDramaSubtitle=withContext(Dispatchers.IO) {
    withTimeoutOrNull(30_000) {
        val started=System.nanoTime()
        val source=dramaNetworkDataSourceFactory(context).createDataSource()
        val input=object:FilterInputStream(DataSourceInputStream(source,DataSpec.Builder().setUri(Uri.parse(url)).build())) {
            override fun close(){try {super.close()} finally {source.close()}}
        }
        val bytes=readPreparedSubtitleBytes(input)
        val summary=summarizeDramaWebVtt(bytes)
        HomeLoadDiagnostics.record("subtitle_ready",started)
        if(yumo.achat.app.BuildConfig.DEBUG) android.util.Log.d("HomeLoadTiming",
            "stage=subtitle_timing cue_count=${summary.cueCount} first_ms=${summary.firstCueMs} last_end_ms=${summary.lastCueEndMs}")
        PreparedDramaSubtitle(url,bytes,summary)
    } ?: throw IOException("Subtitle preparation timed out")
}


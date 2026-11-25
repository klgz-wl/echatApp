package yumo.achat.app.ui.imagevideo

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.compositionLocalOf
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl

internal data class HomeTemplatePlayback(val buffer:HomeTemplateVideoBuffer,val key:String,val index:Int=0,val onFirstFrame:()->Unit)
internal val LocalHomeTemplatePlayback=compositionLocalOf<HomeTemplatePlayback?> {null}

/** Only public template bytes use disk cache; no decoder is allocated for a neighbor. */
@OptIn(UnstableApi::class)
internal class HomeTemplateVideoBuffer(context:Context) {
    @Volatile private var closed=false
    private var currentIndex=0
    private var appliedIndex:Int?=null
    private val positions=mutableMapOf<String,Int>()
    private val registered=linkedMapOf<String,MediaItem>()
    private val ready=java.util.Collections.synchronizedSet(mutableSetOf<String>())
    val sourceKeys:List<String> get()=registered.keys.toList()
    fun isBuffered(key:String)=key in ready
    private val builder=DefaultPreloadManager.Builder(context.applicationContext,
        TargetPreloadStatusControl<Int,DefaultPreloadManager.PreloadStatus> {
            if(!closed)DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3_000)
            else DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        })
        .setCache(null)
        .setDataSourceFactory(TemplateVideoCache.cacheDataSourceFactory(context.applicationContext,DefaultTemplateVideoNetworkConfiguration))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1500,10000,250,750)
            .setTargetBufferBytes(8*1024*1024).setPlayerTargetBufferBytes(PlayerId.PRELOAD.name,6*1024*1024)
            .setPrioritizeTimeOverSizeThresholds(false).build())
    private val manager=builder.build().apply {
        addListener(object:PreloadManagerListener {
            override fun onCompleted(mediaItem:MediaItem){if(!closed && registered[mediaItem.mediaId]==mediaItem)ready.add(mediaItem.mediaId)}
        })
    }
    private fun item(key:String,url:String)=MediaItem.Builder().setMediaId(key).setUri(url).build()
    fun update(entries:List<HomeFeedEntry>,current:Int,allowNext:Boolean) {
        if(closed)return
        currentIndex=current
        val indices=(if(allowNext)listOf(current,current+1) else listOf(current)).filter {
            entries.getOrNull(it)?.item?.let {item->item.kind=="video_template" && item.fileUrl.isNotBlank()}==true
        }
        val candidates=indices.map {entries[it]}
        val desiredPositions=indices.associate {entries[it].key to it}
        val desired=candidates.associate {it.key to item(it.key,it.item.fileUrl)}
        if(appliedIndex==current && registered==desired && positions==desiredPositions)return
        appliedIndex=current
        registered.keys.toList().filter {registered[it]!=desired[it] || positions[it]!=desiredPositions[it]}.forEach(::discard)
        candidates.forEach {entry ->
            if(registered[entry.key]!=desired[entry.key]) {
                val value=desired.getValue(entry.key);registered[entry.key]=value;positions[entry.key]=desiredPositions.getValue(entry.key);manager.add(value,positions.getValue(entry.key))
            }
        }
        val ordered=candidates.map {it.key to registered.getValue(it.key)}
        registered.clear();ordered.forEach {(key,value)->registered[key]=value}
        manager.setCurrentPlayingIndex(current);manager.invalidate()
    }
    fun createPlayer(context:Context,key:String,url:String,index:Int=currentIndex):ExoPlayer {
        check(!closed)
        currentIndex=index
        val value=item(key,url)
        if(registered[key]!=value || positions[key]!=index){discard(key);registered[key]=value;positions[key]=index;manager.add(value,index)}
        manager.setCurrentPlayingIndex(index);manager.invalidate()
        return builder.buildExoPlayer(ExoPlayer.Builder(context.applicationContext)).apply {
            val source=manager.getMediaSource(value)
            if(source!=null)setMediaSource(source) else setMediaItem(value)
        }
    }
    fun discard(key:String) {
        if(closed)return
        registered.remove(key)?.let(manager::remove);positions.remove(key);ready.remove(key)
    }
    fun close(){if(closed)return;closed=true;registered.clear();positions.clear();ready.clear();manager.release()}
}

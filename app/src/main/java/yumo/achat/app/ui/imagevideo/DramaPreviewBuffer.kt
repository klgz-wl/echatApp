package yumo.achat.app.ui.imagevideo

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import kotlin.math.abs

/** Samples stay in Media3 RAM buffers. No decoder, JWT or persistent URL cache for adjacent cards. */
@OptIn(UnstableApi::class)
internal class DramaPreviewBuffer(context:Context) {
    @Volatile private var currentIndex=0
    @Volatile private var closed=false
    private var lastIndex:Int?=null
    private val registered=mutableMapOf<String,MediaItem>()
    private val ready=java.util.Collections.synchronizedSet(mutableSetOf<String>())
    val sourceCount get()=registered.size
    fun isBuffered(key:String)=key in ready
    private val builder=DefaultPreloadManager.Builder(context.applicationContext,
        TargetPreloadStatusControl<Int,DefaultPreloadManager.PreloadStatus> { index ->
            if(!closed && abs(index-currentIndex)<=1) DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3_000) else DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        })
        .setCache(null)
        .setDataSourceFactory(DefaultDataSource.Factory(context.applicationContext,DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000).setUserAgent("Achat-Preview/1.0")))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1500,5000,250,500)
            .setTargetBufferBytes(8*1024*1024).setPlayerTargetBufferBytes(PlayerId.PRELOAD.name,6*1024*1024)
            .setPrioritizeTimeOverSizeThresholds(false).build())
    private val manager=builder.build().apply {
        addListener(object:PreloadManagerListener {
            override fun onCompleted(mediaItem:MediaItem){if(!closed && registered[mediaItem.mediaId]==mediaItem)ready.add(mediaItem.mediaId)}
        })
    }
    private fun item(key:String,url:String,restricted:Boolean)=MediaItem.Builder().setMediaId(key).setUri(url).apply {
        if(restricted)setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(DramaPreviewLimitMs).build())
    }.build()
    fun update(states:List<DramaPreviewWarmupState>,index:Int) {
        if(closed)return
        currentIndex=index
        val candidates=states.filter {it.firstEpisode!=null && !it.usesFullPlayback && it.media!=null && !it.expired && it.expiresAtMs>System.currentTimeMillis()}
        val desired=candidates.associate {it.entry.key to item(it.entry.key,it.media!!.url,it.restricted)}
        if(lastIndex==index && registered==desired)return
        lastIndex=index
        registered.keys.toList().filter {registered[it]!=desired[it]}.forEach {key -> registered.remove(key)?.let(manager::remove);ready.remove(key)}
        candidates.forEach {state -> val value=desired.getValue(state.entry.key)
            if(registered[state.entry.key]!=value){registered[state.entry.key]=value;manager.add(value,state.position)}
        }
        manager.setCurrentPlayingIndex(index);manager.invalidate()
    }
    fun createPlayer(context:Context,key:String,url:String,restricted:Boolean):ExoPlayer {
        check(!closed)
        val value=item(key,url,restricted)
        if(registered[key]!=value){registered.remove(key)?.let(manager::remove);registered[key]=value;ready.remove(key);manager.add(value,currentIndex);manager.invalidate()}
        return builder.buildExoPlayer(ExoPlayer.Builder(context.applicationContext)).apply {
            val source=manager.getMediaSource(value)
            if(source!=null)setMediaSource(source) else setMediaItem(value)
        }
    }
    fun discard(key:String) {
        if(closed)return
        registered.remove(key)?.let(manager::remove);ready.remove(key)
    }
    fun close(){if(closed)return;closed=true;registered.clear();ready.clear();manager.release()}
}

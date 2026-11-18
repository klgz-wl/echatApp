package yumo.achat.app.ui.imagevideo

import androidx.annotation.OptIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/** Preview grants only; the clip and non-looping player cannot reveal full locked media. */
@Composable
@OptIn(UnstableApi::class)
internal fun DramaPreviewSurface(url:String,playing:Boolean,restricted:Boolean,modifier:Modifier=Modifier,
    onProgress:(Float)->Unit,onFinished:()->Unit,onError:()->Unit) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle){mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    val finished by rememberUpdatedState(onFinished)
    val failure by rememberUpdatedState(onError)
    val progress by rememberUpdatedState(onProgress)
    val player=remember(url,restricted){ExoPlayer.Builder(context).setMediaSourceFactory(TemplateVideoCache.mediaSourceFactory(context,cacheEnabled=false)).build().apply {
        val item=MediaItem.Builder().setUri(url)
        if(restricted)item.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(10_000).build())
        setMediaItem(item.build());repeatMode=Player.REPEAT_MODE_OFF;prepare()
    }}
    var frame by remember(player){mutableStateOf(false)}
    DisposableEffect(player,lifecycle){
        val listener=object:Player.Listener {
            override fun onRenderedFirstFrame(){frame=true}
            override fun onPlayerError(error:PlaybackException){failure()}
            override fun onPlaybackStateChanged(state:Int){if(state==Player.STATE_ENDED)finished()}
        }
        val observer=LifecycleEventObserver{_,_->resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
        player.addListener(listener);lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer);player.removeListener(listener);player.release()}
    }
    LaunchedEffect(player,playing,resumed){player.playWhenReady=playing && resumed}
    LaunchedEffect(player){while(true){
        if(player.playbackState==Player.STATE_READY){
            progress(if(player.duration>0)(player.currentPosition.toFloat()/player.duration).coerceIn(0f,1f) else 0f)
            if(restricted && player.currentPosition>=10_000){player.pause();finished()}
        }
        delay(100)
    }}
    AndroidView(factory={PlayerView(it).apply{useController=false;resizeMode=AspectRatioFrameLayout.RESIZE_MODE_ZOOM;setShutterBackgroundColor(android.graphics.Color.TRANSPARENT);this.player=player}},
        update={it.player=player},modifier=modifier.alpha(if(frame)1f else 0f).testTag(if(frame)"template-video-frame" else "template-video-loading"))
}

package yumo.achat.app.ui.imagevideo

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.UUID
import yumo.achat.app.R
import yumo.achat.app.ui.theme.*
import yumo.achat.core.backend.*

@Composable
internal fun DramaPlaybackVisibility(controller: ShortDramaPlaybackController, viewModel: ShortDramaPlayerViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val token = remember(controller) { UUID.randomUUID().toString() }
    DisposableEffect(controller, lifecycle, viewModel, token) {
        fun update() = viewModel.surface(controller, token, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); viewModel.surface(controller, token, false) }
    }
}

@Composable
internal fun ShortDramaPlayerRoute(dramaId: String, userId: String, ownerKey: String,
    gateway: ShortDramaPlaybackGateway, viewModel: ShortDramaPlayerViewModel,
    onBack: () -> Unit, onSubscription: () -> Unit, onTopUp: () -> Unit, preferredEpisodeId: String? = null, requestId: String = "$dramaId|$preferredEpisodeId") {
    val controller = viewModel.bind(ownerKey, userId, dramaId, gateway)
    DramaPlaybackVisibility(controller, viewModel)
    LaunchedEffect(controller, requestId) { viewModel.openRequest(controller,requestId,dramaId,preferredEpisodeId) }
    ShortDramaPlayerScreen(controller, onBack, onSubscription, onTopUp)
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShortDramaPlayerScreen(controller: ShortDramaPlaybackController, onBack: () -> Unit,
    onSubscription: () -> Unit, onTopUp: () -> Unit,
    videoContent: (@Composable (ShortDramaPlaybackController, Modifier, Long, Int) -> Unit)? = null) {
    BackHandler(onBack = onBack)
    var episodesOpen by remember { mutableStateOf(false) }
    var commentsOpen by remember { mutableStateOf(false) }
    var dismissedCompletion by remember(controller) { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var subtitlesOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    var unlockOffer by remember { mutableStateOf<ShortDramaEpisode?>(null) }
    var seekPosition by remember(controller.episode?.episodeId, controller.media?.url, controller.subtitleMedia?.url) { mutableLongStateOf(0L) }
    var seekSerial by remember(controller.episode?.episodeId, controller.media?.url, controller.subtitleMedia?.url) { mutableIntStateOf(0) }
    val subtitleWarning = controller.errorCode == "DRAMA_SUBTITLE_ERROR"
    val detail = controller.detail
    val episode = controller.episode
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(detail?.posterUrl, detail?.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (controller.media != null && (controller.errorCode == null || subtitleWarning) && (controller.action == DramaPlaybackAction.None || subtitleWarning)) {
            if (videoContent == null) DramaVideoSurface(controller, Modifier.fillMaxSize(), seekPosition, seekSerial)
            else videoContent(controller, Modifier.fillMaxSize(), seekPosition, seekSerial)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xBB04050D), Color.Transparent, Color(0x9904050D), Color(0xFF05060F))))
            .clickable(enabled=controller.media != null && controller.action==DramaPlaybackAction.None) { controller.setPlaying(!controller.playing) })
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DramaGlassIcon(R.drawable.ic_drama_back, stringResource(R.string.drama_back_short), onBack)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.drama_episode_pill, episode?.episodeNumber ?: 1), color = AchatCyan, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.clip(CircleShape).background(Color(0xB20F172A))
                        .border(1.dp, AchatCyan.copy(alpha = .3f), CircleShape).padding(horizontal = 15.dp, vertical = 7.dp))
                Spacer(Modifier.weight(1f))
                DramaGlassIcon(R.drawable.ic_drama_comments, stringResource(R.string.drama_comments), { commentsOpen=true })
                Spacer(Modifier.width(8.dp))
                DramaGlassIcon(R.drawable.ic_drama_speed, stringResource(R.string.drama_speed), { speedOpen = true })
                Spacer(Modifier.width(8.dp))
                DramaGlassIcon(R.drawable.ic_drama_audio, stringResource(if (controller.muted) R.string.drama_unmute else R.string.drama_mute),
                    { controller.setMuted(!controller.muted) }, tint = if (controller.muted) AchatPink else Color.White)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xB20F172A))
                    .border(1.dp, AchatPink.copy(alpha = .3f), CircleShape).clickable { subtitlesOpen = true }, contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.drama_cc), color = Color(0xFFF0ABFC), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    controller.loading && controller.media == null -> CircularProgressIndicator(color = AchatCyan)
                    !subtitleWarning && (controller.action != DramaPlaybackAction.None || controller.errorCode != null) -> {
                        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xE6020617)).padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(when (controller.action) {
                                DramaPlaybackAction.VipRequired -> R.string.drama_vip_required
                                DramaPlaybackAction.UnlockRequired -> R.string.drama_locked
                                DramaPlaybackAction.TopUpRequired -> R.string.drama_insufficient_coins
                                DramaPlaybackAction.Unavailable -> R.string.drama_price_unavailable
                                else -> R.string.drama_play_failed
                            }), color = Color.White)
                            when (controller.action) {
                                DramaPlaybackAction.VipRequired -> TextButton(onClick = onSubscription) { Text(stringResource(R.string.drama_subscribe)) }
                                DramaPlaybackAction.TopUpRequired -> TextButton(onClick = onTopUp) { Text(stringResource(R.string.drama_top_up)) }
                                DramaPlaybackAction.UnlockRequired -> TextButton(onClick = { unlockOffer = episode }, enabled = !controller.loading) {
                                    Text(stringResource(R.string.drama_unlock_price, episode?.amount ?: 0))
                                }
                                DramaPlaybackAction.Unavailable -> TextButton(onClick = controller::retry) { Text(stringResource(R.string.template_feed_retry)) }
                                else -> TextButton(onClick = controller::retry, enabled = !controller.loading) { Text(stringResource(R.string.drama_retry_playback)) }
                            }
                        }
                    }
                    controller.media != null -> Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0xCC020617))
                        .border(2.dp, AchatPink, CircleShape).clickable { controller.setPlaying(!controller.playing) }, contentAlignment = Alignment.Center) {
                        Icon(painterResource(if (controller.playing) R.drawable.ic_drama_pause else R.drawable.ic_me_play),
                            stringResource(if (controller.playing) R.string.drama_pause else R.string.drama_play),
                            tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (subtitleWarning) TextButton(onClick = { subtitlesOpen = true }) {
                Text(stringResource(R.string.drama_subtitle_retry), color = AchatCyan)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    detail?.tags?.firstOrNull()?.let {
                        Text(it, color = Color(0xFFF0ABFC), fontSize = 11.sp,
                            modifier = Modifier.clip(CircleShape).background(AchatPink.copy(alpha = .2f))
                                .border(1.dp, AchatPink.copy(alpha = .4f), CircleShape).padding(horizontal = 11.dp, vertical = 4.dp))
                    }
                    Text(detail?.title ?: stringResource(R.string.drama_details), color = Color.White, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xB20F172A))
                    .border(1.dp, Color.White.copy(alpha = .15f), RoundedCornerShape(12.dp)).clickable { episodesOpen = true }.padding(9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_drama_episodes), null, tint = AchatCyan, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.drama_episodes), color = Color(0xFFE2E8F0), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            DramaDescriptionLine(detail?.description.orEmpty(), detailsOpen) { detailsOpen = !detailsOpen }
            var dragging by remember(controller.episode?.episodeId) { mutableStateOf<Float?>(null) }
            val fraction = if (controller.durationMs > 0) (controller.positionMs.toFloat() / controller.durationMs).coerceIn(0f, 1f) else 0f
            Slider(value = dragging ?: fraction, onValueChange = { dragging = it },
                onValueChangeFinished = {
                    seekPosition = ((dragging ?: fraction) * controller.durationMs).toLong()
                    controller.updatePosition(seekPosition, controller.durationMs)
                    seekSerial++; dragging = null
                },
                enabled = controller.media != null && controller.durationMs > 0,
                colors = SliderDefaults.colors(thumbColor = AchatCyan, activeTrackColor = AchatCyan, inactiveTrackColor = Color.White.copy(alpha = .1f)))
        }
    }
    if (commentsOpen) ModalBottomSheet(onDismissRequest={commentsOpen=false},containerColor=DramaGlass) {
        Text(stringResource(R.string.drama_comments),color=Color.White,fontSize=20.sp,modifier=Modifier.padding(20.dp))
        Text(stringResource(R.string.drama_comments_unavailable),color=AchatMuted,modifier=Modifier.padding(20.dp))
        TextButton(onClick={commentsOpen=false}) {Text(stringResource(R.string.drama_close),color=AchatCyan)}
    }
    if (controller.completed && !dismissedCompletion) DramaCompletionDialog(detail?.title.orEmpty()) {dismissedCompletion=true}
    unlockOffer?.let { offer ->
        AlertDialog(onDismissRequest = { unlockOffer = null },
            title = { Text(stringResource(R.string.drama_confirm_unlock)) },
            text = { Text(stringResource(R.string.drama_confirm_unlock_body, offer.episodeNumber, offer.amount ?: 0)) },
            confirmButton = { TextButton(onClick = {
                unlockOffer = null
                if (controller.episode?.episodeId == offer.episodeId && controller.episode?.amount == offer.amount &&
                    controller.action == DramaPlaybackAction.UnlockRequired) controller.confirmUnlock()
            }, enabled = offer.amount != null && !controller.loading) { Text(stringResource(R.string.drama_confirm)) } },
            dismissButton = { TextButton(onClick = { unlockOffer = null }) { Text(stringResource(R.string.drama_cancel)) } })
    }
    if (episodesOpen) ModalBottomSheet(onDismissRequest = { episodesOpen = false }, containerColor = Color(0xFF151329)) {
        Text(stringResource(R.string.drama_episodes), color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(20.dp))
        LazyVerticalGrid(GridCells.Adaptive(64.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(detail?.episodes.orEmpty(), key = { it.episodeId }) { item ->
                Column(Modifier.clip(RoundedCornerShape(8.dp)).background(if (episode?.episodeId == item.episodeId) AchatCyan.copy(alpha = .25f) else Color(0xFF232139))
                    .border(1.dp, if (episode?.episodeId == item.episodeId) AchatCyan else Color.White.copy(alpha = .1f), RoundedCornerShape(8.dp))
                    .clickable { episodesOpen = false; controller.selectEpisode(item.episodeId) }.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(item.episodeNumber.toString(), color = Color.White)
                    if (item.vip) Text(stringResource(R.string.me_vip_label), color = AchatPink, fontSize = 9.sp)
                    else if (!item.canPlay) Text(stringResource(R.string.drama_locked), color = AchatMuted, fontSize = 9.sp, maxLines = 1)
                }
            }
        }
    }
    if (speedOpen) ModalBottomSheet(onDismissRequest={speedOpen=false},containerColor=DramaBackground) {
        DramaSheetHeader(stringResource(R.string.drama_speed)) {speedOpen=false}
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(.5f,.75f,1f,1.25f,1.5f,2f).forEach { value ->
                DramaOptionRow(if(value==1f) stringResource(R.string.drama_speed_normal) else "${value}×", "${value}×",controller.speed==value) {
                    controller.setSpeed(value);speedOpen=false
                }
            }
        }
    }
    if (subtitlesOpen) ModalBottomSheet(onDismissRequest={subtitlesOpen=false},containerColor=DramaBackground) {
        DramaSheetHeader(stringResource(R.string.drama_subtitles)) {subtitlesOpen=false}
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            DramaOptionRow(stringResource(R.string.drama_subtitle_off),stringResource(R.string.drama_subtitle_off),controller.selectedSubtitleId==null) {controller.selectSubtitle(null);subtitlesOpen=false}
            controller.grant?.subtitles.orEmpty().forEach { track ->
                DramaOptionRow(track.displayName.ifBlank {track.language},track.language.uppercase(java.util.Locale.ROOT),controller.selectedSubtitleId==track.trackId) {
                    controller.selectSubtitle(track.trackId);subtitlesOpen=false
                }
            }
        }
    }

}

@Composable
private fun DramaCompletionDialog(title:String,onClose:()->Unit) {
    var rating by remember {mutableIntStateOf(0)}
    var feedback by remember {mutableStateOf("")}
    androidx.compose.ui.window.Dialog(onDismissRequest=onClose) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.verticalGradient(listOf(DramaBackground,DramaGlass)))
            .border(1.dp,AchatCyan.copy(alpha=.5f),RoundedCornerShape(24.dp)).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Box(Modifier.align(Alignment.End).size(28.dp).clickable(onClick=onClose),contentAlignment=Alignment.Center) {
                Image(painterResource(R.drawable.template_subscription_close),stringResource(R.string.drama_close),Modifier.size(16.dp))
            }
            Text(stringResource(R.string.drama_rating_title,title),color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { value -> Icon(painterResource(R.drawable.ic_drama_rating_star),stringResource(R.string.drama_rating_stars,value),
                    tint=if(value<=rating)DramaGold else AchatMuted,modifier=Modifier.size(36.dp).clickable{rating=value}.padding(4.dp)) }
            }
            OutlinedTextField(value=feedback,onValueChange={feedback=it.take(1000)},placeholder={Text(stringResource(R.string.drama_rating_hint))},
                modifier=Modifier.fillMaxWidth().heightIn(min=120.dp),shape=RoundedCornerShape(16.dp))
            Text(stringResource(R.string.drama_rating_unavailable),color=AchatMuted,fontSize=12.sp)
            Button(onClick={},enabled=false,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.drama_rating_send))}
        }
    }
}

@Composable
private fun DramaSheetHeader(title:String,onClose:()->Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,color=AchatCyan,fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
        Box(Modifier.size(36.dp).clip(CircleShape).background(DramaGlass).clickable(onClick=onClose),contentAlignment=Alignment.Center) {
            Image(painterResource(R.drawable.template_subscription_close),stringResource(R.string.drama_close),Modifier.size(16.dp))
        }
    }
}

@Composable
private fun DramaOptionRow(title:String,badge:String,selected:Boolean,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if(selected)DramaGlass else Color(0xFF0F0C1E))
        .border(1.dp,if(selected)AchatCyan else Color.White.copy(alpha=.08f),RoundedCornerShape(16.dp)).clickable(onClick=onClick).padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,color=Color.White,fontWeight=if(selected)FontWeight.Bold else FontWeight.Normal,modifier=Modifier.weight(1f))
        Text(badge,color=if(selected)AchatCyan else AchatMuted,fontSize=11.sp,modifier=Modifier.background(DramaGlass,RoundedCornerShape(6.dp)).padding(6.dp))
    }
}

@Composable
private fun DramaGlassIcon(icon: Int, description: String, onClick: () -> Unit, tint: Color = Color.White) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0x990F172A))
        .border(1.dp, Color.White.copy(alpha = .1f), CircleShape).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun DramaDescriptionLine(description: String, expanded: Boolean = false, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = if (expanded) Alignment.Bottom else Alignment.CenterVertically) {
        Text(description.ifBlank { stringResource(R.string.drama_description_unavailable) }, color = Color(0xFFCBD5E1), fontSize = 12.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).then(Modifier.testTag("drama-description-line")))
        Text(stringResource(if (expanded) R.string.drama_less else R.string.drama_more), color = AchatCyan,
            fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onToggle).padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
    }
}

@Composable
@OptIn(UnstableApi::class)
internal fun DramaVideoSurface(controller: ShortDramaPlaybackController, modifier: Modifier = Modifier,
    seekPosition: Long = 0, seekSerial: Int = 0) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val media = controller.media ?: return
    val track = controller.grant?.subtitles?.firstOrNull { it.trackId == controller.selectedSubtitleId }
    val episodeId = controller.episode?.episodeId
    val authorizationId = controller.grant?.authorizationId
    val subtitleUrl = controller.subtitleMedia?.url
    val expiry = listOfNotNull(media.expiresAt, controller.grant?.expiresAt, controller.subtitleMedia?.expiresAt)
        .mapNotNull(::previewExpiryMillis).minOrNull() ?: 0L
    var unexpired by remember(media.url, expiry) { mutableStateOf(expiry > System.currentTimeMillis()) }
    LaunchedEffect(media.url, expiry) {
        unexpired = expiry > System.currentTimeMillis()
        if (unexpired) { delay((expiry - System.currentTimeMillis()).coerceAtLeast(1)); unexpired = false }
    }
    val player = remember(episodeId, authorizationId, media.url, subtitleUrl) {
        ExoPlayer.Builder(context).setMediaSourceFactory(TemplateVideoCache.mediaSourceFactory(context, cacheEnabled = false)).build().apply {
            val builder = MediaItem.Builder().setUri(media.url)
            if (track != null && subtitleUrl != null) builder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                .setMimeType(MimeTypes.TEXT_VTT).setLanguage(track.language).setLabel(track.displayName).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
            setMediaItem(builder.build(), controller.positionMs)
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
        }
    }
    var hasFrame by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { hasFrame = true }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && controller.media?.url == media.url && episodeId != null && authorizationId != null)
                    controller.episodeEnded(episodeId, authorizationId)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (controller.episode?.episodeId == episodeId && controller.media?.url == media.url && controller.grant?.authorizationId == authorizationId) controller.reportMediaError()
            }
        }
        player.addListener(listener)
        onDispose {
            if (controller.episode?.episodeId == episodeId && controller.media?.url == media.url && controller.grant?.authorizationId == authorizationId && player.playbackState == Player.STATE_READY)
                controller.updatePosition(player.currentPosition, player.duration)
            player.removeListener(listener); player.release()
        }
    }
    LaunchedEffect(player, controller.playing, controller.speed, controller.muted, resumed, unexpired) {
        player.playWhenReady = controller.playing && resumed && unexpired; player.setPlaybackSpeed(controller.speed); player.volume = if (controller.muted) 0f else 1f
    }
    LaunchedEffect(player, seekSerial) { if (seekSerial > 0) player.seekTo(seekPosition) }
    LaunchedEffect(player) {
        while (true) {
            if (player.playbackState == Player.STATE_READY && controller.episode?.episodeId == episodeId && controller.media?.url == media.url && controller.grant?.authorizationId == authorizationId)
                controller.updatePosition(player.currentPosition, player.duration)
            delay(250)
        }
    }
    AndroidView(factory = { PlayerView(it).apply {
        useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT); this.player = player
    } }, update = { it.player = player }, modifier = modifier.alpha(if (hasFrame) 1f else 0f).testTag(if (hasFrame) "drama-video-frame" else "drama-video-loading"))
}

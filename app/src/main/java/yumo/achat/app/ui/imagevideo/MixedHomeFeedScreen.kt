package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import yumo.achat.app.R
import yumo.achat.app.ui.theme.*
import yumo.achat.core.backend.*

internal fun HomeFeedItem.generationTemplate(): SelectedGenerationTemplate? {
    if (kind != "video_template" && kind != "image_template") return null
    if (fastPrice == null && qualityPrice == null) return null
    val modality = if (kind == "image_template") "image" else "video"
    val media = if (fileUrl.isBlank()) TemplatePreviewMedia.Unavailable else if (modality == "image") {
        TemplatePreviewMedia.RemoteImage(previewUrl.ifBlank { fileUrl })
    } else TemplatePreviewMedia.RemoteVideo(fileUrl, previewUrl.ifBlank { coverUrl })
    if (!media.isUsableGenerationMedia()) return null
    return SelectedGenerationTemplate(resourceId, modality, if (fastPrice != null) "fast" else "quality", title,
        media, durationSeconds.takeIf { it > 0 } ?: 5, "", "home", fastPrice ?: qualityPrice ?: 0, vip, catalogContext)
}

internal fun HomeFeedItem.visualTemplate() = VisualTemplate(resourceId, null, null, title, fileUrl,
    previewUrl.ifBlank { coverUrl }, if (kind == "image_template") "image/*" else "video/*",
    0, 0, durationSeconds, 0, fastPrice, qualityPrice, vip)

@Composable
internal fun ZorvHomeFeed(
    gateway: HomeFeedGateway, balance: Int, isVip: Boolean?, playbackEnabled: Boolean,
    onSettings: () -> Unit, onSubscription: () -> Unit, onBalance: () -> Unit,
    onNavigation: (Int) -> Unit, onUse: (SelectedGenerationTemplate?) -> Unit,
    onExpose: (SelectedGenerationTemplate, Int) -> Unit, statusContent: @Composable () -> Unit = {},
    visible: Boolean = true, onOpenDrama: (String) -> Unit,
    playbackViewModel: ShortDramaPlayerViewModel? = null, ownerKey: String = "", userId: String = "",
    onPreviewSubscription: () -> Unit = onSubscription,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(gateway, scope) { MixedHomeFeedController(gateway, scope) }
    DisposableEffect(controller) { controller.start(); onDispose { controller.close() } }
    var savedHomePage by remember { mutableIntStateOf(0) }
    if (!visible) return
    Column(Modifier.fillMaxSize().background(Color(0xFF070613)).statusBarsPadding().navigationBarsPadding()) {
        ZorvBrandHeader(balance, isVip, onSettings, onSubscription, onBalance)
        statusContent()
        if (controller.entries.isEmpty()) {
            HomeFeedPlaceholder(controller.loading || (!controller.error && !controller.exhausted), controller.error,
                if (controller.empty) controller::refresh else controller::retry, Modifier.weight(1f))
        } else {
            val pager = rememberPagerState(initialPage = savedHomePage.coerceIn(controller.entries.indices)) { controller.entries.size }
            LaunchedEffect(pager.currentPage) { savedHomePage = pager.currentPage }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val expose by rememberUpdatedState(onExpose)
            val entries = controller.entries
            val page = pager.currentPage.coerceIn(entries.indices)
            // Only template neighbors are prefetched. Preview grants/signatures stay out of the disk cache.
            val nearby = (page - 1..page + 1).filter { it in entries.indices && it != page }
                .map { entries[it].item }.filter { it.kind == "video_template" || it.kind == "image_template" }
            TemplateMediaPreloader(nearby.filter { it.kind == "video_template" && it.fileUrl.isNotBlank() }
                .map { TemplateVideoPreloadTarget(it.fileUrl, 256 * 1024L) }, nearby.map { it.coverUrl }.filter { it.isNotBlank() })
            val settled = entries.getOrNull(pager.settledPage)
            LaunchedEffect(settled?.key, lifecycle, controller) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    withFrameNanos { }
                    settled?.let {
                        controller.displayed(it.key)
                        if (yumo.achat.app.BuildConfig.DEBUG) {
                            val kind = it.item.kind.takeIf { value -> value in setOf("short_drama", "video_template", "image_template", "live_persona") } ?: "unknown"
                            android.util.Log.d("ZorvHomeFeed", "Displayed resource kind=$kind")
                        }
                        it.item.generationTemplate()?.let { template -> expose(template, pager.settledPage + 1) }
                    }
                    awaitCancellation()
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            VerticalPager(pager, key = { controller.entries[it].key }, pageSpacing = 16.dp,
                modifier = Modifier.fillMaxSize().testTag("zorv-home-pager")) { index ->
                val item = controller.entries[index].item
                val active = index == pager.currentPage
                when (item.kind) {
                    "video_template", "image_template" -> HomeFeaturedCard(item.visualTemplate(), active, playbackEnabled,
                        useEnabled = item.generationTemplate() != null) {
                        onUse(item.generationTemplate())
                    }
                    "short_drama" -> {
                        ShortDramaHomeCard(item,gateway,active,playbackEnabled,
                            onOpen={savedHomePage=index;onOpenDrama(item.resourceId)},onRefresh=controller::refresh,
                            isVip=isVip,onSubscribe=onPreviewSubscription)
                    }
                    else -> HomeOtherResourceCard(item, active, playbackEnabled)
                }
            }
            if (controller.error) TextButton(onClick = controller::retry, modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp)) {
                Text(stringResource(R.string.home_mixed_retry), color = AchatPink)
            }
            if (controller.exhausted) TextButton(onClick = controller::refresh, modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp)) {
                Text(stringResource(R.string.home_mixed_refresh), color = AchatCyan)
            }
            }
        }
        BottomNavigation(0, onNavigation, fullBleed = true)
    }
}

@Composable
private fun HomeOtherResourceCard(item: HomeFeedItem, active: Boolean, playbackEnabled: Boolean) {
    HomeFeedCardFrame {
        if (item.kind == "live_persona" && item.fileUrl.isNotBlank()) {
            TemplatePreviewImage(
                TemplatePreviewMedia.RemoteVideo(item.fileUrl, item.coverUrl),
                active && playbackEnabled,
                item.title,
                videoPlaybackEnabled = active,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AsyncImage(item.coverUrl, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC05060F)))))
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Text(item.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.me_coming_soon), color = AchatMuted)
        }
    }
}

@Composable
private fun HomeFeedPlaceholder(loading: Boolean, failed: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    HomeFeedCardFrame(modifier) {
        Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) CircularProgressIndicator(color = AchatCyan)
            Text(stringResource(when { loading -> R.string.home_feed_loading; failed -> R.string.home_mixed_load_error; else -> R.string.home_feed_empty }),
                color = Color.White, fontWeight = FontWeight.Bold)
            if (!loading) TextButton(onClick = onRetry) { Text(stringResource(R.string.template_feed_retry), color = AchatCyan) }
        }
    }
}

internal fun previewExpiryMillis(raw: String): Long? = yumo.achat.core.payment.paymentTime(raw)

internal suspend fun resolveDramaPreviewWithRetry(
    gateway: HomeFeedGateway,
    resolverUrl: String,
    maxAttempts: Int = 3,
    waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
): ResolvedDramaPreview {
    require(maxAttempts > 0)
    var lastFailure: Exception? = null
    repeat(maxAttempts) { attempt ->
        try {
            return gateway.resolveDramaPreview(resolverUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            lastFailure = error
            val temporary = error is HomeFeedServiceException && error.code == "MEDIA_TEMPORARILY_UNAVAILABLE"
            if (!temporary || attempt == maxAttempts - 1) throw error
            waitBeforeRetry(1_000)
        }
    }
    throw checkNotNull(lastFailure)
}

@Composable
internal fun ShortDramaHomeCard(item: HomeFeedItem, gateway: HomeFeedGateway, active: Boolean,
    playbackEnabled: Boolean, onOpen: () -> Unit, onRefresh: () -> Unit,
    fallbackController: ShortDramaPlaybackController? = null, playbackViewModel: ShortDramaPlayerViewModel? = null,
    onPreviewExpired: () -> Unit = {}, isVip: Boolean? = null, onSubscribe: () -> Unit = onOpen) {
    var expandedDetails by remember { mutableStateOf(false) }
    var finished by remember(item.id,item.preview) { mutableStateOf(false) }
    var resolved by remember(item.preview) { mutableStateOf<ResolvedDramaPreview?>(null) }
    var failed by remember(item.preview) { mutableStateOf(false) }
    var expired by remember(item.preview) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var progress by remember(item.id) { mutableFloatStateOf(0f) }
    var detail by remember(item.resourceId) { mutableStateOf<ShortDramaDetail?>(null) }
    var metadataFailed by remember(item.resourceId) { mutableStateOf(false) }
    LaunchedEffect(item.resourceId, active, isVip, retry) {
        if(active) {
            detail=null;metadataFailed=false
            try { detail=gateway.shortDramaDetail(item.resourceId) }
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){metadataFailed=true}
        }
    }
    LaunchedEffect(item.preview, active, retry) {
        resolved = null
        failed = false
        expired = false
        progress = 0f
        val preview = item.preview
        if (!active || preview == null) return@LaunchedEffect
        val grantExpiry = previewExpiryMillis(preview.expiresAt)
        if (grantExpiry == null || grantExpiry <= System.currentTimeMillis()) { expired = true; return@LaunchedEffect }
        try {
            val media = resolveDramaPreviewWithRetry(gateway, preview.resolverUrl)
            val until = minOf(grantExpiry, previewExpiryMillis(media.expiresAt) ?: 0L)
            if (until <= System.currentTimeMillis()) { expired = true; return@LaunchedEffect }
            resolved = media
            delay((until - System.currentTimeMillis()).coerceAtLeast(1))
            resolved = null
            expired = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { expired = error is HomeFeedServiceException && error.code == "GRANT_EXPIRED"; failed = !expired }
    }
    LaunchedEffect(expired, active) {
        if (expired && active) onPreviewExpired()
    }
    val metadata = detail
    val firstEpisode=metadata?.episodes?.minByOrNull { it.episodeNumber }
    val restricted=firstEpisode?.let { !it.canPlay && (it.vip || (it.amount ?: 0)>0) } == true
    val gate=previewGate(finished,firstEpisode,isVip)
    val description = metadata?.description?.takeIf { it.isNotBlank() } ?: item.description
    val actualProgress = progress
    HomeFeedCardFrame {
        AsyncImage(item.coverUrl, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        resolved?.let { media ->
            if(active && firstEpisode!=null && !failed && !finished) DramaPreviewSurface(media.url,playbackEnabled,restricted,Modifier.fillMaxSize(),
                onProgress={progress=it},onFinished={finished=true;progress=1f},onError={failed=true;resolved=null})
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66070613), Color.Transparent, Color(0xFF070613)))))
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .2f))
                .border(1.dp, Color.White.copy(alpha = .5f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(AchatPink, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.home_short_badge), color = Color.White, fontSize = 12.sp)
            }
            TemplateVipBadge(firstEpisode?.vip == true)
        }
        if(metadataFailed) TextButton(onClick={retry++},modifier=Modifier.align(Alignment.Center)) {
            Text(stringResource(R.string.drama_preview_rights_retry),color=AchatCyan)
        }
        if (failed || expired) TextButton(onClick = { if (expired) onRefresh() else retry++ }, modifier = Modifier.align(Alignment.Center)) {
            Text(stringResource(if (expired) R.string.drama_preview_expired else R.string.drama_preview_retry), color = AchatCyan)
        }
        if (finished && gate != DramaPreviewGate.Watch) {
            Column(Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE6070613)).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text(stringResource(when(gate){DramaPreviewGate.Subscribe->R.string.drama_preview_subscribe;DramaPreviewGate.Unlock->R.string.drama_preview_unlock;else->R.string.drama_price_unavailable}),color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold)
                if(gate==DramaPreviewGate.Unlock && firstEpisode?.amount!=null)
                    Text(stringResource(R.string.drama_confirm_unlock_body,firstEpisode.episodeNumber,firstEpisode.amount ?: 0),color=AchatCyan)
                TextButton(onClick={if(gate==DramaPreviewGate.Subscribe)onSubscribe() else onOpen()}) {
                    Text(stringResource(if(gate==DramaPreviewGate.Subscribe)R.string.drama_subscribe else if(gate==DramaPreviewGate.Unlock)R.string.drama_preview_unlock else R.string.drama_watch_full),color=AchatCyan)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            metadata?.tags?.firstOrNull()?.let { tag ->
                Text(tag, color = AchatPink, fontSize = 11.sp, modifier = Modifier.clip(CircleShape)
                    .background(AchatPink.copy(alpha = .2f)).border(1.dp, AchatPink.copy(alpha = .6f), CircleShape)
                    .padding(horizontal = 11.dp, vertical = 4.dp))
            }
            Text(item.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DramaDescriptionLine(description, expandedDetails) { expandedDetails = !expandedDetails }
            Row(Modifier.fillMaxWidth().height(54.dp).clip(CircleShape).background(Color(0xE6171433))
                .border(1.dp, AchatCyan.copy(alpha = .5f), CircleShape).clickable(role = Role.Button, onClick = {if(gate==DramaPreviewGate.Subscribe)onSubscribe() else onOpen()})
                .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Image(painterResource(R.drawable.ic_home_watch_drama), null, Modifier.size(20.dp))
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.drama_watch_full), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("›", color = AchatCyan, fontSize = 24.sp)
            }
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = .1f))) {
                Box(Modifier.fillMaxWidth(actualProgress.coerceIn(0f, 1f)).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(AchatCyan, Color(0xFFB026FF)))))
            }
        }
    }
}

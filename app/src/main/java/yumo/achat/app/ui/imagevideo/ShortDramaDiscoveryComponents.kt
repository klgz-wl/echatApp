package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collectLatest
import yumo.achat.app.R
import yumo.achat.app.ui.theme.*
import yumo.achat.core.backend.*

private val DiscoveryMagenta=Color(0xFFE024C3)
private val DiscoveryCyan=Color(0xFF00F2FE)

@Composable
internal fun DramaSectionHeader(title:String,onMore:()->Unit) {
    val first=title.substringBefore(' ')
    val emphasis=if(title.startsWith("Top 10"))"Top 10" else first
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color=DiscoveryMagenta)){append(emphasis)}
            withStyle(SpanStyle(color=Color.White)){append(title.removePrefix(emphasis))}
        },fontWeight=FontWeight.ExtraBold,fontSize=18.sp,modifier=Modifier.weight(1f))
        TextButton(onClick=onMore,contentPadding=PaddingValues(start=8.dp),modifier=Modifier.testTag("drama-more-$title")) {
            Text(stringResource(R.string.drama_discovery_more),color=DiscoveryCyan,fontSize=16.sp)
        }
    }
}

@Composable
internal fun DramaPoster(drama:ShortDramaSummary,modifier:Modifier,rank:Int?=null,onClick:()->Unit) {
    val shape=RoundedCornerShape(16.dp)
    val gold=Color(0xFFF5C84B)
    val accent=if(rank==1)gold else if(rank!=null)DiscoveryCyan else Color(0xFFA855F7)
    Box(modifier.aspectRatio(if(rank==null).65f else .67f)
        .shadow(if(rank!=null)8.dp else 0.dp,shape,clip=false,ambientColor=accent.copy(alpha=.25f),spotColor=accent.copy(alpha=.25f))
        .clip(shape).background(DramaGlass).border(1.dp,accent.copy(alpha=if(rank!=null).4f else .2f),shape)
        .clickable(enabled=drama.firstEpisodeId.isNotBlank(),role=Role.Button,onClick=onClick).testTag("drama-card-${drama.dramaId}")) {
        AsyncImage(drama.posterUrl,drama.title,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Transparent,Color(0xEE000000)))))
        if(rank!=null) {
            Row(Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(bottomEnd=12.dp))
                .background(Brush.horizontalGradient(if(rank==1)listOf(Color(0xFFFF4F68),Color(0xFFF5B42C)) else listOf(Color(0xFF168CE5),DiscoveryCyan)))
                .padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                Image(painterResource(R.drawable.subscription_small_crown),null,Modifier.size(10.dp))
                Text(stringResource(R.string.drama_rank_top,rank),color=Color.White,fontWeight=FontWeight.Bold,fontSize=10.sp)
            }
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(24.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFFFFE175),Color(0xFFE08B1A),Color(0xFF8C2A05))))
                .border(1.dp,Color(0xBFFFEB96),CircleShape),contentAlignment=Alignment.Center) {
                Image(painterResource(R.drawable.ic_short_rank_fire),null,Modifier.size(10.5.dp,11.45.dp))
            }
            Text(drama.title,color=if(rank==1)Color(0xFFFBCFE8) else Color(0xFFFEF08A),fontWeight=FontWeight.ExtraBold,
                fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center,
                modifier=Modifier.align(Alignment.BottomCenter).padding(horizontal=8.dp,vertical=10.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha=.8f))
                    .border(1.dp,Color.White.copy(alpha=.1f),RoundedCornerShape(12.dp)).padding(horizontal=9.dp,vertical=7.dp))
        } else Text(drama.title,color=Color(0xFFF9A8D4),fontWeight=FontWeight.ExtraBold,fontSize=10.sp,maxLines=2,
            textAlign=TextAlign.Center,overflow=TextOverflow.Ellipsis,modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
    }
}

@Composable
internal fun HistoryCard(entry:DramaHistoryEntry,modifier:Modifier,onClick:()->Unit) {
    Box(modifier.height(150.dp).clip(RoundedCornerShape(16.dp)).background(DramaGlass)
        .border(1.dp,Color(0xFFA855F7).copy(alpha=.2f),RoundedCornerShape(16.dp))
        .clickable(enabled=entry.available && entry.lastEpisodeId.isNotBlank(),role=Role.Button,onClick=onClick)) {
        AsyncImage(entry.posterUrl,entry.title,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color(0xF0000000)))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment=Alignment.Bottom) {
                Text(entry.title.ifBlank {stringResource(R.string.drama_details)},color=Color.White,fontWeight=FontWeight.Bold,fontSize=12.sp,maxLines=1,
                    overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                if(entry.available) Text(stringResource(R.string.drama_episode_pill,entry.episodeNumber),color=DiscoveryCyan,fontSize=11.sp,
                    modifier=Modifier.padding(start=8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha=.7f))
                        .border(1.dp,DiscoveryCyan.copy(alpha=.4f),RoundedCornerShape(6.dp)).padding(horizontal=9.dp,vertical=3.dp))
            }
            if(entry.episodeTitle.isNotBlank())Text(entry.episodeTitle,color=Color(0xFFCBD5E1),fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(!entry.available)Text(stringResource(when(entry.unavailableReason.uppercase()) {
                "CONTENT_UNAVAILABLE","DRAMA_UNAVAILABLE","NOT_FOUND"->R.string.drama_history_gone
                "EPISODE_UNAVAILABLE"->R.string.drama_history_episode_gone
                else->R.string.drama_history_rights
            }),color=Color(0xFF94A3B8),fontSize=10.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun DramaBanner(gateway:ShortDramaDiscoveryGateway,details:HomeFeedGateway,onOpen:(String,String?)->Unit,onSubscription:()->Unit,isVip:Boolean?) {
    var ads by remember(gateway){mutableStateOf<List<ShortDramaAd>>(emptyList())}
    var failed by remember(gateway){mutableStateOf(false)}
    var loading by remember(gateway){mutableStateOf(true)}
    var revision by remember{mutableIntStateOf(0)}
    var info by remember(gateway){mutableStateOf<ShortDramaAd?>(null)}
    LaunchedEffect(gateway,revision) {
        loading=true;failed=false
        try {ads=gateway.shortDramaAds().take(6)}catch(e:CancellationException){throw e}catch(_:Exception){failed=true}finally{loading=false}
    }
    if(loading){Box(Modifier.fillMaxWidth().height(457.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(color=DiscoveryCyan)};return}
    if(failed){TextButton(onClick={revision++}){Text(stringResource(R.string.drama_banner_retry),color=DiscoveryCyan)};return}
    if(ads.isEmpty())return
    val pager=rememberPagerState {ads.size}
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle){mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,_->resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
    LaunchedEffect(pager,resumed,ads,info) {
        if(resumed && info==null && ads.size>1) {
            snapshotFlow {Triple(pager.settledPage,pager.isScrollInProgress,dragged)}
                .filter {!it.second && !it.third}
                .collectLatest { settled ->
                    delay(2000)
                    if(!pager.isScrollInProgress && !dragged) pager.animateScrollToPage((settled.first+1)%ads.size)
                }
        }
    }
    val positionLabel=stringResource(R.string.drama_episode_progress,pager.settledPage+1,ads.size)
    HorizontalPager(pager,modifier=Modifier.fillMaxWidth().heightIn(min=457.dp).testTag("drama-banner-pager").semantics {stateDescription=positionLabel},key={ads[it].adId}) { index ->
        val ad=ads[index]
        var poster by remember(details,ad.dramaId){mutableStateOf("")}
        var posterTitle by remember(details,ad.dramaId){mutableStateOf("")}
        var posterFailed by remember(details,ad.dramaId){mutableStateOf(false)}
        var posterLoading by remember(details,ad.dramaId){mutableStateOf(true)}
        var posterReady by remember(details,ad.dramaId){mutableStateOf(false)}
        var posterRevision by remember(details,ad.dramaId){mutableIntStateOf(0)}
        LaunchedEffect(details,ad.dramaId,posterRevision) {
            posterLoading=true;posterFailed=false;posterReady=false
            try {val detail=details.shortDramaDetail(ad.dramaId);poster=detail.posterUrl;posterTitle=detail.title;posterFailed=poster.isBlank()}
            catch(e:CancellationException){throw e}catch(_:Exception){posterFailed=true}finally{posterLoading=false}
        }
        val shape=RoundedCornerShape(24.dp)
        Box(Modifier.fillMaxWidth().heightIn(min=457.dp).shadow(12.dp,shape,clip=false,ambientColor=Color(0xFF8B5CF6).copy(alpha=.18f),spotColor=Color(0xFF8B5CF6).copy(alpha=.18f))
            .clip(shape).background(Brush.verticalGradient(listOf(Color(0xE6191136),Color(0xF2100C22),Color(0xFF090714))))
            .border(1.dp,Color(0xFFA855F7).copy(alpha=.3f),shape)) {
            Column(Modifier.fillMaxWidth().heightIn(min=457.dp).padding(horizontal=20.dp).padding(top=8.dp,bottom=18.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
                    when {
                        posterLoading -> CircularProgressIndicator(color=DiscoveryCyan,modifier=Modifier.size(24.dp))
                        posterFailed -> TextButton(onClick={posterRevision++}) {
                            Text(stringResource(R.string.drama_banner_image_retry),color=DiscoveryCyan,fontSize=11.sp,textAlign=TextAlign.Center)
                        }
                        else -> key(posterRevision) {
                            AsyncImage(poster,posterTitle,Modifier.fillMaxSize()
                                .testTag(if(posterReady)"drama-banner-poster-ready" else "drama-banner-poster-loading"),
                                contentScale=ContentScale.Fit,onSuccess={posterReady=true},onError={posterFailed=true})
                        }
                    }
                }
                val breakAt=ad.title.indexOf('\n').takeIf {it>=0} ?: ad.title.indexOf(". ").takeIf {it>=0}?.plus(1) ?: ad.title.length
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color=Color.White)){append(ad.title.take(breakAt))}
                    withStyle(SpanStyle(color=Color(0xFFF472B6))){append(ad.title.drop(breakAt))}
                },fontSize=24.sp,lineHeight=30.sp,fontWeight=FontWeight.ExtraBold,maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center)
                Text(ad.copy,color=Color(0xFFCBD5E1).copy(alpha=.8f),fontSize=12.sp,lineHeight=19.sp,maxLines=3,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center,
                    modifier=Modifier.widthIn(max=240.dp).padding(top=8.dp,bottom=12.dp))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().heightIn(min=50.dp).clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFAB28D6),DiscoveryMagenta,Color(0xFF7928CA))))
                    .border(1.dp,Color(0xFFF9A8D4).copy(alpha=.4f),RoundedCornerShape(16.dp))
                    .clickable(role=Role.Button){if(isVip==true)onOpen(ad.dramaId,null) else onSubscription()}
                    .padding(horizontal=16.dp,vertical=15.dp),contentAlignment=Alignment.Center) {
                    Text(stringResource(R.string.drama_banner_upgrade),color=Color.White,fontSize=14.sp,fontWeight=FontWeight.ExtraBold,textAlign=TextAlign.Center)
                }
                Row(Modifier.padding(top=20.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                    ads.indices.forEach {i->Box(Modifier.size(if(i==pager.currentPage)24.dp else 6.dp,if(i==pager.currentPage)4.dp else 6.dp)
                        .background(if(i==pager.currentPage)DiscoveryCyan else Color.White.copy(alpha=.3f),CircleShape))}
                }
            }
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(28.dp)
                .testTag("drama-banner-info-${ad.adId}").clip(CircleShape).background(Color(0x990F172A),CircleShape).border(1.dp,Color(0x80334155),CircleShape)
                .clickable(role=Role.Button){info=ad},contentAlignment=Alignment.Center) {
                Image(painterResource(R.drawable.ic_short_banner_info),stringResource(R.string.drama_banner_details),Modifier.size(14.dp))
            }
        }
    }
    info?.let {ad -> AlertDialog(onDismissRequest={info=null},containerColor=DramaGlass,title={Text(ad.title,color=Color.White)},
        text={Text(ad.description.ifBlank{ad.copy},color=Color(0xFFCBD5E1))},confirmButton={TextButton(onClick={info=null}){Text(stringResource(R.string.drama_close),color=DiscoveryCyan)}}) }
}

package yumo.achat.app.ui.imagevideo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import yumo.achat.app.R
import yumo.achat.app.ui.theme.*
import yumo.achat.core.backend.*

@Composable
private fun <T> rememberDramaPage(owner: Any, key: String, identity: (T)->String,
    fetch: suspend (Int)->DramaPage<T>): DramaPageController<T> {
    val scope=rememberCoroutineScope()
    val currentFetch by rememberUpdatedState(fetch)
    val page=remember(owner,key) { DramaPageController(scope,identity) { currentFetch(it) } }
    DisposableEffect(page) { page.reload(); onDispose { page.close() } }
    return page
}

@Composable
internal fun ShortDramaDiscoveryScreen(gateway: ShortDramaDiscoveryGateway, balance: Int, isVip: Boolean?,
    onSettings: ()->Unit, onSubscription: ()->Unit, onBalance: ()->Unit, onNavigation: (Int)->Unit,
    onHistory: ()->Unit, onOpen: (String,String?)->Unit, detailGateway: HomeFeedGateway) {
    var destination by rememberSaveable { mutableStateOf("home") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("hot") }
    var tag by rememberSaveable { mutableStateOf("") }
    val hot=rememberDramaPage(gateway,"hot",ShortDramaSummary::dramaId) { gateway.shortDramas(1,10,"hot","") }
    val homeAll=rememberDramaPage(gateway,"home-all",ShortDramaSummary::dramaId) { gateway.shortDramas(it,20,"hot","") }
    val all=if(destination=="all") rememberDramaPage(gateway,"all:$sort:$tag",ShortDramaSummary::dramaId) { gateway.shortDramas(it,20,sort,tag) } else homeAll
    val history=rememberDramaPage(gateway,"history",DramaHistoryEntry::dramaId) { gateway.shortDramaHistory(it,20) }
    BackHandler(destination!="home") { destination="home" }
    val openAll = { destination="all" }
    val sectionState=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize().background(DramaBackground).statusBarsPadding().navigationBarsPadding().testTag("short-discovery")) {
        if(destination=="home") ZorvBrandHeader(balance,isVip,onSettings,onSubscription,onBalance)
        else Box(Modifier.padding(horizontal=16.dp,vertical=8.dp)) {
            MeDetailHeader(stringResource(if(destination=="rank") R.string.drama_top_ten else R.string.drama_all),{destination="home"},R.string.drama_back_short)
        }
        Box(Modifier.weight(1f)) { sectionState.SaveableStateProvider(destination) { LazyColumn(Modifier.fillMaxSize().testTag("drama-discovery-list"),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(destination=="home") {
                item { DramaBanner(gateway,detailGateway,onOpen,onSubscription,isVip) }
                item { DramaSectionHeader(stringResource(R.string.drama_continue),onHistory) }
                if(history.loading || history.failed || history.items.isEmpty()) item { DramaPageStatus(history) }
                if(history.items.isNotEmpty()) item {
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        items(history.items.take(6),key={it.dramaId}) { entry -> HistoryCard(entry,Modifier.width(255.dp)) {onOpen(entry.dramaId,entry.lastEpisodeId)} }
                    }
                }
            }
            if(destination!="all") {
                if(destination=="home") item { DramaSectionHeader(stringResource(R.string.drama_top_ten),{destination="rank"}) }
                if(hot.loading || hot.failed || hot.items.isEmpty()) item { DramaPageStatus(hot) }
                val ranked=if(destination=="home")hot.items.take(2) else visibleRanking(hot.items,expanded)
                ranked.chunked(2).forEachIndexed { rowIndex,row -> item(key="rank$rowIndex") {
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        row.forEachIndexed { i,d -> DramaPoster(d,Modifier.weight(1f),rank=rowIndex*2+i+1) {onOpen(d.dramaId,d.firstEpisodeId.takeIf(String::isNotBlank))} }
                        if(row.size==1) Spacer(Modifier.weight(1f))
                    }
                } }
                if(destination=="rank" && hot.items.size>4 && !expanded) item {
                    OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth().testTag("expand-top-ten")) {
                        Text(stringResource(R.string.drama_expand_top_ten),color=AchatCyan)
                    }
                }
                item(key="all-section") { DramaSectionHeader(stringResource(R.string.drama_all),openAll) }
            }
            if(destination=="all") item {
                val realTags=all.items.flatMap { it.tags }.distinct().sorted()
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected=sort=="hot" && tag.isEmpty(),onClick={sort="hot";tag=""},label={Text(stringResource(R.string.drama_all_series))}) }
                    item { FilterChip(selected=sort=="latest" && tag.isEmpty(),onClick={sort="latest";tag=""},label={Text(stringResource(R.string.drama_new))}) }
                    items((realTags+listOfNotNull(tag.takeIf(String::isNotEmpty))).distinct(),key={it}) { value ->
                        FilterChip(selected=tag==value,onClick={tag=value},label={Text(value)})
                    }
                }
            }
            if(all.loading || all.failed || all.items.isEmpty()) item { DramaPageStatus(all) }
            val displayed=if(destination=="home")all.items.take(3) else all.items
            displayed.chunked(3).forEachIndexed { index,row -> item(key="all$index") {
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    row.forEach { d -> DramaPoster(d,Modifier.weight(1f)) {onOpen(d.dramaId,d.firstEpisodeId.takeIf(String::isNotBlank))} }
                    repeat(3-row.size) {Spacer(Modifier.weight(1f))}
                }
            } }
            if(destination!="home" && all.hasMore && !all.loading && !all.failed) item {
                TextButton(onClick=all::more,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.drama_load_more),color=AchatCyan)}
            }
            item { TextButton(onClick={hot.reload(); all.reload(); history.reload()},modifier=Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_mixed_refresh),color=AchatCyan)
            } }
        }
        } }
        BottomNavigation(4,onNavigation,fullBleed=true)
    }
}

@Composable
internal fun DramaHistoryScreen(gateway: ShortDramaDiscoveryGateway,onBack: ()->Unit,onOpen: (String,String?)->Unit) {
    BackHandler(onBack=onBack)
    val page=rememberDramaPage(gateway,"complete-history",DramaHistoryEntry::dramaId) { gateway.shortDramaHistory(it,20) }
    Column(Modifier.fillMaxSize().background(DramaBackground).statusBarsPadding().navigationBarsPadding().padding(16.dp).testTag("drama-history")) {
        MeDetailHeader(stringResource(R.string.me_watch_history),onBack)
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(vertical=16.dp)) {
            item { DramaPageStatus(page) }
            items(page.items,key={it.dramaId}) { entry -> HistoryCard(entry,Modifier.fillMaxWidth()) {onOpen(entry.dramaId,entry.lastEpisodeId)} }
            if(page.hasMore && !page.loading && !page.failed) item { TextButton(onClick=page::more,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.drama_load_more),color=AchatCyan)} }
            item { TextButton(onClick=page::reload) {Text(stringResource(R.string.home_mixed_refresh),color=AchatCyan)} }
        }
    }
}

@Composable
private fun <T> DramaPageStatus(page: DramaPageController<T>) {
    if(page.loading) Box(Modifier.fillMaxWidth().padding(12.dp),contentAlignment=Alignment.Center) {CircularProgressIndicator(color=AchatCyan)}
    else if(page.failed) Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
        Text(stringResource(R.string.drama_discovery_failed),color=AchatMuted)
        TextButton(onClick=page::more) {Text(stringResource(R.string.template_feed_retry),color=AchatCyan)}
    } else if(page.items.isEmpty()) Text(stringResource(R.string.drama_discovery_empty),color=AchatMuted,modifier=Modifier.padding(12.dp))
}

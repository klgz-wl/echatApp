package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yumo.achat.app.R
import yumo.achat.core.backend.*

private val EpisodeCyan=Color(0xFF00F2FE)
private val EpisodePurple=Color(0xFFB026FF)
private val EpisodeFont=FontFamily(Font(R.font.orbitron_bold,FontWeight.Bold))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DramaEpisodesSheet(detail:ShortDramaDetail?,currentEpisodeId:String?,playing:Boolean,
    onSelect:(String)->Unit,onDismiss:()->Unit) {
    val episodes=remember(detail?.episodes){detail?.episodes.orEmpty().sortedBy {it.episodeNumber}}
    val index=episodes.indexOfFirst {it.episodeId==currentEpisodeId}
    val total=maxOf(detail?.episodeCount ?: 0,episodes.size)
    val ordinal=if(index>=0)index+1 else 0
    val progress=if(total>0)ordinal.toFloat()/total else 0f
    val shape=RoundedCornerShape(topStart=32.dp,topEnd=32.dp)
    val grid=rememberLazyGridState(initialFirstVisibleItemIndex=(index.coerceAtLeast(0)/4)*4)
    ModalBottomSheet(onDismissRequest=onDismiss,shape=shape,
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=Color(0xF20B0B18),contentColor=Color.White,scrimColor=Color.Black.copy(alpha=.45f),
        dragHandle=null) {
        Column(Modifier.fillMaxWidth().testTag("drama-episodes-sheet").drawWithCache {
            val stroke=1.dp.toPx()
            val edge=stroke/2
            val radius=32.dp.toPx().coerceAtMost(size.width/2)
            val outline=Path().apply {
                moveTo(edge,size.height)
                lineTo(edge,radius)
                quadraticBezierTo(edge,edge,radius,edge)
                lineTo(size.width-radius,edge)
                quadraticBezierTo(size.width-edge,edge,size.width-edge,radius)
                lineTo(size.width-edge,size.height)
            }
            onDrawWithContent {
                drawContent()
                drawPath(outline,EpisodeCyan.copy(alpha=.3f),style=Stroke(width=stroke))
            }
        }) {
            Box(Modifier.fillMaxWidth().padding(top=15.dp,bottom=8.dp),contentAlignment=Alignment.Center) {
                Box(Modifier.size(48.dp,4.dp).shadow(6.dp,CircleShape,ambientColor=EpisodeCyan,spotColor=EpisodePurple)
                    .clip(CircleShape).background(Brush.horizontalGradient(listOf(EpisodeCyan.copy(alpha=.4f),EpisodePurple.copy(alpha=.6f),EpisodeCyan.copy(alpha=.4f)))))
            }
        val closeLabel=stringResource(R.string.drama_episode_close)
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,top=4.dp,bottom=12.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(8.dp).rotate(45f).background(EpisodeCyan))
                    Text(stringResource(R.string.drama_episodes),fontFamily=EpisodeFont,fontSize=20.sp,color=Color.White)
                }
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.drama_episode_progress,ordinal,total),color=Color(0xFF94A3B8),fontSize=12.sp,fontFamily=EpisodeFont)
                    Text(stringResource(if(playing)R.string.drama_episode_playing else R.string.drama_episode_selected).uppercase(),
                        color=EpisodePurple,fontSize=10.sp,fontFamily=EpisodeFont,letterSpacing=1.sp,
                        modifier=Modifier.clip(CircleShape).background(EpisodePurple.copy(alpha=.2f))
                            .border(1.dp,EpisodePurple.copy(alpha=.4f),CircleShape).padding(horizontal=9.dp,vertical=3.dp))
                }
            }
            Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha=.05f))
                .border(1.dp,Color.White.copy(alpha=.1f),CircleShape)
                .clickable(role=Role.Button,onClick=onDismiss).semantics {contentDescription=closeLabel},contentAlignment=Alignment.Center) {
                Image(painterResource(R.drawable.ic_drama_episode_close),null,Modifier.size(16.dp))
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=16.dp)) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha=.1f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f,1f)).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(EpisodeCyan,EpisodePurple))))
            }
        }
        if(episodes.isEmpty()) Text(stringResource(R.string.drama_episode_empty),color=Color(0xFF94A3B8),modifier=Modifier.padding(20.dp))
        else {
            val rows=(episodes.size+3)/4
            val height=(rows*81+(rows-1)*10+8).coerceAtMost(460).dp
            LazyVerticalGrid(columns=GridCells.Fixed(4),state=grid,
                modifier=Modifier.fillMaxWidth().height(height).padding(horizontal=16.dp),
                contentPadding=PaddingValues(bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                items(episodes,key={it.episodeId}) { episode ->
                    DramaEpisodeTile(episode,episode.episodeId==currentEpisodeId,playing) {onSelect(episode.episodeId)}
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DramaEpisodeTile(episode:ShortDramaEpisode,selected:Boolean,playing:Boolean,onClick:()->Unit) {
    val shape=RoundedCornerShape(16.dp)
    Column(Modifier.height(81.dp).fillMaxWidth()
        .shadow(if(selected)8.dp else 0.dp,shape,clip=false,ambientColor=EpisodeCyan.copy(alpha=.4f),spotColor=EpisodeCyan.copy(alpha=.4f))
        .clip(shape).background(if(selected)Brush.verticalGradient(listOf(Color(0xFF131233),Color(0xFF0C0C20))) else Brush.verticalGradient(listOf(Color(0xCC0D0D22),Color(0xCC0D0D22))))
        .border(if(selected)2.dp else 1.dp,if(selected)EpisodeCyan else Color.White.copy(alpha=.1f),shape)
        .selectable(selected=selected,role=Role.Button,onClick=onClick).testTag("drama-episode-${episode.episodeId}")
        .padding(horizontal=9.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth().height(14.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            if(selected && playing && episode.canPlay) Image(painterResource(R.drawable.home_use_wave),null,Modifier.size(13.dp,12.dp),colorFilter=ColorFilter.tint(EpisodeCyan))
            else Spacer(Modifier.width(13.dp))
            if(episode.vip) Text(stringResource(R.string.me_vip_label),fontSize=9.sp,fontWeight=FontWeight.Bold,
                style=TextStyle(brush=Brush.horizontalGradient(listOf(EpisodeCyan,Color(0xFFF472B6),Color(0xFFFF007F)))))
        }
        Text(episode.episodeNumber.toString(),color=if(selected)Color.White else Color(0xFFE2E8F0),fontFamily=EpisodeFont,fontSize=20.sp,lineHeight=20.sp)
        when {
            episode.canPlay && episode.amount==0 && !episode.vip -> Text(stringResource(R.string.drama_free),color=if(selected)EpisodeCyan else Color(0xFF94A3B8),fontSize=11.sp)
            episode.canPlay -> Text(stringResource(R.string.drama_episode_unlocked),color=if(selected)EpisodeCyan else Color(0xFF94A3B8),fontSize=11.sp,maxLines=1)
            (episode.amount ?: 0)>0 -> {
                val price=stringResource(R.string.drama_coin_price,episode.amount!!)
                Row(Modifier.clearAndSetSemantics {contentDescription=price},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(episode.amount.toString(),color=Color(0xFFCBD5E1),fontFamily=EpisodeFont,fontSize=11.sp)
                    Image(painterResource(R.drawable.ic_me_diamond),null,Modifier.size(10.dp))
                }
            }
            else -> Text(stringResource(if(episode.vip)R.string.drama_subscribe else R.string.drama_episode_unavailable),color=Color(0xFF94A3B8),fontSize=10.sp,maxLines=1)
        }
    }
}

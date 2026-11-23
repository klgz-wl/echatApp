package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import yumo.achat.app.R
import yumo.achat.app.ui.theme.*
import yumo.achat.core.backend.*

@Composable
internal fun DramaPaidEpisodeGate(detail:ShortDramaDetail?,episode:ShortDramaEpisode,priceChanged:Boolean,
    onWatch:()->Unit,onClose:()->Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xB305040C),Color(0xD9090717),Color(0xFF070611))))
        .statusBarsPadding().navigationBarsPadding().testTag("drama-paid-episode-gate"),contentAlignment=Alignment.Center) {
        val shape=RoundedCornerShape(32.dp)
        Column(Modifier.padding(horizontal=16.dp,vertical=24.dp).widthIn(max=380.dp).fillMaxWidth()
            .shadow(18.dp,shape,clip=false,ambientColor=AchatCyan.copy(alpha=.18f),spotColor=DramaGateStart.copy(alpha=.3f))
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF109BAE),Color(0xFF443274),Color(0xFF9D2AB9))))
            .border(1.dp,Brush.linearGradient(listOf(AchatCyan.copy(alpha=.7f),DramaGateEnd.copy(alpha=.35f),DramaGateStart.copy(alpha=.75f))),shape)
            .verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Row(Modifier.weight(1f,fill=false).clip(CircleShape).background(Color.Black.copy(alpha=.6f))
                    .border(1.dp,DramaGateEnd.copy(alpha=.5f),CircleShape).padding(horizontal=13.dp,vertical=7.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Image(painterResource(R.drawable.ic_drama_paid_lock),null,Modifier.size(14.dp))
                    Text(stringResource(R.string.drama_paid_badge),color=Color(0xFFF1F5F9),fontSize=9.sp,letterSpacing=1.1.sp)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha=.5f))
                    .border(1.dp,Color.White.copy(alpha=.15f),CircleShape).clickable(role=Role.Button,onClick=onClose),contentAlignment=Alignment.Center) {
                    Image(painterResource(R.drawable.ic_drama_episode_close),stringResource(R.string.drama_paid_close),Modifier.size(16.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(182.dp).clip(RoundedCornerShape(16.dp))
                .background(DramaBackground).border(1.dp,Color.White.copy(alpha=.1f),RoundedCornerShape(16.dp))) {
                AsyncImage(detail?.posterUrl,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color(0x990E0C1F)))))
            }
            Column(Modifier.padding(top=6.dp,bottom=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(6.dp).background(DramaGateStart,CircleShape))
                    Text(stringResource(R.string.drama_paid_episode,episode.episodeNumber),color=DramaGateStart,fontSize=12.sp,fontWeight=FontWeight.Bold,letterSpacing=.6.sp)
                }
                Text(stringResource(R.string.drama_paid_title),color=Color.White,fontSize=24.sp,lineHeight=32.sp,fontWeight=FontWeight.ExtraBold)
                Text(stringResource(R.string.drama_paid_description),color=Color(0xCCCBD5E1),fontSize=14.sp,lineHeight=23.sp)
                if(priceChanged)Text(stringResource(R.string.drama_paid_price_changed),color=Color.White,fontSize=12.sp)
            }
            val buttonShape=RoundedCornerShape(16.dp)
            Row(Modifier.fillMaxWidth().shadow(12.dp,buttonShape,ambientColor=DramaGateStart,spotColor=DramaGateEnd)
                .clip(buttonShape).background(Brush.horizontalGradient(listOf(DramaGateStart,Color(0xFF9333EA),DramaGateEnd)))
                .border(1.dp,Color(0xFFD5D5D5),buttonShape).clickable(role=Role.Button,onClick=onWatch)
                .padding(horizontal=12.dp,vertical=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                Text(stringResource(R.string.drama_paid_watch),color=Color.White,fontSize=16.sp,fontWeight=FontWeight.Bold,textAlign=TextAlign.Center,modifier=Modifier.weight(1f,fill=false))
                Spacer(Modifier.width(10.dp))
                Image(painterResource(R.drawable.ic_me_diamond),null,Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(episode.amount.toString(),color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
            }
        }
    }
}

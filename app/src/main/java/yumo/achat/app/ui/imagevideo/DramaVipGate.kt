package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yumo.achat.app.R
import yumo.achat.app.ui.theme.*

/** Rights are owned by the retained player; this surface only opens its subscription route. */
@Composable
internal fun DramaVipGate(onSubscription:()->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("drama-player-vip-gate")) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color(0xCC080711),Color(0x4D080711),Color(0xE6080711)))))
        Box(Modifier.align(Alignment.TopStart).offset(x=(-80).dp,y=(-96).dp)
            .size(288.dp).background(Brush.radialGradient(listOf(Color(0x3306B6D4),Color.Transparent)),CircleShape))
        Box(Modifier.align(Alignment.CenterEnd).offset(x=96.dp,y=160.dp)
            .size(320.dp).background(Brush.radialGradient(listOf(DramaGateMiddle.copy(alpha=.25f),Color.Transparent)),CircleShape))
        Box(Modifier.fillMaxWidth().height(maxHeight*.924f),contentAlignment=Alignment.Center) {
            Box(Modifier.padding(horizontal=28.dp).widthIn(max=320.dp).fillMaxWidth()
                .heightIn(min=54.dp)
                .shadow(24.dp,CircleShape,clip=false,
                    ambientColor=DramaGateStart.copy(alpha=.55f),spotColor=DramaGateStart.copy(alpha=.55f))
                .clip(CircleShape).background(Brush.horizontalGradient(listOf(DramaGateStart,DramaGateMiddle,DramaGateEnd)))
                .border(1.dp,DramaGateBorder,CircleShape)
                .clickable(role=Role.Button,onClick=onSubscription)
                .padding(horizontal=16.dp,vertical=13.dp),contentAlignment=Alignment.Center) {
                Text(stringResource(R.string.drama_player_vip_subscribe),color=Color.White,
                    fontSize=16.sp,lineHeight=28.sp,fontWeight=FontWeight.ExtraBold,textAlign=TextAlign.Center)
            }
        }
    }
}

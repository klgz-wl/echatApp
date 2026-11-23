package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yumo.achat.app.R

@Composable
internal fun DramaAudioButton(muted:Boolean,onClick:()->Unit) {
    val description=stringResource(if(muted)R.string.drama_unmute else R.string.drama_mute)
    val status=stringResource(if(muted)R.string.drama_sound_muted else R.string.drama_sound_on)
    Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0x990F172A))
        .border(1.dp,Color.White.copy(alpha=.1f),CircleShape).clickable(role=Role.Button,onClick=onClick)
        .semantics {contentDescription=description;stateDescription=status},contentAlignment=Alignment.Center) {
        Icon(painterResource(if(muted)R.drawable.ic_drama_audio_muted else R.drawable.ic_drama_audio),null,
            tint=Color(0xFFCFD1D4),modifier=Modifier.size(16.dp).testTag(if(muted)"drama-audio-muted" else "drama-audio-on"))
    }
}

@Composable
internal fun DramaSubtitleButton(active:Boolean,selection:String,onClick:()->Unit) {
    val description=stringResource(R.string.drama_subtitles)
    val accent=if(active)Color(0xFF00F2FE) else Color(0xFFD946EF)
    Box(Modifier.size(32.dp).shadow(if(active)8.dp else 0.dp,CircleShape,clip=false,ambientColor=accent,spotColor=accent)
        .clip(CircleShape).background(if(active)Color(0xFF002A32) else Color(0x990F172A))
        .border(1.dp,accent.copy(alpha=if(active).65f else .3f),CircleShape)
        .clickable(role=Role.Button,onClick=onClick)
        .semantics {contentDescription=description;selected=active;stateDescription=selection},contentAlignment=Alignment.Center) {
        Text(stringResource(R.string.drama_language_button),color=if(active)accent else Color(0xFFF0ABFC),fontSize=11.sp,fontWeight=FontWeight.Bold)
    }
}

package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import yumo.achat.app.R
import yumo.achat.app.ui.theme.*

@Composable
internal fun PlaybackTransportButton(playing: Boolean, modifier:Modifier=Modifier, onClick: () -> Unit) {
    Box(modifier.size(72.dp).clip(CircleShape).background(Color(0xCC020617))
        .border(2.dp, DramaGateStart, CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().blur(12.dp).background(Brush.linearGradient(listOf(
            Color(0x4DD946EF), Color(0x339333EA), Color(0x4D06B6D4)))))
        Icon(painterResource(if (playing) R.drawable.ic_drama_pause else R.drawable.ic_drama_play),
            stringResource(if (playing) R.string.drama_pause else R.string.drama_play),
            tint = Color.White,
            modifier = Modifier.offset(x = if (playing) 0.dp else 2.dp)
                .size(width = if (playing) 16.dp else 14.6667.dp, height = 18.6667.dp))
    }
}

/** Thin visual track with the full Material seek gesture and accessibility surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackSeekControl(value:Float,onValueChange:(Float)->Unit,onValueChangeFinished:()->Unit,
    enabled:Boolean,modifier:Modifier=Modifier,trackTag:String) {
    Slider(value=value,onValueChange=onValueChange,onValueChangeFinished=onValueChangeFinished,
        enabled=enabled,modifier=modifier,thumb={},track={slider->
            Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape)
                .background(Color.White.copy(alpha=.1f)).testTag(trackTag)) {
                Box(Modifier.fillMaxWidth(slider.value.coerceIn(0f,1f)).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(AchatCyan,Color(0xFFB026FF)))))
            }
        })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackSpeedSheet(speed:Float,onSelect:(Float)->Unit,onDismiss:()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=DramaBackground) {
        DramaSheetHeader(stringResource(R.string.drama_speed),onDismiss)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(.5f,.75f,1f,1.25f,1.5f,2f).forEach {value->
                Box(Modifier.testTag("playback-speed-$value").semantics {selected=speed==value}) {
                    DramaOptionRow(if(value==1f)stringResource(R.string.drama_speed_normal) else "${value}×",
                        "${value}×",speed==value){onSelect(value)}
                }
            }
        }
    }
}

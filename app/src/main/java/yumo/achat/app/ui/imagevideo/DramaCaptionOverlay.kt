package yumo.achat.app.ui.imagevideo

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import androidx.annotation.OptIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.ensureActive

/** One state per exact player/media/subtitle ownership; old callbacks update only their old state. */
@OptIn(UnstableApi::class)
internal class DramaCaptionState {
    var cues by mutableStateOf<List<Cue>>(emptyList())
}

@OptIn(UnstableApi::class)
@Composable
internal fun DramaCaptionOverlay(state:DramaCaptionState,modifier:Modifier=Modifier) {
    val cues=state.cues
    if(cues.isEmpty())return
    AndroidView(factory={context->SubtitleView(context).apply {
        setFixedTextSize(TypedValue.COMPLEX_UNIT_SP,18f)
        setApplyEmbeddedFontSizes(false)
        setApplyEmbeddedStyles(false)
        setStyle(CaptionStyleCompat(Color.WHITE,Color.TRANSPARENT,Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,Color.BLACK,Typeface.DEFAULT_BOLD))
    }},update={it.setCues(cues)},modifier=modifier.testTag("drama-subtitle-overlay")
        .semantics {contentDescription=cues.mapNotNull {it.text?.toString()}.joinToString("\n")})
}

/** Native WebVTT cue intervals, selected using the video clock rather than download completion time. */
@OptIn(UnstableApi::class)
internal class DramaCaptionTimeline(private val groups:List<androidx.media3.extractor.text.CuesWithTiming>) {
    fun at(positionMs:Long,durationMs:Long,ended:Boolean):List<Cue> {
        if(ended || (durationMs>0 && positionMs>=durationMs))return emptyList()
        val positionUs=positionMs*1000
        return groups.filter {positionUs>=it.startTimeUs && positionUs<it.endTimeUs}.flatMap {it.cues}
    }
}

@OptIn(UnstableApi::class)
internal suspend fun parseDramaCaptionTimeline(bytes:ByteArray):DramaCaptionTimeline=
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val groups=mutableListOf<androidx.media3.extractor.text.CuesWithTiming>()
        val parseContext=kotlin.coroutines.coroutineContext
        androidx.media3.extractor.text.webvtt.WebvttParser().parse(bytes,0,bytes.size,
            androidx.media3.extractor.text.SubtitleParser.OutputOptions.allCues()) {
                parseContext.ensureActive()
                groups.add(it)
            }
        DramaCaptionTimeline(groups)
    }

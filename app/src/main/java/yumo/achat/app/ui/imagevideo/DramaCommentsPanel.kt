package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yumo.achat.app.R
import yumo.achat.app.ui.theme.*

/** Deliberately local demo content: no repository, network or durable account storage. */
@Composable
internal fun DramaCommentsPanel(comments: List<String>, draft: String, onDraftChange: (String)->Unit,
    onSend: ()->Unit, onClose: ()->Unit) {
    val samples=listOf(stringResource(R.string.drama_comment_sample_one),
        stringResource(R.string.drama_comment_sample_two),stringResource(R.string.drama_comment_sample_three))
    val listState=rememberLazyListState()
    LaunchedEffect(comments.size) {if(comments.isNotEmpty())listState.scrollToItem(0)}
    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal=20.dp,vertical=12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(R.string.drama_comments_count,comments.size+samples.size),color=Color.White,
                fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
            Box(Modifier.size(40.dp).clip(CircleShape).background(DramaGlass).clickable(onClick=onClose),contentAlignment=Alignment.Center) {
                Image(painterResource(R.drawable.template_subscription_close),stringResource(R.string.drama_comment_close),Modifier.size(16.dp))
            }
        }
        Text(stringResource(R.string.drama_comments_demo_notice),color=AchatMuted,fontSize=12.sp,modifier=Modifier.padding(vertical=12.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).heightIn(max=340.dp),state=listState,verticalArrangement=Arrangement.spacedBy(16.dp),
            contentPadding=PaddingValues(vertical=8.dp)) {
            itemsIndexed(comments) { _,body -> DemoComment(stringResource(R.string.drama_comment_you),body) }
            itemsIndexed(samples) { index,body -> DemoComment(stringResource(R.string.drama_comment_viewer,index+1),body) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value=draft,onValueChange=onDraftChange,placeholder={Text(stringResource(R.string.drama_comment_hint))},
                maxLines=3,modifier=Modifier.weight(1f).testTag("drama-comment-input"))
            Button(onClick=onSend,enabled=draft.isNotBlank(),modifier=Modifier.testTag("drama-comment-send"),
                colors=ButtonDefaults.buttonColors(containerColor=AchatPink)) {Text(stringResource(R.string.drama_comment_send))}
        }
    }
}

@Composable
private fun DemoComment(author:String,body:String) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(author,color=AchatCyan,fontSize=12.sp,fontWeight=FontWeight.Bold)
        Text(body,color=Color.White,fontSize=14.sp)
        HorizontalDivider(color=Color.White.copy(alpha=.08f),modifier=Modifier.padding(top=8.dp))
    }
}

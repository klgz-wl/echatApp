package com.vexora.app.ui.generation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.vexora.app.R
import com.vexora.app.ui.catalog.*
import com.vexora.app.ui.components.*
import com.vexora.app.ui.theme.*
import com.vexora.core.catalog.TemplateMedia
import com.vexora.core.visual.*
import kotlinx.serialization.Serializable

@Serializable data class ProfilePreview(val nameResource: String, val avatarResource: String)

@Composable
fun ProfileScreen(state: VisualLibraryState, profile: ProfilePreview, balance: String, purchase: () -> Unit, settings: () -> Unit,
    create: () -> Unit, refresh: () -> Unit, more: () -> Unit, filter: (String?) -> Unit,
    task: (GenerationRequest) -> Unit, resource: (VisualResource) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val pending = state.requests.filter { request ->
        (state.modality == null || request.template.mediaKind.name.lowercase(java.util.Locale.ROOT) == state.modality) &&
            (request.task?.status != "succeeded" || state.resources.none { it.taskId == request.task?.taskId })
    }.asReversed()
    Box(Modifier.fillMaxSize().background(Design.Background)) {
        Image(painterResource(R.drawable.page_background), null, Modifier.fillMaxSize(), alpha = Design.PageBackgroundAlpha, contentScale = ContentScale.Crop)
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Design.Page.xdp),
            horizontalArrangement = Arrangement.spacedBy(Design.CatalogGridGap.xdp), verticalArrangement = Arrangement.spacedBy(Design.CatalogGridGap.xdp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(top = Design.ProfileHeaderTop.xdp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.profile_title), fontSize = Design.Title.xsp, modifier = Modifier.weight(1f).padding(start = Design.Page.xdp))
                        IconButton(settings, Modifier.size(Design.Touch.xdp)) {
                            Box(Modifier.size(Design.BackSize.xdp).background(Design.Card, CircleShape), contentAlignment = Alignment.Center) {
                                Image(painterResource(R.drawable.ic_profile_settings), stringResource(R.string.settings), Modifier.size(Design.Icon.xdp))
                            }
                        }
                    }
                    Row(Modifier.padding(top = Design.ProfileInfoTop.xdp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Design.ProfileInfoGap.xdp)) {
                        TemplateImage(TemplateMedia(profile.avatarResource, Design.ProfileAvatar, Design.ProfileAvatar),
                            Modifier.size(Design.ProfileAvatar.xdp).clip(CircleShape))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Design.ProfileTextGap.xdp)) {
                            Text(CatalogText(profile.nameResource), fontSize = Design.Title.xsp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.SmallGap.xdp)) {
                                Image(painterResource(R.drawable.ic_coin), null, Modifier.size(Design.RecordMargin.xdp))
                                Text(balance, color = Design.Muted, fontSize = Design.Body.xsp)
                            }
                            Box(Modifier.fillMaxWidth().height(Design.Touch.xdp).clickable(onClick = purchase), contentAlignment = Alignment.Center) {
                                Box(Modifier.fillMaxWidth().height(Design.ProfilePurchaseHeight.xdp).background(Design.Blue, CircleShape), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.purchase), fontSize = Design.ProductLabel.xsp)
                                }
                            }
                        }
                    }
                    Box(Modifier.padding(top = Design.CreationsTop.xdp)) {
                        Row(Modifier.size(Design.CreationsPillWidth.xdp, Design.CreationsPillHeight.xdp)
                            .background(Design.White, CircleShape).clickable { menu = true }, verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Design.StepGap.xdp, Alignment.CenterHorizontally)) {
                            Image(painterResource(R.drawable.creations_logo), null, Modifier.size(Design.CoinIcon.xdp))
                            Text(stringResource(R.string.creations), color = Design.Background, fontSize = Design.Tiny.xsp)
                        }
                        DropdownMenu(menu, { menu = false }) {
                            listOf(null to R.string.filter_all, "video" to R.string.filter_video, "image" to R.string.filter_image).forEach { (kind, label) ->
                                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { menu = false; filter(kind) })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.refresh)) }, onClick = { menu = false; refresh() })
                        }
                    }
                    Text(stringResource(R.string.creations_note), color = Design.Muted, fontSize = Design.FieldHint.xsp, modifier = Modifier.padding(vertical = Design.ProductLabel.xdp))
                    if (state.pollingFailed) Text(stringResource(R.string.task_poll_error), color = Design.Error, fontSize = Design.FieldHint.xsp)
                }
            }
            if (state.loading) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            if (state.failed) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(stringResource(R.string.library_error)); TextButton(refresh) { Text(stringResource(R.string.retry)) } }
            }
            if (!state.loading && !state.failed && state.resources.isEmpty() && pending.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(top = Design.CreationsEmptyTop.xdp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.creations_empty), fontSize = Design.Icon.xsp)
                    Spacer(Modifier.height(Design.PhotoTitleGap.xdp))
                    WhiteAction(stringResource(R.string.create_now), create, Modifier.width(Design.PurchaseWidth.xdp))
                }
            }
            items(pending, key = { "request:" + it.key }) { request ->
                Box(Modifier.fillMaxWidth().height(Design.CreationCardHeight.xdp).clip(RoundedCornerShape(Design.CreationCardRadius.xdp)).clickable { task(request) }) {
                    TemplateImage(request.template.preview, Modifier.fillMaxSize())
                    if (request.task?.status == "processing") {
                        Text(stringResource(R.string.creation_processing),
                            modifier = Modifier.align(AbsoluteAlignment.TopRight).padding(Design.SmallGap.xdp)
                                .background(Design.MediaBadgeSurface, RoundedCornerShape(Design.BadgeRadius.xdp))
                                .padding(horizontal = Design.BadgeHorizontal.xdp, vertical = Design.BadgeVertical.xdp),
                            color = Design.White, fontSize = Design.FieldHint.xsp)
                    } else if (request.task?.status != "succeeded") Column(Modifier.fillMaxSize().background(Design.Overlay).padding(Design.Page.xdp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text(TaskLabel(request.task), textAlign = TextAlign.Center, fontSize = Design.ProductLabel.xsp)
                    }
                }
            }
            items(state.resources, key = { "resource:" + it.id }) { item ->
                Box(Modifier.fillMaxWidth().height(Design.CreationCardHeight.xdp).clip(RoundedCornerShape(Design.CreationCardRadius.xdp)).clickable { resource(item) }) {
                    TemplateImage(TemplateMedia(item.thumbnailUrl?.takeIf { it.isNotBlank() } ?: item.url, item.width, item.height, true, item.mimeType), Modifier.fillMaxSize())
                    if (item.modality == "video") Box(Modifier.align(Alignment.TopEnd).padding(Design.SmallGap.xdp).size(Design.CreationPlaySize.xdp).background(Design.Blue, CircleShape), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_play), stringResource(R.string.play_media), Modifier.size(Design.FieldHint.xdp))
                    }
                }
            }
            if (state.hasMore) item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(more, Modifier.fillMaxWidth(), enabled = !state.loadingMore) {
                    Text(stringResource(if (state.loadingMore) R.string.loading else if (state.moreFailed) R.string.retry else R.string.load_more))
                }
            }
        }
    }
}

@Composable
fun TaskLabel(task: VisualTask?) = stringResource(when (task?.status) {
    "processing" -> R.string.task_processing
    "succeeded" -> R.string.task_succeeded
    "failed" -> R.string.task_failed
    else -> R.string.task_unknown
})

@Composable
fun BoxScope.HomeTaskNotice(state: VisualLibraryState, open: (GenerationRequest) -> Unit) {
    state.homeNotice?.let { latest ->
        Surface(onClick = { open(latest) }, modifier = Modifier.align(AbsoluteAlignment.TopLeft).padding(top = Design.Page.xdp),
            shape = AbsoluteRoundedCornerShape(topRight = Design.CardRadius.xdp, bottomRight = Design.CardRadius.xdp), color = Design.Blue) {
            Row(Modifier.padding(Design.Page.xdp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.SmallGap.xdp)) {
                if (latest.task?.status == "processing") CircularProgressIndicator(Modifier.size(Design.Icon.xdp), color = Design.White)
                Text(TaskLabel(latest.task), fontSize = Design.FieldHint.xsp)
            }
        }
    }
}

@Composable
fun TaskScreen(request: GenerationRequest?, pollingFailed: Boolean, later: () -> Unit, sourcePath: String? = null, resume: (GenerationRequest) -> Unit, view: (VisualResult) -> Unit) {
    BackHandler(onBack = later)
    val task = request?.task
    Column(Modifier.fillMaxSize().background(Design.Background).statusBarsPadding().navigationBarsPadding()) {
        BackButton(later, Modifier.padding(start = Design.BackLeft.xdp, top = Design.BackTop.xdp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (task?.status == "processing") stringResource(R.string.generating_title) else TaskLabel(task),
                fontSize = Design.TaskHeading.xsp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Design.TaskHeroTop.xdp))
            Text(stringResource(if (task?.status == "processing") R.string.generating_message else R.string.creations_note), color = Design.Muted, fontSize = Design.Body.xsp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Design.Gap.xdp, vertical = Design.TaskHeroGap.xdp))
            Spacer(Modifier.height(Design.TaskMediaTop.xdp))
            Box(Modifier.fillMaxWidth().height(Design.TaskMediaHeight.xdp), contentAlignment = Alignment.Center) {
                if (sourcePath != null) coil.compose.AsyncImage(sourcePath, stringResource(R.string.selected_photo), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else request?.template?.preview?.let { TemplateImage(it, Modifier.fillMaxSize()) }
                if (task?.status == "processing") CircularProgressIndicator(Modifier.size(Design.Touch.xdp))
                task?.resource?.takeIf { task.status == "succeeded" }?.let { result ->
                    Surface(onClick = { view(result) },
                        modifier = Modifier.align(AbsoluteAlignment.TopRight).padding(Design.Page.xdp),
                        shape = RoundedCornerShape(Design.PillRadius.xdp), color = Design.White, contentColor = Design.Background) {
                        Row(Modifier.padding(horizontal = Design.SmallGap.xdp, vertical = Design.SmallGap.xdp),
                            horizontalArrangement = Arrangement.spacedBy(Design.OpenCreationIconGap.xdp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_play), null, Modifier.size(Design.OpenCreationIcon.xdp))
                            Text(stringResource(R.string.open_creation), fontSize = Design.FieldHint.xsp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(Design.TaskStatusGap.xdp))
            if (task != null) {
                val refundAmount = task.refundAmount
                Text(stringResource(R.string.task_created), fontSize = Design.ProductLabel.xsp)
                Text(stringResource(R.string.generation_cost, task.diamondCost), fontSize = Design.ProductLabel.xsp)
                if (task.refunded) Text(if (refundAmount != null) stringResource(R.string.generation_refunded, refundAmount) else stringResource(R.string.generation_refund_confirmed), fontSize = Design.ProductLabel.xsp)
                if (task.status == "failed") Text(task.errorMessage ?: stringResource(R.string.task_failed), color = Design.Error,
                    modifier = Modifier.padding(Design.Page.xdp), textAlign = TextAlign.Center)
            } else if (request != null) {
                Text(GenerationError(request.failure ?: GenerationFailure.UNKNOWN), Modifier.padding(Design.Page.xdp), textAlign = TextAlign.Center)
                TextButton({ resume(request) }) { Text(stringResource(R.string.resume_request)) }
            }
            if (pollingFailed) Text(stringResource(R.string.task_poll_error), color = Design.Error, modifier = Modifier.padding(Design.Page.xdp))
        }
        WhiteAction(stringResource(R.string.view_later), later, Modifier.fillMaxWidth().padding(Design.Page.xdp), icon = R.drawable.ic_view_later)
    }
}

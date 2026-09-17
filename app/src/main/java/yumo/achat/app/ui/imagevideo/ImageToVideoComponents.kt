package yumo.achat.app.ui.imagevideo

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import yumo.achat.app.R
import yumo.achat.app.ui.theme.AchatCyan
import yumo.achat.app.ui.theme.AchatPink
import yumo.achat.app.ui.theme.AchatSurface

@Composable
internal fun HeroCard(
    currentPage: Int,
    totalPages: Int,
    durationSeconds: Int,
    previewMedia: TemplatePreviewMedia = TemplatePreviewMedia.LocalPlaceholder,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    enableSwipeGestures: Boolean = true,
    edgeHint: String? = null,
    modifier: Modifier = Modifier,
) {
    val portraitDescription = stringResource(R.string.template_portrait_description)
    val playDescription = stringResource(
        if (isPlaying) R.string.pause_template_description else R.string.play_template_description,
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(AchatSurface)
            .border(
                1.dp,
                Brush.linearGradient(listOf(AchatCyan.copy(alpha = 0.75f), AchatPink.copy(alpha = 0.75f))),
                RoundedCornerShape(2.dp),
            ),
    ) {
        TemplatePreviewImage(
            media = previewMedia,
            isPlaying = isPlaying,
            contentDescription = portraitDescription,
            modifier = Modifier.fillMaxSize(),
        )
        if (enableSwipeGestures) {
            TemplateSwipeGestureLayer(
                onPrevious = onPrevious,
                onNext = onNext,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.35f)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9060710)))),
        )
        FrameCorners()
        HexPlayButton(
            isPlaying = isPlaying,
            modifier = Modifier
                .align(Alignment.Center)
                .size(90.dp)
                .semantics { contentDescription = playDescription }
                .clickable(onClick = onPlayToggle),
        )
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RoundArrow(up = true, onClick = onPrevious)
            Box(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xB9500B4E))
                    .border(1.dp, AchatPink.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            ) {
                Text(
                    stringResource(R.string.template_page_count, currentPage, totalPages),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            RoundArrow(up = false, onClick = onNext)
        }
        TemplateMetadata(
            durationSeconds = durationSeconds,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
        )
        if (!edgeHint.isNullOrBlank()) {
            Text(
                text = edgeHint,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xB8080A13))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun TemplateSwipeGestureLayer(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.pointerInput(onPrevious, onNext) {
            var totalDrag = 0f
            detectVerticalDragGestures(
                onDragStart = { totalDrag = 0f },
                onVerticalDrag = { change, dragAmount ->
                    totalDrag += dragAmount
                    change.consume()
                },
                onDragEnd = {
                    when {
                        totalDrag < -80f -> onNext()
                        totalDrag > 80f -> onPrevious()
                    }
                    totalDrag = 0f
                },
                onDragCancel = { totalDrag = 0f },
            )
        },
    )
}

@Composable
private fun TemplatePreviewImage(
    media: TemplatePreviewMedia,
    isPlaying: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val placeholder = painterResource(R.drawable.hero_portrait)
    when (media) {
        TemplatePreviewMedia.LocalPlaceholder -> {
            Image(
                painter = placeholder,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = modifier,
            )
        }

        is TemplatePreviewMedia.RemoteImage -> {
            AsyncImage(
                model = media.url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                error = placeholder,
                modifier = modifier,
            )
        }

        is TemplatePreviewMedia.RemoteVideo -> {
            TemplatePreviewVideo(
                url = media.url,
                isPlaying = isPlaying,
                modifier = modifier,
            )
        }
    }
}

@Composable
@OptIn(UnstableApi::class)
private fun TemplatePreviewVideo(
    url: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(TemplateVideoCache.mediaSourceFactory(context))
            .build()
            .apply {
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
    }

    LaunchedEffect(player, isPlaying) {
        player.playWhenReady = isPlaying
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                this.player = player
            }
        },
        update = { playerView ->
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            playerView.player = player
        },
        modifier = modifier.background(Color.Black),
    )
}

@Composable
private fun TemplateMetadata(durationSeconds: Int, modifier: Modifier = Modifier) {
    val duration = stringResource(R.string.template_duration, durationSeconds)
    val quality = stringResource(R.string.template_quality, "720P")
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xCC080B12))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).background(AchatCyan, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = AchatCyan, fontWeight = FontWeight.SemiBold)) {
                    append(duration)
                }
                withStyle(SpanStyle(color = Color(0xFFACABB8))) {
                    append(" · $quality")
                }
            },
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun FrameCorners() {
    val strokeWidth = with(LocalDensity.current) { 2.dp.toPx() }
    Canvas(Modifier.fillMaxSize().padding(2.dp)) {
        val corner = 22.dp.toPx()
        drawLine(AchatCyan, Offset.Zero, Offset(corner, 0f), strokeWidth)
        drawLine(AchatCyan, Offset.Zero, Offset(0f, corner), strokeWidth)
        drawLine(AchatCyan, Offset(size.width - corner, 0f), Offset(size.width, 0f), strokeWidth)
        drawLine(AchatPink, Offset(size.width, 0f), Offset(size.width, corner), strokeWidth)
        drawLine(AchatCyan, Offset(0f, size.height - corner), Offset(0f, size.height), strokeWidth)
        drawLine(AchatPink, Offset(0f, size.height), Offset(corner, size.height), strokeWidth)
        drawLine(AchatPink, Offset(size.width - corner, size.height), Offset(size.width, size.height), strokeWidth)
        drawLine(AchatPink, Offset(size.width, size.height - corner), Offset(size.width, size.height), strokeWidth)
    }
}

@Composable
private fun HexPlayButton(isPlaying: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val hex = Path().apply {
            moveTo(size.width * 0.5f, 0f)
            lineTo(size.width, size.height * 0.25f)
            lineTo(size.width, size.height * 0.75f)
            lineTo(size.width * 0.5f, size.height)
            lineTo(0f, size.height * 0.75f)
            lineTo(0f, size.height * 0.25f)
            close()
        }
        drawPath(hex, Color(0x9B111117))
        drawPath(hex, Color.White.copy(alpha = 0.15f), style = Stroke(1.dp.toPx()))
        if (isPlaying) {
            drawRect(
                color = Color.White,
                topLeft = Offset(size.width * 0.39f, size.height * 0.34f),
                size = Size(size.width * 0.08f, size.height * 0.32f),
            )
            drawRect(
                color = Color.White,
                topLeft = Offset(size.width * 0.55f, size.height * 0.34f),
                size = Size(size.width * 0.08f, size.height * 0.32f),
            )
        } else {
            val play = Path().apply {
                moveTo(size.width * 0.42f, size.height * 0.34f)
                lineTo(size.width * 0.68f, size.height * 0.5f)
                lineTo(size.width * 0.42f, size.height * 0.66f)
                close()
            }
            drawPath(play, Color.White)
        }
    }
}

@Composable
private fun RoundArrow(up: Boolean, onClick: () -> Unit) {
    val description = stringResource(
        if (up) R.string.previous_template_description else R.string.next_template_description,
    )
    Canvas(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(0xCC0B101B))
            .border(1.dp, AchatPink.copy(alpha = 0.7f), CircleShape)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
    ) {
        val y = if (up) size.height * 0.56f else size.height * 0.44f
        val direction = if (up) -1f else 1f
        val path = Path().apply {
            moveTo(size.width * 0.35f, y)
            lineTo(size.width * 0.5f, y + direction * size.height * 0.14f)
            lineTo(size.width * 0.65f, y)
        }
        drawPath(path, AchatCyan, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
internal fun TemplateButton(price: Int, enabled: Boolean = true, onClick: () -> Unit) {
    val gradientColors = if (enabled) {
        listOf(Color(0xFF21D9F2), Color(0xFF7952E8), AchatPink)
    } else {
        listOf(Color(0xFF303545), Color(0xFF343747))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(
                Brush.horizontalGradient(gradientColors),
            )
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.use_this_template),
            color = Color.White.copy(alpha = if (enabled) 1f else 0.55f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(12.dp))
        DiamondIcon(11.dp)
        Spacer(Modifier.width(5.dp))
        Text(
            price.toString(),
            color = Color.White.copy(alpha = if (enabled) 1f else 0.55f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun TemplatePreviewPanel(
    media: TemplatePreviewMedia = TemplatePreviewMedia.LocalPlaceholder,
    durationSeconds: Int = 5,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val selectedTemplateDescription = stringResource(R.string.selected_template_description)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xEE080A13))
            .border(1.dp, AchatPink.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .semantics { contentDescription = selectedTemplateDescription },
    ) {
        TemplatePreviewImage(
            media = media,
            isPlaying = isPlaying,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xF0070710), Color.Transparent, Color(0xF0070710)),
                    ),
                ),
        )
        FrameCorners()
        TemplateMetadata(
            durationSeconds = durationSeconds,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        )
        Text(
            text = stringResource(R.string.template_ratio),
            color = AchatCyan,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 9.dp, end = 10.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color(0x99080B12))
                .border(1.dp, AchatCyan.copy(alpha = 0.45f), RoundedCornerShape(11.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
internal fun PhotoUploadPanel(
    selectedImage: Any? = null,
    uploadMessage: String? = null,
    onChoosePhoto: () -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val photoPickerDescription = stringResource(R.string.photo_picker_description)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xF0121727), Color(0xF6080A13)),
                ),
            )
            .border(
                1.dp,
                Brush.linearGradient(listOf(AchatCyan.copy(alpha = 0.65f), AchatPink.copy(alpha = 0.62f))),
                RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled, onClick = onChoosePhoto)
            .padding(1.dp)
            .semantics { contentDescription = photoPickerDescription },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(AchatPink.copy(alpha = 0.18f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.08f),
                    radius = size.minDimension * 0.55f,
                ),
                radius = size.minDimension * 0.55f,
                center = Offset(size.width * 0.5f, size.height * 0.08f),
            )
        }
        if (selectedImage != null) {
            AsyncImage(
                model = selectedImage,
                contentDescription = photoPickerDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD6080A13)))),
            )
            uploadMessage?.let { message ->
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xB2080B12))
                        .border(1.dp, AchatCyan.copy(alpha = 0.42f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PhotoGlyph(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xD60C1625))
                        .border(1.dp, AchatCyan.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
                        .padding(13.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.upload_photo_prompt),
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 12.sp,
                    letterSpacing = 0.sp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.supported_upload_formats),
                    color = AchatCyan.copy(alpha = 0.75f),
                    fontSize = 9.sp,
                    letterSpacing = 0.sp,
                )
            }
        }
    }
}

@Composable
internal fun UploadActions(
    uploadInProgress: Boolean = false,
    canContinue: Boolean = false,
    onChoosePhoto: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(46.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UploadActionButton(
            label = stringResource(R.string.choose_photo),
            modifier = Modifier.weight(1f),
            primary = false,
            enabled = !uploadInProgress,
            onClick = onChoosePhoto,
        )
        UploadActionButton(
            label = stringResource(if (uploadInProgress) R.string.uploading_action else R.string.continue_action),
            modifier = Modifier.weight(1.15f),
            primary = true,
            enabled = !uploadInProgress && canContinue,
            onClick = onContinue,
        )
    }
}

@Composable
private fun UploadActionButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = when {
        !enabled -> Brush.horizontalGradient(listOf(Color(0xFF2E3340), Color(0xFF373B46)))
        primary -> Brush.horizontalGradient(listOf(Color(0xFF784CE8), AchatPink, Color(0xFF42DDF4)))
        else -> Brush.horizontalGradient(listOf(Color(0xFF11192A), Color(0xFF101420)))
    }
    val borderColor = when {
        !enabled -> Color.White.copy(alpha = 0.08f)
        primary -> Color.White.copy(alpha = 0.12f)
        else -> AchatCyan.copy(alpha = 0.45f)
    }
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(
                1.dp,
                borderColor,
                RoundedCornerShape(6.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.62f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.sp,
        )
    }
}

@Composable
internal fun BackGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Color(0xB20A111C), radius = size.minDimension * 0.48f, center = center)
        drawCircle(AchatCyan.copy(alpha = 0.35f), radius = size.minDimension * 0.48f, center = center, style = stroke)
        drawLine(AchatCyan, Offset(size.width * 0.62f, size.height * 0.26f), Offset(size.width * 0.36f, size.height * 0.5f), 1.8.dp.toPx(), StrokeCap.Round)
        drawLine(AchatCyan, Offset(size.width * 0.36f, size.height * 0.5f), Offset(size.width * 0.62f, size.height * 0.74f), 1.8.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun PhotoGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
        drawRoundRect(
            color = AchatCyan,
            size = size,
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = stroke,
        )
        drawCircle(AchatCyan, size.width * 0.08f, Offset(size.width * 0.7f, size.height * 0.3f))
        drawLine(AchatCyan, Offset(size.width * 0.16f, size.height * 0.72f), Offset(size.width * 0.4f, size.height * 0.48f), 2.dp.toPx())
        drawLine(AchatCyan, Offset(size.width * 0.4f, size.height * 0.48f), Offset(size.width * 0.82f, size.height * 0.78f), 2.dp.toPx())
    }
}

@Composable
internal fun BottomNavigation(selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xD9080A13))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        NavItem(stringResource(R.string.nav_video), NavIcon.Video, selectedIndex == 0) { onSelect(0) }
        NavItem(stringResource(R.string.nav_image), NavIcon.Image, selectedIndex == 1) { onSelect(1) }
        NavItem(stringResource(R.string.nav_top_up), NavIcon.Diamond, selectedIndex == 2) { onSelect(2) }
        NavItem(stringResource(R.string.nav_me), NavIcon.Person, selectedIndex == 3) { onSelect(3) }
    }
}

private enum class NavIcon { Video, Image, Diamond, Person }

@Composable
private fun NavItem(label: String, icon: NavIcon, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(62.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NavGlyph(icon, selected)
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = if (selected) AchatCyan else Color(0xFF565667),
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
        )
    }
}

@Composable
private fun NavGlyph(icon: NavIcon, selected: Boolean) {
    val tint = if (selected) AchatCyan else Color(0xFF656676)
    Canvas(Modifier.size(19.dp)) {
        val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
        when (icon) {
            NavIcon.Video -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * 0.12f, size.height * 0.2f),
                    size = Size(size.width * 0.58f, size.height * 0.6f),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = stroke,
                )
                val triangle = Path().apply {
                    moveTo(size.width * 0.72f, size.height * 0.38f)
                    lineTo(size.width * 0.91f, size.height * 0.28f)
                    lineTo(size.width * 0.91f, size.height * 0.72f)
                    lineTo(size.width * 0.72f, size.height * 0.62f)
                    close()
                }
                drawPath(triangle, tint, style = stroke)
            }
            NavIcon.Image -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * 0.12f, size.height * 0.14f),
                    size = Size(size.width * 0.76f, size.height * 0.72f),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = stroke,
                )
                drawCircle(tint, size.width * 0.08f, Offset(size.width * 0.68f, size.height * 0.35f))
                drawLine(tint, Offset(size.width * 0.2f, size.height * 0.72f), Offset(size.width * 0.42f, size.height * 0.48f), 1.5.dp.toPx())
                drawLine(tint, Offset(size.width * 0.42f, size.height * 0.48f), Offset(size.width * 0.8f, size.height * 0.76f), 1.5.dp.toPx())
            }
            NavIcon.Diamond -> {
                val path = Path().apply {
                    moveTo(size.width * 0.5f, size.height * 0.86f)
                    lineTo(size.width * 0.08f, size.height * 0.34f)
                    lineTo(size.width * 0.27f, size.height * 0.12f)
                    lineTo(size.width * 0.73f, size.height * 0.12f)
                    lineTo(size.width * 0.92f, size.height * 0.34f)
                    close()
                }
                drawPath(path, tint, style = stroke)
            }
            NavIcon.Person -> {
                drawCircle(tint, size.width * 0.2f, Offset(size.width * 0.5f, size.height * 0.32f), style = stroke)
                drawArc(tint, 200f, 140f, false, Offset(size.width * 0.18f, size.height * 0.5f), Size(size.width * 0.64f, size.height * 0.54f), style = stroke)
            }
        }
    }
}

@Composable
internal fun DiamondIcon(iconSize: Dp) {
    Canvas(Modifier.size(iconSize)) {
        val diamond = Path().apply {
            moveTo(size.width * 0.5f, 0f)
            lineTo(size.width, size.height * 0.42f)
            lineTo(size.width * 0.5f, size.height)
            lineTo(0f, size.height * 0.42f)
            close()
        }
        drawPath(diamond, AchatCyan)
    }
}

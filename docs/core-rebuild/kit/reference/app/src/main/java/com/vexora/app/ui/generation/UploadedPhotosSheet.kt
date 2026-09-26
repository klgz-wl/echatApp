package com.vexora.app.ui.generation

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vexora.app.R
import com.vexora.app.ui.catalog.TemplateImage
import com.vexora.app.ui.theme.*
import com.zorv.core.visual.UploadedPhoto

@Composable
fun UploadedPhotosRoute(dismiss: () -> Unit, upload: () -> Unit, select: (UploadedPhoto) -> Unit,
    vm: UploadedPhotosViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load(refresh = true) }
    UploadedPhotosSheet(state, dismiss, upload, select, { vm.load(refresh = state.page == 0) })
}

/** 用户确认改为服务端已上传图片；仅首格加号打开系统文件选择器。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadedPhotosSheet(state: UploadedPhotosState, dismiss: () -> Unit, upload: () -> Unit,
    select: (UploadedPhoto) -> Unit, more: () -> Unit) {
    val context = LocalContext.current
    val uploadDescription = stringResource(R.string.upload_new_photo)
    LaunchedEffect(state.failed) {
        if (state.failed) Toast.makeText(context, R.string.uploaded_photos_failed, Toast.LENGTH_SHORT).show()
    }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Design.White,
        contentColor = Design.Background, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = Design.UploadedSheetHeight.xdp).padding(horizontal = Design.RecordMargin.xdp)) {
            Text(stringResource(R.string.uploaded_photos), fontSize = Design.Title.xsp,
                modifier = Modifier.padding(bottom = Design.RecordMargin.xdp))
            LazyVerticalGrid(columns = GridCells.Fixed(Design.UploadedPhotoColumns),
                horizontalArrangement = Arrangement.spacedBy(Design.UploadedPhotoGap.xdp),
                verticalArrangement = Arrangement.spacedBy(Design.UploadedPhotoGap.xdp),
                contentPadding = PaddingValues(bottom = Design.RecordMargin.xdp)) {
                item(key = "upload") {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(Design.SmallGap.xdp))
                        .border(Design.BorderWidth.xdp, Design.Blue, RoundedCornerShape(Design.SmallGap.xdp))
                        .clickable(onClick = upload).semantics { contentDescription = uploadDescription }, contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.upload_plus), fontSize = Design.BackSize.xsp, color = Design.Blue)
                    }
                }
                items(state.photos, key = { it.id }) { photo ->
                    TemplateImage(photo.media(), Modifier.fillMaxWidth().aspectRatio(1f)
                        .clip(RoundedCornerShape(Design.SmallGap.xdp)).clickable { select(photo) })
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = Design.RecordMargin.xdp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state.busy) CircularProgressIndicator()
                        else if (state.failed) TextButton(more) { Text(stringResource(R.string.retry)) }
                        else if (state.hasMore) TextButton(more) { Text(stringResource(R.string.load_more)) }
                        else if (state.photos.isEmpty()) Text(stringResource(R.string.uploaded_photos_empty), fontSize = Design.ProductLabel.xsp)
                    }
                }
            }
        }
    }
}

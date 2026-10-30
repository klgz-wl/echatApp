package yumo.achat.app.ui.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import yumo.achat.app.R
import yumo.achat.app.ui.theme.AchatCyan
import yumo.achat.app.ui.theme.AchatPink

/** Place this in a Box aligned to the preview's bottom end. Its touch target is 44 dp. */
@Composable
fun MediaReportIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.report_content_description)
    Box(
        modifier = modifier
            .size(44.dp)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(29.dp)) {
            val radius = size.minDimension / 2f - 1.dp.toPx()
            drawCircle(AchatCyan, radius = radius, style = Stroke(width = 1.5.dp.toPx()))
            drawLine(
                AchatCyan,
                start = Offset(center.x, center.y - 7.dp.toPx()),
                end = Offset(center.x, center.y + 2.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(AchatCyan, radius = 1.2.dp.toPx(), center = Offset(center.x, center.y + 7.dp.toPx()))
        }
    }
}

/** Owns one report attempt; remove from composition to cancel its pending request. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaReportSheet(
    mediaId: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    repository: MediaReportRepository = remember { LocalMediaReportRepository() },
) {
    val controller = remember(mediaId, repository) { ReportSubmissionController(repository) }
    val submissionState by controller.state.collectAsState()
    val submittingLabel = stringResource(R.string.report_submitting)
    val scope = rememberCoroutineScope()
    var submissionJob by remember(mediaId, repository) { mutableStateOf<Job?>(null) }
    DisposableEffect(mediaId, repository) {
        onDispose { submissionJob?.cancel() }
    }
    val close = {
        submissionJob?.cancel()
        onDismiss()
    }
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1B1135),
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = 0.72f),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 9.dp)
                    .size(width = 39.dp, height = 3.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(Color.White.copy(alpha = 0.85f)),
            )
        },
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxWidth().height(400.dp).padding(horizontal = 28.dp).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(22.dp))
            Text(
                text = stringResource(R.string.report_explanation),
                color = Color.White,
                fontSize = 16.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
            )
            if (submissionState == ReportSubmissionState.Failure) {
                Spacer(Modifier.height(9.dp))
                Text(
                    text = stringResource(R.string.report_failed),
                    color = Color(0xFFFFB4CA),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.horizontalGradient(listOf(AchatCyan, Color(0xFF6C4BEA), AchatPink)))
                    .clickable(
                        enabled = submissionState != ReportSubmissionState.Submitting,
                        role = Role.Button,
                    ) {
                        submissionJob = scope.launch {
                            if (controller.submit(MediaReport(mediaId))) onSuccess()
                        }
                    }
                    .semantics {
                        if (submissionState == ReportSubmissionState.Submitting) {
                            contentDescription = submittingLabel
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (submissionState == ReportSubmissionState.Submitting) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.report_submit), color = Color.White, fontSize = 15.sp)
                }
            }
        }
    }
}

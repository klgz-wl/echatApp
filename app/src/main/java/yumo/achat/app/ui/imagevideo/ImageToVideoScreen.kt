package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yumo.achat.app.R
import yumo.achat.app.ui.theme.AchatCyan
import yumo.achat.app.ui.theme.AchatDeepNavy
import yumo.achat.app.ui.theme.AchatMuted
import yumo.achat.app.ui.theme.AchatPink
import yumo.achat.app.ui.theme.AchatTheme

private const val TotalTemplateCount = 109

private enum class ImageToVideoDestination {
    Templates,
    UploadPhoto,
}

@Composable
fun ImageToVideoScreen(modifier: Modifier = Modifier) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var currentTemplate by rememberSaveable { mutableIntStateOf(1) }
    var isPlaying by rememberSaveable { mutableStateOf(false) }
    var selectedNavigation by rememberSaveable { mutableIntStateOf(0) }
    var destination by rememberSaveable { mutableStateOf(ImageToVideoDestination.Templates) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF10102A), AchatDeepNavy, Color(0xFF110718)),
                ),
            ),
    ) {
        BackgroundGlow()
        when (destination) {
            ImageToVideoDestination.Templates -> {
                TemplateBrowserScreen(
                    selectedTab = selectedTab,
                    currentTemplate = currentTemplate,
                    isPlaying = isPlaying,
                    selectedNavigation = selectedNavigation,
                    onTabSelect = { selectedTab = it },
                    onPlayToggle = { isPlaying = !isPlaying },
                    onPrevious = { currentTemplate = (currentTemplate - 1).coerceAtLeast(1) },
                    onNext = { currentTemplate = (currentTemplate + 1).coerceAtMost(TotalTemplateCount) },
                    onUseTemplate = { destination = ImageToVideoDestination.UploadPhoto },
                    onNavigationSelect = { selectedNavigation = it },
                )
            }

            ImageToVideoDestination.UploadPhoto -> {
                UploadPhotoScreen(
                    selectedNavigation = selectedNavigation,
                    onBack = { destination = ImageToVideoDestination.Templates },
                    onNavigationSelect = { selectedNavigation = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun TemplateBrowserScreen(
    selectedTab: Int,
    currentTemplate: Int,
    isPlaying: Boolean,
    selectedNavigation: Int,
    onTabSelect: (Int) -> Unit,
    onPlayToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onUseTemplate: () -> Unit,
    onNavigationSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Header()
        Spacer(Modifier.height(8.dp))
        CategoryTabs(selectedTab = selectedTab, onSelect = onTabSelect)
        Spacer(Modifier.height(7.dp))
        HeroCard(
            currentPage = currentTemplate,
            totalPages = TotalTemplateCount,
            isPlaying = isPlaying,
            onPlayToggle = onPlayToggle,
            onPrevious = onPrevious,
            onNext = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Spacer(Modifier.height(12.dp))
        TemplateButton(onClick = onUseTemplate)
        Spacer(Modifier.height(12.dp))
        BottomNavigation(
            selectedIndex = selectedNavigation,
            onSelect = onNavigationSelect,
        )
    }
}

@Composable
private fun UploadPhotoScreen(
    selectedNavigation: Int,
    onBack: () -> Unit,
    onNavigationSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        UploadPhotoHeader(onBack = onBack)
        Spacer(Modifier.height(22.dp))
        Text(
            text = stringResource(R.string.template_preview_heading),
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(7.dp))
        TemplatePreviewPanel(Modifier.fillMaxWidth().height(132.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.template_upload_heading),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        PhotoUploadPanel(Modifier.fillMaxWidth().weight(1f))
        Spacer(Modifier.height(10.dp))
        UploadActions()
        Spacer(Modifier.height(12.dp))
        BottomNavigation(
            selectedIndex = selectedNavigation,
            onSelect = onNavigationSelect,
        )
    }
}

@Composable
private fun UploadPhotoHeader(onBack: () -> Unit) {
    val backDescription = stringResource(R.string.back_to_templates_description)
    val balanceDescription = stringResource(R.string.balance_panel_description)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x78101524))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackGlyph(
                modifier = Modifier
                    .size(28.dp)
                    .semantics { contentDescription = backDescription }
                    .clickable(onClick = onBack),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = stringResource(R.string.upload_photo_title),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(
            modifier = Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, AchatCyan.copy(alpha = 0.34f), RoundedCornerShape(12.dp))
                .background(Color(0xB20A111C))
                .padding(horizontal = 11.dp)
                .semantics { contentDescription = balanceDescription },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DiamondIcon(11.dp)
            Spacer(Modifier.width(6.dp))
            Text("0", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BackgroundGlow() {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(AchatPink.copy(alpha = 0.16f), Color.Transparent),
                Offset(size.width * 0.08f, size.height * 0.3f),
                size.width * 0.75f,
            ),
            radius = size.width * 0.75f,
            center = Offset(size.width * 0.08f, size.height * 0.3f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(AchatCyan.copy(alpha = 0.1f), Color.Transparent),
                Offset(size.width, size.height * 0.08f),
                size.width * 0.65f,
            ),
            radius = size.width * 0.65f,
            center = Offset(size.width, size.height * 0.08f),
        )
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.image_to_video_title),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, AchatCyan.copy(alpha = 0.34f), RoundedCornerShape(12.dp))
                .background(Color(0xB20A111C))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DiamondIcon(12.dp)
            Spacer(Modifier.width(6.dp))
            Text("0", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CategoryTabs(selectedTab: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        CategoryTab(stringResource(R.string.tab_hot), selectedTab == 0) { onSelect(0) }
        CategoryTab(stringResource(R.string.tab_new), selectedTab == 1) { onSelect(1) }
    }
}

@Composable
private fun CategoryTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(min = 32.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else AchatMuted,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(if (selected) 25.dp else 0.dp)
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(listOf(AchatCyan, AchatPink)),
                    RoundedCornerShape(2.dp),
                ),
        )
    }
}

@Preview(widthDp = 375, heightDp = 812, showBackground = true)
@Composable
private fun ImageToVideoPreview() {
    AchatTheme {
        ImageToVideoScreen()
    }
}

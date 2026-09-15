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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
    MyTasks,
}

private enum class TemplateSection {
    Video,
    Image,
}

private data class CreditPack(
    val credits: Int,
    val validityDays: Int,
    val price: String,
    val bonus: Int? = null,
    val badgeTextRes: Int? = null,
)

private val CreditPacks = listOf(
    CreditPack(credits = 200, validityDays = 90, price = "$39.99", bonus = 150),
    CreditPack(credits = 100, validityDays = 60, price = "$19.99", bonus = 50),
    CreditPack(credits = 50, validityDays = 30, price = "$9.99", bonus = 20),
    CreditPack(credits = 25, validityDays = 15, price = "$4.99", bonus = 5),
    CreditPack(credits = 10, validityDays = 15, price = "$1.99", badgeTextRes = R.string.credit_pack_starter),
)

@Composable
fun ImageToVideoScreen(modifier: Modifier = Modifier) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var currentTemplate by rememberSaveable { mutableIntStateOf(1) }
    var isPlaying by rememberSaveable { mutableStateOf(false) }
    var selectedNavigation by rememberSaveable { mutableIntStateOf(0) }
    var selectedCreditPack by rememberSaveable { mutableIntStateOf(0) }
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
                    onConversationLog = { destination = ImageToVideoDestination.MyTasks },
                    onNavigationSelect = { selectedNavigation = it },
                    selectedCreditPack = selectedCreditPack,
                    onCreditPackSelect = { selectedCreditPack = it },
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

            ImageToVideoDestination.MyTasks -> {
                MyTasksScreen(
                    onBack = { destination = ImageToVideoDestination.Templates },
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
    selectedCreditPack: Int,
    onTabSelect: (Int) -> Unit,
    onPlayToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onUseTemplate: () -> Unit,
    onConversationLog: () -> Unit,
    onNavigationSelect: (Int) -> Unit,
    onCreditPackSelect: (Int) -> Unit,
) {
    if (selectedNavigation == 2) {
        TopUpScreen(
            selectedNavigation = selectedNavigation,
            selectedCreditPack = selectedCreditPack,
            onCreditPackSelect = onCreditPackSelect,
            onNavigationSelect = {
                onNavigationSelect(it)
                onTabSelect(0)
            },
        )
        return
    }

    if (selectedNavigation == 3) {
        MeScreen(
            selectedNavigation = selectedNavigation,
            onConversationLog = onConversationLog,
            onNavigationSelect = {
                onNavigationSelect(it)
                onTabSelect(0)
            },
        )
        return
    }

    val section = if (selectedNavigation == 1) TemplateSection.Image else TemplateSection.Video
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Header(section = section)
        Spacer(Modifier.height(8.dp))
        CategoryTabs(section = section, selectedTab = selectedTab, onSelect = onTabSelect)
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
            onSelect = {
                onNavigationSelect(it)
                onTabSelect(0)
            },
        )
    }
}

@Composable
private fun MeScreen(
    selectedNavigation: Int,
    onConversationLog: () -> Unit,
    onNavigationSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        MeHeader()
        Spacer(Modifier.height(28.dp))
        ProfileCard()
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DiamondIcon(7.dp)
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(R.string.system_modules),
                color = AchatCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModuleRow(
                icon = ModuleIcon.Chat,
                title = stringResource(R.string.conversation_log),
                subtitle = stringResource(R.string.conversation_log_subtitle),
                trailing = stringResource(R.string.module_count, 108),
                onClick = onConversationLog,
            )
            ModuleRow(
                icon = ModuleIcon.Feedback,
                title = stringResource(R.string.feedback),
                subtitle = stringResource(R.string.feedback_subtitle),
                trailing = stringResource(R.string.module_new),
            )
            ModuleRow(
                icon = ModuleIcon.Edit,
                title = stringResource(R.string.edit_name),
                subtitle = stringResource(R.string.edit_name_subtitle),
            )
        }
        Spacer(Modifier.weight(1f))
        BottomNavigation(
            selectedIndex = selectedNavigation,
            onSelect = onNavigationSelect,
        )
    }
}

@Composable
private fun MeHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.me_title),
            color = AchatCyan,
            fontSize = 22.sp,
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
private fun ProfileCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xE0161F35), Color(0xD90A0B14))))
            .border(
                1.dp,
                Brush.linearGradient(listOf(AchatCyan.copy(alpha = 0.45f), AchatPink.copy(alpha = 0.35f))),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF3EDBF2), AchatPink)))
                .padding(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF7449E7), Color(0xFF15111E)))),
                contentAlignment = Alignment.Center,
            ) {
                Text("Q", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.profile_name),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = stringResource(R.string.profile_id),
                color = AchatMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = stringResource(R.string.profile_edit),
            color = AchatCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x5913D7EF))
                .border(1.dp, AchatCyan.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

private enum class ModuleIcon { Chat, Feedback, Edit }

@Composable
private fun ModuleRow(
    icon: ModuleIcon,
    title: String,
    subtitle: String,
    trailing: String? = null,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xBA090D19))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModuleGlyph(icon = icon)
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (trailing != null) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = trailing,
                        color = AchatPink,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = AchatMuted,
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Text("›", color = AchatMuted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ModuleGlyph(icon: ModuleIcon) {
    val tint = when (icon) {
        ModuleIcon.Chat -> AchatCyan
        ModuleIcon.Feedback -> AchatPink
        ModuleIcon.Edit -> Color(0xFF7D55E9)
    }
    Canvas(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = 0.1f))
            .border(1.dp, tint.copy(alpha = 0.38f), RoundedCornerShape(4.dp))
            .padding(7.dp),
    ) {
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
        when (icon) {
            ModuleIcon.Chat -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * 0.08f, size.height * 0.14f),
                    size = Size(size.width * 0.74f, size.height * 0.56f),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = stroke,
                )
                drawLine(tint, Offset(size.width * 0.34f, size.height * 0.7f), Offset(size.width * 0.24f, size.height * 0.9f), 1.6.dp.toPx())
            }
            ModuleIcon.Feedback -> {
                drawCircle(tint, size.minDimension * 0.36f, center, style = stroke)
                drawLine(tint, Offset(size.width * 0.5f, size.height * 0.28f), Offset(size.width * 0.5f, size.height * 0.52f), 1.6.dp.toPx())
                drawCircle(tint, size.minDimension * 0.035f, Offset(size.width * 0.5f, size.height * 0.68f))
            }
            ModuleIcon.Edit -> {
                drawLine(tint, Offset(size.width * 0.18f, size.height * 0.78f), Offset(size.width * 0.74f, size.height * 0.22f), 1.8.dp.toPx())
                drawLine(tint, Offset(size.width * 0.58f, size.height * 0.18f), Offset(size.width * 0.78f, size.height * 0.38f), 1.8.dp.toPx())
                drawLine(tint, Offset(size.width * 0.18f, size.height * 0.82f), Offset(size.width * 0.4f, size.height * 0.78f), 1.4.dp.toPx())
            }
        }
    }
}

@Composable
private fun MyTasksScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SecondaryHeader(
            title = stringResource(R.string.my_tasks_title),
            backDescription = stringResource(R.string.back_to_me_description),
            onBack = onBack,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            EmptyTasksCard()
        }
    }
}

@Composable
private fun SecondaryHeader(
    title: String,
    backDescription: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x78101524))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackGlyph(
            modifier = Modifier
                .size(26.dp)
                .semantics { contentDescription = backDescription }
                .clickable(onClick = onBack),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyTasksCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.radialGradient(listOf(Color(0xA61A1433), Color(0xEA080A13))))
            .border(
                1.dp,
                Brush.linearGradient(listOf(AchatPink.copy(alpha = 0.72f), AchatCyan.copy(alpha = 0.64f))),
                RoundedCornerShape(4.dp),
            ),
    ) {
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
            val corner = 25.dp.toPx()
            drawLine(AchatPink, Offset.Zero, Offset(corner, 0f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(AchatPink, Offset.Zero, Offset(0f, corner), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(AchatCyan, Offset(size.width - corner, 0f), Offset(size.width, 0f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(AchatCyan, Offset(size.width, 0f), Offset(size.width, corner), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.05f),
                topLeft = Offset(size.width * 0.16f, size.height * 0.14f),
                size = Size(size.width * 0.68f, size.height * 0.62f),
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = stroke,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x99111224))
                .border(1.dp, AchatPink.copy(alpha = 0.48f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            DiamondIcon(21.dp)
        }
        Text(
            text = stringResource(R.string.no_tasks_empty),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center).padding(top = 86.dp),
        )
    }
}

@Composable
private fun TopUpScreen(
    selectedNavigation: Int,
    selectedCreditPack: Int,
    onCreditPackSelect: (Int) -> Unit,
    onNavigationSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        TopUpHeader()
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DiamondIcon(8.dp)
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(R.string.choose_pack),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CreditPacks.forEachIndexed { index, pack ->
                CreditPackCard(
                    pack = pack,
                    selected = selectedCreditPack == index,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onSelect = { onCreditPackSelect(index) },
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = stringResource(
                R.string.credit_pack_summary,
                CreditPacks[selectedCreditPack].credits + (CreditPacks[selectedCreditPack].bonus ?: 0),
            ),
            color = AchatCyan,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(8.dp))
        StartChatButton()
        Spacer(Modifier.height(12.dp))
        BottomNavigation(
            selectedIndex = selectedNavigation,
            onSelect = onNavigationSelect,
        )
    }
}

@Composable
private fun TopUpHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = stringResource(R.string.system_credits),
                color = AchatPink,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.top_up_title),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }
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
private fun CreditPackCard(
    pack: CreditPack,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderBrush = if (selected) {
        Brush.linearGradient(listOf(AchatCyan.copy(alpha = 0.95f), AchatPink.copy(alpha = 0.82f)))
    } else {
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.03f)))
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.horizontalGradient(
                    if (selected) {
                        listOf(Color(0xEE16244A), Color(0xEE161126))
                    } else {
                        listOf(Color(0xD90C1324), Color(0xD9080A13))
                    },
                ),
            )
            .border(1.dp, borderBrush, RoundedCornerShape(6.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xB20B1322))
                .border(1.dp, AchatCyan.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            DiamondIcon(13.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = pack.credits.toString(),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (pack.bonus != null || pack.badgeTextRes != null) {
                    Spacer(Modifier.width(8.dp))
                    BonusBadge(pack = pack)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.credit_pack_validity, pack.validityDays),
                color = Color(0xFF767A92),
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.credit_pack_protocol),
                color = AchatMuted,
                fontSize = 7.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = pack.price,
            color = if (selected) AchatCyan else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun BonusBadge(pack: CreditPack) {
    val label = pack.bonus?.let { stringResource(R.string.credit_pack_bonus, it) }
        ?: pack.badgeTextRes?.let { stringResource(it) }
        ?: return
    Text(
        text = label,
        color = Color.White,
        fontSize = 7.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Brush.horizontalGradient(listOf(AchatPink, Color(0xFF7A52E8))))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun StartChatButton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF30DDF3), Color(0xFF7D55E9), AchatPink))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.start_chat),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        Text("➜", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
private fun Header(section: TemplateSection) {
    val title = stringResource(
        if (section == TemplateSection.Image) R.string.image_to_image_title else R.string.image_to_video_title,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = if (section == TemplateSection.Image) 20.sp else 24.sp,
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
private fun CategoryTabs(section: TemplateSection, selectedTab: Int, onSelect: (Int) -> Unit) {
    val firstLabel = stringResource(
        if (section == TemplateSection.Image) R.string.tab_single_image else R.string.tab_hot,
    )
    val secondLabel = stringResource(
        if (section == TemplateSection.Image) R.string.tab_multi_image else R.string.tab_new,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        CategoryTab(firstLabel, selectedTab == 0) { onSelect(0) }
        CategoryTab(secondLabel, selectedTab == 1) { onSelect(1) }
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

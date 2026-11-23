package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yumo.achat.app.R
import yumo.achat.app.ui.theme.*
import yumo.achat.core.backend.VisualTemplate

internal fun VisualTemplate.homeGenerationTemplate(): SelectedGenerationTemplate? {
    val media = toPreviewMedia()
    if (!media.isUsableGenerationMedia()) return null
    return SelectedGenerationTemplate(id, "video", if (fastPrice != null) "fast" else "quality", name,
        media, durationSeconds.takeIf { it > 0 } ?: 5, categoryId.orEmpty(), "home", displayPrice ?: 0, vip)
}

@Composable
internal fun ZorvBrandHeader(balance: Int, isVip: Boolean?, onSettings: () -> Unit,
    onSubscription: () -> Unit, onBalance: () -> Unit, horizontalPadding: androidx.compose.ui.unit.Dp = 20.dp) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).testTag("zorv-brand-gear").clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0A0818))
            .border(1.dp, Brush.linearGradient(listOf(AchatPink, AchatCyan)), RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onSettings), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.open_settings),
                tint = AchatCyan, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.zorv_brand_title), color = Color(0xFFF1F5F9), fontSize = 24.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        VipBalanceActions(isVip, balance, onSubscription, onBalance)
    }
}

@Composable
internal fun HomeFeedCardFrame(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)
        .shadow(12.dp, shape, ambientColor = AchatCyan.copy(alpha = .2f), spotColor = AchatCyan.copy(alpha = .15f))
        .clip(shape).background(Color(0xFF0D0B1F)).border(1.dp, AchatCyan.copy(alpha = .25f), shape)
        .testTag("home-feed-card-frame"), content = content)
}

@Composable
internal fun HomeCtaArrow() {
    val cyan = Color(0xFF22D3EE)
    Box(Modifier.size(24.dp)
        .shadow(8.dp, CircleShape, clip = false,
            ambientColor = Color(0x8000F2FE), spotColor = Color(0x8000F2FE))
        .background(cyan.copy(alpha = .2f), CircleShape)
        .border(1.dp, cyan.copy(alpha = .4f), CircleShape),
        contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_home_cta_chevron), null, Modifier.size(5.dp, 8.75.dp))
    }
}

@Composable
internal fun HomeFeaturedCard(template: VisualTemplate, active: Boolean, playbackEnabled: Boolean, useEnabled: Boolean = true, onUse: () -> Unit) {
    HomeFeedCardFrame {
        TemplatePreviewImage(template.toPreviewMedia(), isPlaying = active && playbackEnabled,
            contentDescription = template.name, modifier = Modifier.fillMaxSize(), videoPlaybackEnabled = active)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66070613), Color.Transparent, Color(0xFF070613)))))
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .2f))
                .border(1.dp, Color.White.copy(alpha = .5f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(Color(0xFFFF304B), CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(if (template.mimeType.startsWith("image/")) R.string.nav_image else R.string.home_live), color = Color.White, fontSize = 12.sp)
            }
            TemplateVipBadge(template.vip)
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 40.dp)
            .fillMaxWidth().height(54.dp).clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(Color(0xE6171433), Color(0xE6221C45), Color(0xE6171433))))
            .border(1.dp, AchatCyan.copy(alpha = .5f), CircleShape)
            .clickable(enabled = useEnabled && template.homeGenerationTemplate() != null, role = Role.Button, onClick = onUse)
            .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Image(painterResource(R.drawable.home_use_wave), null, Modifier.size(20.dp))
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.home_use_template), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            HomeCtaArrow()
        }
    }
}

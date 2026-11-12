package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.clickable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yumo.achat.app.R

internal enum class MeVipBadgeStyle { NonVip, Vip }
internal enum class VipBadgeTarget { Subscription }
internal fun vipBadgeTarget(isVip: Boolean?): VipBadgeTarget? = when (isVip) {
    true -> VipBadgeTarget.Subscription
    false -> VipBadgeTarget.Subscription
    null -> null
}

internal fun meVipBadgeStyle(isVip: Boolean?): MeVipBadgeStyle? = when (isVip) {
    true -> MeVipBadgeStyle.Vip
    false -> MeVipBadgeStyle.NonVip
    null -> null
}

@Composable
internal fun MeVipBadge(style: MeVipBadgeStyle, onClick: () -> Unit) {
    val isVip = style == MeVipBadgeStyle.Vip
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .shadow(8.dp, shape, ambientColor = Color(0x66FF007F), spotColor = Color(0x8000F2FE))
            .background(Color(0xE618112D), shape)
            .then(if (isVip) Modifier.border(1.dp, Color(0xCCFF007F), shape) else Modifier)
            .padding(horizontal = if (isVip) 12.dp else 10.dp, vertical = if (isVip) 6.dp else 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.me_vip_crown),
            color = Color.White,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            modifier = if (isVip) Modifier else Modifier.drawWithContent {
                val paint = Paint().apply {
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                }
                drawContext.canvas.saveLayer(Rect(Offset.Zero, size), paint)
                drawContent()
                drawContext.canvas.restore()
            },
        )
        Text(
            stringResource(R.string.me_vip_label),
            style = if (isVip) TextStyle(brush = Brush.horizontalGradient(
                listOf(Color(0xFF00F2FE), Color(0xFFF472B6), Color(0xFFFF007F)),
            )) else TextStyle(color = Color(0xFFAEAEAE)),
            fontSize = 11.sp,
            lineHeight = 16.5.sp,
            letterSpacing = 0.55.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yumo.achat.app.R

/** Noninteractive template attribute, distinct from the account membership action in the header. */
@Composable
internal fun TemplateVipBadge(vip: Boolean, modifier: Modifier = Modifier) {
    if (!vip) return
    val description = stringResource(R.string.template_vip_only)
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.size(46.dp, 27.dp)
        .shadow(8.dp, shape, ambientColor = Color(0x66FF007F), spotColor = Color(0x8000F2FE))
        .background(Color(0xE618112D), shape).border(1.dp, Color(0xCCFF007F), shape)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.me_vip_label), fontSize = 11.sp, lineHeight = 16.5.sp,
            letterSpacing = 0.55.sp, fontWeight = FontWeight.Bold,
            style = TextStyle(brush = Brush.horizontalGradient(listOf(Color(0xFF00F2FE), Color(0xFFF472B6), Color(0xFFFF007F)))))
    }
}

package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yumo.achat.app.ui.theme.AchatCyan

@Composable
internal fun VipBalanceActions(
    isVip: Boolean?, diamondBalance: Int, onVipClick: () -> Unit,
    onBalanceClick: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        meVipBadgeStyle(isVip)?.let { MeVipBadge(it, onClick = onVipClick) }
        Row(
            modifier = Modifier.height(32.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, AchatCyan.copy(alpha = .34f), RoundedCornerShape(12.dp))
                .background(Color(0xB20A111C))
                .then(if (onBalanceClick != null) Modifier.clickable(onClick = onBalanceClick) else Modifier)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DiamondIcon(12.dp)
            Spacer(Modifier.width(6.dp))
            Text(diamondBalance.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

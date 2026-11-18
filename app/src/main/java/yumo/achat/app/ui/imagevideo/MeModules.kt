package yumo.achat.app.ui.imagevideo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import yumo.achat.app.BuildConfig
import yumo.achat.app.R
import yumo.achat.app.ui.theme.AchatCyan
import yumo.achat.app.ui.theme.AchatMuted
import yumo.achat.app.ui.theme.AchatPink
import yumo.achat.core.backend.WalletTransaction
import java.util.Locale

internal enum class MeMenuEntry { Creations, WatchHistory, Feedback, DiamondHistory, Privacy, Terms, Version }
internal fun meMenuEntries() = MeMenuEntry.entries.toList()

@Composable
internal fun MeMenuRow(entry: MeMenuEntry, onClick: () -> Unit) {
    val index = entry.ordinal
    val tint = listOf(AchatCyan, AchatPink, Color(0xFF8523DD))[index % 3]
    val label = listOf(R.string.me_creations, R.string.me_watch_history, R.string.feedback,
        R.string.me_diamond_history, R.string.me_privacy, R.string.me_terms, R.string.me_version)[index]
    val icon = listOf(R.drawable.ic_me_creations, R.drawable.ic_me_watch, R.drawable.ic_me_feedback,
        R.drawable.ic_me_diamond, R.drawable.ic_me_privacy, R.drawable.ic_me_terms, R.drawable.ic_me_version)[index]
    val shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(shape)
        .background(Color(0xB31F1F27)).border(1.dp, Color.White.copy(alpha = .06f), shape)
        .then(if (entry != MeMenuEntry.Version) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(80.dp).background(tint))
        Spacer(Modifier.width(16.dp))
        Box(Modifier.size(44.dp).background(Color(0xE60D0D16), RoundedCornerShape(8.dp))
            .border(1.dp, tint.copy(alpha = .4f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(stringResource(label), color = Color(0xFFE4E1ED), fontSize = 17.sp,
            modifier = Modifier.weight(1f), maxLines = 2)
        Text(if (entry == MeMenuEntry.Version) BuildConfig.VERSION_NAME else "›", color = AchatMuted,
            fontSize = if (entry == MeMenuEntry.Version) 12.sp else 24.sp, modifier = Modifier.padding(16.dp))
    }
}

@Composable
internal fun MeDetailHeader(title: String, onBack: () -> Unit, backDescriptionRes: Int = R.string.back_to_me_description) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xA60D071A))
        .border(1.dp, AchatCyan.copy(alpha = .3f), RoundedCornerShape(16.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_me_back), stringResource(backDescriptionRes), tint = AchatCyan)
        }
        Text(title, color = AchatCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun MePlaceholderScreen(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        MeDetailHeader(title, onBack)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.me_coming_soon), color = AchatMuted)
        }
    }
}

internal fun diamondAmountLabel(amount: Int, type: String): String =
    String.format(Locale.US, "%+.2f", walletTransactionDisplayAmount(amount, type).toDouble())

internal fun diamondRecordTime(raw: String): String {
    val time=yumo.achat.core.payment.paymentTime(raw) ?: return raw
    val zone=if(raw.endsWith("Z")) "UTC" else "GMT${raw.takeLast(6)}"
    return java.text.SimpleDateFormat("HH:mm dd/MM/yyyy",Locale.US).apply {
        timeZone=java.util.TimeZone.getTimeZone(zone)
    }.format(java.util.Date(time))
}


@Composable
internal fun DiamondHistoryScreen(loadPage: suspend (Int) -> List<WalletTransaction>, onBack: () -> Unit) {
    var records by remember { mutableStateOf(emptyList<WalletTransaction>()) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(page, revision) {
        loading = true
        failed = false
        try {
            val result = loadPage(page)
            records = (if (page == 1) result else records + result).distinctBy { it.id }
            hasMore = result.size >= 20
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF211039), Color(0xFF07060F), Color(0xFF210A1C))))
        .statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        MeDetailHeader(stringResource(R.string.me_diamond_history), onBack)
        Spacer(Modifier.height(24.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            records.forEach { record ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(Color(0x26FFFFFF), Color(0x08FFFFFF))))
                    .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(16.dp)).padding(17.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(Color(0x1AA855F7), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0x4DA855F7), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Icon(painterResource(if (walletTransactionDisplayAmount(record.amount, record.type) >= 0) R.drawable.ic_me_diamond else R.drawable.ic_history_video),
                            null, tint = Color(0xFFC084FC), modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(record.description?.takeIf { it.isNotBlank() } ?: record.type, color = Color.White,
                            fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(diamondRecordTime(record.createdAt), color = AchatMuted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(diamondAmountLabel(record.amount, record.type), color = if (walletTransactionDisplayAmount(record.amount, record.type) >= 0) Color(0xFF34D399) else Color(0xFFE2E8F0),
                        fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (loading) Text(stringResource(R.string.task_history_loading), color = AchatCyan)
            if (failed) TextButton(onClick = { revision++ }) { Text(stringResource(R.string.me_history_retry), color = AchatPink) }
            if (!loading && !failed && records.isEmpty()) Text(stringResource(R.string.me_history_empty), color = AchatMuted)
            if (!loading && !failed && hasMore) TextButton(onClick = { page++ }) { Text(stringResource(R.string.task_history_load_more), color = AchatCyan) }
        }
    }
}

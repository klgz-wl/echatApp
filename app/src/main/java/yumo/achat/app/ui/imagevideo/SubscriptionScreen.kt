package yumo.achat.app.ui.imagevideo

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yumo.achat.app.BuildConfig
import yumo.achat.app.R

internal data class SubscriptionPlan(val id: String, val title: String, val price: String, val subtitle: String = "")
private val SubscriptionCyan = Color(0xFF00F0FF)
private val SubscriptionPink = Color(0xFFFF4B89)

@Composable
internal fun SubscriptionScreen(
    plans: List<SubscriptionPlan>, loading: Boolean, error: String?,
    onRetry: () -> Unit, onBack: () -> Unit, onSubscribe: (String) -> Unit,
    modifier: Modifier = Modifier, purchaseBusy: Boolean = false, purchaseMessage: String? = null, purchaseComplete: Boolean = false,
    completedProductId: String? = null, planSelectionEnabled: Boolean = !purchaseBusy,
    checkoutState: yumo.achat.core.billing.SubscriptionPurchaseUiState? = null,
    onCheckPurchase: () -> Unit = {}, onResumePurchase: () -> Unit = {},
    templateEntry: Boolean = false,
) {
    BackHandler(onBack = onBack)
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = plans.firstOrNull { it.id == selectedId } ?: plans.getOrNull(if (plans.size == 3) 1 else 0)
    val context = LocalContext.current
    val backLabel = stringResource(R.string.subscription_back)
    val selectedComplete = purchaseComplete && selected?.id == completedProductId
    val action = checkoutState?.let { yumo.achat.core.billing.subscriptionCheckoutAction(it, selected?.id) }
        ?: yumo.achat.core.billing.SubscriptionCheckoutAction.Buy
    val blocked = action in setOf(yumo.achat.core.billing.SubscriptionCheckoutAction.Unavailable,
        yumo.achat.core.billing.SubscriptionCheckoutAction.Owned)
    val enabled = !blocked && !loading && error == null && selected != null && !purchaseBusy && !selectedComplete
    val purchaseAction: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
            .background(Brush.horizontalGradient(if (enabled) listOf(SubscriptionCyan, Color(0xFF4F46E5), Color(0xFFFF007F)) else listOf(Color(0xFF343341), Color(0xFF343341))))
            .clickable(enabled = enabled, role = Role.Button) {
                when (action) {
                    yumo.achat.core.billing.SubscriptionCheckoutAction.Check -> onCheckPurchase()
                    yumo.achat.core.billing.SubscriptionCheckoutAction.Resume -> onResumePurchase()
                    else -> selected?.let { onSubscribe(it.id) }
                }
            }.padding(14.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (selectedComplete) R.string.subscription_subscribed else if (purchaseBusy) R.string.subscription_processing else when (action) {
                    yumo.achat.core.billing.SubscriptionCheckoutAction.Check -> R.string.subscription_check_status
                    yumo.achat.core.billing.SubscriptionCheckoutAction.Resume -> R.string.subscription_resume_checkout
                    yumo.achat.core.billing.SubscriptionCheckoutAction.Unavailable -> R.string.subscription_plan_unavailable_action
                    yumo.achat.core.billing.SubscriptionCheckoutAction.Owned -> R.string.subscription_already_owned_action
                    else -> R.string.subscription_action
                }), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (!purchaseBusy) Image(painterResource(R.drawable.subscription_arrow), null, Modifier.size(16.dp))
            }
        }
    }
    val body: @Composable ColumnScope.() -> Unit = {
        if (!templateEntry) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xA60D071A))
            .border(1.dp, SubscriptionCyan.copy(alpha = .3f), RoundedCornerShape(16.dp)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BackGlyph(Modifier.size(48.dp).clickable(onClick = onBack).semantics { contentDescription = backLabel })
            Text(stringResource(R.string.subscription_title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Spacer(Modifier.height(22.dp))
        } else Spacer(Modifier.height(8.dp))
        Box(Modifier.size(if (templateEntry) 80.dp else 112.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(if (templateEntry) R.drawable.template_subscription_crown else R.drawable.subscription_crown), null,
                Modifier.requiredSize(if (templateEntry) 192.dp else 112.dp))
        }
        Text(stringResource(R.string.subscription_unlock), style = TextStyle(brush = Brush.horizontalGradient(listOf(SubscriptionCyan, SubscriptionPink))), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.subscription_tagline), color = Color(0xFFB9CACB), fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        FeatureComparison(compact = templateEntry)
        Spacer(Modifier.height(if (templateEntry) 20.dp else 24.dp))
        when {
            loading -> CircularProgressIndicator(color = SubscriptionCyan, modifier = Modifier.size(32.dp))
            error != null -> {
                Text(error, color = SubscriptionPink, textAlign = TextAlign.Center)
                Text(stringResource(R.string.subscription_retry), Modifier.clickable(onClick = onRetry).padding(16.dp), color = SubscriptionCyan)
            }
            plans.isEmpty() -> {
                Text(stringResource(R.string.subscription_empty), color = Color(0xFFB9CACB), textAlign = TextAlign.Center)
                Text(stringResource(R.string.subscription_retry), Modifier.clickable(onClick = onRetry).padding(16.dp), color = SubscriptionCyan)
            }
            else -> {
                // Wrap at three columns so narrow devices and larger catalogs remain usable.
                plans.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (templateEntry) 24.dp else 10.dp)) {
                        row.forEach { plan ->
                            val chosen = selected?.id == plan.id
                            Column(Modifier.weight(1f).heightIn(min = if (templateEntry) 144.dp else 128.dp)
                                .then(if (templateEntry && chosen) Modifier.shadow(10.dp, RoundedCornerShape(16.dp), ambientColor = Color(0x4D00F0FF), spotColor = Color(0x4D00F0FF)) else Modifier)
                                .clip(RoundedCornerShape(16.dp))
                                .then(if (templateEntry && chosen) Modifier.background(Brush.verticalGradient(listOf(Color(0xFF181838), Color(0xFF121026))))
                                    else Modifier.background(if (chosen) Color(0xFF181838) else Color(0x991F1E2B)))
                                .border(if (chosen) 2.dp else 1.dp, if (chosen) SubscriptionCyan else Color(0x803B494B), RoundedCornerShape(16.dp))
                                .selectable(selected = chosen, enabled = !loading && error == null && planSelectionEnabled, role = Role.RadioButton, onClick = { selectedId = plan.id })
                                .padding(horizontal = 8.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(plan.title, color = if (chosen) SubscriptionCyan else Color(0xFFB9CACB), fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                Text(plan.price, color = Color(0xFFDBFCFF), fontSize = if (templateEntry && chosen) 22.sp else 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                if (plan.subtitle.isNotBlank()) Text(plan.subtitle, color = Color(0xFFB9CACB), fontSize = 10.sp, textAlign = TextAlign.Center)
                                if (templateEntry) Text(stringResource(if (chosen) R.string.template_subscription_selected else R.string.template_subscription_standard),
                                    color = if (chosen) SubscriptionCyan else Color(0xFF849495), fontSize = 10.sp,
                                    modifier = Modifier.fillMaxWidth().background(if (chosen) Color(0x3300F0FF) else Color(0x99292936), RoundedCornerShape(4.dp)).padding(4.dp),
                                    textAlign = TextAlign.Center)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        if (!templateEntry) purchaseMessage?.let { Text(it, color = SubscriptionCyan, fontSize = 12.sp, textAlign = TextAlign.Center); Spacer(Modifier.height(8.dp)) }
        if (!templateEntry) purchaseAction()
        if (!templateEntry) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.subscription_renewal), color = Color(0xFF94A3B8), fontSize = 11.sp, textAlign = TextAlign.Center)
            Text(stringResource(R.string.subscription_terms), Modifier.clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.TERMS_OF_SERVICE_URL))) }
            }.padding(8.dp), color = SubscriptionCyan, fontSize = 11.sp)
        }
    }
    if (templateEntry) {
        val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
        Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .clip(shape).background(Color(0xF2100D22))
            .border(1.dp, Brush.linearGradient(listOf(SubscriptionCyan.copy(alpha = .35f), SubscriptionPink.copy(alpha = .35f))), shape)) {
            Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(48.dp, 6.dp).background(SubscriptionCyan.copy(alpha = .25f), RoundedCornerShape(99.dp)))
            }
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = context.getString(R.string.template_subscription_close) }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp).background(Color(0x99292936), RoundedCornerShape(99.dp))
                        .border(1.dp, Color(0x663B494B), RoundedCornerShape(99.dp)), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.template_subscription_close), null, Modifier.size(14.dp))
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, content = body)
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                purchaseMessage?.let {
                    Text(it, color = SubscriptionCyan, fontSize = 12.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                }
                purchaseAction()
            }
        }
    } else {
        Column(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF211036), Color(0xFF07060F), Color(0xFF21091C))))
            .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, content = body)
    }
}

@Composable
private fun FeatureComparison(compact: Boolean = false) {
    val labels = listOf(R.string.subscription_feature_templates, R.string.subscription_feature_ads, R.string.subscription_feature_speed, if (compact) R.string.template_subscription_long_videos else R.string.subscription_feature_video, R.string.subscription_feature_chat)
    val icons = listOf(R.drawable.subscription_template, R.drawable.subscription_adfree, R.drawable.subscription_speed, R.drawable.subscription_video, R.drawable.subscription_chat)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xCC1B1A27)).border(1.dp, Color(0x663B494B), RoundedCornerShape(16.dp))) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 45.dp else 0.dp).background(Color(0x4D343341)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.subscription_comparison), color = Color(0xFFB9CACB), fontSize = 10.sp, modifier = Modifier.weight(2f), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.subscription_free), color = Color(0xFF849495), fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (compact) Image(painterResource(R.drawable.subscription_small_crown), null, Modifier.size(20.dp))
                Text(stringResource(R.string.me_vip_label), color = SubscriptionCyan, fontSize = 10.sp, textAlign = TextAlign.Center)
            }
        }
        labels.forEachIndexed { i, label ->
            Row(Modifier.fillMaxWidth().heightIn(min = 51.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(icons[i]), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(label), color = Color(0xFFE3E0F2), fontSize = if (i == 3) 10.sp else 12.sp)
                }
                Text(stringResource(R.string.subscription_unavailable), color = Color(0x99849495), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Image(painterResource(R.drawable.subscription_check), null, Modifier.size(44.dp)) }
            }
            if (i < 4) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x333B494B)))
        }
    }
}

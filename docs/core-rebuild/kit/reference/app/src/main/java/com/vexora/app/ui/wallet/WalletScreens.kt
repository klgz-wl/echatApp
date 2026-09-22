package com.vexora.app.ui.wallet

import androidx.annotation.DrawableRes
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.vexora.app.R
import com.vexora.app.ui.components.*
import com.vexora.app.ui.theme.*
import com.vexora.core.wallet.CoinProduct
import com.vexora.core.wallet.CoinDisplayConfiguration
import com.vexora.core.billing.*
import java.util.Locale

@Composable
fun WalletBalance(state: WalletUiState): String = state.wallet.balance?.let { stringResource(R.string.coins_count, it) }
    ?: stringResource(R.string.balance_unknown)

@Composable
private fun WalletIcon(@DrawableRes icon: Int, description: String, click: () -> Unit) {
    IconButton(click, Modifier.size(Design.Touch.xdp)) {
        Box(Modifier.size(Design.BackSize.xdp).background(Design.Card, CircleShape), contentAlignment = Alignment.Center) {
            Image(painterResource(icon), description, Modifier.size(Design.Icon.xdp))
        }
    }
}

@Composable
fun PurchaseScreen(state: WalletUiState, displayConfig: CoinDisplayConfiguration, select: (String) -> Unit, refresh: () -> Unit, buy: () -> Unit,
    onBack: () -> Unit, onRecords: () -> Unit) {
    var footerHeight by remember { mutableIntStateOf(0) }
    val footerPadding = if (footerHeight > 0) with(LocalDensity.current) { footerHeight.toDp() } else Design.PurchaseFooterHeight.xdp
    Box(Modifier.fillMaxSize().background(Design.Background)) {
        Image(painterResource(R.drawable.purchase_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Column(Modifier.fillMaxSize().padding(bottom = footerPadding)
            .verticalScroll(rememberScrollState()).padding(top = Design.PurchaseHeroTop.xdp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.purchase_title), stringResource(R.string.purchase),
                Modifier.size(Design.PurchaseTitleWidth.xdp, Design.PurchaseTitleHeight.xdp))
            Spacer(Modifier.height(Design.LogoGap.xdp))
            Text(stringResource(R.string.purchase_subtitle), textAlign = TextAlign.Center, fontSize = Design.ProductLabel.xsp, lineHeight = Design.SubtitleLineHeight.xsp)
            Spacer(Modifier.height(Design.PurchaseGridTopGap.xdp))
            Column(Modifier.fillMaxWidth().padding(horizontal = Design.ProductGridMargin.xdp), verticalArrangement = Arrangement.spacedBy(Design.RecordGap.xdp)) {
                state.wallet.products.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Design.ProductGap.xdp)) {
                        row.forEach { product -> ProductCard(product, displayConfig,
                            product.id == state.selectedId, { select(product.id) }, Modifier.weight(1f), state.purchase.status != CoinPurchaseStatus.BUSY) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (state.productsBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val error = FailureText(state.productsError)
                if (error != null) {
                    Text(error, color = Design.Error, fontSize = Design.ProductLabel.xsp)
                    TextButton(refresh, Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.retry)) }
                } else if (!state.productsBusy && state.wallet.products.isEmpty()) {
                    Text(stringResource(R.string.products_empty), modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = Design.PurchaseHeaderTop.xdp, start = Design.BackLeft.xdp, end = Design.BackLeft.xdp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Box(Modifier.weight(1f).padding(horizontal = Design.SmallGap.xdp), contentAlignment = Alignment.Center) {
                BalancePill(WalletBalance(state))
            }
            WalletIcon(R.drawable.ic_history, stringResource(R.string.record), onRecords)
        }
        Column(Modifier.align(Alignment.BottomCenter).onSizeChanged { footerHeight = it.height }.navigationBarsPadding().padding(Design.Page.xdp), horizontalAlignment = Alignment.CenterHorizontally) {
            val purchaseText = PaymentStatusText(state.payment) ?: PurchaseStatusText(state.purchase)
            if (purchaseText != null) Text(purchaseText, color = Design.Muted, fontSize = Design.ProductBadge.xsp, textAlign = TextAlign.Center)
            PrimaryButton(stringResource(if (state.purchase.status == CoinPurchaseStatus.BUSY) R.string.processing_purchase else R.string.purchase_coins), buy,
                Modifier.fillMaxWidth(), enabled = state.selectedId != null && !state.productsBusy && state.purchase.status != CoinPurchaseStatus.BUSY)
        }
    }
}

@Composable
private fun ProductCard(product: CoinProduct, displayConfig: CoinDisplayConfiguration, selected: Boolean, click: () -> Unit, modifier: Modifier, enabled: Boolean) {
    val shape = RoundedCornerShape(Design.CardRadius.xdp)
    Box(modifier.height(Design.ProductHeight.xdp).clip(shape).clickable(enabled = enabled, onClick = click)) {
        if (!selected) Box(Modifier.matchParentSize().background(Design.Card, shape))
        Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = Design.ProductBorderInset.xdp).padding(bottom = Design.ProductBottomInset.xdp)
            .fillMaxWidth().height(Design.ProductBodyHeight.xdp)) {
            if (selected) Box(Modifier.matchParentSize().background(Design.White, shape))
            else Image(painterResource(R.drawable.purchase_card_border), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Column(Modifier.fillMaxSize().padding(Design.SmallGap.xdp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Design.RecordGap.xdp, Alignment.CenterVertically)) {
                val color = if (selected) Design.Blue else Design.White
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.SmallGap.xdp)) {
                    Image(painterResource(if (selected) R.drawable.ic_coin_blue else R.drawable.ic_coin), null, Modifier.size(Design.CoinIcon.xdp))
                    Text(if (product.showBonus) stringResource(R.string.coin_bonus_count, product.coins ?: 0L, product.displayBonus)
                        else stringResource(R.string.coins_count, product.coins ?: 0L), Modifier.weight(1f), fontSize = Design.ProductLabel.xsp, color = color)
                }
                Text(String.format(Locale.US, stringResource(R.string.product_price_format), displayConfig.symbol(product.currency), product.displayPrice),
                    fontSize = Design.ProductLabel.xsp, color = color, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun PurchaseStatusText(state: CoinPurchaseState): String? = when (state.status) {
    CoinPurchaseStatus.PENDING -> stringResource(R.string.purchase_pending)
    CoinPurchaseStatus.CANCELLED -> stringResource(R.string.purchase_cancelled)
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(state: WalletUiState, refresh: () -> Unit, more: () -> Unit, retry: () -> Unit, onBack: () -> Unit) {
    var help by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Design.Background)) {
        Image(painterResource(R.drawable.page_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = Design.PageBackgroundAlpha)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = Design.RecordHeaderTop.xdp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = Design.BackLeft.xdp)) {
                BackButton(onBack)
                Text(stringResource(R.string.record), Modifier.align(Alignment.Center), fontSize = Design.Title.xsp)
                Box(Modifier.align(Alignment.CenterEnd)) { WalletIcon(R.drawable.ic_help, stringResource(R.string.recharge_help)) { help = true } }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = Design.RecordMargin.xdp, vertical = Design.HeaderListGap.xdp),
                verticalArrangement = Arrangement.spacedBy(Design.RecordGap.xdp)) {
                items(state.wallet.transactions, key = { it.id }) { record ->
                    Row(Modifier.fillMaxWidth().heightIn(min = Design.RecordHeight.xdp)
                        .background(Design.Card, RoundedCornerShape(Design.CardRadius.xdp))
                        .border(Design.BorderWidth.xdp, Design.Border, RoundedCornerShape(Design.CardRadius.xdp))
                        .padding(horizontal = Design.RecordPadding.xdp, vertical = Design.RecordVerticalPadding.xdp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Design.ProductGap.xdp)) {
                            Text(record.description?.takeIf(String::isNotBlank) ?: stringResource(R.string.transaction_fallback), fontSize = Design.Title.xsp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(record.createdAt, color = Design.Muted, fontSize = Design.Body.xsp)
                        }
                        Spacer(Modifier.width(Design.SmallGap.xdp))
                        val amount = record.amountPresentation()
                        Text(stringResource(amount.format, amount.value), fontSize = Design.RecordAmount.xsp,
                            color = amount.color, fontWeight = FontWeight.Medium)
                    }
                }
                item {
                    if (state.recordsBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else if (state.recordsError != null) {
                        Text(FailureText(state.recordsError).orEmpty(), color = Design.Error)
                        TextButton(retry) { Text(stringResource(R.string.retry)) }
                    } else if (state.wallet.transactions.isEmpty()) Text(stringResource(R.string.records_empty), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    else if (state.wallet.hasMore) TextButton(more, Modifier.fillMaxWidth()) { Text(stringResource(R.string.load_more)) }
                }
            }
            TextButton(refresh, Modifier.fillMaxWidth(), enabled = !state.recordsBusy) { Text(stringResource(R.string.refresh)) }
        }
    }
    if (help) ModalBottomSheet(onDismissRequest = { help = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Design.Background, dragHandle = null, shape = RoundedCornerShape(topStart = Design.CardRadius.xdp, topEnd = Design.CardRadius.xdp)) {
        DarkDialogSystemBars()
        Column(Modifier.fillMaxWidth().heightIn(min = Design.SheetHeight.xdp).padding(horizontal = Design.Page.xdp).padding(bottom = Design.SheetBottom.xdp)) {
            IconButton({ help = false }, Modifier.align(Alignment.End)) {
                Image(painterResource(R.drawable.ic_close), stringResource(R.string.close), Modifier.size(Design.BackIconHeight.xdp))
            }
            Spacer(Modifier.height(Design.SheetContentGap.xdp))
            Text(stringResource(R.string.recharge_explanation), Modifier.padding(horizontal = Design.Page.xdp), fontSize = Design.Title.xsp, lineHeight = Design.SheetLineHeight.xsp)
            Spacer(Modifier.height(Design.SheetContentGap.xdp))
            Button({ help = false }, Modifier.fillMaxWidth().height(Design.Touch.xdp), colors = ButtonDefaults.buttonColors(containerColor = Design.White, contentColor = Design.Background)) {
                Text(stringResource(R.string.ok), fontSize = Design.Body.xsp)
            }
        }
    }
}

@Composable
private fun PaymentStatusText(state: com.vexora.core.payment.PaymentViewState): String? {
    val record = state.record ?: return null
    val resource = when {
        record.stage == com.vexora.core.payment.PaymentStage.AWAITING_FULFILLMENT -> R.string.payment_fulfillment_pending
        record.stage in setOf(com.vexora.core.payment.PaymentStage.CLOSED, com.vexora.core.payment.PaymentStage.TIMED_OUT,
            com.vexora.core.payment.PaymentStage.UNCERTAIN) -> R.string.payment_check_later
        else -> return null
    }
    return stringResource(resource)
}

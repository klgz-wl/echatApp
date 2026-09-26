package com.vexora.app.ui.wallet

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vexora.app.R
import com.vexora.app.ui.components.*
import com.vexora.app.ui.theme.*
import com.zorv.core.payment.*
import kotlinx.coroutines.delay

/** 原生收银台容器复用既有 WebScreen 控件；渠道内容由真实 HTTPS 网页提供。 */
@Composable
fun PaymentHost(vm: PaymentViewModel, epoch: String?, onSuccessReturn: (String) -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val record = state.record?.takeIf { epoch != null && state.epoch == epoch }
    val owner = LocalLifecycleOwner.current
    val currentEpoch by rememberUpdatedState(epoch)
    LaunchedEffect(vm, owner, context) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var toast: android.widget.Toast? = null
            try {
                vm.failures.collect { failedEpoch ->
                    if (failedEpoch == currentEpoch) {
                        toast?.cancel()
                        toast = android.widget.Toast.makeText(context, R.string.payment_failure_toast, android.widget.Toast.LENGTH_SHORT)
                        toast?.show()
                    }
                }
            } finally { toast?.cancel() }
        }
    }
    val onReturn by rememberUpdatedState(onSuccessReturn)
    // 退出或切换账号后清除内置网页会话；外部浏览器的会话由浏览器自身管理。
    var lastEpoch by rememberSaveable { mutableStateOf(epoch) }
    LaunchedEffect(epoch) {
        if (lastEpoch != epoch) { CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData() }
        lastEpoch = epoch
    }
    if (record?.stage == PaymentStage.SUCCESS && !record.successAcknowledged) {
        LaunchedEffect(record.key, epoch) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                vm.shown(record.key)
                val current = vm.state.value.record ?: return@repeatOnLifecycle
                val remaining = vm.displayMs - (System.currentTimeMillis() - (current.successShownAt ?: System.currentTimeMillis())).coerceAtLeast(0)
                delay(remaining.coerceIn(0, vm.displayMs))
                if (vm.state.value.epoch == epoch && vm.state.value.record?.key == record.key) {
                    vm.acknowledge(record.key)
                    if (vm.state.value.epoch == epoch) onReturn(record.source)
                }
            }
        }
        AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.payment_success_title)) },
            text = { Text(stringResource(R.string.recharge_successful)) }, confirmButton = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false))
    } else if (record != null && state.checkoutVisible) {
        PaymentScreen(record, state.busy, vm::opened, vm::close, vm::pageEvent)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PaymentScreen(record: PaymentRecord, checking: Boolean, opened: suspend (String) -> Boolean,
    close: (String) -> Unit, pageEvent: (String, String) -> Unit) {
    val context = LocalContext.current
    val init = record.initialized ?: return
    com.vexora.app.analytics.TrackPage("payment_checkout", mapOf("entry_source" to record.source, "pay_method" to init.channelCode, "open_mode" to init.openMode))
    var prepared by rememberSaveable(record.key) { mutableStateOf(false) }
    var launched by rememberSaveable(record.key) { mutableStateOf(false) }
    var failed by rememberSaveable(record.key) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val savedWeb = rememberSaveable(record.key) { Bundle() }
    val finish = { if (!checking) close(record.key); Unit }
    LaunchedEffect(record.key) {
        if (!opened(record.key)) return@LaunchedEffect
        prepared = true
        if (init.openMode == "external_browser" && !launched && !record.expired(System.currentTimeMillis()) &&
            (paymentTime(init.expiresAt) ?: 0) > System.currentTimeMillis()) {
            launched = true
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(init.paymentUrl)).addCategory(Intent.CATEGORY_BROWSABLE)) }
            catch (_: Exception) { failed = true; pageEvent(record.key, "page_load_error") }
        }
    }
    Dialog(onDismissRequest = finish, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = finish)
        Column(Modifier.fillMaxSize().background(Design.Background).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Design.Page.xdp), horizontalArrangement = Arrangement.SpaceBetween) {
                BackButton(finish)
                TextButton(finish, enabled = !checking) { Text(stringResource(R.string.payment_close)) }
            }
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (init.openMode == "external_browser") {
                StatusContent(stringResource(if (failed) R.string.web_failed else R.string.payment_browser_wait),
                    busy = checking, onRetry = if (failed) ({ launched = false; failed = false
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(init.paymentUrl)).addCategory(Intent.CATEGORY_BROWSABLE)); launched = true }
                        catch (_: Exception) { failed = true; pageEvent(record.key, "page_load_error") }
                    }) else null)
            } else if (prepared && !record.expired(System.currentTimeMillis())) {
                if (loading && !failed) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) {
                    Text(stringResource(R.string.web_failed), Modifier.padding(Design.Page.xdp), color = Design.Error)
                    TextButton(onClick = { failed = false; loading = true; web?.reload() }) { Text(stringResource(R.string.retry)) }
                }
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { viewContext ->
                    WebView(viewContext).apply {
                        web = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        webViewClient = object : WebViewClient() {
                            private var mainFailed = false
                            private var loaded = false
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val allowed = isPaymentHttpsUrl(request.url.toString().substringBefore('#'))
                                if (!allowed && request.isForMainFrame) { failed = true; mainFailed = true; pageEvent(record.key, "page_load_error") }
                                return !allowed
                            }
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { mainFailed = false; loaded = false; loading = true }
                            override fun onPageFinished(view: WebView, url: String) {
                                loading = false
                                if (!mainFailed && !loaded && isPaymentHttpsUrl(url)) { loaded = true; pageEvent(record.key, "page_loaded") }
                            }
                            private fun failMain() {
                                if (!mainFailed) pageEvent(record.key, "page_load_error")
                                mainFailed = true; failed = true; loading = false
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) failMain() }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) { if (request.isForMainFrame && response.statusCode >= 400) failMain() }
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) { handler.cancel(); failMain() }
                        }
                        if (savedWeb.isEmpty || restoreState(savedWeb) == null) loadUrl(init.paymentUrl!!)
                    }
                }, onRelease = { view -> view.saveState(savedWeb); view.stopLoading(); view.destroy(); web = null })
            }
        }
    }
}

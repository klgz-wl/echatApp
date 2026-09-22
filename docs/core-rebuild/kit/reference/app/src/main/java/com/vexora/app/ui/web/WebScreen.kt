package com.vexora.app.ui.web

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vexora.app.R
import com.vexora.app.ui.components.BackButton
import com.vexora.app.ui.components.StatusContent
import com.vexora.app.ui.theme.Design

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(url: String, onBack: () -> Unit, vm: WebViewModel = hiltViewModel()) {
    val content by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(url) { vm.prepare(url) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    var failed by remember(url) { mutableStateOf(false) }
    val back = { if (web?.canGoBack() == true) web?.goBack() else onBack(); Unit }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().background(Design.Background).safeDrawingPadding()) {
        BackButton(back)
        if (!failed && !content.failed && (loading || content.loading)) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed || content.failed) StatusContent(stringResource(R.string.web_failed), onRetry = { failed = false; loading = true; if (content.failed) vm.prepare(url) else web?.reload() })
        if (!content.loading && !content.failed) AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
            WebView(context).apply {
                web = this
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url
                        return target.scheme !in setOf("https", "http") && !(target.scheme == "file" && target.path.orEmpty().startsWith("/android_asset/web/"))
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                        if (request.isForMainFrame && response.statusCode >= 400) { failed = true; loading = false }
                    }
                    override fun onPageFinished(view: WebView, value: String) { loading = false }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) { failed = true; loading = false }
                    }
                }
                if (content.html != null) loadDataWithBaseURL("file:///android_asset/web/", content.html!!, "text/html", "UTF-8", null)
                else loadUrl(url)
            }
        })
    }
    DisposableEffect(Unit) { onDispose { web?.stopLoading(); web?.destroy(); web = null } }
}

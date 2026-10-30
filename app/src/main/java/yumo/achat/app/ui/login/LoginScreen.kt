package yumo.achat.app.ui.login

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import yumo.achat.app.BuildConfig
import yumo.achat.app.R

private val cyan = Color(0xFF00E5F5)
private val magenta = Color(0xFFF32996)
private val loginGradient = Brush.horizontalGradient(listOf(magenta, Color(0xFF9348E8), cyan))

private enum class LoginPage { Choice, Account, Terms, Privacy }

@Composable
fun LoginScreen(
    submitting: Boolean,
    serverError: String?,
    onDeviceLogin: () -> Unit,
    onAccountLogin: (String, String) -> Unit,
    onNavigate: () -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf(LoginPage.Choice) }
    var agreed by rememberSaveable { mutableStateOf(true) }
    var account by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var localError by remember { mutableStateOf<Int?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val agreementDescription = stringResource(R.string.login_agreement_checkbox)
    fun closeKeyboard() { keyboard?.hide(); focus.clearFocus() }
    fun submitAccount() {
        if (submitting) return
        closeKeyboard()
        localError = if (!agreed) R.string.login_agreement_required
        else if (!validLoginInput(account, password)) {
            if (account.length !in 3..50) R.string.login_account_invalid else R.string.login_password_invalid
        } else null
        if (localError == null) onAccountLogin(account, password)
    }
    BackHandler(page != LoginPage.Choice) {
        page = LoginPage.Choice
        localError = null
        onNavigate()
    }
    if (page == LoginPage.Terms || page == LoginPage.Privacy) {
        PolicyWebView(
            url = if (page == LoginPage.Terms) BuildConfig.TERMS_OF_SERVICE_URL else BuildConfig.PRIVACY_POLICY_URL,
            onBack = { page = LoginPage.Choice; onNavigate() },
        )
        return
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF270D27), Color(0xFF080A19), Color(0xFF0C0716))),
        ),
    ) {
        val contentInsets = if (page == LoginPage.Account) {
            Modifier.imePadding().verticalScroll(rememberScrollState())
        } else {
            Modifier.navigationBarsPadding()
        }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().then(contentInsets)
                .padding(horizontal = 25.dp).padding(bottom = 25.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (page == LoginPage.Choice) {
                Row(Modifier.fillMaxWidth().padding(top = 132.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    ProtocolPill(stringResource(R.string.login_system_id))
                    ProtocolPill(stringResource(R.string.login_version, BuildConfig.VERSION_NAME))
                }
                Spacer(Modifier.height(13.dp))
                Column(
                    Modifier.fillMaxWidth().height(250.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, cyan.copy(alpha = .30f), RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF090713), Color(0xFF061D25))))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.login_brand), color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.login_tagline), color = cyan, fontSize = 12.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(25.dp))
                    ProtocolPill(stringResource(R.string.login_studio))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProtocolPill(stringResource(R.string.login_cyber_avatar), magenta)
                        ProtocolPill(stringResource(R.string.login_neural_render), cyan)
                    }
                    Spacer(Modifier.height(15.dp))
                    Text(stringResource(R.string.login_pager_decoration), color = cyan, fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                GradientAction(stringResource(R.string.login_device), submitting) {
                    closeKeyboard()
                    localError = if (agreed) null else R.string.login_agreement_required
                    if (agreed) onDeviceLogin()
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Color.White.copy(alpha = .36f), RoundedCornerShape(14.dp))
                        .clickable(enabled = !submitting) { page = LoginPage.Account; localError = null; onNavigate() },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.login_account_option), color = Color.White, fontWeight = FontWeight.Medium) }
                Spacer(Modifier.height(45.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = agreed, onCheckedChange = { agreed = it; localError = null },
                        modifier = Modifier.size(30.dp).semantics { contentDescription = agreementDescription },
                        colors = CheckboxDefaults.colors(checkedColor = cyan, checkmarkColor = Color.Black),
                    )
                    Column {
                        Row {
                            Text("${stringResource(R.string.login_agreement_intro)} ", color = Color(0xFFADB6CB), fontSize = 11.sp)
                            Text(stringResource(R.string.login_terms), Modifier.clickable { page = LoginPage.Terms; onNavigate() }, color = cyan, fontSize = 11.sp)
                        }
                        Row {
                            Text("${stringResource(R.string.login_agreement_join)} ", color = Color(0xFFADB6CB), fontSize = 11.sp)
                            Text(stringResource(R.string.login_privacy), Modifier.clickable { page = LoginPage.Privacy; onNavigate() }, color = cyan, fontSize = 11.sp)
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 17.dp)) {
                    BackCircle { page = LoginPage.Choice; localError = null; onNavigate() }
                }
                Spacer(Modifier.height(135.dp))
                ProtocolPill(stringResource(R.string.login_auth_protocol), magenta)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.login_brand), color = Color.White, fontSize = 43.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.login_account_caption), color = Color(0xFF9CA9C2),
                    textAlign = TextAlign.Center, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 29.dp),
                )
                Spacer(Modifier.height(22.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(21.dp))
                        .border(1.dp, Color(0xFF44506B), RoundedCornerShape(21.dp))
                        .background(Color(0xD9151428)).padding(17.dp),
                ) {
                    FormLabel(stringResource(R.string.login_account_label), stringResource(R.string.login_required), cyan)
                    Spacer(Modifier.height(8.dp))
                    LoginField(account, { account = cleanAccountInput(it); localError = null }, stringResource(R.string.login_account_hint), ImeAction.Next)
                    Spacer(Modifier.height(17.dp))
                    FormLabel(stringResource(R.string.login_password_label), stringResource(R.string.login_encrypted), magenta)
                    Spacer(Modifier.height(8.dp))
                    val passwordDescription = stringResource(if (showPassword) R.string.login_hide_password else R.string.login_show_password)
                    LoginField(password, { password = cleanPasswordInput(it); localError = null }, stringResource(R.string.login_password_hint), ImeAction.Done,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(), onDone = ::submitAccount,
                        trailing = {
                            Text(if (showPassword) "◉" else "◎", Modifier.clickable { showPassword = !showPassword }
                                .semantics { contentDescription = passwordDescription }, color = Color(0xFF8090AB), fontSize = 23.sp)
                        })
                    Spacer(Modifier.height(15.dp))
                    GradientAction(stringResource(R.string.login_sign_in), submitting, ::submitAccount)
                }
                Spacer(Modifier.height(25.dp))
            }
            (localError?.let { stringResource(it) } ?: serverError)?.let {
                Text(it, color = Color(0xFFFF9AC7), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable private fun ProtocolPill(label: String, color: Color = cyan) {
    Box(Modifier.clip(RoundedCornerShape(30.dp)).border(1.dp, color.copy(alpha = .65f), RoundedCornerShape(30.dp))
        .background(Color(0xFF090E1D)).padding(horizontal = 9.dp, vertical = 5.dp)) {
        Text(label, color = color, fontSize = 8.sp, letterSpacing = 1.sp)
    }
}

@Composable private fun GradientAction(label: String, busy: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(13.dp)).background(loginGradient)
        .clickable(enabled = !busy, onClick = onClick), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable private fun FormLabel(label: String, suffix: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(suffix, color = color.copy(alpha = .7f), fontSize = 8.sp)
    }
}

@Composable private fun LoginField(
    value: String, onValueChange: (String) -> Unit, hint: String, imeAction: ImeAction,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onDone: () -> Unit = {}, trailing: @Composable (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp)).background(Color.White)
        .padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = true,
            textStyle = TextStyle(color = Color(0xFF172239), fontSize = 14.sp),
            cursorBrush = SolidColor(cyan), visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = Color(0xFF9AA8BC), fontSize = 13.sp); inner() } },
        )
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

@Composable private fun BackCircle(onClick: () -> Unit) {
    val description = stringResource(R.string.login_back)
    Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF111429)).clickable(onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            drawLine(Color.White, Offset(size.width * .68f, size.height * .18f), Offset(size.width * .32f, size.height * .5f), 2.dp.toPx(), StrokeCap.Round)
            drawLine(Color.White, Offset(size.width * .32f, size.height * .5f), Offset(size.width * .68f, size.height * .82f), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable private fun PolicyWebView(url: String, onBack: () -> Unit) {
    var loading by remember(url) { mutableStateOf(true) }
    var failed by remember(url) { mutableStateOf(false) }
    var view by remember(url) { mutableStateOf<WebView?>(null) }
    val loadingDescription = stringResource(R.string.login_loading_page)
    DisposableEffect(view) { onDispose { view?.destroy() } }
    Column(Modifier.fillMaxSize().background(Color(0xFF090C1D)).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.padding(16.dp)) { BackCircle(onBack) }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) { loading = false }
                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                if (request?.isForMainFrame == true) { failed = true; loading = false }
                            }
                        }
                        loadUrl(url)
                        view = this
                    }
                }, modifier = Modifier.fillMaxSize(),
            )
            if (loading) CircularProgressIndicator(
                Modifier.align(Alignment.Center).semantics { contentDescription = loadingDescription },
                color = cyan,
            )
            if (failed) Text(stringResource(R.string.login_page_unavailable), Modifier.align(Alignment.Center), color = Color.White)
        }
    }
}

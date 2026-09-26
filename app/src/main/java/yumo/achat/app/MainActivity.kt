package yumo.achat.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import yumo.achat.app.attribution.AchatAttributionRuntime
import yumo.achat.app.analytics.AchatAnalyticsRuntime
import yumo.achat.app.analytics.AnalyticsConsent
import yumo.achat.app.ui.imagevideo.ImageToVideoScreen
import yumo.achat.app.ui.theme.AchatTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.Lazy
import javax.inject.Inject
import yumo.achat.core.billing.BillingRepository
import yumo.achat.core.payment.PaymentCoordinator
import yumo.achat.core.payment.PurchaseRouter
import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.wallet.RechargeNotifications
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var billingProvider: Lazy<BillingRepository>
    @Inject lateinit var paymentCoordinatorProvider: Lazy<PaymentCoordinator>
    @Inject lateinit var purchaseRouterProvider: Lazy<PurchaseRouter>
    @Inject lateinit var sessionCoordinatorProvider: Lazy<SessionCoordinator>
    @Inject lateinit var rechargeNotificationsProvider: Lazy<RechargeNotifications>
    private var businessStarted = false

    private fun beginBusinessStartup() {
        if (businessStarted) return
        businessStarted = true
        if (AnalyticsConsent.granted(this) && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            AchatAnalyticsRuntime.get(this).foreground(true)
        }
        if (AnalyticsConsent.granted(this) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            AchatAttributionRuntime.get(this).onActivityResumed(this)
        }
        lifecycleScope.launch {
            billingProvider.get().recovered.collect { orderId ->
                purchaseRouterProvider.get().recoveredPurchase(orderId)
            }
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) lifecycleScope.launch {
            rechargeNotificationsProvider.get().setForeground(true)
            sessionCoordinatorProvider.get().synchronize()
            billingProvider.get().onForeground()
            paymentCoordinatorProvider.get().foreground(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (shouldEnableSecureWindow(BuildConfig.ENABLE_SECURE_WINDOW, BuildConfig.DEBUG)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            AchatTheme {
                val gate: RegionGateViewModel = viewModel()
                val state by gate.state.collectAsState()
                LaunchedEffect(Unit) { gate.check() }
                LaunchedEffect(state.allowed) {
                    if (state.allowed) beginBusinessStartup()
                }
                BackHandler(enabled = !state.allowed) { finishAndRemoveTask() }
                when {
                    !state.allowed -> RegionGateScreen(
                        state = state,
                        onRetry = gate::check,
                        onExit = ::finishAndRemoveTask,
                    )
                    else -> ImageToVideoScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (businessStarted && AnalyticsConsent.granted(this)) {
            AchatAttributionRuntime.get(this).onActivityResumed(this)
        }
    }

    override fun onStart() {
        super.onStart()
        if (businessStarted) {
            rechargeNotificationsProvider.get().setForeground(true)
            if (AnalyticsConsent.granted(this)) AchatAnalyticsRuntime.get(this).foreground(true)
            lifecycleScope.launch {
                sessionCoordinatorProvider.get().synchronize()
                billingProvider.get().onForeground()
                paymentCoordinatorProvider.get().foreground(true)
            }
        }
    }

    override fun onStop() {
        if (businessStarted && AnalyticsConsent.granted(this) && !isChangingConfigurations) {
            AchatAnalyticsRuntime.get(this).foreground(false)
        }
        if (businessStarted) {
            rechargeNotificationsProvider.get().setForeground(false)
            paymentCoordinatorProvider.get().foreground(false)
        }
        super.onStop()
    }

    override fun onPause() {
        if (businessStarted && AnalyticsConsent.granted(this)) {
            AchatAttributionRuntime.get(this).onActivityPaused(this)
        }
        super.onPause()
    }

}

@Composable
private fun RegionGateScreen(state: RegionGateState, onRetry: () -> Unit, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (state.busy) {
                CircularProgressIndicator()
                Text(stringResource(R.string.region_checking))
            } else if (state.restricted) {
                Text(stringResource(R.string.region_restricted))
                RegionDiagnosticReference(state.diagnosticCode)
                Button(onClick = onExit) { Text(stringResource(R.string.region_exit)) }
            } else if (state.error) {
                Text(stringResource(if (state.offline) R.string.region_offline else R.string.region_unavailable))
                RegionDiagnosticReference(state.diagnosticCode)
                Button(onClick = onRetry) { Text(stringResource(R.string.region_retry)) }
                Button(onClick = onExit) { Text(stringResource(R.string.region_exit)) }
            }
        }
    }
}

@Composable
private fun RegionDiagnosticReference(code: String?) {
    code?.let {
        Text(
            text = stringResource(R.string.region_reference, it),
            color = Color.Gray,
            fontSize = 13.sp,
        )
    }
}

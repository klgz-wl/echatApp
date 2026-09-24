package yumo.achat.app

import android.os.Bundle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import yumo.achat.app.attribution.AchatAttributionRuntime
import yumo.achat.app.analytics.AchatAnalyticsRuntime
import yumo.achat.app.analytics.AnalyticsConsent
import yumo.achat.app.ui.imagevideo.ImageToVideoScreen
import yumo.achat.app.ui.theme.AchatTheme

class MainActivity : ComponentActivity() {
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            AchatTheme {
                val gate: RegionGateViewModel = viewModel()
                val state by gate.state.collectAsState()
                var analyticsConsent by remember { mutableStateOf(AnalyticsConsent.decision(this@MainActivity)) }
                LaunchedEffect(Unit) { gate.check() }
                LaunchedEffect(state.allowed, analyticsConsent) {
                    if (state.allowed && analyticsConsent != null) beginBusinessStartup()
                }
                when {
                    !state.allowed -> RegionGateScreen(state = state, onRetry = gate::check)
                    analyticsConsent == null -> AnalyticsConsentScreen(
                        onAccept = {
                            AnalyticsConsent.save(this@MainActivity, true)
                            analyticsConsent = true
                        },
                        onDecline = {
                            AnalyticsConsent.save(this@MainActivity, false)
                            analyticsConsent = false
                        },
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
        if (businessStarted && AnalyticsConsent.granted(this)) AchatAnalyticsRuntime.get(this).foreground(true)
    }

    override fun onStop() {
        if (businessStarted && AnalyticsConsent.granted(this) && !isChangingConfigurations) {
            AchatAnalyticsRuntime.get(this).foreground(false)
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
private fun RegionGateScreen(state: RegionGateState, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (state.busy) {
                CircularProgressIndicator()
                Text(stringResource(R.string.region_checking))
            } else if (state.restricted) {
                Text(stringResource(R.string.region_restricted))
            } else if (state.error) {
                Text(stringResource(R.string.region_unavailable))
                Button(onClick = onRetry) { Text(stringResource(R.string.top_up_retry)) }
            }
        }
    }
}

@Composable
private fun AnalyticsConsentScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.analytics_consent_message))
            Button(onClick = onAccept) { Text(stringResource(R.string.analytics_consent_accept)) }
            Button(onClick = onDecline) { Text(stringResource(R.string.analytics_consent_decline)) }
        }
    }
}

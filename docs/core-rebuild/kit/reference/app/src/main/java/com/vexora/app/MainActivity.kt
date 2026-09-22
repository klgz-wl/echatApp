package com.vexora.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.flowWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.vexora.core.payment.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.navigation.navArgument
import androidx.navigation.compose.*
import com.vexora.app.ui.components.*
import com.vexora.app.ui.login.*
import com.vexora.app.ui.generation.*
import com.vexora.app.ui.catalog.CatalogRoute
import com.vexora.core.catalog.CatalogChannel
import com.vexora.app.ui.navigation.MainTab
import com.vexora.app.ui.navigation.selectMainTab
import com.vexora.app.ui.navigation.MainNavigation
import com.vexora.app.ui.theme.*
import com.vexora.app.ui.web.WebScreen
import com.vexora.app.ui.wallet.*
import com.vexora.core.config.AppMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @javax.inject.Inject lateinit var appsFlyerProvider: dagger.Lazy<com.vexora.core.integration.appsflyer.AppsFlyerAnalytics>
    @javax.inject.Inject lateinit var businessEventsProvider: dagger.Lazy<com.vexora.app.analytics.BusinessEventQueue>
    @javax.inject.Inject lateinit var foregroundAnalyticsProvider: dagger.Lazy<com.vexora.app.analytics.ForegroundAnalytics>
    @javax.inject.Inject lateinit var billingProvider: dagger.Lazy<com.vexora.core.billing.BillingRepository>
    @javax.inject.Inject lateinit var purchaseRouterProvider: dagger.Lazy<com.vexora.core.payment.PurchaseRouter>
    @javax.inject.Inject lateinit var notificationsProvider: dagger.Lazy<com.vexora.core.wallet.RechargeNotifications>
    @javax.inject.Inject lateinit var paymentsProvider: dagger.Lazy<PaymentCoordinator>
    private val foregroundAnalytics get() = foregroundAnalyticsProvider.get()
    private val billing get() = billingProvider.get()
    val purchaseRouter get() = purchaseRouterProvider.get()
    val notifications get() = notificationsProvider.get()
    private val payments get() = paymentsProvider.get()
    private var businessStarted = false
    fun purchase(product: com.vexora.core.wallet.CoinProduct, source: String) { if (businessStarted) purchaseRouter.buy(this, product, source) }
    private fun businessForeground() {
        foregroundAnalytics.foreground(true); billing.onForeground(); notifications.setForeground(true); payments.foreground(true)
    }
    override fun onStart() { super.onStart(); if (businessStarted) businessForeground() }
    override fun onResume() {
        super.onResume()
        if (businessStarted) appsFlyerProvider.get().onActivityResumed(this)
    }
    override fun onPause() {
        if (businessStarted) appsFlyerProvider.get().onActivityPaused(this)
        super.onPause()
    }
    override fun onStop() {
        if (businessStarted) {
            if (!isChangingConfigurations) foregroundAnalytics.foreground(false)
            notifications.setForeground(false); payments.foreground(false)
        }
        super.onStop()
    }
    /** 仅地区入口放行后调用，包含此前会由 onStart 提前执行的恢复逻辑。 */
    private fun beginBusinessStartup() {
        if (businessStarted) return
        com.google.firebase.FirebaseApp.initializeApp(this)
        businessStarted = true
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) appsFlyerProvider.get().onActivityResumed(this)
        lifecycleScope.launch {
            payments.state.map { it.epoch to it.record?.takeIf { record -> record.stage == PaymentStage.OFFICIAL_READY }?.key }
                .distinctUntilChanged().flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED).collect { (epoch, key) ->
                    if (key == null || epoch == null) return@collect
                    purchaseRouter.launchOfficial(this@MainActivity, key, epoch)
                }
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) businessForeground()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.ENABLE_SECURE_WINDOW && !BuildConfig.DEBUG) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val region = ViewModelProvider(this)[RegionGateViewModel::class.java]
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setVexoraContent {
            VexoraTheme {
                val state by region.state.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { withFrameNanos { }; region.start() }
                var ready by remember { mutableStateOf(false) }
                LaunchedEffect(state.allowed) {
                    if (state.allowed) { beginBusinessStartup(); ready = true }
                }
                if (!ready) SplashScreen(state.error, region::check, ::finishAndRemoveTask)
                else CompositionLocalProvider(com.vexora.app.analytics.LocalEventTracker provides businessEventsProvider.get()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot(auth: AuthViewModel = hiltViewModel(), wallet: WalletViewModel = hiltViewModel(), generation: GenerationViewModel = hiltViewModel(), library: VisualLibraryViewModel = hiltViewModel(), payment: PaymentViewModel = hiltViewModel()) {
    val state by auth.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { withFrameNanos { }; auth.start() }
    val libraryState by library.state.collectAsStateWithLifecycle()
    var taskKey by rememberSaveable { mutableStateOf<String?>(null) }
    var mediaJson by rememberSaveable { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { library.active(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { library.active(false) }
    val walletState by wallet.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { wallet.refreshBalance() }
    val controller = rememberNavController()
    val mainController = key(state.sessionEpoch) { rememberNavController() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webUrl by rememberSaveable { mutableStateOf(auth.config.termsUrl) }
    var routeInitialized by rememberSaveable { mutableStateOf(false) }
    var routedEpoch by rememberSaveable { mutableStateOf<String?>(null) }
    val openMedia: (String, String, Int, Int) -> Unit = { url, mime, width, height ->
        if (width >= 0 && height >= 0 && url.isNotBlank()) {
            mediaJson = kotlinx.serialization.json.Json.encodeToString(com.vexora.core.catalog.TemplateMedia.serializer(), com.vexora.core.catalog.TemplateMedia(url, width, height, true, mime))
            controller.navigate("media")
        }
    }
    val openTask: (com.vexora.core.visual.GenerationRequest) -> Unit = { request ->
        val result = request.task?.takeIf { it.status == "succeeded" }?.resource
        if (result != null) openMedia(result.url, result.mimeType, result.width, result.height)
        else if (request.task == null) {
            generation.resume(request)
            controller.navigate("generation")
        } else { taskKey = request.key; controller.navigate("task") }
    }
    var openingHomeNotice by remember { mutableStateOf(false) }
    val openHomeNotice: (com.vexora.core.visual.GenerationRequest) -> Unit = { request ->
        if (!openingHomeNotice) {
            openingHomeNotice = true
            scope.launch {
                try {
                    library.openHomeNotice(request) ?: return@launch
                    library.filter(null)
                    library.refresh()
                    mainController.selectMainTab(MainTab.Profile)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    android.widget.Toast.makeText(context, R.string.task_notice_open_error, android.widget.Toast.LENGTH_SHORT).show()
                } finally { openingHomeNotice = false }
            }
        }
    }
    val web: (String) -> Unit = { webUrl = it; controller.navigate("web") }
    LaunchedEffect(Unit) {
        val notifications = (context as? MainActivity)?.notifications
        notifications?.events?.collect { notice ->
            if (!notifications.isCurrent(notice)) return@collect
            if ((context as? MainActivity)?.purchaseRouter?.recharge(notice.orderId) == true) return@collect
            android.widget.Toast.makeText(context, R.string.recharge_successful, android.widget.Toast.LENGTH_SHORT).show()
            wallet.refreshBalance(); wallet.loadProducts()
            val route = controller.currentDestination?.route
            if (route == "purchase?source={source}" || route == "records") {
                val purchaseEntry = runCatching { controller.getBackStackEntry("purchase?source={source}") }.getOrNull()
                if (purchaseEntry?.arguments?.getString("source") == "generation")
                    controller.popBackStack("purchase?source={source}", inclusive = true)
            }
        }
    }
    LaunchedEffect(state.restoring, state.sessionEpoch, state.mode) {
        if (!state.restoring && (!routeInitialized || routedEpoch != state.sessionEpoch)) {
            val target = if (state.userId != null) "main" else "gate"
            controller.navigate(target) { popUpTo(controller.graph.id) { inclusive = true }; launchSingleTop = true }
            routeInitialized = true
            routedEpoch = state.sessionEpoch
        }
    }
    if (state.restoring) {
        com.vexora.app.analytics.TrackPage("splash")
        SplashScreen(state.error, auth::restore) { (context as? android.app.Activity)?.finishAndRemoveTask() }
        return
    }
    PaymentHost(payment, state.sessionEpoch) { source ->
        wallet.refreshBalance(); wallet.loadProducts()
        if (source == "generation" && controller.currentDestination?.route in setOf("purchase?source={source}", "records")) {
            val entry = runCatching { controller.getBackStackEntry("purchase?source={source}") }.getOrNull()
            if (entry?.arguments?.getString("source") == "generation") controller.popBackStack("purchase?source={source}", inclusive = true)
        }
    }
    NavHost(controller, startDestination = "gate") {
        composable("gate") {
            if (!state.restoring) SplashScreen(state.error, auth::restore) { (context as? android.app.Activity)?.finishAndRemoveTask() }
        }
        composable("main") {
            MainNavigation(mainController) { tab ->
                if (tab != MainTab.Profile) CatalogRoute(
                    when (tab) { MainTab.Home -> CatalogChannel.HOME; MainTab.Video -> CatalogChannel.VIDEO; else -> CatalogChannel.IMAGE },
                    state.mode ?: return@MainNavigation, WalletBalance(walletState), { controller.navigate("purchase?source=${tab.name.lowercase()}_balance") }, { controller.navigate("records") },
                    { template -> generation.open(template, tab.name.lowercase()); controller.navigate("generation") },
                    taskStatus = { HomeTaskNotice(libraryState, openHomeNotice) })
                else ProfileScreen(libraryState, library.profile, WalletBalance(walletState), { controller.navigate("purchase?source=profile_purchase") },
                    { controller.navigate("settings") }, { mainController.selectMainTab(MainTab.Home) },
                    library::refresh, library::more, library::filter, openTask,
                    { resource -> openMedia(resource.url, resource.mimeType, resource.width, resource.height) })
            }
        }
        composable("purchase?source={source}", arguments = listOf(navArgument("source") { defaultValue = "main" })) { entry ->
            com.vexora.app.analytics.TrackPage("purchase", mapOf("entry_source" to (entry.arguments?.getString("source") ?: "main")))
            var insufficiencyShown by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (entry.arguments?.getString("source") == "generation" && !insufficiencyShown) {
                    android.widget.Toast.makeText(context, R.string.balance_insufficient, android.widget.Toast.LENGTH_LONG).show()
                    insufficiencyShown = true
                }
                wallet.refreshBalance(); wallet.loadProducts()
            }
            PurchaseScreen(walletState, wallet.displayConfig, { id ->
                wallet.select(id)
                walletState.wallet.products.firstOrNull { it.id == id }?.let { (context as? MainActivity)?.purchase(it, entry.arguments?.getString("source") ?: "main") }
            }, wallet::loadProducts,
                { walletState.wallet.products.firstOrNull { it.id == walletState.selectedId }?.let { (context as? MainActivity)?.purchase(it, entry.arguments?.getString("source") ?: "main") } },
                { controller.popBackStack() }, { controller.navigate("records") })
        }
        composable("records") {
            com.vexora.app.analytics.TrackPage("records")
            LaunchedEffect(Unit) { wallet.records(refresh = true) }
            RecordsScreen(walletState, { wallet.records(refresh = true) }, { wallet.records() }, wallet::retryRecords, { controller.popBackStack() })
        }
        composable("generation") {
            com.vexora.app.analytics.TrackPage("generation")
            GenerationRoute(generation, state.mode ?: return@composable,
                { if (controller.currentDestination?.route == "generation") controller.popBackStack() }, { controller.navigate("purchase?source=generation") },
                { wallet.refreshBalance(); library.refresh(); taskKey = generation.state.value.request?.key; controller.navigate("task") { popUpTo("generation") { inclusive = true } } })
        }
        composable("task") {
            com.vexora.app.analytics.TrackPage("task")
            val generationState by generation.state.collectAsStateWithLifecycle()
            val request = libraryState.requests.firstOrNull { it.key == taskKey }
                ?: generationState.request?.takeIf { it.key == taskKey }
            TaskScreen(request, libraryState.pollingFailed, { controller.popBackStack("main", inclusive = false) }, library.sourcePath(request),
                { saved -> generation.resume(saved); controller.navigate("generation") { popUpTo("task") { inclusive = true } } },
                { result -> openMedia(result.url, result.mimeType, result.width, result.height) })
        }
        composable("media") {
            com.vexora.app.analytics.TrackPage("media")
            val media = remember(mediaJson) { mediaJson?.let { kotlinx.serialization.json.Json.decodeFromString<com.vexora.core.catalog.TemplateMedia>(it) } }
            MediaScreen(media) { if (controller.currentDestination?.route == "media") controller.popBackStack() }
        }
        composable("settings") { com.vexora.app.analytics.TrackPage("settings"); com.vexora.app.ui.settings.SettingsRoute({ controller.popBackStack() }, web) }
        composable("web") { com.vexora.app.analytics.TrackPage("web"); WebScreen(webUrl, { controller.popBackStack() }) }
    }
}

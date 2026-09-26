package com.vexora.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vexora.app.analytics.AnalyticsHub
import com.vexora.app.analytics.BusinessEventQueue
import com.zorv.core.auth.AnonymousStartup
import com.zorv.core.auth.SessionCoordinator
import com.zorv.core.auth.UserProfileRepository
import com.zorv.core.billing.BillingRepository
import com.zorv.core.payment.PaymentCoordinator
import com.zorv.core.payment.PurchaseRouter
import com.zorv.core.region.RegionAccess
import com.zorv.core.wallet.RechargeNotifications
import com.zorv.core.wallet.WalletRepository
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 只有地区放行后才创建业务图；实际页面在目标项目按提示词实现。 */
@Singleton
class HarnessRuntime @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext val context: android.content.Context,
    val foregroundEvents: com.vexora.app.analytics.ForegroundAnalytics,
    val hub: AnalyticsHub, val startup: AnonymousStartup, val wallet: WalletRepository,
    val sessions: SessionCoordinator, val profiles: UserProfileRepository,
    val billing: BillingRepository, val payments: PaymentCoordinator,
    val recharge: RechargeNotifications, val router: PurchaseRouter, val events: BusinessEventQueue,
    val catalog: com.zorv.core.catalog.TemplateRepository,
    val library: com.zorv.core.visual.VisualLibraryRepository,
    val photos: com.zorv.core.visual.UploadedPhotoRepository,
    val images: com.zorv.core.visual.PrivateGenerationStorage,
    val requests: com.zorv.core.visual.GenerationRequestRepository,
)

@HiltViewModel
class HarnessViewModel @Inject constructor(private val region: RegionAccess, private val runtime: Lazy<HarnessRuntime>) : ViewModel() {
    private val mutable = MutableStateFlow(R.string.status_idle)
    val status = mutable.asStateFlow()
    private val admitted = MutableStateFlow(false)
    val allowed = admitted.asStateFlow()
    private var job: Job? = null
    private var active = false
    init { start() }
    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            mutable.value = R.string.status_loading
            try {
                region.check()
                admitted.value = true
                val graph = runtime.get()
                com.google.firebase.FirebaseApp.initializeApp(graph.context)
                graph.hub.initialize()
                val mode = graph.startup.start()
                graph.events.mode = mode.name
                mutable.value = if (mode.name == "A") R.string.status_mode_a else R.string.status_mode_b
                if (active) foreground(true)
                launch { graph.profiles.state.collect { profile ->
                    if (profile != null && profile.epoch == graph.sessions.current?.epoch) {
                        graph.events.mode = profile.mode.name
                        mutable.value = if (profile.mode.name == "A") R.string.status_mode_a else R.string.status_mode_b
                    }
                } }
                launch { graph.recharge.events.collect { notice ->
                    if (notice.epoch == graph.sessions.current?.epoch) graph.router.recharge(notice.orderId)
                } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = R.string.status_failed }
        }
    }
    fun foreground(value: Boolean) {
        active = value
        if (!admitted.value) return
        val graph = runtime.get()
        graph.foregroundEvents.foreground(value)
        graph.payments.foreground(value)
        graph.recharge.setForeground(value)
        if (value) graph.billing.onForeground()
    }
    fun loadWallet() {
        if (!admitted.value) return
        viewModelScope.launch {
            try { runtime.get().wallet.refreshBalance(); runtime.get().wallet.refreshProducts(); mutable.value = R.string.status_wallet }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = R.string.status_failed }
        }
    }
    fun readCatalogAndLibrary() {
        if (!admitted.value) return
        viewModelScope.launch {
            try {
                val graph = runtime.get()
                graph.catalog.load(com.zorv.core.catalog.CatalogChannel.HOME)
                graph.photos.photos(1)
                graph.library.resources(1, null)
                mutable.value = R.string.status_library
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = R.string.status_failed }
        }
    }
}

package yumo.achat.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import yumo.achat.app.BuildConfig
import yumo.achat.app.analytics.AchatAnalyticsRuntime
import yumo.achat.app.analytics.AnalyticsConsent
import yumo.achat.app.attribution.AchatAttributionRuntime
import yumo.achat.app.ui.imagevideo.corePaymentConfiguration
import yumo.achat.core.analytics.AttributionIdProvider
import yumo.achat.core.analytics.EventTracker
import yumo.achat.core.billing.BillingConfiguration
import yumo.achat.core.config.ClientIdentity
import yumo.achat.core.config.CoreRuntimeConfig
import yumo.achat.core.config.DiagnosticsConfig
import yumo.achat.core.config.NetworkConfig
import yumo.achat.core.config.StorageConfig
import yumo.achat.core.payment.LegacyOrderStorage
import yumo.achat.core.payment.PaymentConfiguration
import yumo.achat.core.payment.PreferenceLegacyOrderStorage
import yumo.achat.core.payment.PurchaseFlow
import yumo.achat.core.payment.PurchaseFlowConfiguration
import yumo.achat.core.wallet.WalletConfiguration

@Module
@InstallIn(SingletonComponent::class)
object CorePaymentModule {
    @Provides
    @Singleton
    fun paymentConfiguration(): PaymentConfiguration = corePaymentConfiguration().payment

    @Provides
    @Singleton
    fun billingConfiguration(): BillingConfiguration = corePaymentConfiguration().billing

    @Provides
    @Singleton
    fun purchaseFlowConfiguration() = PurchaseFlowConfiguration(
        PurchaseFlow.valueOf(BuildConfig.PAYMENT_FLOW.uppercase()),
    )

    @Provides
    @Singleton
    fun coreRuntimeConfiguration() = CoreRuntimeConfig(
        network = NetworkConfig(BuildConfig.CORE_BASE_URL, BuildConfig.CORE_STREAM_URL, BuildConfig.CORE_CDN_URL),
        storage = StorageConfig(BuildConfig.DATABASE_NAME, BuildConfig.STORAGE_NAME),
        diagnostics = DiagnosticsConfig(
            enableDebugLogging = BuildConfig.DEBUG && BuildConfig.ENABLE_DEBUG_LOG,
            redactHttpLogs = BuildConfig.HTTP_LOG_REDACTION,
        ),
    )

    @Provides
    @Singleton
    fun clientIdentity() = ClientIdentity(
        packageName = BuildConfig.APPLICATION_ID,
        versionName = BuildConfig.ACHAT_CLIENT_VERSION,
        timeoutSeconds = BuildConfig.NETWORK_TIMEOUT_SECONDS.toLong(),
    )

    @Provides
    @Singleton
    fun walletConfiguration() = WalletConfiguration(
        productType = BuildConfig.WALLET_PRODUCT_TYPE,
        location = BuildConfig.WALLET_PRODUCT_LOCATION,
        pageSize = BuildConfig.WALLET_PAGE_SIZE,
    )

    @Provides
    @Singleton
    fun eventTracker(@ApplicationContext context: Context): EventTracker = AchatAnalyticsRuntime.get(context)

    @Provides
    @Singleton
    fun attributionIdProvider(@ApplicationContext context: Context): AttributionIdProvider =
        object : AttributionIdProvider {
            override fun currentId(): String =
                if (AnalyticsConsent.granted(context)) AchatAttributionRuntime.get(context).currentId() else ""
        }

    @Provides
    @Singleton
    fun legacyOrderStorage(value: PreferenceLegacyOrderStorage): LegacyOrderStorage = value
}

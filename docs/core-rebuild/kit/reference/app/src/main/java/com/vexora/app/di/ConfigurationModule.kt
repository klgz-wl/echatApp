package com.vexora.app.di

import com.vexora.app.BuildConfig
import com.zorv.core.analytics.AttributionIdProvider
import com.zorv.core.integration.appsflyer.AppsFlyerAnalytics
import com.zorv.core.config.*
import com.zorv.core.integration.appsflyer.AppsFlyerConfig
import com.zorv.core.integration.thinkingdata.ThinkingDataConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConfigurationModule {
    @Provides @Singleton fun regionAccess(local: com.vexora.app.ui.login.DeviceRegionSource,
        network: com.vexora.app.ui.login.RegionNetwork) =
        com.zorv.core.region.RegionAccess(
            com.zorv.core.region.RegionAccessConfiguration(
                BuildConfig.ENABLE_REGION_RESTRICTION && !BuildConfig.DEBUG,
                BuildConfig.BLOCKED_REGION_CODES.split(',').toSet(),
                BuildConfig.REGION_LOOKUP_RETRY_ATTEMPTS,
                BuildConfig.REGION_LOOKUP_RETRY_DELAY_MS.toLong()), local,
            com.zorv.core.region.CountryIsRegionSource(BuildConfig.REGION_LOOKUP_URL,
                BuildConfig.REGION_LOOKUP_TIMEOUT_MS.toLong()), network)

    @Provides @Singleton fun eventTracker(queue: com.vexora.app.analytics.BusinessEventQueue): com.zorv.core.analytics.EventTracker = queue
    @Provides @Singleton fun analyticsConfiguration() = com.zorv.core.analytics.AnalyticsConfiguration(
        BuildConfig.ANALYTICS_STORAGE_NAME, BuildConfig.ANALYTICS_BACKGROUND_TIMEOUT_MS.toLong(),
        BuildConfig.ANALYTICS_QUEUE_CAPACITY, BuildConfig.ANALYTICS_DEDUPE_LIMIT)

    @Provides @Singleton fun legacyOrderStorage(value: com.zorv.core.payment.PreferenceLegacyOrderStorage): com.zorv.core.payment.LegacyOrderStorage = value

    @Provides @Singleton fun paymentFlow() = com.zorv.core.payment.PurchaseFlowConfiguration(
        com.zorv.core.payment.PurchaseFlow.valueOf(BuildConfig.PAYMENT_FLOW))

    @Provides @Singleton fun paymentConfiguration() = com.zorv.core.payment.PaymentConfiguration(
        BuildConfig.PAYMENT_BASE_URL, BuildConfig.PAYMENT_STORAGE_NAME, BuildConfig.PAYMENT_POLL_INTERVAL_SECONDS,
        BuildConfig.PAYMENT_POLL_MAX_SECONDS, BuildConfig.PAYMENT_SUCCESS_DISPLAY_MS.toLong(),
        BuildConfig.PAYMENT_EVENT_LIMIT, BuildConfig.PAYMENT_EVENT_ATTEMPTS, BuildConfig.PAYMENT_EVENT_RETRY_MS.toLong())

    @Provides @Singleton fun conversionSource(sdk: AppsFlyerAnalytics): com.zorv.core.attribution.ConversionSource = sdk
    @Provides @Singleton fun attributionStorage(value: com.zorv.core.auth.PreferenceSessionStorage): com.zorv.core.attribution.AttributionStorage = value
    @Provides @Singleton fun loginAttribution(value: com.zorv.core.attribution.AttributionCoordinator): com.zorv.core.attribution.LoginAttributionProvider = value
    @Provides @Singleton fun attributionConfiguration() = com.zorv.core.attribution.AttributionConfiguration(BuildConfig.ATTRIBUTION_TIMEOUT_MS.toLong(), BuildConfig.ATTRIBUTION_RETRY_ATTEMPTS, BuildConfig.ATTRIBUTION_RETRY_INTERVAL_MS.toLong(), BuildConfig.ATTRIBUTION_CACHE_WAIT_MS.toLong())
    @Provides @Singleton fun startupConfiguration() = com.zorv.core.auth.StartupConfiguration(BuildConfig.STARTUP_TIMEOUT_MS.toLong())
    @Provides @Singleton fun startupNetwork(network: com.vexora.app.ui.login.StartupNetwork): com.zorv.core.auth.NetworkAvailability = network

    @Provides @Singleton fun settingsConfiguration() = com.vexora.app.ui.settings.SettingsConfiguration(BuildConfig.VERSION_NAME, BuildConfig.CONTACT_EMAIL)

    @Provides @Singleton fun userDataCleaner(tasks: com.zorv.core.visual.GenerationRequestRepository,
        wallet: com.zorv.core.wallet.WalletRepository,
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context): com.zorv.core.auth.UserDataCleaner =
        com.zorv.core.auth.UserDataCleaner { userId ->
            tasks.clear(userId)
            wallet.clear()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { coil.Coil.imageLoader(context).diskCache?.clear() }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { coil.Coil.imageLoader(context).memoryCache?.clear() }
        }

    @Provides @Singleton fun profilePreview(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        json: kotlinx.serialization.json.Json): com.vexora.app.ui.generation.ProfilePreview =
        context.assets.open("config/profile_preview.json").bufferedReader().use { json.decodeFromString(it.readText()) }

    @Provides @Singleton fun pollingConfiguration() = com.zorv.core.visual.VisualPollingConfiguration(
        BuildConfig.VISUAL_POLL_DEFAULT_SECONDS, BuildConfig.VISUAL_POLL_MIN_SECONDS, BuildConfig.VISUAL_POLL_MAX_SECONDS, BuildConfig.VISUAL_PAGE_SIZE)

    @Provides @Singleton fun generationConfiguration() = com.zorv.core.visual.GenerationConfiguration(
        BuildConfig.GENERATION_STORAGE_DIRECTORY, BuildConfig.GENERATION_MAX_IMAGE_BYTES.toLong())
    @Provides @Singleton fun generationStorage(value: com.zorv.core.visual.PrivateGenerationStorage): com.zorv.core.visual.GenerationStorage = value
    @Provides @Singleton fun generationOptions(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        json: kotlinx.serialization.json.Json): com.vexora.app.ui.generation.GenerationOptions =
        context.assets.open("config/generation_options.json").bufferedReader().use { json.decodeFromString(it.readText()) }

    @Provides @Singleton fun catalogConfiguration() = com.zorv.core.catalog.CatalogConfiguration(
        com.zorv.core.catalog.MediaKind.valueOf(BuildConfig.HOME_TEMPLATE_MODALITY), BuildConfig.VISUAL_PAGE_SIZE)
    @Provides @Singleton fun templates(value: com.zorv.core.catalog.ApiTemplateRepository): com.zorv.core.catalog.TemplateRepository = value
    @Provides @Singleton @javax.inject.Named("mockCatalog") fun mockTemplates(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        json: kotlinx.serialization.json.Json): com.zorv.core.catalog.TemplateRepository {
        val paths = context.assets.open("config/catalog_sources.json").bufferedReader().use { json.decodeFromString<Map<String, String>>(it.readText()) }
        return com.zorv.core.catalog.MockTemplateRepository(
            com.zorv.core.catalog.CatalogAssetSource { path -> context.assets.open(path).bufferedReader().use { it.readText() } }, paths, json)
    }

    @Provides @Singleton fun rechargeStream() = com.zorv.core.wallet.RechargeStreamConfiguration(
        BuildConfig.RECHARGE_RETRY_INITIAL_MS.toLong(), BuildConfig.RECHARGE_RETRY_MAX_MS.toLong(),
        BuildConfig.RECHARGE_FIRST_MESSAGE_TIMEOUT_MS.toLong(), BuildConfig.RECHARGE_IDLE_TIMEOUT_MS.toLong(),
        BuildConfig.RECHARGE_CHECK_INTERVAL_MS.toLong(), BuildConfig.RECHARGE_DEDUPE_WINDOW_MS.toLong())
    @Provides @Singleton fun coinDisplay(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        json: kotlinx.serialization.json.Json) = com.zorv.core.wallet.CoinDisplayConfiguration(
        context.assets.open("config/currency_symbols.json").bufferedReader().use { json.decodeFromString<Map<String, String>>(it.readText()) })
    @Provides @Singleton fun billing() = com.zorv.core.billing.BillingConfiguration(BuildConfig.BILLING_STORAGE_NAME, BuildConfig.PURCHASE_TRIGGER)
    @Provides @Singleton fun wallet() = com.zorv.core.wallet.WalletConfiguration(
        BuildConfig.WALLET_PRODUCT_TYPE, BuildConfig.WALLET_PRODUCT_LOCATION, BuildConfig.WALLET_PAGE_SIZE)
    @Provides @Singleton fun analyticsPolicy() = com.zorv.core.analytics.AnalyticsPolicy(
        BuildConfig.ENABLE_FIREBASE_ANALYTICS, BuildConfig.ENABLE_APPSFLYER, BuildConfig.ENABLE_THINKINGDATA, BuildConfig.ENABLE_BACKEND_ANALYTICS)
    @Provides @Singleton fun attribution(sdk: AppsFlyerAnalytics): AttributionIdProvider = sdk
    @Provides @Singleton fun identity() = ClientIdentity(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME, BuildConfig.NETWORK_TIMEOUT_SECONDS.toLong())
    @Provides @Singleton fun core() = CoreRuntimeConfig(
        NetworkConfig(BuildConfig.CORE_BASE_URL, BuildConfig.CORE_STREAM_URL, BuildConfig.CORE_CDN_URL),
        StorageConfig(BuildConfig.DATABASE_NAME, BuildConfig.STORAGE_NAME),
        DiagnosticsConfig(BuildConfig.DEBUG && BuildConfig.ENABLE_DEBUG_LOG, BuildConfig.HTTP_LOG_REDACTION),
    )
    @Provides @Singleton fun app() = AppConfiguration(
        AccountRules(BuildConfig.USERNAME_MIN_LENGTH, BuildConfig.USERNAME_MAX_LENGTH, BuildConfig.PASSWORD_MIN_LENGTH, BuildConfig.PASSWORD_MAX_LENGTH),
        BuildConfig.PRIVACY_POLICY_URL, BuildConfig.TERMS_OF_SERVICE_URL,
        BuildConfig.ABOUT_URL, BuildConfig.CONTACT_URL,
    )
    @Provides fun appsFlyer() = AppsFlyerConfig(BuildConfig.APPSFLYER_DEV_KEY, BuildConfig.DEBUG && BuildConfig.APPSFLYER_DEBUG_LOGGING, BuildConfig.ENABLE_APPSFLYER, BuildConfig.APPSFLYER_DIAGNOSTIC_LOGGING)
    @Provides fun thinkingData() = ThinkingDataConfig(BuildConfig.TD_APP_ID, BuildConfig.TD_SERVER_URL, BuildConfig.TD_DEBUG_MODE)
}

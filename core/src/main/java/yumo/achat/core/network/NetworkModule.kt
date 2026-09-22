package yumo.achat.core.network

import yumo.achat.core.auth.*
import yumo.achat.core.analytics.AttributionIdProvider
import yumo.achat.core.config.ClientIdentity
import yumo.achat.core.config.CoreRuntimeConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton fun paymentStorage(value: yumo.achat.core.payment.PreferencePaymentStorage): yumo.achat.core.payment.PaymentStorage = value
    @Provides @Singleton fun paymentEvents(value: yumo.achat.core.payment.PaymentEventQueue): yumo.achat.core.payment.PaymentEventSink = value
    @Provides @Singleton fun paymentClock() = yumo.achat.core.payment.PaymentClock { System.currentTimeMillis() }
    @Provides @Singleton fun paymentOrders(wallet: yumo.achat.core.wallet.WalletRepository, config: yumo.achat.core.billing.BillingConfiguration) =
        yumo.achat.core.payment.PaymentOrderFactory { productId, session -> wallet.createOrder(productId, config.defaultTrigger, session).orderId }

    @Provides @Singleton fun paymentRepository(value: yumo.achat.core.payment.ApiPaymentRepository): yumo.achat.core.payment.PaymentRepository = value
    @Provides @Singleton fun paymentApi(@Named("publicClient") client: OkHttpClient, sessions: SessionCoordinator,
        api: PublicAuthApi, config: CoreRuntimeConfig, identity: ClientIdentity, json: Json): yumo.achat.core.payment.PaymentServiceApi {
        val paymentClient = paymentClient(client, sessions, api, config.diagnostics.enableDebugLogging, identity.timeoutSeconds, json, config.diagnostics.redactHttpLogs)
        return retrofit(paymentClient, config, json).create(yumo.achat.core.payment.PaymentServiceApi::class.java)
    }

    @Provides @Singleton fun storage(value: PreferenceSessionStorage): SessionStorage = value
    @Provides @Singleton fun repository(value: ApiAuthRepository): AuthRepository = value
    @Provides @Singleton fun json() = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    @Provides @Singleton fun logging(config: CoreRuntimeConfig): HttpLoggingInterceptor = createHttpLoggingInterceptor(config)
    @Provides @Singleton @Named("publicClient") fun publicClient(identity: ClientIdentity, storage: SessionStorage,
        sessions: SessionCoordinator, attribution: AttributionIdProvider, logging: HttpLoggingInterceptor): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(identity.timeoutSeconds, TimeUnit.SECONDS).readTimeout(identity.timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(identity.timeoutSeconds, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder().header("X-Package-Name", identity.packageName)
                .header("X-Platform", "Android").header("X-App-Platform", "Android")
                .header("X-App-Version", identity.versionName).header("X-Version", identity.versionName)
                .header("X-AF-UID", attribution.currentId()).header("X-Device-ID", runBlocking { storage.deviceId() })
                .header("X-APP-USER-ID", sessions.current?.userId.orEmpty()).build()
            chain.proceed(request)
        }.addInterceptor(logging).build()
    @Provides @Singleton fun attributionReportApi(@Named("publicClient") client: OkHttpClient, config: CoreRuntimeConfig, json: Json): yumo.achat.core.attribution.AttributionReportApi =
        retrofit(client, config, json).create(yumo.achat.core.attribution.AttributionReportApi::class.java)
    @Provides @Singleton fun eventApi(@Named("publicClient") client: OkHttpClient, config: CoreRuntimeConfig, json: Json): yumo.achat.core.analytics.EventApi =
        retrofit(client, config, json).create(yumo.achat.core.analytics.EventApi::class.java)
    @Provides @Singleton fun publicApi(@Named("publicClient") client: OkHttpClient, config: CoreRuntimeConfig, json: Json): PublicAuthApi =
        retrofit(client, config, json).create(PublicAuthApi::class.java)
    @Provides @Singleton fun client(@Named("publicClient") client: OkHttpClient, sessions: SessionCoordinator, api: PublicAuthApi): OkHttpClient =
        client.newBuilder().addInterceptor(SessionInterceptor(sessions, api)).build()
    @Provides @Singleton fun protectedRetrofit(client: OkHttpClient, config: CoreRuntimeConfig, json: Json): Retrofit = retrofit(client, config, json)
    @Provides @Singleton fun userProfileApi(retrofit: Retrofit): UserProfileApi = retrofit.create(UserProfileApi::class.java)
    @Provides @Singleton fun accountApi(retrofit: Retrofit): AccountApi = retrofit.create(AccountApi::class.java)
    @Provides @Singleton fun uploadedPhotoApi(retrofit: Retrofit): yumo.achat.core.visual.UploadedPhotoApi = retrofit.create(yumo.achat.core.visual.UploadedPhotoApi::class.java)
    @Provides @Singleton fun photoDownloadApi(identity: ClientIdentity, config: CoreRuntimeConfig, json: Json): yumo.achat.core.visual.UploadedPhotoDownloadApi =
        retrofit(OkHttpClient.Builder().connectTimeout(identity.timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(identity.timeoutSeconds, TimeUnit.SECONDS).callTimeout(identity.timeoutSeconds, TimeUnit.SECONDS).build(), config, json)
            .create(yumo.achat.core.visual.UploadedPhotoDownloadApi::class.java)
    @Provides @Singleton fun visualApi(retrofit: Retrofit): yumo.achat.core.visual.VisualGenerationApi = retrofit.create(yumo.achat.core.visual.VisualGenerationApi::class.java)
    @Provides @Singleton fun walletApi(client: OkHttpClient, config: CoreRuntimeConfig, json: Json): yumo.achat.core.wallet.WalletApi =
        retrofit(client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(), config, json)
            .create(yumo.achat.core.wallet.WalletApi::class.java)
    private fun retrofit(client: OkHttpClient, config: CoreRuntimeConfig, json: Json) = Retrofit.Builder()
        .baseUrl(config.network.baseUrl).client(client).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
}

class SessionInterceptor(private val sessions: SessionCoordinator, private val api: PublicAuthApi,
    private val invalidateOnRetriedUnauthorized: Boolean = true) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val active = sessions.current ?: throw IOException("认证会话不存在")
        val owner = chain.request().tag(Session::class.java)
        if (owner != null && owner.epoch != active.epoch) throw IOException("请求所属会话已变化")
        val request = chain.request().newBuilder().header("Authorization", "Bearer ${active.token}").header("X-APP-USER-ID", active.userId).build()
        val response = chain.proceed(request)
        if (response.code != 401) return response
        val refreshed = try {
            sessions.refresh(active) { refresh ->
                val result = api.refresh(RefreshRequest(refresh)).execute()
                if (result.code() == 401 || result.code() == 403) {
                    result.errorBody()?.close()
                    sessions.invalidate(active)
                    throw ServiceFailure.SignedOut
                }
                if (!result.isSuccessful) { result.errorBody()?.close(); throw IOException("刷新请求失败") }
                result.body()?.requireData() ?: throw ServiceFailure.InvalidResponse
            }
        } catch (error: ServiceFailure) {
            sessions.invalidate(active)
            return response
        } catch (error: IOException) {
            response.close()
            throw error
        } catch (error: Exception) {
            response.close()
            throw IOException("刷新响应解析失败", error)
        }
        if (refreshed == null) return response
        response.close()
        val retry = chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${refreshed.token}").header("X-APP-USER-ID", refreshed.userId).build())
        // 独立支付服务拒绝新令牌不能证明主业务会话失效；刷新接口的真实失效仍在上方处理。
        if (retry.code == 401 && invalidateOnRetriedUnauthorized) sessions.invalidate(refreshed)
        return retry
    }
}

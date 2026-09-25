package yumo.achat.app.ui.imagevideo

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import java.io.File
import java.util.Collections

private const val TemplateVideoCacheBytes = 120L * 1024L * 1024L

internal data class TemplateVideoNetworkConfiguration(
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val minimumRetryCount: Int,
)

internal val DefaultTemplateVideoNetworkConfiguration = TemplateVideoNetworkConfiguration(
    connectTimeoutMs = 30_000,
    readTimeoutMs = 60_000,
    minimumRetryCount = 3,
)

@OptIn(UnstableApi::class)
internal object TemplateVideoCache {
    private val preloadUrls = Collections.synchronizedSet(mutableSetOf<String>())
    private var simpleCache: SimpleCache? = null

    fun mediaSourceFactory(
        context: Context,
        networkConfiguration: TemplateVideoNetworkConfiguration = DefaultTemplateVideoNetworkConfiguration,
    ): DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(cacheDataSourceFactory(context, networkConfiguration))
            .setLoadErrorHandlingPolicy(
                DefaultLoadErrorHandlingPolicy(networkConfiguration.minimumRetryCount),
            )

    fun preload(context: Context, targets: List<TemplateVideoPreloadTarget>, session: TemplateVideoPreloadSession) {
        val appContext = context.applicationContext
        targets.filter { it.url.isNotBlank() && it.bytes > 0 }.forEach targetLoop@ { target ->
            if (session.isCancelled) return
            if (!preloadUrls.add(target.url)) {
                return@targetLoop
            }

            try {
                repeat(DefaultTemplateVideoNetworkConfiguration.minimumRetryCount.coerceAtLeast(1)) {
                    if (session.isCancelled) return@targetLoop
                    val writer = CacheWriter(
                        cacheDataSourceFactory(appContext, DefaultTemplateVideoNetworkConfiguration).createDataSource(),
                        DataSpec.Builder()
                            .setUri(Uri.parse(target.url))
                            .setPosition(0)
                            .setLength(target.bytes)
                            .build(),
                        null,
                        null,
                    )
                    session.attach(writer)
                    val succeeded = runCatching { writer.cache() }.isSuccess
                    session.detach(writer)
                    if (succeeded) return@targetLoop
                }
            } finally {
                preloadUrls.remove(target.url)
            }
        }
    }

    private fun cacheDataSourceFactory(
        context: Context,
        networkConfiguration: TemplateVideoNetworkConfiguration,
    ): CacheDataSource.Factory {
        val appContext = context.applicationContext
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(networkConfiguration.connectTimeoutMs)
            .setReadTimeoutMs(networkConfiguration.readTimeoutMs)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Achat-Video/1.0")
        val upstreamFactory = DefaultDataSource.Factory(appContext, httpFactory)
        return CacheDataSource.Factory()
            .setCache(cache(appContext))
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun cache(context: Context): SimpleCache {
        simpleCache?.let { return it }

        synchronized(this) {
            simpleCache?.let { return it }
            val appContext = context.applicationContext
            return SimpleCache(
                File(appContext.cacheDir, "template-video-cache"),
                LeastRecentlyUsedCacheEvictor(TemplateVideoCacheBytes),
                StandaloneDatabaseProvider(appContext),
            ).also { simpleCache = it }
        }
    }
}

@OptIn(UnstableApi::class)
internal class TemplateVideoPreloadSession {
    @Volatile var isCancelled: Boolean = false
        private set

    @Volatile private var writer: CacheWriter? = null

    @Synchronized
    fun attach(writer: CacheWriter) {
        if (isCancelled) {
            writer.cancel()
        } else {
            this.writer = writer
        }
    }

    @Synchronized
    fun detach(writer: CacheWriter) {
        if (this.writer === writer) this.writer = null
    }

    @Synchronized
    fun cancel() {
        isCancelled = true
        writer?.cancel()
        writer = null
    }
}

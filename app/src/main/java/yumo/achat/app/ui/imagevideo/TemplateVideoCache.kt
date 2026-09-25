package yumo.achat.app.ui.imagevideo

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.util.Collections

private const val TemplateVideoCacheBytes = 120L * 1024L * 1024L

@OptIn(UnstableApi::class)
internal object TemplateVideoCache {
    private val preloadUrls = Collections.synchronizedSet(mutableSetOf<String>())
    private var simpleCache: SimpleCache? = null

    fun mediaSourceFactory(context: Context): DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(cacheDataSourceFactory(context))

    fun preload(context: Context, targets: List<TemplateVideoPreloadTarget>, session: TemplateVideoPreloadSession) {
        val appContext = context.applicationContext
        targets.filter { it.url.isNotBlank() && it.bytes > 0 }.forEach { target ->
            if (session.isCancelled) return
            if (!preloadUrls.add(target.url)) {
                return@forEach
            }

            val writer = CacheWriter(
                cacheDataSourceFactory(appContext).createDataSource(),
                DataSpec.Builder()
                    .setUri(Uri.parse(target.url))
                    .setPosition(0)
                    .setLength(target.bytes)
                    .build(),
                null,
                null,
            )
            session.attach(writer)
            runCatching { writer.cache() }.also {
                session.detach(writer)
                preloadUrls.remove(target.url)
            }
        }
    }

    private fun cacheDataSourceFactory(context: Context): CacheDataSource.Factory {
        val upstreamFactory = DefaultDataSource.Factory(context.applicationContext)
        return CacheDataSource.Factory()
            .setCache(cache(context))
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

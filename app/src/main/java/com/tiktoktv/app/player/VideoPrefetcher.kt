package com.tiktoktv.app.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.tiktoktv.app.data.config.ScraperConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

@OptIn(UnstableApi::class)
class VideoPrefetcher(
    context: Context,
    okHttpClient: OkHttpClient
) {
    private val cacheDir = File(context.cacheDir, "media3_video_cache").apply { mkdirs() }
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private val evictor = LeastRecentlyUsedCacheEvictor(MAX_VIDEO_CACHE_BYTES)

    val simpleCache: SimpleCache = SimpleCache(cacheDir, evictor, databaseProvider)

    private val playbackClient = okHttpClient.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val upstreamFactory = OkHttpDataSource.Factory(playbackClient)
        .setUserAgent(ScraperConfig.HEADER_USER_AGENT_VALUE)
        .setDefaultRequestProperties(
            mapOf(
                ScraperConfig.HEADER_REFERER_KEY to ScraperConfig.HEADER_REFERER_VALUE,
                "Origin" to ScraperConfig.TIKTOK_WEB_BASE
            )
        )

    val cacheDataSourceFactory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(simpleCache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefetchJob: Job? = null

    fun prefetchUrls(urls: List<String>) {
        prefetchJob?.cancel()
        val targets = urls.filter { it.isNotBlank() }.distinct().take(2)
        if (targets.isEmpty()) return

        prefetchJob = scope.launch {
            for (url in targets) {
                runCatching {
                    val dataSource = cacheDataSourceFactory.createDataSource()
                    val dataSpec = DataSpec.Builder()
                        .setUri(Uri.parse(url))
                        .setPosition(0L)
                        .setLength(PREFETCH_BYTES_PER_VIDEO)
                        .build()
                    val writer = CacheWriter(dataSource, dataSpec, null, null)
                    writer.cache()
                }.onFailure { err ->
                    if (err is CancellationException) throw err
                }
            }
        }
    }

    fun getCacheSizeBytes(): Long {
        return runCatching { simpleCache.cacheSpace }.getOrDefault(0L)
    }

    fun clearCache() {
        prefetchJob?.cancel()
        runCatching {
            simpleCache.keys.toList().forEach { key ->
                simpleCache.removeResource(key)
            }
        }
    }

    fun release() {
        prefetchJob?.cancel()
        runCatching { simpleCache.release() }
    }

    companion object {
        private const val MAX_VIDEO_CACHE_BYTES = 180L * 1024L * 1024L // 180 MB
        private const val PREFETCH_BYTES_PER_VIDEO = 1_500_000L // ~1.5 MB per next video
    }
}

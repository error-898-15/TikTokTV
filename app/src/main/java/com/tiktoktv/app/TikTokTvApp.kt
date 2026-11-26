package com.tiktoktv.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.tiktoktv.app.data.config.ScraperConfig
import com.tiktoktv.app.data.local.LocalPrefsRepository
import com.tiktoktv.app.data.repository.ScraperVideoRepository
import com.tiktoktv.app.player.VideoPlayerManager
import com.tiktoktv.app.player.VideoPrefetcher

class TikTokTvApp : Application(), ImageLoaderFactory {

    lateinit var prefsRepository: LocalPrefsRepository
        private set

    lateinit var videoRepository: ScraperVideoRepository
        private set

    lateinit var videoPrefetcher: VideoPrefetcher
        private set

    lateinit var playerManager: VideoPlayerManager
        private set

    override fun onCreate() {
        super.onCreate()
        prefsRepository = LocalPrefsRepository(this)
        videoRepository = ScraperVideoRepository(this)
        videoPrefetcher = VideoPrefetcher(this, videoRepository.okHttpClient)
        playerManager = VideoPlayerManager(this, videoPrefetcher)
    }

    override fun newImageLoader(): ImageLoader {
        val imageClient = videoRepository.okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                val reqBuilder = original.newBuilder()
                if (original.header(ScraperConfig.HEADER_USER_AGENT_KEY).isNullOrBlank()) {
                    reqBuilder.header(
                        ScraperConfig.HEADER_USER_AGENT_KEY,
                        ScraperConfig.HEADER_USER_AGENT_VALUE
                    )
                }
                if (original.header(ScraperConfig.HEADER_REFERER_KEY).isNullOrBlank()) {
                    reqBuilder.header(
                        ScraperConfig.HEADER_REFERER_KEY,
                        ScraperConfig.HEADER_REFERER_VALUE
                    )
                }
                if (original.header(ScraperConfig.HEADER_ACCEPT_KEY).isNullOrBlank()) {
                    reqBuilder.header(
                        ScraperConfig.HEADER_ACCEPT_KEY,
                        "image/webp,image/png,image/jpeg,image/*;q=0.8,*/*;q=0.5"
                    )
                }
                chain.proceed(reqBuilder.build())
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(imageClient)
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.22)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_image_cache"))
                    .maxSizeBytes(120L * 1024L * 1024L)
                    .build()
            }
            .crossfade(120)
            .build()
    }

    override fun onTerminate() {
        super.onTerminate()
        playerManager.release()
        videoPrefetcher.release()
    }
}

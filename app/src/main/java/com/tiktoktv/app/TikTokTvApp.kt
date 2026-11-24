package com.tiktoktv.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
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
        return ImageLoader.Builder(this)
            .okHttpClient(videoRepository.okHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_image_cache"))
                    .maxSizeBytes(80L * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    override fun onTerminate() {
        super.onTerminate()
        playerManager.release()
        videoPrefetcher.release()
    }
}

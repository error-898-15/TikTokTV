package com.tiktoktv.app.player

import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.tiktoktv.app.data.model.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(UnstableApi::class)
class VideoPlayerManager(
    context: Context,
    private val prefetcher: VideoPrefetcher
) {
    private val mainTrackSelector = DefaultTrackSelector(context)
    private val mediaSourceFactory = DefaultMediaSourceFactory(prefetcher.cacheDataSourceFactory)

    val mainPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setTrackSelector(mainTrackSelector)
        .setMediaSourceFactory(mediaSourceFactory)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        .build()

    /**
     * Secondary muted low-res ExoPlayer used on API 31+ to render a live blurred
     * enlarged copy of the same video on the left/right TV backdrop panels.
     */
    val backdropPlayer: ExoPlayer? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val backdropSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setMaxVideoSize(360, 640)
                .build()
        }
        ExoPlayer.Builder(context)
            .setTrackSelector(backdropSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
            }
    } else {
        null
    }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _backdropFailed = MutableStateFlow(false)
    val backdropFailed: StateFlow<Boolean> = _backdropFailed.asStateFlow()

    private var autoScrollEnabled = true
    private var currentVideoUrl: String = ""
    var onVideoEndedCallback: (() -> Unit)? = null

    init {
        mainPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    backdropPlayer?.play()
                } else {
                    backdropPlayer?.pause()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _isBuffering.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED && autoScrollEnabled) {
                    onVideoEndedCallback?.invoke()
                }
            }
        })

        backdropPlayer?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                _backdropFailed.value = true
            }
        })
    }

    fun playVideo(url: String, autoplay: Boolean) {
        if (url.isBlank()) return
        if (currentVideoUrl == url && mainPlayer.playbackState != Player.STATE_IDLE) {
            if (autoplay && !mainPlayer.isPlaying) {
                mainPlayer.play()
                backdropPlayer?.play()
            }
            return
        }
        currentVideoUrl = url
        _backdropFailed.value = false

        val mediaItem = MediaItem.fromUri(url)
        mainPlayer.setMediaItem(mediaItem)
        mainPlayer.prepare()
        mainPlayer.playWhenReady = autoplay

        backdropPlayer?.let { bp ->
            bp.setMediaItem(mediaItem)
            bp.prepare()
            bp.playWhenReady = autoplay
        }
    }

    fun togglePlayPause(): Boolean {
        return if (mainPlayer.isPlaying) {
            mainPlayer.pause()
            backdropPlayer?.pause()
            false
        } else {
            if (mainPlayer.playbackState == Player.STATE_ENDED) {
                mainPlayer.seekTo(0L)
                backdropPlayer?.seekTo(0L)
            }
            mainPlayer.play()
            backdropPlayer?.play()
            true
        }
    }

    fun pause() {
        mainPlayer.pause()
        backdropPlayer?.pause()
    }

    fun resume() {
        if (currentVideoUrl.isNotBlank()) {
            mainPlayer.play()
            backdropPlayer?.play()
        }
    }

    fun setAutoScroll(enabled: Boolean) {
        autoScrollEnabled = enabled
        mainPlayer.repeatMode = if (enabled) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
    }

    fun setQuality(quality: VideoQuality) {
        val builder = mainTrackSelector.buildUponParameters()
        when (quality) {
            VideoQuality.AUTO -> builder.clearVideoSizeConstraints()
            VideoQuality.HD_1080 -> builder.setMaxVideoSize(1080, 1920)
            VideoQuality.HD_720 -> builder.setMaxVideoSize(720, 1280)
            VideoQuality.SD_480 -> builder.setMaxVideoSize(480, 854)
        }
        mainTrackSelector.parameters = builder.build()
    }

    fun release() {
        mainPlayer.release()
        backdropPlayer?.release()
    }
}

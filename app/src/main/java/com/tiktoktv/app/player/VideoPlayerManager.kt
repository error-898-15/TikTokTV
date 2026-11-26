package com.tiktoktv.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
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
    prefetcher: VideoPrefetcher
) {
    private val mainTrackSelector = DefaultTrackSelector(context)
    private val mediaSourceFactory = DefaultMediaSourceFactory(prefetcher.cacheDataSourceFactory)
    private val renderersFactory = DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)

    val mainPlayer: ExoPlayer = ExoPlayer.Builder(context, renderersFactory)
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

    // Kept as null for backward compatibility with BlurredVideoBackdrop signature while
    // eliminating the second simultaneous hardware video decoder that caused black screens on TV.
    val backdropPlayer: ExoPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _hasRenderedFirstFrame = MutableStateFlow(false)
    val hasRenderedFirstFrame: StateFlow<Boolean> = _hasRenderedFirstFrame.asStateFlow()

    private val _backdropFailed = MutableStateFlow(true)
    val backdropFailed: StateFlow<Boolean> = _backdropFailed.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private var autoScrollEnabled = true
    private var candidateUrls: List<String> = emptyList()
    private var candidateIndex = 0
    private var currentAutoplay = true
    private var currentVideoUrl: String = ""
    var onVideoEndedCallback: (() -> Unit)? = null
    var onAllCandidatesFailedCallback: ((failedUrl: String) -> Boolean)? = null

    init {
        mainPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onRenderedFirstFrame() {
                _hasRenderedFirstFrame.value = true
                _playbackError.value = null
                _isBuffering.value = false
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _isBuffering.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED && autoScrollEnabled) {
                    onVideoEndedCallback?.invoke()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val nextIndex = candidateIndex + 1
                if (nextIndex < candidateUrls.size) {
                    candidateIndex = nextIndex
                    prepareUrl(candidateUrls[nextIndex], currentAutoplay)
                } else {
                    val recoveryStarted = onAllCandidatesFailedCallback?.invoke(currentVideoUrl) == true
                    if (recoveryStarted) {
                        _isBuffering.value = true
                        _playbackError.value = null
                    } else {
                        _isBuffering.value = false
                        _playbackError.value = error.message ?: "Playback error"
                    }
                }
            }
        })
    }

    fun playVideo(url: String, autoplay: Boolean) {
        playVideo(listOf(url), autoplay, forceReload = false)
    }

    fun playPhotoAudio(audioUrl: String, autoplay: Boolean) {
        val clean = audioUrl.trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }.orEmpty()
        if (clean.isBlank()) {
            clearForPhotoPost()
            return
        }
        mainPlayer.repeatMode = Player.REPEAT_MODE_ONE
        playVideoInternal(listOf(clean), autoplay, forceReload = false)
    }

    fun clearForPhotoPost() {
        currentVideoUrl = ""
        candidateUrls = emptyList()
        candidateIndex = 0
        _isBuffering.value = false
        _playbackError.value = null
        _hasRenderedFirstFrame.value = false
        mainPlayer.stop()
        mainPlayer.clearMediaItems()
    }

    fun playVideo(urls: List<String>, autoplay: Boolean, forceReload: Boolean = false) {
        mainPlayer.repeatMode = if (autoScrollEnabled) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
        playVideoInternal(urls, autoplay, forceReload)
    }

    fun reportPlaybackError(message: String) {
        _isBuffering.value = false
        _playbackError.value = message
    }

    private fun playVideoInternal(urls: List<String>, autoplay: Boolean, forceReload: Boolean) {
        val cleanUrls = urls.map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            .distinct()
        if (cleanUrls.isEmpty()) {
            val recoveryStarted = onAllCandidatesFailedCallback?.invoke("") == true
            if (!recoveryStarted) {
                _isBuffering.value = false
                _playbackError.value = "Video stream unavailable"
            }
            return
        }

        val primary = cleanUrls.first()
        if (!forceReload &&
            currentVideoUrl == primary &&
            mainPlayer.playbackState != Player.STATE_IDLE &&
            _playbackError.value == null &&
            _hasRenderedFirstFrame.value
        ) {
            if (mainPlayer.playbackState == Player.STATE_ENDED) {
                mainPlayer.seekTo(0L)
            }
            if (autoplay && !mainPlayer.isPlaying) {
                mainPlayer.play()
            }
            return
        }

        candidateUrls = cleanUrls
        candidateIndex = 0
        currentAutoplay = autoplay
        _playbackError.value = null
        _isBuffering.value = true
        prepareUrl(primary, autoplay)
    }

    private fun prepareUrl(url: String, autoplay: Boolean) {
        currentVideoUrl = url
        _hasRenderedFirstFrame.value = false
        val mediaItem = MediaItem.fromUri(url)
        mainPlayer.setMediaItem(mediaItem)
        mainPlayer.prepare()
        mainPlayer.playWhenReady = autoplay
    }

    fun togglePlayPause(): Boolean {
        return if (mainPlayer.isPlaying) {
            mainPlayer.pause()
            false
        } else {
            if (mainPlayer.playbackState == Player.STATE_ENDED) {
                mainPlayer.seekTo(0L)
            }
            mainPlayer.play()
            true
        }
    }

    fun pause() {
        mainPlayer.pause()
    }

    fun resume() {
        if (currentVideoUrl.isNotBlank()) {
            mainPlayer.play()
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
    }
}

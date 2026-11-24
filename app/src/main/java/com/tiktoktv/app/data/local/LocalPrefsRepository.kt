package com.tiktoktv.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tiktoktv.app.data.model.AppSettings
import com.tiktoktv.app.data.model.VideoItem
import com.tiktoktv.app.data.model.VideoQuality

class LocalPrefsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getSettings(): AppSettings {
        val qualityName = prefs.getString(KEY_VIDEO_QUALITY, VideoQuality.AUTO.name)
            ?: VideoQuality.AUTO.name
        val quality = runCatching { VideoQuality.valueOf(qualityName) }
            .getOrDefault(VideoQuality.AUTO)
        return AppSettings(
            autoScroll = prefs.getBoolean(KEY_AUTO_SCROLL, true),
            autoplay = prefs.getBoolean(KEY_AUTOPLAY, true),
            videoQuality = quality
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putBoolean(KEY_AUTO_SCROLL, settings.autoScroll)
            .putBoolean(KEY_AUTOPLAY, settings.autoplay)
            .putString(KEY_VIDEO_QUALITY, settings.videoQuality.name)
            .apply()
    }

    fun getLikedVideoIds(): Set<String> {
        return prefs.getStringSet(KEY_LIKED_IDS, emptySet())?.toSet() ?: emptySet()
    }

    fun getSavedVideoIds(): Set<String> {
        return prefs.getStringSet(KEY_SAVED_IDS, emptySet())?.toSet() ?: emptySet()
    }

    fun getNotInterestedIds(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN_IDS, emptySet())?.toSet() ?: emptySet()
    }

    fun getLikedVideos(): List<VideoItem> {
        val json = prefs.getString(KEY_LIKED_VIDEOS_JSON, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<VideoItem>>() {}.type
            gson.fromJson<List<VideoItem>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun getSavedVideos(): List<VideoItem> {
        val json = prefs.getString(KEY_SAVED_VIDEOS_JSON, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<VideoItem>>() {}.type
            gson.fromJson<List<VideoItem>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun toggleLike(video: VideoItem): Boolean {
        val ids = getLikedVideoIds().toMutableSet()
        val list = getLikedVideos().toMutableList()
        val nowLiked: Boolean
        if (ids.contains(video.id)) {
            ids.remove(video.id)
            list.removeAll { it.id == video.id }
            nowLiked = false
        } else {
            ids.add(video.id)
            list.removeAll { it.id == video.id }
            list.add(0, video.copy(isLiked = true))
            nowLiked = true
        }
        prefs.edit()
            .putStringSet(KEY_LIKED_IDS, ids)
            .putString(KEY_LIKED_VIDEOS_JSON, gson.toJson(list))
            .apply()
        return nowLiked
    }

    fun toggleSave(video: VideoItem): Boolean {
        val ids = getSavedVideoIds().toMutableSet()
        val list = getSavedVideos().toMutableList()
        val nowSaved: Boolean
        if (ids.contains(video.id)) {
            ids.remove(video.id)
            list.removeAll { it.id == video.id }
            nowSaved = false
        } else {
            ids.add(video.id)
            list.removeAll { it.id == video.id }
            list.add(0, video.copy(isSaved = true))
            nowSaved = true
        }
        prefs.edit()
            .putStringSet(KEY_SAVED_IDS, ids)
            .putString(KEY_SAVED_VIDEOS_JSON, gson.toJson(list))
            .apply()
        return nowSaved
    }

    fun markNotInterested(videoId: String) {
        val ids = getNotInterestedIds().toMutableSet()
        ids.add(videoId)
        prefs.edit().putStringSet(KEY_HIDDEN_IDS, ids).apply()
    }

    fun getLikedCommentIds(): Set<String> {
        return prefs.getStringSet(KEY_LIKED_COMMENT_IDS, emptySet())?.toSet() ?: emptySet()
    }

    fun toggleCommentLike(commentId: String): Boolean {
        val ids = getLikedCommentIds().toMutableSet()
        val nowLiked = if (ids.contains(commentId)) {
            ids.remove(commentId)
            false
        } else {
            ids.add(commentId)
            true
        }
        prefs.edit().putStringSet(KEY_LIKED_COMMENT_IDS, ids).apply()
        return nowLiked
    }

    companion object {
        private const val PREFS_NAME = "tiktok_tv_prefs"
        private const val KEY_AUTO_SCROLL = "auto_scroll"
        private const val KEY_AUTOPLAY = "autoplay"
        private const val KEY_VIDEO_QUALITY = "video_quality"
        private const val KEY_LIKED_IDS = "liked_video_ids"
        private const val KEY_SAVED_IDS = "saved_video_ids"
        private const val KEY_HIDDEN_IDS = "not_interested_ids"
        private const val KEY_LIKED_VIDEOS_JSON = "liked_videos_json"
        private const val KEY_SAVED_VIDEOS_JSON = "saved_videos_json"
        private const val KEY_LIKED_COMMENT_IDS = "liked_comment_ids"
    }
}

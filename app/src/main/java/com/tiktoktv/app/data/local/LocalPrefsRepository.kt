package com.tiktoktv.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tiktoktv.app.data.model.AppSettings
import com.tiktoktv.app.data.model.ContentRegion
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
        val regionName = prefs.getString(KEY_CONTENT_REGION, ContentRegion.BANGLADESH_MIX.name)
            ?: ContentRegion.BANGLADESH_MIX.name
        val region = runCatching { ContentRegion.valueOf(regionName) }
            .getOrDefault(ContentRegion.BANGLADESH_MIX)
        return AppSettings(
            autoScroll = prefs.getBoolean(KEY_AUTO_SCROLL, true),
            autoplay = prefs.getBoolean(KEY_AUTOPLAY, true),
            videoQuality = quality,
            contentRegion = region
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putBoolean(KEY_AUTO_SCROLL, settings.autoScroll)
            .putBoolean(KEY_AUTOPLAY, settings.autoplay)
            .putString(KEY_VIDEO_QUALITY, settings.videoQuality.name)
            .putString(KEY_CONTENT_REGION, settings.contentRegion.name)
            .apply()
    }

    fun getAndIncrementFeedCycle(): Int {
        val current = prefs.getInt(KEY_FEED_CYCLE, 0)
        val next = (current + 1) % 10000
        prefs.edit().putInt(KEY_FEED_CYCLE, next).apply()
        return current
    }

    fun getLikedVideoIds(): Set<String> =
        prefs.getStringSet(KEY_LIKED_IDS, emptySet())?.toSet() ?: emptySet()

    fun getSavedVideoIds(): Set<String> =
        prefs.getStringSet(KEY_SAVED_IDS, emptySet())?.toSet() ?: emptySet()

    fun getNotInterestedIds(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN_IDS, emptySet())?.toSet() ?: emptySet()

    fun getLikedVideos(): List<VideoItem> = readVideoList(KEY_LIKED_VIDEOS_JSON)

    fun getSavedVideos(): List<VideoItem> = readVideoList(KEY_SAVED_VIDEOS_JSON)

    fun toggleLike(video: VideoItem): Boolean {
        val ids = getLikedVideoIds().toMutableSet()
        val list = getLikedVideos().toMutableList()
        val nowLiked = if (ids.contains(video.id)) {
            ids.remove(video.id)
            list.removeAll { it.id == video.id }
            false
        } else {
            ids.add(video.id)
            list.removeAll { it.id == video.id }
            list.add(0, video.copy(isLiked = true))
            true
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
        val nowSaved = if (ids.contains(video.id)) {
            ids.remove(video.id)
            list.removeAll { it.id == video.id }
            false
        } else {
            ids.add(video.id)
            list.removeAll { it.id == video.id }
            list.add(0, video.copy(isSaved = true))
            true
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

    fun getLikedCommentIds(): Set<String> =
        prefs.getStringSet(KEY_LIKED_COMMENT_IDS, emptySet())?.toSet() ?: emptySet()

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

    // --- Track recently seen video IDs across app launches ---
    fun getSeenVideoIds(): Set<String> {
        val json = prefs.getString(KEY_SEEN_IDS_JSON, null) ?: return emptySet()
        return runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            (gson.fromJson<List<String>>(json, type) ?: emptyList()).toSet()
        }.getOrDefault(emptySet())
    }

    fun markVideosSeen(videoIds: List<String>) {
        if (videoIds.isEmpty()) return
        val existing = runCatching {
            val json = prefs.getString(KEY_SEEN_IDS_JSON, null)
            if (json.isNullOrBlank()) mutableListOf()
            else {
                val type = object : TypeToken<MutableList<String>>() {}.type
                gson.fromJson<MutableList<String>>(json, type) ?: mutableListOf()
            }
        }.getOrDefault(mutableListOf())

        val combined = (videoIds + existing).distinct().take(MAX_SEEN_IDS)
        prefs.edit().putString(KEY_SEEN_IDS_JSON, gson.toJson(combined)).apply()
    }

    fun clearSeenHistory() {
        prefs.edit().remove(KEY_SEEN_IDS_JSON).apply()
    }

    // --- Watch History (for Own Profile History tab) ---
    fun getWatchHistory(): List<VideoItem> = readVideoList(KEY_WATCH_HISTORY_JSON)

    fun recordWatchHistory(video: VideoItem) {
        val list = getWatchHistory().toMutableList()
        list.removeAll { it.id == video.id }
        list.add(0, video)
        prefs.edit().putString(KEY_WATCH_HISTORY_JSON, gson.toJson(list.take(MAX_WATCH_HISTORY))).apply()
        recordAuthorVideos(video.author.username, listOf(video))
    }

    // --- Persistent per-creator video archive (strictly authored by username) ---
    fun getAuthorVideoHistory(username: String): List<VideoItem> {
        val key = username.trim().removePrefix("@").lowercase()
        if (key.isBlank()) return emptyList()
        val map = readAuthorArchiveMap()
        return map[key].orEmpty().filter {
            it.author.username.trim().removePrefix("@").equals(key, ignoreCase = true)
        }
    }

    fun recordAuthorVideos(username: String, videos: List<VideoItem>) {
        val key = username.trim().removePrefix("@").lowercase()
        if (key.isBlank() || videos.isEmpty()) return
        val ownVideos = videos.filter {
            it.author.username.trim().removePrefix("@").equals(key, ignoreCase = true)
        }
        if (ownVideos.isEmpty()) return
        val map = readAuthorArchiveMap().toMutableMap()
        val existing = map[key].orEmpty().filter {
            it.author.username.trim().removePrefix("@").equals(key, ignoreCase = true)
        }
        val merged = (ownVideos + existing).distinctBy { it.id }.take(MAX_VIDEOS_PER_AUTHOR)
        map[key] = merged
        val trimmedMap = if (map.size > 60) {
            map.entries.toList().takeLast(60).associate { it.key to it.value }
        } else {
            map
        }
        prefs.edit().putString(KEY_AUTHOR_ARCHIVE_JSON, gson.toJson(trimmedMap)).apply()
    }

    // --- Search History (for Instant Search Suggestions) ---
    fun getSearchHistory(): List<String> {
        val json = prefs.getString(KEY_SEARCH_HISTORY_JSON, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun addSearchHistory(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        val updated = (listOf(clean) + getSearchHistory().filterNot { it.equals(clean, ignoreCase = true) })
            .take(MAX_SEARCH_HISTORY)
        prefs.edit().putString(KEY_SEARCH_HISTORY_JSON, gson.toJson(updated)).apply()
    }

    private fun readVideoList(key: String): List<VideoItem> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<VideoItem>>() {}.type
            gson.fromJson<List<VideoItem>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun readAuthorArchiveMap(): Map<String, List<VideoItem>> {
        val json = prefs.getString(KEY_AUTHOR_ARCHIVE_JSON, null) ?: return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, List<VideoItem>>>() {}.type
            gson.fromJson<Map<String, List<VideoItem>>>(json, type) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    companion object {
        private const val PREFS_NAME = "tiktok_tv_prefs"
        private const val KEY_AUTO_SCROLL = "auto_scroll"
        private const val KEY_AUTOPLAY = "autoplay"
        private const val KEY_VIDEO_QUALITY = "video_quality"
        private const val KEY_CONTENT_REGION = "content_region"
        private const val KEY_FEED_CYCLE = "feed_cycle_counter"
        private const val KEY_LIKED_IDS = "liked_video_ids"
        private const val KEY_SAVED_IDS = "saved_video_ids"
        private const val KEY_HIDDEN_IDS = "not_interested_ids"
        private const val KEY_LIKED_VIDEOS_JSON = "liked_videos_json"
        private const val KEY_SAVED_VIDEOS_JSON = "saved_videos_json"
        private const val KEY_LIKED_COMMENT_IDS = "liked_comment_ids"
        private const val KEY_SEEN_IDS_JSON = "seen_video_ids_json"
        private const val KEY_WATCH_HISTORY_JSON = "watch_history_json"
        private const val KEY_AUTHOR_ARCHIVE_JSON = "author_archive_json"
        private const val KEY_SEARCH_HISTORY_JSON = "search_history_json"
        private const val MAX_SEEN_IDS = 1000
        private const val MAX_WATCH_HISTORY = 100
        private const val MAX_VIDEOS_PER_AUTHOR = 200
        private const val MAX_SEARCH_HISTORY = 15
    }
}

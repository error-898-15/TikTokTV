package com.tiktoktv.app.data.model

data class Category(
    val id: String,
    val label: String,
    val slug: String,
    val hashtag: String,
    val previewImageUrl: String = ""
)

data class Author(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String = "",
    val bio: String = "",
    val verified: Boolean = false,
    val secUid: String = "",
    val followingCount: Long = 0L,
    val followersCount: Long = 0L,
    val likesCount: Long = 0L,
    val videoCount: Long = 0L
)

data class MusicInfo(
    val id: String = "",
    val title: String = "",
    val author: String = "",
    val coverUrl: String = ""
) {
    val displayText: String
        get() = when {
            title.isBlank() -> "Original Sound"
            author.isBlank() || title.contains(author, ignoreCase = true) -> title
            else -> "$title - $author"
        }
}

data class VideoStats(
    val likesCount: Long = 0L,
    val commentsCount: Long = 0L,
    val sharesCount: Long = 0L,
    val viewsCount: Long = 0L
)

enum class VideoQuality(val label: String) {
    AUTO("Auto"),
    HD_1080("1080p"),
    HD_720("720p"),
    SD_480("480p")
}

data class VideoItem(
    val id: String,
    val videoUrl: String,
    val hdVideoUrl: String = "",
    val sdVideoUrl: String = "",
    val coverUrl: String = "",
    val caption: String = "",
    val hashtags: List<String> = emptyList(),
    val author: Author,
    val music: MusicInfo = MusicInfo(),
    val stats: VideoStats = VideoStats(),
    val shareUrl: String = "",
    val createdAt: Long = 0L,
    val isLiked: Boolean = false,
    val isSaved: Boolean = false
) {
    fun resolveUrlForQuality(quality: VideoQuality): String = when (quality) {
        VideoQuality.AUTO -> videoUrl
        VideoQuality.HD_1080, VideoQuality.HD_720 -> hdVideoUrl.ifBlank { videoUrl }
        VideoQuality.SD_480 -> sdVideoUrl.ifBlank { videoUrl }
    }

    fun resolveCandidateUrls(quality: VideoQuality): List<String> {
        val primary = resolveUrlForQuality(quality)
        return listOf(primary, videoUrl, sdVideoUrl, hdVideoUrl)
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            .distinct()
    }
}

data class Comment(
    val id: String,
    val authorName: String,
    val authorUsername: String = "",
    val authorAvatarUrl: String = "",
    val text: String,
    val likesCount: Long = 0L,
    val createdAtText: String = "",
    val isLiked: Boolean = false
)

data class FeedPage(
    val videos: List<VideoItem>,
    val nextCursor: String? = null,
    val hasMore: Boolean = false
)

data class UserProfileResult(
    val author: Author,
    val videos: List<VideoItem>,
    val nextCursor: String? = null,
    val hasMore: Boolean = false
)

enum class SearchTab(val label: String) {
    TOP("Top"),
    USERS("Users"),
    VIDEOS("Videos"),
    LIVE("Live"),
    HASHTAGS("Hashtags")
}

enum class SuggestionType {
    HISTORY,
    TRENDING,
    KEYWORD,
    CREATOR,
    HASHTAG
}

data class SearchSuggestion(
    val text: String,
    val type: SuggestionType,
    val subtitle: String = "",
    val avatarUrl: String = "",
    val verified: Boolean = false
)

data class LiveStreamItem(
    val id: String,
    val roomId: String,
    val title: String,
    val host: Author,
    val viewerCount: Long = 0L,
    val coverUrl: String = "",
    val streamUrl: String = "",
    val category: String = "Live"
)

data class HashtagResult(
    val tag: String,
    val viewCountText: String = "",
    val videoCount: Int = 0,
    val previewCoverUrl: String = "",
    val challengeId: String = "",
    val viewCount: Long = 0L
)

data class UsersPage(
    val users: List<Author>,
    val nextCursor: String? = null,
    val hasMore: Boolean = false
)

data class HashtagsPage(
    val hashtags: List<HashtagResult>,
    val nextCursor: String? = null,
    val hasMore: Boolean = false
)

data class UnifiedSearchResult(
    val query: String,
    val videos: List<VideoItem> = emptyList(),
    val users: List<Author> = emptyList(),
    val liveStreams: List<LiveStreamItem> = emptyList(),
    val hashtags: List<HashtagResult> = emptyList(),
    val relatedQueries: List<String> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val usersNextCursor: String? = null,
    val usersHasMore: Boolean = false,
    val hashtagsNextCursor: String? = null,
    val hashtagsHasMore: Boolean = false
)

data class AppSettings(
    val autoScroll: Boolean = true,
    val autoplay: Boolean = true,
    val videoQuality: VideoQuality = VideoQuality.AUTO
)

class ScraperException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

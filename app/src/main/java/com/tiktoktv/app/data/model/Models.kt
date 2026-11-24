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
    val followingCount: Long = 0L,
    val followersCount: Long = 0L,
    val likesCount: Long = 0L
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
    val isLiked: Boolean = false,
    val isSaved: Boolean = false
) {
    fun resolveUrlForQuality(quality: VideoQuality): String = when (quality) {
        VideoQuality.AUTO -> videoUrl
        VideoQuality.HD_1080, VideoQuality.HD_720 -> hdVideoUrl.ifBlank { videoUrl }
        VideoQuality.SD_480 -> sdVideoUrl.ifBlank { videoUrl }
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

data class AppSettings(
    val autoScroll: Boolean = true,
    val autoplay: Boolean = true,
    val videoQuality: VideoQuality = VideoQuality.AUTO
)

class ScraperException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

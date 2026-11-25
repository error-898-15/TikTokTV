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

enum class ContentRegion(
    val code: String,
    val label: String,
    val shortLabel: String,
    val acceptLanguage: String,
    val timeZone: String,
    val discoveryHashtags: List<String>
) {
    BANGLADESH_MIX(
        code = "BD",
        label = "Bangladesh & Mix (Default)",
        shortLabel = "BD + Mix",
        acceptLanguage = "bn-BD,bn;q=0.9,en-US;q=0.8,en;q=0.7",
        timeZone = "Asia/Dhaka",
        discoveryHashtags = listOf("bangladesh", "banglatiktok", "bdfyp", "dhaka", "bangla", "bd")
    ),
    BANGLADESH(
        code = "BD",
        label = "Bangladesh Only",
        shortLabel = "Bangladesh (BD)",
        acceptLanguage = "bn-BD,bn;q=0.9,en-US;q=0.8,en;q=0.7",
        timeZone = "Asia/Dhaka",
        discoveryHashtags = listOf("bangladesh", "banglatiktok", "bdfyp", "dhaka", "bangla", "bangladeshi")
    ),
    INDIA(
        code = "IN",
        label = "India",
        shortLabel = "India (IN)",
        acceptLanguage = "en-IN,hi-IN;q=0.9,en;q=0.8",
        timeZone = "Asia/Kolkata",
        discoveryHashtags = listOf("india", "indiatiktok", "indian", "bollywood", "desi")
    ),
    PAKISTAN(
        code = "PK",
        label = "Pakistan",
        shortLabel = "Pakistan (PK)",
        acceptLanguage = "en-PK,ur-PK;q=0.9,en;q=0.8",
        timeZone = "Asia/Karachi",
        discoveryHashtags = listOf("pakistan", "pakistantiktok", "pakistani", "lahore", "karachi")
    ),
    UNITED_STATES(
        code = "US",
        label = "United States",
        shortLabel = "United States (US)",
        acceptLanguage = "en-US,en;q=0.9",
        timeZone = "America/New_York",
        discoveryHashtags = listOf("fyp", "foryou", "usa", "viral")
    ),
    UNITED_KINGDOM(
        code = "GB",
        label = "United Kingdom",
        shortLabel = "United Kingdom (GB)",
        acceptLanguage = "en-GB,en;q=0.9",
        timeZone = "Europe/London",
        discoveryHashtags = listOf("uk", "uktiktok", "london", "british")
    ),
    CANADA(
        code = "CA",
        label = "Canada",
        shortLabel = "Canada (CA)",
        acceptLanguage = "en-CA,en;q=0.9",
        timeZone = "America/Toronto",
        discoveryHashtags = listOf("canada", "canadatiktok", "toronto")
    ),
    AUSTRALIA(
        code = "AU",
        label = "Australia",
        shortLabel = "Australia (AU)",
        acceptLanguage = "en-AU,en;q=0.9",
        timeZone = "Australia/Sydney",
        discoveryHashtags = listOf("australia", "aussie", "sydney")
    ),
    MALAYSIA(
        code = "MY",
        label = "Malaysia",
        shortLabel = "Malaysia (MY)",
        acceptLanguage = "ms-MY,en-MY;q=0.9,en;q=0.8",
        timeZone = "Asia/Kuala_Lumpur",
        discoveryHashtags = listOf("malaysia", "malaysiatiktok", "fypmalaysia")
    ),
    INDONESIA(
        code = "ID",
        label = "Indonesia",
        shortLabel = "Indonesia (ID)",
        acceptLanguage = "id-ID,id;q=0.9,en;q=0.8",
        timeZone = "Asia/Jakarta",
        discoveryHashtags = listOf("indonesia", "fypindonesia", "tiktokindonesia")
    ),
    SINGAPORE(
        code = "SG",
        label = "Singapore",
        shortLabel = "Singapore (SG)",
        acceptLanguage = "en-SG,en;q=0.9",
        timeZone = "Asia/Singapore",
        discoveryHashtags = listOf("singapore", "sgtiktok")
    ),
    UAE(
        code = "AE",
        label = "United Arab Emirates",
        shortLabel = "UAE (AE)",
        acceptLanguage = "ar-AE,en-AE;q=0.9,en;q=0.8",
        timeZone = "Asia/Dubai",
        discoveryHashtags = listOf("dubai", "uae", "emirates")
    ),
    SAUDI_ARABIA(
        code = "SA",
        label = "Saudi Arabia",
        shortLabel = "Saudi Arabia (SA)",
        acceptLanguage = "ar-SA,en;q=0.8",
        timeZone = "Asia/Riyadh",
        discoveryHashtags = listOf("saudi", "ksa", "riyadh")
    ),
    JAPAN(
        code = "JP",
        label = "Japan",
        shortLabel = "Japan (JP)",
        acceptLanguage = "ja-JP,ja;q=0.9,en;q=0.8",
        timeZone = "Asia/Tokyo",
        discoveryHashtags = listOf("japan", "japantiktok", "tokyo")
    ),
    SOUTH_KOREA(
        code = "KR",
        label = "South Korea",
        shortLabel = "South Korea (KR)",
        acceptLanguage = "ko-KR,ko;q=0.9,en;q=0.8",
        timeZone = "Asia/Seoul",
        discoveryHashtags = listOf("korea", "kpop", "seoul")
    ),
    GERMANY(
        code = "DE",
        label = "Germany",
        shortLabel = "Germany (DE)",
        acceptLanguage = "de-DE,de;q=0.9,en;q=0.8",
        timeZone = "Europe/Berlin",
        discoveryHashtags = listOf("deutschland", "germany", "fypdeutschland")
    ),
    FRANCE(
        code = "FR",
        label = "France",
        shortLabel = "France (FR)",
        acceptLanguage = "fr-FR,fr;q=0.9,en;q=0.8",
        timeZone = "Europe/Paris",
        discoveryHashtags = listOf("france", "pourtoi", "paris")
    ),
    BRAZIL(
        code = "BR",
        label = "Brazil",
        shortLabel = "Brazil (BR)",
        acceptLanguage = "pt-BR,pt;q=0.9,en;q=0.8",
        timeZone = "America/Sao_Paulo",
        discoveryHashtags = listOf("brasil", "brazil", "fypbrasil")
    ),
    GLOBAL_MIX(
        code = "GLOBAL",
        label = "Global Mix",
        shortLabel = "Global Mix",
        acceptLanguage = "en-US,en;q=0.9",
        timeZone = "UTC",
        discoveryHashtags = listOf("fyp", "foryou", "viral", "trending")
    )
}

data class AppSettings(
    val autoScroll: Boolean = true,
    val autoplay: Boolean = true,
    val videoQuality: VideoQuality = VideoQuality.AUTO,
    val contentRegion: ContentRegion = ContentRegion.BANGLADESH_MIX
)

class ScraperException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

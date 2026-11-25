package com.tiktoktv.app.data.config

import com.tiktoktv.app.data.model.Category

/**
 * Centralized scraping & API configuration.
 * Update URLs, headers, JSON keys, and Jsoup selectors here if upstream structures change.
 */
object ScraperConfig {

    // Base URLs
    const val TIKTOK_WEB_BASE = "https://www.tiktok.com"
    const val TIKTOK_EXPLORE_URL = "$TIKTOK_WEB_BASE/explore"
    const val TIKTOK_LIVE_URL = "$TIKTOK_WEB_BASE/live"
    const val TIKTOK_TAG_URL_TEMPLATE = "$TIKTOK_WEB_BASE/tag/%s"
    const val TIKTOK_DISCOVER_URL_TEMPLATE = "$TIKTOK_WEB_BASE/discover/%s"
    const val TIKTOK_PROFILE_URL_TEMPLATE = "$TIKTOK_WEB_BASE/@%s"
    const val TIKTOK_EMBED_PROFILE_TEMPLATE = "$TIKTOK_WEB_BASE/embed/@%s"
    const val TIKTOK_VIDEO_SHARE_TEMPLATE = "$TIKTOK_WEB_BASE/@%s/video/%s"

    // Direct TikTok Web JSON Endpoints (session cookies warmed from TIKTOK_EXPLORE_URL)
    const val TIKTOK_EXPLORE_API = "$TIKTOK_WEB_BASE/api/explore/item_list/"
    const val TIKTOK_POST_ITEM_LIST = "$TIKTOK_WEB_BASE/api/post/item_list/"
    const val TIKTOK_CREATOR_ITEM_LIST = "$TIKTOK_WEB_BASE/api/creator/item_list/"
    const val TIKTOK_COMMENT_API = "$TIKTOK_WEB_BASE/api/comment/list/"

    // Live Autocomplete Endpoint
    const val API_SEARCH_SUGGEST = "https://suggestqueries.google.com/complete/search"

    // Public JSON Mirror Endpoints
    const val PUBLIC_API_BASE = "https://www.tikwm.com/api"
    const val API_TRENDING_FEED = "$PUBLIC_API_BASE/feed/list"
    const val API_SEARCH_FEED = "$PUBLIC_API_BASE/feed/search"
    const val API_USER_SEARCH = "$PUBLIC_API_BASE/user/search"
    const val API_USER_POSTS = "$PUBLIC_API_BASE/user/posts"
    const val API_USER_INFO = "$PUBLIC_API_BASE/user/info"
    const val API_COMMENT_LIST = "$PUBLIC_API_BASE/comment/list"

    // Network & Cache Settings
    const val CONNECT_TIMEOUT_SEC = 12L
    const val READ_TIMEOUT_SEC = 18L
    const val HTTP_DISK_CACHE_BYTES = 80L * 1024L * 1024L // 80 MB
    const val MEMORY_CACHE_TTL_MS = 3 * 60 * 1000L // 3 minutes (never blocks fresh launch)
    const val DISK_CACHE_TTL_MS = 15 * 60 * 1000L // 15 minutes
    const val BLOCKED_ENDPOINT_COOLDOWN_MS = 5 * 60 * 1000L // 5 min circuit breaker for CF-blocked routes
    const val DEFAULT_PAGE_SIZE = 18
    const val CATEGORY_PAGE_SIZE = 15
    const val PROFILE_INITIAL_BATCH_SIZE = 30
    const val PROFILE_CREATOR_PAGE_SIZE = 15
    const val PROFILE_WEB_PAGE_SIZE = 35
    const val HYDRATION_CONCURRENCY = 5
    const val WEB_SESSION_TIMEOUT_MS = 22_000L
    const val WEBMSSDK_FALLBACK_URL =
        "https://sf16-website-login.neutral.ttwstatic.com/obj/tiktok_web_login_static/webmssdk/1.0.0.422/webmssdk.js"

    // Region pool rotated on every launch/refresh for fresh content
    val TRENDING_REGION_POOL: List<String> = listOf(
        "US", "GB", "CA", "AU", "SG", "DE", "FR", "BR", "JP", "ID"
    )

    // HTTP Headers
    const val HEADER_USER_AGENT_KEY = "User-Agent"
    const val HEADER_USER_AGENT_VALUE =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    const val HEADER_MOBILE_USER_AGENT_VALUE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    const val HEADER_GOOGLEBOT_MOBILE_USER_AGENT_VALUE =
        "Mozilla/5.0 (Linux; Android 6.0.1; Nexus 5X Build/MMB29P) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36 " +
            "(compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
    const val HEADER_GOOGLEBOT_DESKTOP_USER_AGENT_VALUE =
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
    const val HEADER_ACCEPT_LANGUAGE_KEY = "Accept-Language"
    const val HEADER_ACCEPT_LANGUAGE_VALUE = "en-US,en;q=0.9"
    const val HEADER_REFERER_KEY = "Referer"
    const val HEADER_REFERER_VALUE = "$TIKTOK_WEB_BASE/"
    const val HEADER_ACCEPT_KEY = "Accept"
    const val HEADER_ACCEPT_HTML =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    const val HEADER_ACCEPT_JSON = "application/json, text/plain, */*"

    // Jsoup HTML Selectors
    const val SELECTOR_UNIVERSAL_DATA_SCRIPT = "script#__UNIVERSAL_DATA_FOR_REHYDRATION__"
    const val SELECTOR_API_DATA_SCRIPT = "script#api-data"
    const val SELECTOR_SIGI_STATE_SCRIPT = "script#SIGI_STATE"
    const val SELECTOR_NEXT_DATA_SCRIPT = "script#__NEXT_DATA__"
    const val SELECTOR_FRONTITY_STATE_SCRIPT = "script#__FRONTITY_CONNECT_STATE__"
    const val SELECTOR_PROFILE_PAGE_LD = "script#ProfilePage"
    const val SELECTOR_ITEM_LIST_LD = "script#ItemList"
    const val SELECTOR_OG_VIDEO = "meta[property=og:video]"
    const val SELECTOR_OG_IMAGE = "meta[property=og:image]"
    const val SELECTOR_OG_TITLE = "meta[property=og:title]"
    const val SELECTOR_OG_DESCRIPTION = "meta[property=og:description]"
    const val SELECTOR_USER_TITLE = "[data-e2e=user-title]"
    const val SELECTOR_USER_SUBTITLE = "[data-e2e=user-subtitle]"
    const val SELECTOR_USER_BIO = "[data-e2e=user-bio]"
    const val SELECTOR_USER_AVATAR = "[data-e2e=user-avatar] img"
    const val SELECTOR_FOLLOWING_COUNT = "[data-e2e=following-count]"
    const val SELECTOR_FOLLOWERS_COUNT = "[data-e2e=followers-count]"
    const val SELECTOR_LIKES_COUNT = "[data-e2e=likes-count]"
    const val SELECTOR_VIDEO_CARD_LINKS = "a[href*=/video/]"

    // Embedded JSON Keys
    const val JSON_KEY_DEFAULT_SCOPE = "__DEFAULT_SCOPE__"
    const val JSON_KEY_ITEM_MODULE = "ItemModule"
    const val JSON_KEY_USER_MODULE = "UserModule"
    const val JSON_KEY_USERS = "users"
    const val JSON_KEY_STATS = "stats"
    const val JSON_KEY_WEBAPP_USER_DETAIL = "webapp.user-detail"
    const val JSON_KEY_WEBAPP_EXPLORE = "webapp.explore-detail"
    const val JSON_KEY_USER_INFO = "userInfo"

    // Exact TikTok Explore categoryType IDs verified against webapp.explore.categoryList
    // (Genres without an exact Explore ID like Travel, Art, Music, Following use /tag/ scraping directly)
    val EXPLORE_CATEGORY_TYPE_MAP: Map<String, Int> = mapOf(
        "for_you" to 120,
        "comedy" to 104,
        "learning" to 116,
        "sports" to 112,
        "animals" to 113,
        "food" to 111,
        "games" to 103,
        "entertainment" to 101,
        "dance" to 119
    )

    // Primary & secondary hashtags per genre so /tag/ & /discover/ scraping yields deep, accurate feeds
    val CATEGORY_HASHTAG_ALIASES: Map<String, List<String>> = mapOf(
        "for_you" to listOf("fyp", "foryou", "viral"),
        "following" to listOf("trending", "viral", "fyp"),
        "comedy" to listOf("comedy", "funny", "memes"),
        "learning" to listOf("learnontiktok", "education", "facts"),
        "sports" to listOf("sports", "football", "basketball"),
        "travel" to listOf("travel", "traveltiktok", "travelvlog"),
        "animals" to listOf("animals", "pets", "cuteanimals"),
        "art" to listOf("art", "drawing", "artist"),
        "food" to listOf("food", "foodie", "recipe"),
        "games" to listOf("gaming", "gamer", "videogames"),
        "entertainment" to listOf("entertainment", "movies", "celebrity"),
        "music" to listOf("music", "song", "singer"),
        "dance" to listOf("dance", "dancing", "choreography")
    )

    val DEFAULT_CATEGORIES: List<Category> = listOf(
        Category(id = "for_you", label = "For You", slug = "foryou", hashtag = "fyp"),
        Category(id = "following", label = "Following", slug = "following", hashtag = "trending"),
        Category(id = "comedy", label = "Comedy", slug = "comedy", hashtag = "comedy"),
        Category(id = "learning", label = "Learning", slug = "learning", hashtag = "learnontiktok"),
        Category(id = "sports", label = "Sports", slug = "sports", hashtag = "sports"),
        Category(id = "travel", label = "Travel", slug = "travel", hashtag = "travel"),
        Category(id = "animals", label = "Animals", slug = "animals", hashtag = "animals"),
        Category(id = "art", label = "Art", slug = "art", hashtag = "art"),
        Category(id = "food", label = "Food", slug = "food", hashtag = "food"),
        Category(id = "games", label = "Games", slug = "games", hashtag = "gaming"),
        Category(id = "entertainment", label = "Entertainment", slug = "entertainment", hashtag = "entertainment"),
        Category(id = "music", label = "Music", slug = "music", hashtag = "music"),
        Category(id = "dance", label = "Dance", slug = "dance", hashtag = "dance")
    )

    val TRENDING_SEARCH_CHIPS: List<String> = listOf(
        "#comedy",
        "#travel",
        "#animals",
        "#food",
        "#gaming",
        "#sports",
        "#learning",
        "#music"
    )
}

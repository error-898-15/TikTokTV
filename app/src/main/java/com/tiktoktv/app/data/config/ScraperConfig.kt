package com.tiktoktv.app.data.config

import com.tiktoktv.app.data.model.Category

/**
 * Centralized scraping configuration.
 * Update URLs, headers, JSON keys, and Jsoup selectors here if the upstream site structure changes.
 */
object ScraperConfig {

    // Base URLs
    const val TIKTOK_WEB_BASE = "https://www.tiktok.com"
    const val TIKTOK_EXPLORE_URL = "$TIKTOK_WEB_BASE/explore"
    const val TIKTOK_TAG_URL_TEMPLATE = "$TIKTOK_WEB_BASE/tag/%s"
    const val TIKTOK_DISCOVER_URL_TEMPLATE = "$TIKTOK_WEB_BASE/discover/%s"
    const val TIKTOK_PROFILE_URL_TEMPLATE = "$TIKTOK_WEB_BASE/@%s"
    const val TIKTOK_VIDEO_SHARE_TEMPLATE = "$TIKTOK_WEB_BASE/@%s/video/%s"

    // Direct TikTok Web JSON Endpoints (session cookies warmed from TIKTOK_EXPLORE_URL)
    const val TIKTOK_EXPLORE_API = "$TIKTOK_WEB_BASE/api/explore/item_list/"
    const val TIKTOK_COMMENT_API = "$TIKTOK_WEB_BASE/api/comment/list/"

    // Public JSON Scraper Mirror Endpoints (secondary/search/profile endpoints)
    const val PUBLIC_API_BASE = "https://www.tikwm.com/api"
    const val API_TRENDING_FEED = "$PUBLIC_API_BASE/feed/list"
    const val API_SEARCH_FEED = "$PUBLIC_API_BASE/feed/search"
    const val API_USER_POSTS = "$PUBLIC_API_BASE/user/posts"
    const val API_USER_INFO = "$PUBLIC_API_BASE/user/info"
    const val API_COMMENT_LIST = "$PUBLIC_API_BASE/comment/list"

    // Network & Cache Settings
    const val CONNECT_TIMEOUT_SEC = 15L
    const val READ_TIMEOUT_SEC = 20L
    const val HTTP_DISK_CACHE_BYTES = 100L * 1024L * 1024L // 100 MB
    const val MEMORY_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    const val DISK_CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes
    const val DEFAULT_PAGE_SIZE = 15

    // HTTP Headers
    const val HEADER_USER_AGENT_KEY = "User-Agent"
    const val HEADER_USER_AGENT_VALUE =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    const val HEADER_MOBILE_USER_AGENT_VALUE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    const val HEADER_ACCEPT_LANGUAGE_KEY = "Accept-Language"
    const val HEADER_ACCEPT_LANGUAGE_VALUE = "en-US,en;q=0.9"
    const val HEADER_REFERER_KEY = "Referer"
    const val HEADER_REFERER_VALUE = "$TIKTOK_WEB_BASE/"
    const val HEADER_ACCEPT_KEY = "Accept"
    const val HEADER_ACCEPT_HTML =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    const val HEADER_ACCEPT_JSON = "application/json, text/plain, */*"

    // Jsoup HTML Selectors (for direct web page scraping)
    const val SELECTOR_UNIVERSAL_DATA_SCRIPT = "script#__UNIVERSAL_DATA_FOR_REHYDRATION__"
    const val SELECTOR_API_DATA_SCRIPT = "script#api-data"
    const val SELECTOR_SIGI_STATE_SCRIPT = "script#SIGI_STATE"
    const val SELECTOR_NEXT_DATA_SCRIPT = "script#__NEXT_DATA__"
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

    // Embedded JSON Keys (TikTok Hydration State)
    const val JSON_KEY_DEFAULT_SCOPE = "__DEFAULT_SCOPE__"
    const val JSON_KEY_ITEM_MODULE = "ItemModule"
    const val JSON_KEY_USER_MODULE = "UserModule"
    const val JSON_KEY_USERS = "users"
    const val JSON_KEY_STATS = "stats"
    const val JSON_KEY_WEBAPP_USER_DETAIL = "webapp.user-detail"
    const val JSON_KEY_WEBAPP_EXPLORE = "webapp.explore-detail"
    const val JSON_KEY_USER_INFO = "userInfo"

    // TikTok Explore categoryType IDs (from webapp.explore.categoryList)
    val EXPLORE_CATEGORY_TYPE_MAP: Map<String, Int> = mapOf(
        "for_you" to 120,
        "following" to 119,
        "comedy" to 104,
        "learning" to 116,
        "sports" to 112,
        "travel" to 105,
        "animals" to 113,
        "art" to 100,
        "food" to 111,
        "games" to 103,
        "entertainment" to 101,
        "music" to 119,
        "dance" to 119
    )

    // Default TV Categories matching the TikTok TV Browse Bar
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

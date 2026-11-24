package com.tiktoktv.app.data.repository

import android.content.Context
import com.google.gson.Gson
import com.tiktoktv.app.data.config.ScraperConfig
import com.tiktoktv.app.data.model.Author
import com.tiktoktv.app.data.model.Category
import com.tiktoktv.app.data.model.Comment
import com.tiktoktv.app.data.model.FeedPage
import com.tiktoktv.app.data.model.MusicInfo
import com.tiktoktv.app.data.model.ScraperException
import com.tiktoktv.app.data.model.UserProfileResult
import com.tiktoktv.app.data.model.VideoItem
import com.tiktoktv.app.data.model.VideoStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ScraperVideoRepository(
    context: Context
) : VideoRepository {

    private val gson = Gson()
    private val httpCacheDir = File(context.cacheDir, "http_cache").apply { mkdirs() }
    private val scraperCacheDir = File(context.cacheDir, "scraper_cache").apply { mkdirs() }

    private val memoryCache = ConcurrentHashMap<String, CacheEntry>()
    private val videoIndex = ConcurrentHashMap<String, VideoItem>()

    // The public mirror API allows ~1 request/second. Requests are serialized and spaced out here.
    private val apiThrottleLock = Any()
    private var lastApiCallAt = 0L

    @Volatile
    private var lastApiError: String? = null

    @Volatile
    private var tikTokCookiesWarmedAt = 0L
    private val cookieLock = Any()
    private val cookies = mutableListOf<Cookie>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, incomingCookies: List<Cookie>) {
            synchronized(cookieLock) {
                val now = System.currentTimeMillis()
                cookies.removeAll { it.expiresAt < now }
                incomingCookies.forEach { incoming ->
                    cookies.removeAll {
                        it.name == incoming.name &&
                            it.domain.equals(incoming.domain, ignoreCase = true) &&
                            it.path == incoming.path
                    }
                    if (incoming.expiresAt >= now) {
                        cookies.add(incoming)
                    }
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            synchronized(cookieLock) {
                val now = System.currentTimeMillis()
                cookies.removeAll { it.expiresAt < now }
                return cookies.filter { it.matches(url) }
            }
        }
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(ScraperConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(ScraperConfig.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .cache(Cache(httpCacheDir, ScraperConfig.HTTP_DISK_CACHE_BYTES))
        .followRedirects(true)
        .build()

    private fun throttleApi() {
        synchronized(apiThrottleLock) {
            val wait = lastApiCallAt + API_MIN_INTERVAL_MS - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastApiCallAt = System.currentTimeMillis()
        }
    }

    private fun ensureTikTokSessionCookies(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val hasChainCookie = synchronized(cookieLock) {
            cookies.any {
                it.domain.contains("tiktok.com", ignoreCase = true) &&
                    it.expiresAt > now &&
                    (it.name == "tt_chain_token" || it.name == "ttwid")
            }
        }
        if (!force && hasChainCookie && (now - tikTokCookiesWarmedAt) < 10 * 60 * 1000L) {
            return
        }
        runCatching {
            executeGet(
                url = ScraperConfig.TIKTOK_EXPLORE_URL,
                acceptJson = false,
                userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
                referer = ScraperConfig.HEADER_REFERER_VALUE
            )
            tikTokCookiesWarmedAt = System.currentTimeMillis()
        }
    }

    private fun apiPostJson(url: String, form: FormBody): JSONObject {
        var attempt = 0
        while (true) {
            val text = executePost(url, form)
            val root = runCatching { JSONObject(text) }
                .getOrElse { throw ScraperException("Invalid JSON response from mirror API") }
            val code = root.optInt("code", -1)
            if (code == 0) return root
            val msg = root.optCleanString("msg")
            val retryable = msg.contains("limit", ignoreCase = true) ||
                msg.contains("again", ignoreCase = true) ||
                msg.contains("busy", ignoreCase = true) ||
                msg.contains("error occurred", ignoreCase = true)
            if (attempt < 2 && retryable) {
                attempt++
                Thread.sleep(1600L * attempt)
                continue
            }
            throw ScraperException("API error ($code): ${msg.ifBlank { "Unknown error" }}")
        }
    }

    private fun apiGetJson(url: String): JSONObject {
        var attempt = 0
        while (true) {
            val text = executeGet(
                url = url,
                acceptJson = true,
                userAgent = ScraperConfig.HEADER_USER_AGENT_VALUE,
                referer = "${ScraperConfig.PUBLIC_API_BASE.removeSuffix("/api")}/"
            )
            val root = runCatching { JSONObject(text) }
                .getOrElse { throw ScraperException("Invalid JSON response from mirror API") }
            val code = root.optInt("code", -1)
            if (code == 0) return root
            val msg = root.optCleanString("msg")
            val retryable = msg.contains("limit", ignoreCase = true) ||
                msg.contains("again", ignoreCase = true) ||
                msg.contains("busy", ignoreCase = true) ||
                msg.contains("error occurred", ignoreCase = true)
            if (attempt < 2 && retryable) {
                attempt++
                Thread.sleep(1600L * attempt)
                continue
            }
            throw ScraperException("API error ($code): ${msg.ifBlank { "Unknown error" }}")
        }
    }

    private fun noVideosMessage(base: String): String =
        base + (lastApiError?.let { " ($it)" } ?: "")

    override suspend fun getForYouFeed(
        cursor: String?,
        forceRefresh: Boolean
    ): Result<FeedPage> = withContext(Dispatchers.IO) {
        val cacheKey = "foryou_${cursor ?: "0"}"
        if (!forceRefresh) {
            readCachedFeed(cacheKey)?.let { page ->
                indexVideos(page.videos)
                return@withContext Result.success(page)
            }
        }

        lastApiError = null
        runCatching {
            // 1) Primary: Direct TikTok Explore API (fast, no third-party rate limit)
            val exploreResult = fetchTikTokExploreApi(categoryType = 120, cursor = cursor)
            if (exploreResult != null && exploreResult.videos.isNotEmpty()) {
                indexVideos(exploreResult.videos)
                saveCachedFeed(cacheKey, exploreResult)
                return@runCatching exploreResult
            }

            // 2) Secondary: Public mirror trending feed (multiple regions)
            val apiResult = fetchTrendingFeedApi(cursor)
            if (apiResult != null && apiResult.videos.isNotEmpty()) {
                indexVideos(apiResult.videos)
                saveCachedFeed(cacheKey, apiResult)
                return@runCatching apiResult
            }

            // 3) Tertiary: Mirror search for #fyp + Discover page HTML
            val fypSearch = fetchSearchFeedApi("fyp", cursor)
            if (fypSearch != null && fypSearch.videos.isNotEmpty()) {
                indexVideos(fypSearch.videos)
                saveCachedFeed(cacheKey, fypSearch)
                return@runCatching fypSearch
            }

            val discoverPage = runCatching {
                fetchAndParseHtmlFeed(ScraperConfig.TIKTOK_DISCOVER_URL_TEMPLATE.format("fyp"))
            }.onFailure { if (lastApiError == null) lastApiError = it.message }.getOrNull()
            if (discoverPage != null && discoverPage.videos.isNotEmpty()) {
                indexVideos(discoverPage.videos)
                saveCachedFeed(cacheKey, discoverPage)
                return@runCatching discoverPage
            }

            throw ScraperException(noVideosMessage("No videos returned from feed endpoints."))
        }.recoverCatching { throwable ->
            readCachedFeed(cacheKey, allowExpired = true)?.let {
                indexVideos(it.videos)
                return@recoverCatching it
            }
            throw (throwable as? ScraperException)
                ?: ScraperException(throwable.message ?: "Failed to load For You feed", throwable)
        }
    }

    override suspend fun getCategoryFeed(
        category: Category,
        cursor: String?,
        forceRefresh: Boolean
    ): Result<FeedPage> = withContext(Dispatchers.IO) {
        if (category.id == "for_you") {
            return@withContext getForYouFeed(cursor, forceRefresh)
        }
        val cacheKey = "cat_${category.id}_${cursor ?: "0"}"
        if (!forceRefresh) {
            readCachedFeed(cacheKey)?.let { page ->
                indexVideos(page.videos)
                return@withContext Result.success(page)
            }
        }

        lastApiError = null
        runCatching {
            // 1) Primary: Direct TikTok Explore API mapped to this category's type ID
            val categoryType = ScraperConfig.EXPLORE_CATEGORY_TYPE_MAP[category.id]
            if (categoryType != null) {
                val exploreResult = fetchTikTokExploreApi(categoryType = categoryType, cursor = cursor)
                if (exploreResult != null && exploreResult.videos.isNotEmpty()) {
                    indexVideos(exploreResult.videos)
                    saveCachedFeed(cacheKey, exploreResult)
                    return@runCatching exploreResult
                }
            }

            // 2) Secondary: Mirror search API for category hashtag
            val apiResult = fetchSearchFeedApi(category.hashtag, cursor)
            if (apiResult != null && apiResult.videos.isNotEmpty()) {
                indexVideos(apiResult.videos)
                saveCachedFeed(cacheKey, apiResult)
                return@runCatching apiResult
            }

            // 3) Tertiary: TikTok Discover page (<script id="api-data">)
            val discoverUrl = ScraperConfig.TIKTOK_DISCOVER_URL_TEMPLATE.format(
                urlEncode(category.hashtag)
            )
            val discoverPage = runCatching { fetchAndParseHtmlFeed(discoverUrl) }
                .onFailure { if (lastApiError == null) lastApiError = it.message }
                .getOrNull()
            if (discoverPage != null && discoverPage.videos.isNotEmpty()) {
                indexVideos(discoverPage.videos)
                saveCachedFeed(cacheKey, discoverPage)
                return@runCatching discoverPage
            }

            // 4) Fallback: Tag page HTML
            val tagUrl = ScraperConfig.TIKTOK_TAG_URL_TEMPLATE.format(category.hashtag)
            val htmlPage = runCatching { fetchAndParseHtmlFeed(tagUrl) }
                .onFailure { if (lastApiError == null) lastApiError = it.message }
                .getOrNull()
            if (htmlPage != null && htmlPage.videos.isNotEmpty()) {
                indexVideos(htmlPage.videos)
                saveCachedFeed(cacheKey, htmlPage)
                return@runCatching htmlPage
            }

            throw ScraperException(noVideosMessage("Could not load videos for ${category.label}."))
        }.recoverCatching { throwable ->
            readCachedFeed(cacheKey, allowExpired = true)?.let {
                indexVideos(it.videos)
                return@recoverCatching it
            }
            throw (throwable as? ScraperException)
                ?: ScraperException(throwable.message ?: "Failed to load category", throwable)
        }
    }

    override suspend fun searchVideos(
        query: String,
        cursor: String?
    ): Result<FeedPage> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        val cleanQuery = trimmed.removePrefix("#").removePrefix("@").trim()
        if (cleanQuery.isBlank()) {
            return@withContext Result.success(FeedPage(emptyList(), null, false))
        }
        val cacheKey = "search_${trimmed.lowercase()}_${cursor ?: "0"}"
        readCachedFeed(cacheKey)?.let { page ->
            indexVideos(page.videos)
            return@withContext Result.success(page)
        }

        lastApiError = null
        runCatching {
            // 1) If user explicitly searched @username, check profile posts first
            if (trimmed.startsWith("@")) {
                val profile = runCatching {
                    fetchUserProfileInternal(cleanQuery, cursor)
                }.getOrNull()
                if (profile != null && profile.videos.isNotEmpty()) {
                    val page = FeedPage(profile.videos, profile.nextCursor, profile.hasMore)
                    indexVideos(page.videos)
                    saveCachedFeed(cacheKey, page)
                    return@runCatching page
                }
            }

            // 2) Primary Search: Mirror search API (POST then GET fallback)
            val apiResult = fetchSearchFeedApi(cleanQuery, cursor)
            if (apiResult != null && apiResult.videos.isNotEmpty()) {
                indexVideos(apiResult.videos)
                saveCachedFeed(cacheKey, apiResult)
                return@runCatching apiResult
            }

            // 3) Secondary Search: TikTok Discover page (<script id="api-data"> videoListData)
            val slug = urlEncode(cleanQuery.lowercase().replace(Regex("\\s+"), "-"))
            val discoverUrl = ScraperConfig.TIKTOK_DISCOVER_URL_TEMPLATE.format(slug)
            val discoverPage = runCatching { fetchAndParseHtmlFeed(discoverUrl) }
                .onFailure { if (lastApiError == null) lastApiError = it.message }
                .getOrNull()
            if (discoverPage != null && discoverPage.videos.isNotEmpty()) {
                indexVideos(discoverPage.videos)
                saveCachedFeed(cacheKey, discoverPage)
                return@runCatching discoverPage
            }

            // Also try compact hashtag slug on Discover & Tag pages
            val compactSlug = urlEncode(cleanQuery.replace(" ", ""))
            if (!compactSlug.equals(slug, ignoreCase = true)) {
                val altDiscover = runCatching {
                    fetchAndParseHtmlFeed(ScraperConfig.TIKTOK_DISCOVER_URL_TEMPLATE.format(compactSlug))
                }.getOrNull()
                if (altDiscover != null && altDiscover.videos.isNotEmpty()) {
                    indexVideos(altDiscover.videos)
                    saveCachedFeed(cacheKey, altDiscover)
                    return@runCatching altDiscover
                }
            }

            val tagUrl = ScraperConfig.TIKTOK_TAG_URL_TEMPLATE.format(compactSlug)
            val tagPage = runCatching { fetchAndParseHtmlFeed(tagUrl) }
                .onFailure { if (lastApiError == null) lastApiError = it.message }
                .getOrNull()
            if (tagPage != null && tagPage.videos.isNotEmpty()) {
                indexVideos(tagPage.videos)
                saveCachedFeed(cacheKey, tagPage)
                return@runCatching tagPage
            }

            // 4) Topic/Trending-chip fallback: map known topics & trending chips to TikTok Explore API
            val matchedCategoryType = resolveCategoryTypeForQuery(cleanQuery)
            if (matchedCategoryType != null) {
                val topicPage = fetchTikTokExploreApi(matchedCategoryType, cursor)
                if (topicPage != null && topicPage.videos.isNotEmpty()) {
                    indexVideos(topicPage.videos)
                    saveCachedFeed(cacheKey, topicPage)
                    return@runCatching topicPage
                }
            }

            // 5) Check if query matches a creator handle / profile
            if (!cleanQuery.contains(" ")) {
                val profileFallback = runCatching {
                    fetchUserProfileInternal(cleanQuery, cursor)
                }.getOrNull()
                if (profileFallback != null && profileFallback.videos.isNotEmpty()) {
                    val page = FeedPage(profileFallback.videos, profileFallback.nextCursor, profileFallback.hasMore)
                    indexVideos(page.videos)
                    saveCachedFeed(cacheKey, page)
                    return@runCatching page
                }
            }

            // 6) Local index keyword match from loaded feeds
            val localMatches = findMatchingIndexedVideos(cleanQuery)
            if (localMatches.isNotEmpty()) {
                return@runCatching FeedPage(localMatches, null, false)
            }

            if (lastApiError != null) {
                throw ScraperException("Search unavailable right now ($lastApiError)")
            }

            FeedPage(emptyList(), null, false)
        }.recoverCatching { throwable ->
            readCachedFeed(cacheKey, allowExpired = true)?.let { return@recoverCatching it }
            throw (throwable as? ScraperException)
                ?: ScraperException(throwable.message ?: "Search failed", throwable)
        }
    }

    override suspend fun getUserProfile(
        username: String,
        cursor: String?,
        forceRefresh: Boolean
    ): Result<UserProfileResult> = withContext(Dispatchers.IO) {
        val cleanUser = username.trim().removePrefix("@")
        val cacheKey = "profile_${cleanUser.lowercase()}_${cursor ?: "0"}"
        if (!forceRefresh) {
            readCachedProfile(cacheKey)?.let { profile ->
                indexVideos(profile.videos)
                return@withContext Result.success(profile)
            }
        }

        lastApiError = null
        runCatching {
            val profile = fetchUserProfileInternal(cleanUser, cursor)
            if (profile != null) {
                indexVideos(profile.videos)
                saveCachedProfile(cacheKey, profile)
                return@runCatching profile
            }

            throw ScraperException(noVideosMessage("Unable to load profile @$cleanUser."))
        }.recoverCatching { throwable ->
            readCachedProfile(cacheKey, allowExpired = true)?.let {
                indexVideos(it.videos)
                return@recoverCatching it
            }
            throw (throwable as? ScraperException)
                ?: ScraperException(throwable.message ?: "Failed to load profile", throwable)
        }
    }

    override suspend fun getComments(
        videoId: String,
        cursor: String?
    ): Result<List<Comment>> = withContext(Dispatchers.IO) {
        val cacheKey = "comments_${videoId}_${cursor ?: "0"}"
        readCachedComments(cacheKey)?.let { return@withContext Result.success(it) }

        runCatching {
            // 1) Primary: Direct TikTok comments endpoint
            val directComments = fetchTikTokCommentsApi(videoId, cursor)
            if (directComments != null) {
                saveCachedComments(cacheKey, directComments)
                return@runCatching directComments
            }

            // 2) Secondary: Mirror comments endpoint
            val url = ScraperConfig.API_COMMENT_LIST.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("url", "${ScraperConfig.TIKTOK_WEB_BASE}/video/$videoId")
                ?.addQueryParameter("count", "20")
                ?.addQueryParameter("cursor", cursor ?: "0")
                ?.build()
                ?: throw ScraperException("Invalid comments endpoint URL")

            val root = apiGetJson(url.toString())
            val dataObj = root.optJSONObject("data")
            val commentsArray = dataObj?.optJSONArray("comments") ?: JSONArray()
            val parsed = parseApiComments(commentsArray)
            saveCachedComments(cacheKey, parsed)
            parsed
        }.recoverCatching { throwable ->
            readCachedComments(cacheKey, allowExpired = true)?.let { return@recoverCatching it }
            throw (throwable as? ScraperException)
                ?: ScraperException(throwable.message ?: "Failed to load comments", throwable)
        }
    }

    override suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val before = getCacheSizeBytes()
        memoryCache.clear()
        videoIndex.clear()
        scraperCacheDir.listFiles()?.forEach { it.deleteRecursively() }
        runCatching { okHttpClient.cache?.evictAll() }
        before
    }

    override suspend fun getCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        dirSize(httpCacheDir) + dirSize(scraperCacheDir)
    }

    private fun fetchTikTokExploreApi(categoryType: Int, cursor: String?): FeedPage? {
        return runCatching {
            ensureTikTokSessionCookies()
            val url = ScraperConfig.TIKTOK_EXPLORE_API.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("aid", "1988")
                ?.addQueryParameter("count", ScraperConfig.DEFAULT_PAGE_SIZE.toString())
                ?.addQueryParameter("categoryType", categoryType.toString())
                ?.build()
                ?.toString()
                ?: return null

            var body = executeGet(
                url = url,
                acceptJson = true,
                userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
                referer = ScraperConfig.TIKTOK_EXPLORE_URL
            )
            var root = runCatching { JSONObject(body) }.getOrNull()
            var itemList = root?.optJSONArray("itemList")
            if (itemList == null || itemList.length() == 0) {
                // Retry once after refreshing cookies
                ensureTikTokSessionCookies(force = true)
                body = executeGet(
                    url = url,
                    acceptJson = true,
                    userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
                    referer = ScraperConfig.TIKTOK_EXPLORE_URL
                )
                root = runCatching { JSONObject(body) }.getOrNull()
                itemList = root?.optJSONArray("itemList")
            }

            if (itemList == null || itemList.length() == 0) return null
            val videos = parseTikTokJsonArray(itemList)
            if (videos.isEmpty()) return null
            val pageNum = (cursor?.removePrefix("explore_")?.toIntOrNull() ?: 0) + 1
            val hasMore = root?.optBoolean("hasMore", true) ?: true
            FeedPage(
                videos = videos,
                nextCursor = if (hasMore) "explore_$pageNum" else null,
                hasMore = hasMore
            )
        }.onFailure {
            if (lastApiError == null) lastApiError = it.message ?: it.javaClass.simpleName
        }.getOrNull()
    }

    private fun fetchTikTokCommentsApi(videoId: String, cursor: String?): List<Comment>? {
        return runCatching {
            ensureTikTokSessionCookies()
            val url = ScraperConfig.TIKTOK_COMMENT_API.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("aid", "1988")
                ?.addQueryParameter("aweme_id", videoId)
                ?.addQueryParameter("count", "20")
                ?.addQueryParameter("cursor", cursor ?: "0")
                ?.build()
                ?.toString()
                ?: return null

            val body = executeGet(
                url = url,
                acceptJson = true,
                userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
                referer = ScraperConfig.HEADER_REFERER_VALUE
            )
            val root = JSONObject(body)
            val commentsArray = root.optJSONArray("comments") ?: return null
            parseTikTokComments(commentsArray)
        }.getOrNull()
    }

    private fun fetchTrendingFeedApi(cursor: String?): FeedPage? {
        val regions = listOf("US", "GB", "")
        for (region in regions) {
            val result = runCatching {
                val form = FormBody.Builder()
                    .apply { if (region.isNotBlank()) add("region", region) }
                    .add("count", ScraperConfig.DEFAULT_PAGE_SIZE.toString())
                    .apply {
                        if (!cursor.isNullOrBlank() && !cursor.startsWith("explore_")) {
                            add("cursor", cursor)
                        }
                    }
                    .build()

                val root = apiPostJson(ScraperConfig.API_TRENDING_FEED, form)
                extractFeedPageFromMirrorJson(root)
            }.onFailure { lastApiError = it.message ?: it.javaClass.simpleName }.getOrNull()

            if (result != null && result.videos.isNotEmpty()) {
                return result
            }
        }
        return null
    }

    private fun fetchSearchFeedApi(keywords: String, cursor: String?): FeedPage? {
        val cleanCursor = cursor?.takeUnless { it.startsWith("explore_") } ?: "0"

        // Attempt 1: POST form
        val postResult = runCatching {
            val form = FormBody.Builder()
                .add("keywords", keywords)
                .add("count", ScraperConfig.DEFAULT_PAGE_SIZE.toString())
                .add("cursor", cleanCursor)
                .add("web", "1")
                .add("hd", "1")
                .build()
            val root = apiPostJson(ScraperConfig.API_SEARCH_FEED, form)
            extractFeedPageFromMirrorJson(root)
        }.onFailure { lastApiError = it.message ?: it.javaClass.simpleName }.getOrNull()

        if (postResult != null && postResult.videos.isNotEmpty()) {
            return postResult
        }

        // Attempt 2: GET query (without hd=1 in case hd=1 triggers mirror error)
        val getResult = runCatching {
            val url = ScraperConfig.API_SEARCH_FEED.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("keywords", keywords)
                ?.addQueryParameter("count", ScraperConfig.DEFAULT_PAGE_SIZE.toString())
                ?.addQueryParameter("cursor", cleanCursor)
                ?.build()
                ?.toString()
                ?: return null
            val root = apiGetJson(url)
            extractFeedPageFromMirrorJson(root)
        }.onFailure {
            if (lastApiError == null) lastApiError = it.message ?: it.javaClass.simpleName
        }.getOrNull()

        return getResult?.takeIf { it.videos.isNotEmpty() }
    }

    private fun extractFeedPageFromMirrorJson(root: JSONObject): FeedPage {
        val data = root.opt("data")
        val list = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("videos")
                ?: data.optJSONArray("aweme_list")
                ?: data.optJSONArray("list")
                ?: JSONArray()
            else -> JSONArray()
        }
        val videos = parseApiVideoArray(list)
        val nextCursor = if (data is JSONObject) {
            data.optCleanString("cursor").takeIf { it.isNotBlank() && it != "0" }
        } else {
            videos.lastOrNull()?.id?.let { "${System.currentTimeMillis() / 1000}" }
        }
        val hasMore = if (data is JSONObject) {
            data.optBoolean("hasMore", videos.isNotEmpty())
        } else {
            videos.isNotEmpty()
        }
        return FeedPage(videos = videos, nextCursor = nextCursor, hasMore = hasMore)
    }

    private fun fetchUserProfileInternal(username: String, cursor: String?): UserProfileResult? {
        // 1) Try TikTok web profile directly with Mobile UA (returns full userInfo + stats)
        val htmlProfile = runCatching {
            val url = ScraperConfig.TIKTOK_PROFILE_URL_TEMPLATE.format(urlEncode(username))
            fetchAndParseHtmlProfile(url, username)
        }.onFailure {
            if (lastApiError == null) lastApiError = it.message
        }.getOrNull()

        // 2) Try mirror user/info independently (in case TikTok web page didn't return stats)
        var mirrorAuthor: Author? = null
        if (htmlProfile == null) {
            mirrorAuthor = runCatching {
                val infoUrl = ScraperConfig.API_USER_INFO.toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("unique_id", username)
                    ?.build()
                    ?.toString()
                    ?: return@runCatching null

                val infoJson = apiGetJson(infoUrl)
                val infoData = infoJson.optJSONObject("data")
                val userObj = infoData?.optJSONObject("user") ?: return@runCatching null
                val statsObj = infoData.optJSONObject("stats")
                buildAuthorFromUserJson(userObj, statsObj, username)
            }.onFailure {
                if (lastApiError == null) lastApiError = it.message
            }.getOrNull()
        }

        // 3) Try mirror user/posts independently (POST then GET fallback)
        var postsVideos: List<VideoItem> = emptyList()
        var postsNextCursor: String? = null
        var postsHasMore = false

        val postsObj = runCatching {
            val postsForm = FormBody.Builder()
                .add("unique_id", username)
                .add("count", "30")
                .add("cursor", cursor ?: "0")
                .build()
            apiPostJson(ScraperConfig.API_USER_POSTS, postsForm)
        }.recoverCatching {
            val postsUrl = ScraperConfig.API_USER_POSTS.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("unique_id", username)
                ?.addQueryParameter("count", "30")
                ?.addQueryParameter("cursor", cursor ?: "0")
                ?.build()
                ?.toString()
                ?: throw it
            apiGetJson(postsUrl)
        }.onFailure {
            if (lastApiError == null) lastApiError = it.message
        }.getOrNull()

        if (postsObj != null) {
            val postsData = postsObj.opt("data")
            val videoArray = when (postsData) {
                is JSONObject -> postsData.optJSONArray("videos")
                    ?: postsData.optJSONArray("aweme_list")
                    ?: JSONArray()
                is JSONArray -> postsData
                else -> JSONArray()
            }
            postsVideos = parseApiVideoArray(videoArray)
            if (postsData is JSONObject) {
                postsNextCursor = postsData.optCleanString("cursor")
                    .takeIf { it.isNotBlank() && it != "0" }
                postsHasMore = postsData.optBoolean("hasMore", false)
            }
        }

        // 4) If user/posts returned empty, try HTML videos, Discover page, Search API, and local index
        var resolvedVideos = postsVideos.ifEmpty { htmlProfile?.videos.orEmpty() }
        if (resolvedVideos.isEmpty()) {
            val searchMatches = runCatching {
                fetchSearchFeedApi(username, null)?.videos.orEmpty()
            }.getOrDefault(emptyList())
            val exactAuthorMatches = searchMatches.filter {
                it.author.username.equals(username, ignoreCase = true)
            }
            resolvedVideos = exactAuthorMatches.ifEmpty {
                searchMatches.filter {
                    it.author.username.contains(username, ignoreCase = true) ||
                        it.author.displayName.contains(username, ignoreCase = true)
                }
            }
        }
        if (resolvedVideos.isEmpty()) {
            val discoverMatches = runCatching {
                val url = ScraperConfig.TIKTOK_DISCOVER_URL_TEMPLATE.format(urlEncode(username))
                fetchAndParseHtmlFeed(url).videos
            }.getOrDefault(emptyList())
            val exact = discoverMatches.filter {
                it.author.username.equals(username, ignoreCase = true)
            }
            resolvedVideos = exact.ifEmpty { discoverMatches }
        }
        if (resolvedVideos.isEmpty()) {
            resolvedVideos = videoIndex.values
                .filter { it.author.username.equals(username, ignoreCase = true) }
                .toList()
        }

        val resolvedAuthor = htmlProfile?.author
            ?: mirrorAuthor
            ?: resolvedVideos.firstOrNull()?.author
            ?: videoIndex.values.firstOrNull {
                it.author.username.equals(username, ignoreCase = true)
            }?.author
            ?: return null

        val enrichedVideos = resolvedVideos.map { item ->
            if (item.author.avatarUrl.isBlank() && resolvedAuthor.avatarUrl.isNotBlank()) {
                item.copy(author = resolvedAuthor)
            } else {
                item
            }
        }

        return UserProfileResult(
            author = resolvedAuthor,
            videos = enrichedVideos,
            nextCursor = postsNextCursor,
            hasMore = postsHasMore
        )
    }

    private fun fetchAndParseHtmlFeed(url: String): FeedPage {
        val html = executeGet(
            url = url,
            acceptJson = false,
            userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
            referer = ScraperConfig.HEADER_REFERER_VALUE
        )
        val doc: Document = Jsoup.parse(html, url)

        doc.selectFirst(ScraperConfig.SELECTOR_API_DATA_SCRIPT)?.data()?.let { raw ->
            val items = parseApiDataScriptVideos(raw)
            if (items.isNotEmpty()) {
                return FeedPage(videos = items, nextCursor = null, hasMore = false)
            }
        }

        doc.selectFirst(ScraperConfig.SELECTOR_UNIVERSAL_DATA_SCRIPT)?.data()?.let { raw ->
            val items = parseEmbeddedJsonVideos(raw)
            if (items.isNotEmpty()) {
                return FeedPage(videos = items, nextCursor = null, hasMore = false)
            }
        }

        doc.selectFirst(ScraperConfig.SELECTOR_SIGI_STATE_SCRIPT)?.data()?.let { raw ->
            val items = parseEmbeddedJsonVideos(raw)
            if (items.isNotEmpty()) {
                return FeedPage(videos = items, nextCursor = null, hasMore = false)
            }
        }

        val ogVideo = doc.selectFirst(ScraperConfig.SELECTOR_OG_VIDEO)?.attr("content").orEmpty()
        if (ogVideo.isNotBlank() && !ogVideo.equals("null", ignoreCase = true)) {
            val ogImage = doc.selectFirst(ScraperConfig.SELECTOR_OG_IMAGE)?.attr("content").orEmpty()
            val ogDesc = doc.selectFirst(ScraperConfig.SELECTOR_OG_DESCRIPTION)?.attr("content")
                ?: doc.selectFirst(ScraperConfig.SELECTOR_OG_TITLE)?.attr("content").orEmpty()
            val item = VideoItem(
                id = md5(ogVideo),
                videoUrl = ogVideo,
                coverUrl = ogImage,
                caption = ogDesc,
                hashtags = extractHashtags(ogDesc),
                author = Author(
                    id = "tiktok",
                    username = "tiktok",
                    displayName = "TikTok",
                    avatarUrl = ogImage
                ),
                music = MusicInfo(title = "Original Sound"),
                stats = VideoStats(),
                shareUrl = url
            )
            return FeedPage(videos = listOf(item), nextCursor = null, hasMore = false)
        }

        return FeedPage(videos = emptyList(), nextCursor = null, hasMore = false)
    }

    private fun fetchAndParseHtmlProfile(url: String, username: String): UserProfileResult? {
        val html = executeGet(
            url = url,
            acceptJson = false,
            userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
            referer = ScraperConfig.HEADER_REFERER_VALUE
        )
        val doc: Document = Jsoup.parse(html, url)

        val apiDataVideos = doc.selectFirst(ScraperConfig.SELECTOR_API_DATA_SCRIPT)?.data()?.let { raw ->
            parseApiDataScriptVideos(raw)
        }.orEmpty()

        doc.selectFirst(ScraperConfig.SELECTOR_UNIVERSAL_DATA_SCRIPT)?.data()?.let { raw ->
            val root = runCatching { JSONObject(raw) }.getOrNull()
            val defaultScope = root?.optJSONObject(ScraperConfig.JSON_KEY_DEFAULT_SCOPE)
            val userDetail = defaultScope?.optJSONObject(ScraperConfig.JSON_KEY_WEBAPP_USER_DETAIL)
            val userInfo = userDetail?.optJSONObject(ScraperConfig.JSON_KEY_USER_INFO)
            val userObj = userInfo?.optJSONObject("user")
            val statsObj = userInfo?.optJSONObject(ScraperConfig.JSON_KEY_STATS)
            val videos = apiDataVideos.ifEmpty { parseEmbeddedJsonVideos(raw) }

            if (userObj != null) {
                val author = buildAuthorFromUserJson(userObj, statsObj, username)
                return UserProfileResult(
                    author = author,
                    videos = videos,
                    nextCursor = null,
                    hasMore = false
                )
            }
        }

        val title = doc.selectFirst(ScraperConfig.SELECTOR_USER_TITLE)?.text().orEmpty()
        val subtitle = doc.selectFirst(ScraperConfig.SELECTOR_USER_SUBTITLE)?.text().orEmpty()
        if (title.isNotBlank() || subtitle.isNotBlank()) {
            val bio = doc.selectFirst(ScraperConfig.SELECTOR_USER_BIO)?.text().orEmpty()
            val avatar = doc.selectFirst(ScraperConfig.SELECTOR_USER_AVATAR)?.attr("src").orEmpty()
            val following = parseCompactCount(doc.selectFirst(ScraperConfig.SELECTOR_FOLLOWING_COUNT)?.text())
            val followers = parseCompactCount(doc.selectFirst(ScraperConfig.SELECTOR_FOLLOWERS_COUNT)?.text())
            val likes = parseCompactCount(doc.selectFirst(ScraperConfig.SELECTOR_LIKES_COUNT)?.text())
            val videos = apiDataVideos.ifEmpty {
                parseEmbeddedJsonVideos(
                    doc.selectFirst(ScraperConfig.SELECTOR_SIGI_STATE_SCRIPT)?.data().orEmpty()
                )
            }
            return UserProfileResult(
                author = Author(
                    id = username,
                    username = title.ifBlank { username },
                    displayName = subtitle.ifBlank { username },
                    avatarUrl = avatar,
                    bio = bio,
                    followingCount = following,
                    followersCount = followers,
                    likesCount = likes
                ),
                videos = videos,
                nextCursor = null,
                hasMore = false
            )
        }

        return null
    }

    private fun buildAuthorFromUserJson(
        userObj: JSONObject,
        statsObj: JSONObject?,
        fallbackUsername: String
    ): Author {
        val uname = userObj.optCleanString("uniqueId")
            .ifBlank { userObj.optCleanString("unique_id") }
            .ifBlank { fallbackUsername }
        return Author(
            id = userObj.optCleanString("id").ifBlank { uname },
            username = uname,
            displayName = userObj.optCleanString("nickname").ifBlank { uname },
            avatarUrl = normalizeUrl(
                userObj.optCleanString("avatarLarger")
                    .ifBlank { userObj.optCleanString("avatarMedium") }
                    .ifBlank { userObj.optCleanString("avatarThumb") }
            ),
            bio = userObj.optCleanString("signature"),
            verified = userObj.optBoolean("verified", false),
            followingCount = statsObj?.optLong("followingCount", 0L) ?: 0L,
            followersCount = statsObj?.optLong("followerCount", 0L) ?: 0L,
            likesCount = (statsObj?.optLong("heartCount", 0L) ?: 0L).takeIf { it > 0L }
                ?: statsObj?.optLong("heart", 0L) ?: 0L
        )
    }

    private fun parseApiDataScriptVideos(rawJson: String): List<VideoItem> {
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(rawJson)
            val videoList = root.optJSONObject("videoListData")?.optJSONArray("videoList")
                ?: root.optJSONArray("videoList")
                ?: root.optJSONArray("itemList")
                ?: return emptyList()
            parseTikTokJsonArray(videoList)
        }.getOrDefault(emptyList())
    }

    private fun parseTikTokJsonArray(array: JSONArray): List<VideoItem> {
        val list = ArrayList<VideoItem>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            parseSingleTikTokItem(obj, fallbackId = "tt_$i")?.let { list.add(it) }
        }
        return list
    }

    private fun parseSingleTikTokItem(obj: JSONObject, fallbackId: String): VideoItem? {
        val id = obj.optCleanString("id").ifBlank { fallbackId }
        val videoObj = obj.optJSONObject("video") ?: return null

        // Prefer H.264 URLs from bitrateInfo so Android TV hardware decoders never output black frames
        val h264Urls = mutableListOf<String>()
        val otherBitrateUrls = mutableListOf<String>()
        val bitrateInfo = videoObj.optJSONArray("bitrateInfo")
        if (bitrateInfo != null) {
            for (b in 0 until bitrateInfo.length()) {
                val entry = bitrateInfo.optJSONObject(b) ?: continue
                val codec = entry.optCleanString("CodecType").lowercase()
                val urlList = entry.optJSONObject("PlayAddr")?.optJSONArray("UrlList")
                val firstUrl = urlList?.optString(0)?.takeIf {
                    it.isNotBlank() && !it.equals("null", ignoreCase = true)
                }.orEmpty()
                if (firstUrl.isNotBlank()) {
                    if (codec.contains("h264") || codec.contains("avc")) {
                        h264Urls.add(firstUrl)
                    } else {
                        otherBitrateUrls.add(firstUrl)
                    }
                }
            }
        }

        val playAddr = normalizeUrl(videoObj.optCleanString("playAddr"))
        val downloadAddr = normalizeUrl(videoObj.optCleanString("downloadAddr"))
        val primaryUrl = h264Urls.firstOrNull()
            ?: playAddr.ifBlank { downloadAddr }.ifBlank { otherBitrateUrls.firstOrNull().orEmpty() }
        if (primaryUrl.isBlank()) return null

        val hdUrl = h264Urls.firstOrNull() ?: playAddr.ifBlank { primaryUrl }
        val sdUrl = h264Urls.lastOrNull() ?: downloadAddr.ifBlank { playAddr }.ifBlank { primaryUrl }

        val authorObj = obj.optJSONObject("author")
        val authorStatsObj = obj.optJSONObject("authorStats")
        val username = authorObj?.optCleanString("uniqueId").orEmpty().ifBlank { "creator" }
        val displayName = authorObj?.optCleanString("nickname").orEmpty().ifBlank { username }
        val avatar = normalizeUrl(
            authorObj?.optCleanString("avatarLarger").orEmpty()
                .ifBlank { authorObj?.optCleanString("avatarMedium").orEmpty() }
                .ifBlank { authorObj?.optCleanString("avatarThumb").orEmpty() }
        )

        val musicObj = obj.optJSONObject("music")
        val statsObj = obj.optJSONObject("stats")
        val desc = obj.optCleanString("desc")
        val cleanCaption = stripTrailingHashtags(desc).ifBlank { desc }

        return VideoItem(
            id = id,
            videoUrl = primaryUrl,
            hdVideoUrl = hdUrl,
            sdVideoUrl = sdUrl,
            coverUrl = normalizeUrl(
                videoObj.optCleanString("originCover")
                    .ifBlank { videoObj.optCleanString("cover") }
                    .ifBlank { videoObj.optCleanString("dynamicCover") }
            ),
            caption = cleanCaption,
            hashtags = extractHashtags(desc),
            author = Author(
                id = authorObj?.optCleanString("id").orEmpty().ifBlank { username },
                username = username,
                displayName = displayName,
                avatarUrl = avatar,
                bio = authorObj?.optCleanString("signature").orEmpty(),
                verified = authorObj?.optBoolean("verified", false) ?: false,
                followingCount = authorStatsObj?.optLong("followingCount", 0L) ?: 0L,
                followersCount = authorStatsObj?.optLong("followerCount", 0L) ?: 0L,
                likesCount = authorStatsObj?.optLong("heartCount", 0L)
                    ?.takeIf { it > 0L }
                    ?: authorStatsObj?.optLong("heart", 0L) ?: 0L
            ),
            music = MusicInfo(
                id = musicObj?.optCleanString("id").orEmpty(),
                title = musicObj?.optCleanString("title").orEmpty().ifBlank { "Original Sound" },
                author = musicObj?.optCleanString("authorName").orEmpty(),
                coverUrl = normalizeUrl(
                    musicObj?.optCleanString("coverLarge").orEmpty()
                        .ifBlank { musicObj?.optCleanString("coverMedium").orEmpty() }
                )
            ),
            stats = VideoStats(
                likesCount = statsObj?.optLong("diggCount", 0L) ?: 0L,
                commentsCount = statsObj?.optLong("commentCount", 0L) ?: 0L,
                sharesCount = statsObj?.optLong("shareCount", 0L) ?: 0L,
                viewsCount = statsObj?.optLong("playCount", 0L) ?: 0L
            ),
            shareUrl = ScraperConfig.TIKTOK_VIDEO_SHARE_TEMPLATE.format(username, id)
        )
    }

    private fun parseApiVideoArray(array: JSONArray): List<VideoItem> {
        val result = ArrayList<VideoItem>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optCleanString("video_id")
                .ifBlank { obj.optCleanString("aweme_id") }
                .ifBlank { obj.optCleanString("id") }
            val play = normalizeUrl(obj.optCleanString("play"))
            val hdPlay = normalizeUrl(obj.optCleanString("hdplay"))
            val wmPlay = normalizeUrl(obj.optCleanString("wmplay"))
            val playable = play.ifBlank { hdPlay }.ifBlank { wmPlay }
            if (id.isBlank() || playable.isBlank()) continue

            val authorObj = obj.optJSONObject("author")
            val username = authorObj?.optCleanString("unique_id").orEmpty()
                .ifBlank { authorObj?.optCleanString("uniqueId").orEmpty() }
                .ifBlank { "creator" }
            val displayName = authorObj?.optCleanString("nickname").orEmpty().ifBlank { username }
            val avatar = normalizeUrl(
                authorObj?.optCleanString("avatar").orEmpty()
                    .ifBlank { authorObj?.optCleanString("avatar_thumb").orEmpty() }
            )
            val authorId = authorObj?.optCleanString("id").orEmpty().ifBlank { username }

            val musicInfoObj = obj.optJSONObject("music_info")
            val musicTitle = musicInfoObj?.optCleanString("title")
                ?.ifBlank { obj.optCleanString("music") }
                .orEmpty()
                .ifBlank { "Original Sound - $displayName" }
            val musicAuthor = musicInfoObj?.optCleanString("author").orEmpty().ifBlank { displayName }
            val musicCover = normalizeUrl(musicInfoObj?.optCleanString("cover").orEmpty())

            val rawCaption = obj.optCleanString("title")
                .ifBlank { obj.optCleanString("desc") }
            val hashtags = extractHashtags(rawCaption)
            val cleanCaption = stripTrailingHashtags(rawCaption)

            result.add(
                VideoItem(
                    id = id,
                    videoUrl = playable,
                    hdVideoUrl = hdPlay.ifBlank { playable },
                    sdVideoUrl = wmPlay.ifBlank { playable },
                    coverUrl = normalizeUrl(
                        obj.optCleanString("origin_cover")
                            .ifBlank { obj.optCleanString("cover") }
                            .ifBlank { obj.optCleanString("ai_dynamic_cover") }
                    ),
                    caption = cleanCaption.ifBlank { rawCaption },
                    hashtags = hashtags,
                    author = Author(
                        id = authorId,
                        username = username,
                        displayName = displayName,
                        avatarUrl = avatar,
                        verified = authorObj?.optInt("verified", 0) == 1 ||
                            authorObj?.optBoolean("verified", false) == true
                    ),
                    music = MusicInfo(
                        id = musicInfoObj?.optCleanString("id").orEmpty(),
                        title = musicTitle,
                        author = musicAuthor,
                        coverUrl = musicCover
                    ),
                    stats = VideoStats(
                        likesCount = obj.optLong("digg_count", 0L),
                        commentsCount = obj.optLong("comment_count", 0L),
                        sharesCount = obj.optLong("share_count", 0L),
                        viewsCount = obj.optLong("play_count", 0L)
                    ),
                    shareUrl = ScraperConfig.TIKTOK_VIDEO_SHARE_TEMPLATE.format(username, id)
                )
            )
        }
        return result
    }

    private fun parseEmbeddedJsonVideos(rawJson: String): List<VideoItem> {
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(rawJson)
            val itemModule = root.optJSONObject(ScraperConfig.JSON_KEY_ITEM_MODULE)
                ?: root.optJSONObject(ScraperConfig.JSON_KEY_DEFAULT_SCOPE)
                    ?.optJSONObject(ScraperConfig.JSON_KEY_WEBAPP_EXPLORE)
                    ?.optJSONObject("itemList")

            if (itemModule != null) {
                val userModule = root.optJSONObject(ScraperConfig.JSON_KEY_USER_MODULE)
                    ?.optJSONObject(ScraperConfig.JSON_KEY_USERS)

                val list = mutableListOf<VideoItem>()
                val keys = itemModule.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = itemModule.optJSONObject(key) ?: continue
                    val id = obj.optCleanString("id").ifBlank { key }
                    val videoObj = obj.optJSONObject("video") ?: continue
                    val playAddr = normalizeUrl(
                        videoObj.optCleanString("playAddr")
                            .ifBlank { videoObj.optCleanString("downloadAddr") }
                    )
                    if (playAddr.isBlank()) continue

                    val authorKey = obj.optCleanString("author")
                    val authorObj = obj.optJSONObject("author")
                        ?: userModule?.optJSONObject(authorKey)
                    val username = authorObj?.optCleanString("uniqueId").orEmpty()
                        .ifBlank { authorKey.ifBlank { "creator" } }
                    val displayName = authorObj?.optCleanString("nickname").orEmpty().ifBlank { username }
                    val avatar = normalizeUrl(
                        authorObj?.optCleanString("avatarLarger").orEmpty()
                            .ifBlank { authorObj?.optCleanString("avatarMedium").orEmpty() }
                    )

                    val musicObj = obj.optJSONObject("music")
                    val statsObj = obj.optJSONObject("stats")
                    val desc = obj.optCleanString("desc")

                    list.add(
                        VideoItem(
                            id = id,
                            videoUrl = playAddr,
                            hdVideoUrl = playAddr,
                            sdVideoUrl = playAddr,
                            coverUrl = normalizeUrl(
                                videoObj.optCleanString("originCover")
                                    .ifBlank { videoObj.optCleanString("cover") }
                            ),
                            caption = stripTrailingHashtags(desc).ifBlank { desc },
                            hashtags = extractHashtags(desc),
                            author = Author(
                                id = authorObj?.optCleanString("id").orEmpty().ifBlank { username },
                                username = username,
                                displayName = displayName,
                                avatarUrl = avatar,
                                bio = authorObj?.optCleanString("signature").orEmpty(),
                                verified = authorObj?.optBoolean("verified", false) ?: false
                            ),
                            music = MusicInfo(
                                id = musicObj?.optCleanString("id").orEmpty(),
                                title = musicObj?.optCleanString("title").orEmpty().ifBlank { "Original Sound" },
                                author = musicObj?.optCleanString("authorName").orEmpty(),
                                coverUrl = normalizeUrl(musicObj?.optCleanString("coverLarge").orEmpty())
                            ),
                            stats = VideoStats(
                                likesCount = statsObj?.optLong("diggCount", 0L) ?: 0L,
                                commentsCount = statsObj?.optLong("commentCount", 0L) ?: 0L,
                                sharesCount = statsObj?.optLong("shareCount", 0L) ?: 0L,
                                viewsCount = statsObj?.optLong("playCount", 0L) ?: 0L
                            ),
                            shareUrl = ScraperConfig.TIKTOK_VIDEO_SHARE_TEMPLATE.format(username, id)
                        )
                    )
                }
                return@runCatching list
            }

            // Also check JSONArray itemList if present inside __DEFAULT_SCOPE__
            val defaultScope = root.optJSONObject(ScraperConfig.JSON_KEY_DEFAULT_SCOPE)
            val exploreItems = defaultScope?.optJSONObject(ScraperConfig.JSON_KEY_WEBAPP_EXPLORE)
                ?.optJSONArray("itemList")
            if (exploreItems != null) {
                return@runCatching parseTikTokJsonArray(exploreItems)
            }

            emptyList()
        }.getOrDefault(emptyList())
    }

    private fun parseTikTokComments(array: JSONArray): List<Comment> {
        val list = ArrayList<Comment>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val userObj = obj.optJSONObject("user")
            val text = obj.optCleanString("text")
            if (text.isBlank()) continue
            val avatarThumb = userObj?.optJSONObject("avatar_thumb")
                ?.optJSONArray("url_list")
                ?.optString(0)
                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                .orEmpty()
            val uniqueId = userObj?.optCleanString("unique_id").orEmpty().ifBlank { "user" }
            val nickname = userObj?.optCleanString("nickname").orEmpty().ifBlank { uniqueId }
            list.add(
                Comment(
                    id = obj.optCleanString("cid").ifBlank { "$i-${text.hashCode()}" },
                    authorName = nickname,
                    authorUsername = uniqueId,
                    authorAvatarUrl = normalizeUrl(avatarThumb),
                    text = text,
                    likesCount = obj.optLong("digg_count", 0L),
                    createdAtText = ""
                )
            )
        }
        return list
    }

    private fun parseApiComments(array: JSONArray): List<Comment> {
        val list = ArrayList<Comment>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val userObj = obj.optJSONObject("user")
            val text = obj.optCleanString("text")
            if (text.isBlank()) continue
            list.add(
                Comment(
                    id = obj.optCleanString("id").ifBlank { "$i-${text.hashCode()}" },
                    authorName = userObj?.optCleanString("nickname").orEmpty()
                        .ifBlank { userObj?.optCleanString("unique_id").orEmpty().ifBlank { "User" } },
                    authorUsername = userObj?.optCleanString("unique_id").orEmpty().ifBlank { "user" },
                    authorAvatarUrl = normalizeUrl(userObj?.optCleanString("avatar").orEmpty()),
                    text = text,
                    likesCount = obj.optLong("digg_count", 0L),
                    createdAtText = obj.optCleanString("create_time_str")
                )
            )
        }
        return list
    }

    private fun executeGet(
        url: String,
        acceptJson: Boolean,
        userAgent: String = ScraperConfig.HEADER_USER_AGENT_VALUE,
        referer: String = ScraperConfig.HEADER_REFERER_VALUE
    ): String {
        if (url.startsWith(ScraperConfig.PUBLIC_API_BASE)) throttleApi()
        val request = Request.Builder()
            .url(url)
            .header(ScraperConfig.HEADER_USER_AGENT_KEY, userAgent)
            .header(ScraperConfig.HEADER_ACCEPT_LANGUAGE_KEY, ScraperConfig.HEADER_ACCEPT_LANGUAGE_VALUE)
            .header(ScraperConfig.HEADER_REFERER_KEY, referer)
            .header(
                ScraperConfig.HEADER_ACCEPT_KEY,
                if (acceptJson) ScraperConfig.HEADER_ACCEPT_JSON else ScraperConfig.HEADER_ACCEPT_HTML
            )
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ScraperException("HTTP ${response.code} fetching $url")
            }
            if (body.isBlank()) {
                throw ScraperException("Empty response from $url")
            }
            return body
        }
    }

    private fun executePost(url: String, formBody: FormBody): String {
        if (url.startsWith(ScraperConfig.PUBLIC_API_BASE)) throttleApi()
        val origin = ScraperConfig.PUBLIC_API_BASE.removeSuffix("/api")
        val request = Request.Builder()
            .url(url)
            .header(ScraperConfig.HEADER_USER_AGENT_KEY, ScraperConfig.HEADER_USER_AGENT_VALUE)
            .header(ScraperConfig.HEADER_ACCEPT_LANGUAGE_KEY, ScraperConfig.HEADER_ACCEPT_LANGUAGE_VALUE)
            .header(ScraperConfig.HEADER_REFERER_KEY, "$origin/")
            .header("Origin", origin)
            .header(ScraperConfig.HEADER_ACCEPT_KEY, ScraperConfig.HEADER_ACCEPT_JSON)
            .post(formBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ScraperException("HTTP ${response.code} posting to $url")
            }
            if (body.isBlank()) {
                throw ScraperException("Empty response from $url")
            }
            return body
        }
    }

    private fun indexVideos(videos: List<VideoItem>) {
        videos.forEach { videoIndex[it.id] = it }
    }

    private fun findMatchingIndexedVideos(query: String): List<VideoItem> {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return emptyList()
        return videoIndex.values.filter { video ->
            val haystack = buildString {
                append(video.caption.lowercase())
                append(' ')
                append(video.hashtags.joinToString(" ").lowercase())
                append(' ')
                append(video.author.username.lowercase())
                append(' ')
                append(video.author.displayName.lowercase())
                append(' ')
                append(video.music.displayText.lowercase())
            }
            tokens.all { haystack.contains(it) }
        }.take(20)
    }

    private fun resolveCategoryTypeForQuery(query: String): Int? {
        val q = query.lowercase().trim()
        return when {
            q.contains("comedy") || q.contains("funny") || q.contains("meme") -> 104
            q.contains("travel") || q.contains("vlog") || q.contains("daily") -> 105
            q.contains("animal") || q.contains("cat") || q.contains("dog") || q.contains("pet") -> 113
            q.contains("food") || q.contains("cook") || q.contains("recipe") -> 111
            q.contains("gam") -> 103
            q.contains("sport") || q.contains("football") || q.contains("cricket") -> 112
            q.contains("learn") || q.contains("edu") || q.contains("study") || q.contains("tech") -> 116
            q.contains("music") || q.contains("song") || q.contains("dance") || q.contains("sing") -> 119
            q.contains("art") || q.contains("anime") || q.contains("draw") -> 100
            q.contains("fyp") || q.contains("trend") || q.contains("viral") -> 120
            else -> null
        }
    }

    private fun readCachedFeed(key: String, allowExpired: Boolean = false): FeedPage? {
        return readCached(key, FeedPage::class.java, allowExpired)
    }

    private fun saveCachedFeed(key: String, page: FeedPage) {
        saveCached(key, page)
    }

    private fun readCachedProfile(key: String, allowExpired: Boolean = false): UserProfileResult? {
        return readCached(key, UserProfileResult::class.java, allowExpired)
    }

    private fun saveCachedProfile(key: String, profile: UserProfileResult) {
        saveCached(key, profile)
    }

    private fun readCachedComments(key: String, allowExpired: Boolean = false): List<Comment>? {
        val wrapper = readCached(key, CommentListWrapper::class.java, allowExpired)
        return wrapper?.comments
    }

    private fun saveCachedComments(key: String, comments: List<Comment>) {
        saveCached(key, CommentListWrapper(comments))
    }

    private fun <T> readCached(key: String, clazz: Class<T>, allowExpired: Boolean): T? {
        val now = System.currentTimeMillis()
        memoryCache[key]?.let { entry ->
            if (allowExpired || (now - entry.timestamp) < ScraperConfig.MEMORY_CACHE_TTL_MS) {
                if (clazz.isInstance(entry.data)) {
                    @Suppress("UNCHECKED_CAST")
                    return entry.data as T
                }
            }
        }
        val file = File(scraperCacheDir, "${md5(key)}.json")
        if (!file.exists()) return null
        val age = now - file.lastModified()
        if (!allowExpired && age > ScraperConfig.DISK_CACHE_TTL_MS) return null
        return runCatching {
            val parsed = gson.fromJson(file.readText(), clazz)
            if (parsed != null) {
                memoryCache[key] = CacheEntry(parsed, file.lastModified())
            }
            parsed
        }.getOrNull()
    }

    private fun saveCached(key: String, data: Any) {
        val now = System.currentTimeMillis()
        memoryCache[key] = CacheEntry(data, now)
        runCatching {
            val file = File(scraperCacheDir, "${md5(key)}.json")
            file.writeText(gson.toJson(data))
        }
    }

    private fun JSONObject.optCleanString(key: String): String {
        if (isNull(key)) return ""
        val value = optString(key, "").trim()
        return if (value.equals("null", ignoreCase = true)) "" else value
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed.equals("null", ignoreCase = true)) return ""
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "${ScraperConfig.PUBLIC_API_BASE.removeSuffix("/api")}$trimmed"
            else -> trimmed
        }
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun extractHashtags(text: String): List<String> {
        return Regex("#[\\p{L}0-9_]+")
            .findAll(text)
            .map { it.value }
            .distinct()
            .take(8)
            .toList()
    }

    private fun stripTrailingHashtags(text: String): String {
        return text.replace(Regex("#[\\p{L}0-9_]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseCompactCount(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val clean = raw.trim().uppercase()
        return runCatching {
            when {
                clean.endsWith("M") -> (clean.dropLast(1).toDouble() * 1_000_000).toLong()
                clean.endsWith("K") -> (clean.dropLast(1).toDouble() * 1_000).toLong()
                clean.endsWith("B") -> (clean.dropLast(1).toDouble() * 1_000_000_000).toLong()
                else -> clean.replace(",", "").toLong()
            }
        }.getOrDefault(0L)
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private companion object {
        const val API_MIN_INTERVAL_MS = 1500L
    }

    private data class CacheEntry(val data: Any, val timestamp: Long)
    private data class CommentListWrapper(val comments: List<Comment>)
}

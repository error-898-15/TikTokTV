package com.tiktoktv.app.data.repository

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.tiktoktv.app.data.config.ScraperConfig
import com.tiktoktv.app.data.local.LocalPrefsRepository
import com.tiktoktv.app.data.model.Author
import com.tiktoktv.app.data.model.Category
import com.tiktoktv.app.data.model.Comment
import com.tiktoktv.app.data.model.ContentRegion
import com.tiktoktv.app.data.model.FeedPage
import com.tiktoktv.app.data.model.HashtagResult
import com.tiktoktv.app.data.model.HashtagsPage
import com.tiktoktv.app.data.model.LiveStreamItem
import com.tiktoktv.app.data.model.MusicInfo
import com.tiktoktv.app.data.model.ScraperException
import com.tiktoktv.app.data.model.SearchSuggestion
import com.tiktoktv.app.data.model.SuggestionType
import com.tiktoktv.app.data.model.UnifiedSearchResult
import com.tiktoktv.app.data.model.UserProfileResult
import com.tiktoktv.app.data.model.UsersPage
import com.tiktoktv.app.data.model.VideoItem
import com.tiktoktv.app.data.model.VideoStats
import com.tiktoktv.app.data.remote.TikTokWebSessionClient
import com.tiktoktv.app.data.remote.WebSearchSessionResponse
import com.tiktoktv.app.data.remote.WebSessionPageResponse
import com.tiktoktv.app.util.formatCompactCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
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

    private val prefs = LocalPrefsRepository(context)
    private val gson = Gson()
    private val httpCacheDir = File(context.cacheDir, "http_cache").apply { mkdirs() }
    private val scraperCacheDir = File(context.cacheDir, "scraper_cache").apply { mkdirs() }
    private val memoryCache = ConcurrentHashMap<String, CacheEntry>()
    private val videoIndex = ConcurrentHashMap<String, VideoItem>()
    private val authorVideoMap = ConcurrentHashMap<String, MutableMap<String, VideoItem>>()
    private val blockedEndpointsUntil = ConcurrentHashMap<String, Long>()
    private val pendingProfileRefs = ConcurrentHashMap<String, List<Pair<String, String>>>()
    private val profileFallbackQueues = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val categoryRefPools = ConcurrentHashMap<String, List<Pair<String, String>>>()
    private val secUidByUsername = ConcurrentHashMap<String, String>()
    private val userIdByUsername = ConcurrentHashMap<String, String>()
    private val canonicalUsernameByQuery = ConcurrentHashMap<String, Set<String>>()
    private val authorHintByUsername = ConcurrentHashMap<String, Author>()

    override fun seedAuthorHint(author: Author) {
        val clean = author.username.trim().removePrefix("@")
        if (clean.isBlank()) return
        val key = clean.lowercase()
        val existing = authorHintByUsername[key]
        val merged = if (existing == null) author else Author(
            id = author.id.ifBlank { existing.id },
            username = author.username.ifBlank { existing.username },
            displayName = author.displayName.ifBlank { existing.displayName },
            avatarUrl = author.avatarUrl.ifBlank { existing.avatarUrl },
            bio = author.bio.ifBlank { existing.bio },
            verified = author.verified || existing.verified,
            secUid = author.secUid.ifBlank { existing.secUid },
            followingCount = maxOf(author.followingCount, existing.followingCount),
            followersCount = maxOf(author.followersCount, existing.followersCount),
            likesCount = maxOf(author.likesCount, existing.likesCount),
            videoCount = maxOf(author.videoCount, existing.videoCount)
        )
        authorHintByUsername[key] = merged
        if (merged.secUid.isNotBlank()) {
            secUidByUsername[key] = merged.secUid
        }
        if (merged.id.isNotBlank() && !merged.id.equals(clean, ignoreCase = true)) {
            userIdByUsername[key] = merged.id
        }
    }

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

    // Cookieless client for Googlebot SSR HTML scraping so bot responses never overwrite playback cookies
    private val googlebotHttpClient: OkHttpClient = okHttpClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    private val webSessionClient = TikTokWebSessionClient(
        context = context,
        onCookiesSynced = ::importCookieHeader
    )

    private fun importCookieHeader(rawCookieHeader: String) {
        if (rawCookieHeader.isBlank()) return
        val now = System.currentTimeMillis()
        val futureExpiry = now + 7L * 24L * 60L * 60L * 1000L
        val parsed = rawCookieHeader.split(";")
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (name.isBlank()) return@mapNotNull null
                runCatching {
                    Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain("tiktok.com")
                        .path("/")
                        .expiresAt(futureExpiry)
                        .secure()
                        .build()
                }.getOrNull()
            }
        if (parsed.isEmpty()) return
        synchronized(cookieLock) {
            cookies.removeAll { it.expiresAt < now }
            parsed.forEach { incoming ->
                cookies.removeAll {
                    it.name == incoming.name &&
                        it.domain.equals(incoming.domain, ignoreCase = true)
                }
                cookies.add(incoming)
            }
            tikTokCookiesWarmedAt = now
        }
    }

    private fun isEndpointBlocked(key: String): Boolean {
        val until = blockedEndpointsUntil[key] ?: 0L
        return System.currentTimeMillis() < until
    }

    private fun markEndpointBlocked(key: String) {
        blockedEndpointsUntil[key] =
            System.currentTimeMillis() + ScraperConfig.BLOCKED_ENDPOINT_COOLDOWN_MS
    }

    private fun isCloudflareChallenge(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true) ||
            body.contains("Just a moment...", ignoreCase = true) ||
            body.contains("cf_chl", ignoreCase = true) ||
            body.contains("challenges.cloudflare.com", ignoreCase = true)
    }

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
                referer = ScraperConfig.HEADER_REFERER_VALUE,
                bypassHttpCache = force
            )
            tikTokCookiesWarmedAt = System.currentTimeMillis()
        }
    }

    // -------------------------------------------------------------------------
    // 1. FOR YOU & GENRE/CATEGORY FEEDS
    // -------------------------------------------------------------------------

    override suspend fun getForYouFeed(
        cursor: String?,
        forceRefresh: Boolean,
        excludeIds: Set<String>
    ): Result<FeedPage> = withContext(Dispatchers.IO) {
        val region = prefs.getSettings().contentRegion
        val cursorState = cursor?.let { decodeForYouCursor(it) }
        val pageNum = cursorState?.pageNum ?: 0
        val cacheKey = "foryou_v3_${region.name}_$pageNum"

        if (!forceRefresh && cursor != null) {
            readCachedFeed(cacheKey)?.let { cached ->
                val unseenCached = cached.videos.filterNot { excludeIds.contains(it.id) }
                if (unseenCached.size >= 8) {
                    indexVideos(unseenCached)
                    return@withContext Result.success(cached.copy(videos = unseenCached))
                }
            }
        }
        lastApiError = null

        runCatching {
            val cycle = if (cursor == null) prefs.getAndIncrementFeedCycle() else pageNum
            val primaryPool = LinkedHashMap<String, VideoItem>()
            val mixPool = LinkedHashMap<String, VideoItem>()

            var nextTrendingCursor = cursorState?.trendingCursor.orEmpty()
            var nextMixCursor = cursorState?.mixCursor.orEmpty()
            var nextTagIdx = cursorState?.tagIdx ?: (cycle % maxOf(1, region.discoveryHashtags.size))

            when (region) {
                ContentRegion.BANGLADESH_MIX -> {
                    coroutineScope {
                        val bdTrendingTask = async {
                            fetchTrendingFeedApi(
                                regionCode = "BD",
                                cursor = nextTrendingCursor.ifBlank { null },
                                count = ScraperConfig.DEFAULT_PAGE_SIZE,
                                forceNetwork = forceRefresh || cursor == null
                            )
                        }
                        val bdTagTask = async {
                            val tag = region.discoveryHashtags[nextTagIdx % region.discoveryHashtags.size]
                            fetchRegionTagOrSearchVideos(
                                tag = tag,
                                offset = if (cursor == null) 0 else pageNum * 12,
                                forceNetwork = forceRefresh
                            )
                        }
                        val exploreTask = async {
                            fetchTikTokExploreApi(
                                categoryType = 120,
                                cursor = cursor,
                                region = region,
                                forceNetwork = forceRefresh
                            )
                        }
                        val mixTask = async {
                            val pool = ScraperConfig.GLOBAL_MIX_REGION_POOL
                            val mixCode = pool[(cycle + pageNum) % pool.size]
                            fetchTrendingFeedApi(
                                regionCode = mixCode,
                                cursor = nextMixCursor.ifBlank { null },
                                count = 8,
                                forceNetwork = forceRefresh || cursor == null
                            )
                        }

                        bdTrendingTask.await()?.let { page ->
                            page.videos.forEach { primaryPool[it.id] = it }
                            nextTrendingCursor = page.nextCursor.orEmpty()
                        }
                        bdTagTask.await().forEach { primaryPool.putIfAbsent(it.id, it) }
                        if (primaryPool.size < 14) {
                            exploreTask.await()?.videos?.forEach { primaryPool.putIfAbsent(it.id, it) }
                        } else {
                            exploreTask.await()?.videos?.forEach { mixPool.putIfAbsent(it.id, it) }
                        }
                        mixTask.await()?.let { page ->
                            page.videos.forEach { mixPool.putIfAbsent(it.id, it) }
                            nextMixCursor = page.nextCursor.orEmpty()
                        }
                    }

                    // If user already watched most top BD items on previous opens, pull deeper unseen BD batches
                    var extraAttempts = 0
                    var bdUnseenCount = primaryPool.values.count { !excludeIds.contains(it.id) }
                    while (bdUnseenCount < 10 && extraAttempts < 2) {
                        extraAttempts++
                        nextTagIdx = (nextTagIdx + 1) % region.discoveryHashtags.size
                        val extraTag = region.discoveryHashtags[nextTagIdx]
                        val moreTrending = if (nextTrendingCursor.isNotBlank()) {
                            fetchTrendingFeedApi(
                                regionCode = "BD",
                                cursor = nextTrendingCursor,
                                count = ScraperConfig.DEFAULT_PAGE_SIZE,
                                forceNetwork = true
                            )?.also { page ->
                                nextTrendingCursor = page.nextCursor.orEmpty()
                            }?.videos.orEmpty()
                        } else emptyList()
                        val moreTagVideos = fetchRegionTagOrSearchVideos(
                            tag = extraTag,
                            offset = extraAttempts * 15,
                            forceNetwork = forceRefresh
                        )
                        (moreTrending + moreTagVideos).forEach { primaryPool.putIfAbsent(it.id, it) }
                        bdUnseenCount = primaryPool.values.count { !excludeIds.contains(it.id) }
                    }
                }

                ContentRegion.GLOBAL_MIX -> {
                    val pool = ScraperConfig.TRENDING_REGION_POOL
                    val r1 = pool[cycle % pool.size]
                    val r2 = pool[(cycle + 3) % pool.size]
                    coroutineScope {
                        val t1 = async {
                            fetchTrendingFeedApi(
                                regionCode = r1,
                                cursor = nextTrendingCursor.ifBlank { null },
                                count = ScraperConfig.DEFAULT_PAGE_SIZE,
                                forceNetwork = forceRefresh || cursor == null
                            )
                        }
                        val t2 = async {
                            fetchTrendingFeedApi(
                                regionCode = r2,
                                cursor = nextMixCursor.ifBlank { null },
                                count = 10,
                                forceNetwork = forceRefresh || cursor == null
                            )
                        }
                        val exploreTask = async {
                            fetchTikTokExploreApi(
                                categoryType = 120,
                                cursor = cursor,
                                region = region,
                                forceNetwork = forceRefresh
                            )
                        }
                        t1.await()?.let { page ->
                            page.videos.forEach { primaryPool[it.id] = it }
                            nextTrendingCursor = page.nextCursor.orEmpty()
                        }
                        t2.await()?.let { page ->
                            page.videos.forEach { primaryPool.putIfAbsent(it.id, it) }
                            nextMixCursor = page.nextCursor.orEmpty()
                        }
                        exploreTask.await()?.videos?.forEach { primaryPool.putIfAbsent(it.id, it) }
                    }
                }

                else -> {
                    coroutineScope {
                        val regTask = async {
                            fetchTrendingFeedApi(
                                regionCode = region.code,
                                cursor = nextTrendingCursor.ifBlank { null },
                                count = ScraperConfig.DEFAULT_PAGE_SIZE,
                                forceNetwork = forceRefresh || cursor == null
                            )
                        }
                        val tagTask = async {
                            val tag = region.discoveryHashtags[nextTagIdx % region.discoveryHashtags.size]
                            fetchRegionTagOrSearchVideos(
                                tag = tag,
                                offset = if (cursor == null) 0 else pageNum * 12,
                                forceNetwork = forceRefresh
                            )
                        }
                        val exploreTask = async {
                            fetchTikTokExploreApi(
                                categoryType = 120,
                                cursor = cursor,
                                region = region,
                                forceNetwork = forceRefresh
                            )
                        }
                        regTask.await()?.let { page ->
                            page.videos.forEach { primaryPool[it.id] = it }
                            nextTrendingCursor = page.nextCursor.orEmpty()
                        }
                        tagTask.await().forEach { primaryPool.putIfAbsent(it.id, it) }
                        exploreTask.await()?.videos?.forEach { primaryPool.putIfAbsent(it.id, it) }
                    }

                    var extraAttempts = 0
                    var unseenCount = primaryPool.values.count { !excludeIds.contains(it.id) }
                    while (unseenCount < 10 && extraAttempts < 2) {
                        extraAttempts++
                        nextTagIdx = (nextTagIdx + 1) % region.discoveryHashtags.size
                        val extraTag = region.discoveryHashtags[nextTagIdx]
                        val moreTrending = if (nextTrendingCursor.isNotBlank()) {
                            fetchTrendingFeedApi(
                                regionCode = region.code,
                                cursor = nextTrendingCursor,
                                count = ScraperConfig.DEFAULT_PAGE_SIZE,
                                forceNetwork = true
                            )?.also { page ->
                                nextTrendingCursor = page.nextCursor.orEmpty()
                            }?.videos.orEmpty()
                        } else emptyList()
                        val moreTagVideos = fetchRegionTagOrSearchVideos(
                            tag = extraTag,
                            offset = extraAttempts * 15,
                            forceNetwork = forceRefresh
                        )
                        (moreTrending + moreTagVideos).forEach { primaryPool.putIfAbsent(it.id, it) }
                        unseenCount = primaryPool.values.count { !excludeIds.contains(it.id) }
                    }
                }
            }

            if (primaryPool.isEmpty() && mixPool.isEmpty()) {
                val fallbackTag = region.discoveryHashtags.firstOrNull() ?: "foryou"
                val refs = fetchTagRefsFromHtml(
                    ScraperConfig.TIKTOK_TAG_URL_TEMPLATE.format(urlEncode(fallbackTag)),
                    forceNetwork = forceRefresh
                ).take(ScraperConfig.CATEGORY_PAGE_SIZE)
                hydrateVideoRefs(refs).forEach { primaryPool[it.id] = it }
            }

            if (primaryPool.isEmpty() && mixPool.isEmpty()) {
                throw ScraperException(noVideosMessage("No videos returned from feed endpoints."))
            }

            val allCollected = (primaryPool.values + mixPool.values).distinctBy { it.id }
            indexVideos(allCollected)

            val primaryUnseen = primaryPool.values
                .filterNot { excludeIds.contains(it.id) }
                .shuffled()
            val mixUnseen = mixPool.values
                .filterNot { excludeIds.contains(it.id) || primaryPool.containsKey(it.id) }
                .shuffled()

            val orderedUnseen = if (region == ContentRegion.BANGLADESH_MIX && mixUnseen.isNotEmpty()) {
                // Mostly Bangladeshi (~75%) interleaved with Global Mix (~25%)
                interleaveWithRatio(primaryUnseen, mixUnseen, primaryPerBatch = 3)
            } else {
                (primaryUnseen + mixUnseen).distinctBy { it.id }
            }

            val ordered = if (orderedUnseen.size >= 4) {
                orderedUnseen
            } else {
                val seenFallback = allCollected.filter { excludeIds.contains(it.id) }.shuffled()
                (orderedUnseen + seenFallback).distinctBy { it.id }
            }

            val nextCursorState = ForYouCursorState(
                pageNum = pageNum + 1,
                trendingCursor = nextTrendingCursor,
                mixCursor = nextMixCursor,
                tagIdx = (nextTagIdx + 1) % maxOf(1, region.discoveryHashtags.size)
            )
            val page = FeedPage(
                videos = ordered,
                nextCursor = encodeForYouCursor(nextCursorState),
                hasMore = true
            )
            saveCachedFeed(cacheKey, page)
            page
        }.recoverCatching { throwable ->
            readCachedFeed(cacheKey, allowExpired = true)?.let { cached ->
                val unseen = cached.videos.filterNot { excludeIds.contains(it.id) }.shuffled()
                val seen = cached.videos.filter { excludeIds.contains(it.id) }.shuffled()
                val ordered = (unseen + seen).distinctBy { it.id }
                indexVideos(ordered)
                return@recoverCatching cached.copy(videos = ordered)
            }
            throw (throwable as? ScraperException)
                ?: ScraperException(throwable.message ?: "Failed to load For You feed", throwable)
        }
    }

    override suspend fun getCategoryFeed(
        category: Category,
        cursor: String?,
        forceRefresh: Boolean,
        excludeIds: Set<String>
    ): Result<FeedPage> = withContext(Dispatchers.IO) {
        if (category.id == "for_you") {
            return@withContext getForYouFeed(cursor, forceRefresh, excludeIds)
        }
        val cacheKey = "cat_v2_${category.id}_${cursor ?: "0"}"
        if (!forceRefresh && cursor != null) {
            readCachedFeed(cacheKey)?.let { page ->
                indexVideos(page.videos)
                return@withContext Result.success(page)
            }
        }
        lastApiError = null

        runCatching {
            val aliases = ScraperConfig.CATEGORY_HASHTAG_ALIASES[category.id]
                ?: listOf(category.hashtag)
            val offset = cursor?.removePrefix("tag_")?.toIntOrNull() ?: 0

            val pool = if (offset == 0 || forceRefresh || categoryRefPools[category.id].isNullOrEmpty()) {
                val collected = collectCategoryVideoRefs(aliases, forceNetwork = forceRefresh)
                val unseenRefs = collected.filterNot { excludeIds.contains(it.second) }
                val seenRefs = collected.filter { excludeIds.contains(it.second) }
                val orderedRefs = if (unseenRefs.size >= ScraperConfig.CATEGORY_PAGE_SIZE) {
                    if (forceRefresh) unseenRefs.shuffled() else unseenRefs
                } else if (forceRefresh && unseenRefs.size > 4) {
                    unseenRefs.shuffled() + seenRefs.shuffled()
                } else {
                    unseenRefs + seenRefs
                }
                categoryRefPools[category.id] = orderedRefs
                orderedRefs
            } else {
                var currentPool = categoryRefPools[category.id].orEmpty()
                if (offset >= currentPool.size && aliases.size > 1) {
                    val existingIds = currentPool.map { it.second }.toHashSet()
                    val extra = collectCategoryVideoRefs(aliases.drop(1), forceNetwork = false)
                        .filterNot { existingIds.contains(it.second) }
                    if (extra.isNotEmpty()) {
                        currentPool = currentPool + extra
                        categoryRefPools[category.id] = currentPool
                    }
                }
                currentPool
            }

            val pageSize = ScraperConfig.CATEGORY_PAGE_SIZE
            val slice = pool.drop(offset).take(pageSize)
            val combined = LinkedHashMap<String, VideoItem>()
            if (slice.isNotEmpty()) {
                hydrateVideoRefs(slice).forEach { combined[it.id] = it }
            }

            // Fallback to mirror search / verified Explore API ONLY if /tag/ scraping returned nothing
            if (combined.isEmpty()) {
                val categoryType = ScraperConfig.EXPLORE_CATEGORY_TYPE_MAP[category.id]
                coroutineScope {
                    val searchTask = async { fetchSearchFeedApi(category.hashtag, cursor) }
                    val exploreTask = async {
                        categoryType?.let {
                            fetchTikTokExploreApi(it, cursor, forceNetwork = forceRefresh)
                        }
                    }
                    searchTask.await()?.videos?.forEach { combined[it.id] = it }
                    exploreTask.await()?.videos?.forEach { combined.putIfAbsent(it.id, it) }
                }
            }

            if (combined.isEmpty()) {
                throw ScraperException(noVideosMessage("Could not load videos for ${category.label}."))
            }

            val allVideos = combined.values.toList()
            indexVideos(allVideos)
            val nextOffset = offset + slice.size
            val hasMoreRefs = slice.isNotEmpty() && nextOffset < pool.size
            val nextCursorValue = when {
                hasMoreRefs -> "tag_$nextOffset"
                slice.isEmpty() -> {
                    val nextPageNum = (cursor?.removePrefix("explore_")?.toIntOrNull() ?: 0) + 1
                    "explore_$nextPageNum"
                }
                else -> null
            }
            val page = FeedPage(
                videos = allVideos,
                nextCursor = nextCursorValue,
                hasMore = nextCursorValue != null
            )
            saveCachedFeed(cacheKey, page)
            page
        }.recoverCatching { throwable ->
            readCachedFeed(cacheKey, allowExpired = true)?.let { cached ->
                val unseen = cached.videos.filterNot { excludeIds.contains(it.id) }.shuffled()
                val seen = cached.videos.filter { excludeIds.contains(it.id) }.shuffled()
                val ordered = (unseen + seen).distinctBy { it.id }
                indexVideos(ordered)
                return@recoverCatching cached.copy(videos = ordered)
            }
            throw (throwable as? ScraperException)
                ?: ScraperException(throwable.message ?: "Failed to load category", throwable)
        }
    }

    private suspend fun collectCategoryVideoRefs(
        hashtags: List<String>,
        forceNetwork: Boolean
    ): List<Pair<String, String>> = coroutineScope {
        val primaryTag = hashtags.firstOrNull().orEmpty()
        val deduped = LinkedHashMap<String, Pair<String, String>>()
        if (primaryTag.isNotBlank()) {
            val tagDeferred = async {
                fetchTagRefsFromHtml(
                    ScraperConfig.TIKTOK_TAG_URL_TEMPLATE.format(urlEncode(primaryTag)),
                    forceNetwork = forceNetwork
                )
            }
            val discoverDeferred = async {
                fetchTagRefsFromHtml(
                    ScraperConfig.TIKTOK_DISCOVER_URL_TEMPLATE.format(urlEncode(primaryTag)),
                    forceNetwork = forceNetwork
                )
            }
            (tagDeferred.await() + discoverDeferred.await()).forEach { pair ->
                deduped.putIfAbsent(pair.second, pair)
            }
        }
        if (deduped.size < 30 && hashtags.size > 1) {
            val secondaryResults = hashtags.drop(1).take(2).map { tag ->
                async {
                    fetchTagRefsFromHtml(
                        ScraperConfig.TIKTOK_TAG_URL_TEMPLATE.format(urlEncode(tag)),
                        forceNetwork = forceNetwork
                    )
                }
            }.awaitAll()
            secondaryResults.flatten().forEach { pair ->
                deduped.putIfAbsent(pair.second, pair)
            }
        }
        deduped.values.toList()
    }

    // -------------------------------------------------------------------------
    // 2. LIVE SEARCH SUGGESTIONS
    // -------------------------------------------------------------------------

    override suspend fun getSearchSuggestions(query: String): List<SearchSuggestion> =
        withContext(Dispatchers.IO) {
            val raw = query.trim()
            val history = prefs.getSearchHistory()
            if (raw.isBlank()) {
                val items = mutableListOf<SearchSuggestion>()
                history.take(4).forEach { h ->
                    items.add(SearchSuggestion(text = h, type = SuggestionType.HISTORY, subtitle = "Recent search"))
                }
                ScraperConfig.TRENDING_SEARCH_CHIPS.take(6).forEach { chip ->
                    items.add(SearchSuggestion(text = chip, type = SuggestionType.HASHTAG, subtitle = "Trending tag"))
                }
                return@withContext items
            }

            val clean = raw.removePrefix("#").removePrefix("@").trim()
            val seen = HashSet<String>()
            val results = mutableListOf<SearchSuggestion>()

            fun addUnique(item: SearchSuggestion) {
                if (seen.add(item.text.lowercase())) {
                    results.add(item)
                }
            }

            addUnique(
                SearchSuggestion(
                    text = raw,
                    type = when {
                        raw.startsWith("@") -> SuggestionType.CREATOR
                        raw.startsWith("#") -> SuggestionType.HASHTAG
                        else -> SuggestionType.KEYWORD
                    },
                    subtitle = "Search exact match"
                )
            )

            history.filter { it.contains(clean, ignoreCase = true) }.take(3).forEach { h ->
                addUnique(SearchSuggestion(text = h, type = SuggestionType.HISTORY, subtitle = "Recent search"))
            }

            coroutineScope {
                val tikTokSuggestDeferred = async { fetchTikTokSearchSuggestKeywords(clean) }
                val userDeferred = async {
                    if (clean.length >= 2) fetchUserSearchApi(clean, count = 4) else emptyList()
                }
                val challengeDeferred = async {
                    if (clean.length >= 2) fetchChallengeSearchApi(clean, count = 4).challenges else emptyList()
                }

                val kwList = tikTokSuggestDeferred.await().ifEmpty { fetchAutocompleteKeywords(clean) }
                kwList.take(6).forEach { kw ->
                    addUnique(
                        SearchSuggestion(
                            text = kw,
                            type = SuggestionType.KEYWORD,
                            subtitle = "TikTok search"
                        )
                    )
                }
                userDeferred.await().take(3).forEach { u ->
                    addUnique(
                        SearchSuggestion(
                            text = "@${u.username}",
                            type = SuggestionType.CREATOR,
                            subtitle = u.displayName,
                            avatarUrl = u.avatarUrl,
                            verified = u.verified
                        )
                    )
                }
                challengeDeferred.await().take(3).forEach { ch ->
                    addUnique(
                        SearchSuggestion(
                            text = "#${ch.chaName}",
                            type = SuggestionType.HASHTAG,
                            subtitle = "${formatCompactCount(ch.userCount.toLong())} posts"
                        )
                    )
                }
            }

            results.take(12)
        }

    // -------------------------------------------------------------------------
    // 3. REAL TIKTOK UNIFIED SEARCH & INFINITE PAGINATION
    // -------------------------------------------------------------------------

    override suspend fun searchVideos(
        query: String,
        cursor: String?
    ): Result<FeedPage> {
        return searchUnified(query, cursor).map { unified ->
            FeedPage(
                videos = unified.videos,
                nextCursor = unified.nextCursor,
                hasMore = unified.hasMore
            )
        }
    }

    override suspend fun searchUsersPage(
        query: String,
        cursor: String?
    ): Result<UsersPage> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().removePrefix("#").removePrefix("@").trim()
        if (cleanQuery.isBlank()) return@withContext Result.success(UsersPage(emptyList()))
        runCatching {
            fetchUserSearchPageApi(cleanQuery, count = 20, cursor = cursor ?: "0")
        }
    }

    override suspend fun searchHashtagsPage(
        query: String,
        cursor: String?
    ): Result<HashtagsPage> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().removePrefix("#").removePrefix("@").trim()
        if (cleanQuery.isBlank()) return@withContext Result.success(HashtagsPage(emptyList()))
        runCatching {
            val res = fetchChallengeSearchApi(cleanQuery, count = 20, cursor = cursor ?: "0")
            val items = res.challenges.map { ch ->
                HashtagResult(
                    tag = "#${ch.chaName}",
                    viewCountText = "${formatCompactCount(ch.viewCount)} views",
                    videoCount = ch.userCount,
                    challengeId = ch.id,
                    viewCount = ch.viewCount
                )
            }
            HashtagsPage(
                hashtags = items,
                nextCursor = if (res.hasMore && res.nextCursor.isNotBlank()) res.nextCursor else null,
                hasMore = res.hasMore && res.nextCursor.isNotBlank()
            )
        }
    }

    override suspend fun searchUnified(
        query: String,
        cursor: String?
    ): Result<UnifiedSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        val cleanQuery = trimmed.removePrefix("#").removePrefix("@").trim()
        if (cleanQuery.isBlank()) {
            return@withContext Result.success(UnifiedSearchResult(query = trimmed))
        }

        val region = prefs.getSettings().contentRegion
        val cacheKey = "unified_search_v5_${region.code}_${trimmed.lowercase()}_${cursor ?: "0"}"
        readCachedUnifiedSearch(cacheKey)?.let { cached ->
            indexVideos(cached.videos)
            return@withContext Result.success(cached)
        }
        lastApiError = null

        runCatching {
            val tokens = cleanQuery.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }

            // Paginated load-more path (True Infinite Scroll across Web Search + Keyword Feed Search + Challenges)
            if (!cursor.isNullOrBlank()) {
                val state = decodeSearchCursor(cursor)
                    ?: return@runCatching UnifiedSearchResult(query = trimmed, nextCursor = null, hasMore = false)

                val candidateMap = LinkedHashMap<String, VideoItem>()
                val directIds = HashSet<String>()
                var nextWebOffset = state.webOffset
                var webHasMore = false
                var feedSearchCursor = state.feedSearchCursor
                var feedSearchHasMore = state.feedSearchHasMore

                coroutineScope {
                    val webTask = async {
                        if (state.webOffset >= 0) {
                            runCatching {
                                webSessionClient.searchTikTokWeb(
                                    query = cleanQuery,
                                    searchType = "item",
                                    offset = state.webOffset,
                                    count = ScraperConfig.SEARCH_PAGE_SIZE,
                                    regionCode = region.code,
                                    timeZone = region.timeZone
                                )
                            }.getOrNull()
                        } else null
                    }
                    val feedTask = async {
                        if (state.feedSearchHasMore && state.feedSearchCursor.isNotBlank()) {
                            fetchSearchFeedApi(
                                keywords = cleanQuery,
                                cursor = state.feedSearchCursor,
                                count = ScraperConfig.SEARCH_PAGE_SIZE
                            )
                        } else null
                    }

                    val webPage = webTask.await()
                    if (webPage != null) {
                        val parsedWeb = parseWebSearchVideos(webPage)
                        parsedWeb.videos.forEach {
                            candidateMap.putIfAbsent(it.id, it)
                            directIds.add(it.id)
                        }
                        if (parsedWeb.videos.size < 6 && webPage.domVideoRefs.isNotEmpty()) {
                            val missingRefs = webPage.domVideoRefs
                                .filterNot { candidateMap.containsKey(it.second) }
                                .take(12)
                            hydrateVideoRefs(missingRefs).forEach {
                                candidateMap.putIfAbsent(it.id, it)
                                directIds.add(it.id)
                            }
                        }
                        if (parsedWeb.hasMore) {
                            val computedOffset = if (parsedWeb.nextOffset > state.webOffset) {
                                parsedWeb.nextOffset
                            } else {
                                state.webOffset + maxOf(parsedWeb.videos.size, ScraperConfig.SEARCH_PAGE_SIZE)
                            }
                            nextWebOffset = computedOffset
                            webHasMore = parsedWeb.videos.isNotEmpty()
                        } else {
                            nextWebOffset = -1
                        }
                    } else {
                        nextWebOffset = -1
                    }

                    val feedPage = feedTask.await()
                    if (feedPage != null) {
                        feedPage.videos.forEach {
                            candidateMap.putIfAbsent(it.id, it)
                            directIds.add(it.id)
                        }
                        val nextC = feedPage.nextCursor.orEmpty()
                        val more = feedPage.hasMore && nextC.isNotBlank() && nextC != state.feedSearchCursor
                        feedSearchHasMore = more
                        feedSearchCursor = if (more) nextC else ""
                    } else {
                        feedSearchHasMore = false
                        feedSearchCursor = ""
                    }
                }

                val queue = state.queue.map { it.copy() }.toMutableList()
                var qIdx = state.qIdx
                var chSearchCursor = state.chSearchCursor
                var chSearchHasMore = state.chSearchHasMore

                var attempts = 0
                while (candidateMap.size < ScraperConfig.SEARCH_PAGE_SIZE && attempts < 3) {
                    if (qIdx >= queue.size) {
                        if (chSearchHasMore && chSearchCursor.isNotBlank()) {
                            val extraChallenges = fetchChallengeSearchApi(
                                keywords = cleanQuery,
                                count = 15,
                                cursor = chSearchCursor
                            )
                            val existingIds = queue.map { it.id }.toHashSet()
                            extraChallenges.challenges
                                .filter { it.userCount > 0 && !existingIds.contains(it.id) }
                                .forEach { ch ->
                                    queue.add(
                                        ChallengeQueueEntry(
                                            id = ch.id,
                                            name = ch.chaName,
                                            cursor = "0",
                                            hasMore = true
                                        )
                                    )
                                }
                            chSearchCursor = extraChallenges.nextCursor
                            chSearchHasMore = extraChallenges.hasMore && extraChallenges.nextCursor.isNotBlank()
                            if (qIdx >= queue.size) break
                        } else {
                            break
                        }
                    }

                    val entry = queue[qIdx]
                    if (!entry.hasMore) {
                        qIdx++
                        continue
                    }
                    attempts++
                    val posts = fetchChallengePostsApi(
                        challengeId = entry.id,
                        cursor = entry.cursor,
                        count = ScraperConfig.DEFAULT_PAGE_SIZE
                    )
                    posts.videos.forEach { candidateMap.putIfAbsent(it.id, it) }
                    val nextC = posts.nextCursor.orEmpty()
                    val more = posts.hasMore && nextC.isNotBlank() && nextC != entry.cursor
                    queue[qIdx] = entry.copy(
                        cursor = if (more) nextC else entry.cursor,
                        hasMore = more
                    )
                    if (!more) qIdx++
                }

                val rankedPage = candidateMap.values.sortedWith(
                    compareByDescending<VideoItem> {
                        scoreVideoMatch(it, tokens, cleanQuery, isDirectSearch = directIds.contains(it.id))
                    }.thenByDescending { it.stats.viewsCount }
                )
                indexVideos(rankedPage)

                val queueHasMore = qIdx < queue.size && queue.drop(qIdx).any { it.hasMore }
                val hasNext = webHasMore || feedSearchHasMore || queueHasMore || chSearchHasMore
                val nextCursorEncoded = if (hasNext) {
                    encodeSearchCursor(
                        SearchCursorState(
                            webOffset = if (webHasMore) nextWebOffset else -1,
                            feedSearchCursor = if (feedSearchHasMore) feedSearchCursor else "",
                            feedSearchHasMore = feedSearchHasMore,
                            queue = queue,
                            qIdx = qIdx,
                            chSearchCursor = chSearchCursor,
                            chSearchHasMore = chSearchHasMore
                        )
                    )
                } else null

                val nextResult = UnifiedSearchResult(
                    query = trimmed,
                    videos = rankedPage,
                    nextCursor = nextCursorEncoded,
                    hasMore = nextCursorEncoded != null
                )
                saveCachedUnifiedSearch(cacheKey, nextResult)
                return@runCatching nextResult
            }

            // Initial Search (Page 0) - Real TikTok Web General + Video Item Search + Keyword Search + Creators + Hashtags
            val (webGeneralRes, feedSearchPage, usersPage, challengeMatches) = coroutineScope {
                val wTask = async {
                    runCatching {
                        webSessionClient.searchTikTokWeb(
                            query = cleanQuery,
                            searchType = "general",
                            offset = 0,
                            count = ScraperConfig.SEARCH_PAGE_SIZE,
                            regionCode = region.code,
                            timeZone = region.timeZone
                        )
                    }.getOrNull()
                }
                val fTask = async {
                    fetchSearchFeedApi(
                        keywords = cleanQuery,
                        cursor = "0",
                        count = ScraperConfig.SEARCH_PAGE_SIZE
                    )
                }
                val uTask = async { fetchUserSearchPageApi(cleanQuery, count = 20, cursor = "0") }
                val cTask = async { fetchMatchingChallengesForQuery(cleanQuery, tokens) }
                Quadruple(wTask.await(), fTask.await(), uTask.await(), cTask.await())
            }

            val candidateMap = LinkedHashMap<String, VideoItem>()
            val directIds = HashSet<String>()

            var webParsed = webGeneralRes?.let { parseWebSearchResponse(it) }
            webParsed?.videos?.forEach {
                candidateMap[it.id] = it
                directIds.add(it.id)
            }

            // If /search?q= (general) only returned ~4 top cards and /api/search/item/full wasn't captured,
            // explicitly load TikTok's Videos search tab (/search/video?q=) so we get full video search results!
            if ((webParsed?.videos?.size ?: 0) < 8) {
                val webItemRes = runCatching {
                    webSessionClient.searchTikTokWeb(
                        query = cleanQuery,
                        searchType = "item",
                        offset = 0,
                        count = ScraperConfig.SEARCH_PAGE_SIZE,
                        regionCode = region.code,
                        timeZone = region.timeZone
                    )
                }.getOrNull()
                val itemParsed = webItemRes?.let { parseWebSearchResponse(it) }
                itemParsed?.videos?.forEach {
                    candidateMap.putIfAbsent(it.id, it)
                    directIds.add(it.id)
                }
                if (itemParsed != null && itemParsed.videos.isNotEmpty()) {
                    webParsed = ParsedWebSearchResult(
                        videos = candidateMap.values.toList(),
                        users = (webParsed?.users.orEmpty() + itemParsed.users).distinctBy { it.username.lowercase() },
                        hashtags = (webParsed?.hashtags.orEmpty() + itemParsed.hashtags).distinctBy { it.tag.lowercase() },
                        liveStreams = (webParsed?.liveStreams.orEmpty() + itemParsed.liveStreams).distinctBy { it.roomId },
                        nextOffset = maxOf(webParsed?.nextOffset ?: -1, itemParsed.nextOffset, itemParsed.videos.size),
                        hasMore = (webParsed?.hasMore == true) || itemParsed.hasMore || itemParsed.videos.size >= 8
                    )
                } else if (webItemRes != null && webItemRes.domVideoRefs.isNotEmpty() && candidateMap.size < 10) {
                    hydrateVideoRefs(webItemRes.domVideoRefs.take(15)).forEach {
                        candidateMap.putIfAbsent(it.id, it)
                        directIds.add(it.id)
                    }
                }
            }

            // Always include direct keyword search API results on Page 0 (never gated behind < 6!)
            feedSearchPage?.videos?.forEach {
                candidateMap.putIfAbsent(it.id, it)
                directIds.add(it.id)
            }

            // Hydrate DOM video refs from general search if we still have few videos
            if (candidateMap.size < 10 && webGeneralRes?.domVideoRefs?.isNotEmpty() == true) {
                val missing = webGeneralRes.domVideoRefs
                    .filterNot { candidateMap.containsKey(it.second) }
                    .take(12)
                hydrateVideoRefs(missing).forEach {
                    candidateMap.putIfAbsent(it.id, it)
                    directIds.add(it.id)
                }
            }

            val compact = cleanQuery.lowercase().replace(Regex("[^\\p{L}0-9_]"), "")
            val queue = challengeMatches.challenges
                .filter { it.userCount > 0 && scoreChallengeMatch(it.chaName, it.userCount, tokens, compact) >= 40 }
                .take(18)
                .map { ch ->
                    ChallengeQueueEntry(
                        id = ch.id,
                        name = ch.chaName,
                        cursor = "0",
                        hasMore = true
                    )
                }
                .toMutableList()

            // Supplement with top tightly-matched TikTok hashtags if needed
            val initialChallengeCount = if (candidateMap.size < 16) minOf(2, queue.size) else 0
            for (i in 0 until initialChallengeCount) {
                val entry = queue[i]
                val posts = fetchChallengePostsApi(
                    challengeId = entry.id,
                    cursor = "0",
                    count = ScraperConfig.DEFAULT_PAGE_SIZE
                )
                posts.videos.forEach { candidateMap.putIfAbsent(it.id, it) }
                val nextC = posts.nextCursor.orEmpty()
                val more = posts.hasMore && nextC.isNotBlank() && nextC != "0"
                queue[i] = entry.copy(
                    cursor = if (more) nextC else "18",
                    hasMore = more
                )
            }

            // If user searched a @handle or exact creator, also include their real videos
            val compactSlug = cleanQuery.lowercase().replace(Regex("[^a-z0-9._]"), "")
            val likelyHandle = trimmed.startsWith("@") ||
                (!cleanQuery.contains(" ") && compactSlug.length >= 3)
            val exactUser = usersPage.users.firstOrNull {
                it.username.equals(cleanQuery, ignoreCase = true) ||
                    it.displayName.equals(cleanQuery, ignoreCase = true)
            }
            val directCreatorProfile = when {
                trimmed.startsWith("@") && compactSlug.isNotBlank() ->
                    runCatching { fetchUserProfileInternal(compactSlug, null) }.getOrNull()
                exactUser != null ->
                    runCatching { fetchUserProfileInternal(exactUser.username, null) }.getOrNull()
                likelyHandle && candidateMap.size < 8 && compactSlug.isNotBlank() ->
                    runCatching { fetchUserProfileInternal(compactSlug, null) }.getOrNull()
                else -> null
            }
            directCreatorProfile?.videos?.forEach {
                candidateMap.putIfAbsent(it.id, it)
                directIds.add(it.id)
            }

            val rankedVideos = candidateMap.values.sortedWith(
                compareByDescending<VideoItem> {
                    scoreVideoMatch(it, tokens, cleanQuery, isDirectSearch = directIds.contains(it.id))
                }.thenByDescending { it.stats.viewsCount }
            )
            indexVideos(rankedVideos)

            val userMap = LinkedHashMap<String, Author>()
            directCreatorProfile?.author?.let { a ->
                if (a.followersCount > 0L || directCreatorProfile.videos.isNotEmpty()) {
                    userMap[a.username.lowercase()] = a
                }
            }
            webParsed?.users?.forEach { userMap.putIfAbsent(it.username.lowercase(), it) }
            usersPage.users.forEach { userMap.putIfAbsent(it.username.lowercase(), it) }

            val rankedUsers = userMap.values.sortedWith(
                compareByDescending<Author> {
                    it.username.equals(cleanQuery, ignoreCase = true) ||
                        it.displayName.equals(cleanQuery, ignoreCase = true)
                }.thenByDescending { it.followersCount }
            )

            // Real Hashtags from TikTok challenge search + matched video hashtags
            val hashtagMap = LinkedHashMap<String, HashtagResult>()
            challengeMatches.challenges.forEach { ch ->
                val tag = "#${ch.chaName}"
                hashtagMap[tag.lowercase()] = HashtagResult(
                    tag = tag,
                    viewCountText = "${formatCompactCount(ch.viewCount)} views",
                    videoCount = ch.userCount,
                    previewCoverUrl = rankedVideos.firstOrNull {
                        it.hashtags.any { h -> h.equals(tag, ignoreCase = true) }
                    }?.coverUrl.orEmpty(),
                    challengeId = ch.id,
                    viewCount = ch.viewCount
                )
            }
            webParsed?.hashtags?.forEach { h ->
                hashtagMap.putIfAbsent(h.tag.lowercase(), h)
            }
            rankedVideos.forEach { v ->
                v.hashtags.forEach { h ->
                    val key = h.lowercase()
                    if (!hashtagMap.containsKey(key)) {
                        hashtagMap[key] = HashtagResult(
                            tag = h,
                            viewCountText = "${formatCompactCount(maxOf(1000L, v.stats.viewsCount))} views",
                            videoCount = 1,
                            previewCoverUrl = v.coverUrl
                        )
                    }
                }
            }

            val firstActiveIdx = queue.indexOfFirst { it.hasMore }
            val webHasMore = webParsed?.hasMore == true && (webParsed?.nextOffset ?: -1) > 0
            val feedNextCursor = feedSearchPage?.nextCursor.orEmpty()
            val feedSearchHasMore = feedSearchPage != null &&
                feedSearchPage.hasMore &&
                feedNextCursor.isNotBlank() &&
                feedNextCursor != "0"

            val hasMore = webHasMore || feedSearchHasMore || firstActiveIdx != -1 || challengeMatches.hasMore
            val nextCursorEncoded = if (hasMore) {
                encodeSearchCursor(
                    SearchCursorState(
                        webOffset = if (webHasMore) (webParsed?.nextOffset ?: -1) else -1,
                        feedSearchCursor = if (feedSearchHasMore) feedNextCursor else "",
                        feedSearchHasMore = feedSearchHasMore,
                        queue = queue,
                        qIdx = if (firstActiveIdx != -1) firstActiveIdx else queue.size,
                        chSearchCursor = challengeMatches.nextCursor,
                        chSearchHasMore = challengeMatches.hasMore
                    )
                )
            } else null

            val result = UnifiedSearchResult(
                query = trimmed,
                videos = rankedVideos,
                users = rankedUsers,
                liveStreams = webParsed?.liveStreams.orEmpty(),
                hashtags = hashtagMap.values.toList(),
                relatedQueries = emptyList(),
                nextCursor = nextCursorEncoded,
                hasMore = nextCursorEncoded != null,
                usersNextCursor = if (usersPage.hasMore) usersPage.nextCursor else null,
                usersHasMore = usersPage.hasMore,
                hashtagsNextCursor = if (challengeMatches.hasMore) challengeMatches.nextCursor else null,
                hashtagsHasMore = challengeMatches.hasMore
            )

            saveCachedUnifiedSearch(cacheKey, result)
            result
        }.recoverCatching { throwable ->
            readCachedUnifiedSearch(cacheKey, allowExpired = true)?.let { return@recoverCatching it }
            throw (throwable as? ScraperException)
                ?: ScraperException(throwable.message ?: "Search failed", throwable)
        }
    }

    // -------------------------------------------------------------------------
    // 4. COMPLETE USER PROFILE & CREATOR VIDEOS
    // -------------------------------------------------------------------------

    override suspend fun getUserProfile(
        username: String,
        cursor: String?,
        forceRefresh: Boolean
    ): Result<UserProfileResult> = withContext(Dispatchers.IO) {
        val cleanUser = username.trim().removePrefix("@")
        val keyLower = cleanUser.lowercase()
        val cacheKey = "profile_v7_${keyLower}_${cursor ?: "0"}"
        if (!forceRefresh && cursor.isNullOrBlank()) {
            readCachedProfile(cacheKey)?.let { profile ->
                val expectedTotal = profile.author.videoCount
                val isFullyLoaded = profile.videos.isNotEmpty() &&
                    expectedTotal > 0L &&
                    profile.videos.size >= expectedTotal &&
                    !profile.hasMore
                val hasLiveQueue = profile.videos.isNotEmpty() &&
                    profile.hasMore &&
                    profileFallbackQueues.containsKey(keyLower)
                if (isFullyLoaded || hasLiveQueue) {
                    indexVideos(profile.videos)
                    return@withContext Result.success(profile)
                }
            }
        } else if (!forceRefresh) {
            readCachedProfile(cacheKey)?.let { profile ->
                val refsOk = cursor?.startsWith("refs_") != true || pendingProfileRefs.containsKey(keyLower)
                val queueOk = profile.hasMore == false ||
                    !profile.nextCursor.isNullOrBlank() ||
                    profileFallbackQueues.containsKey(keyLower)
                if (profile.videos.isNotEmpty() && refsOk && queueOk) {
                    indexVideos(profile.videos)
                    return@withContext Result.success(profile)
                }
            }
        }
        lastApiError = null

        runCatching {
            val profile = fetchUserProfileInternal(cleanUser, cursor, forceNetwork = forceRefresh)
                ?: throw ScraperException(noVideosMessage("Unable to load profile @$cleanUser."))
            indexVideos(profile.videos)
            val expectedTotal = profile.author.videoCount
            val shouldCache = profile.videos.isNotEmpty() && (
                (cursor != null && !cursor.startsWith("refs_")) ||
                    (!profile.hasMore && expectedTotal > 0L && profile.videos.size >= expectedTotal)
                )
            if (shouldCache) {
                saveCachedProfile(cacheKey, profile)
            }
            profile
        }.recoverCatching { throwable ->
            readCachedProfile(cacheKey, allowExpired = true)?.let {
                if (it.videos.isNotEmpty()) {
                    indexVideos(it.videos)
                    return@recoverCatching it
                }
            }
            throw (throwable as? ScraperException)
                ?: ScraperException(throwable.message ?: "Failed to load profile", throwable)
        }
    }

    private fun aliasProfileState(
        primaryKeyLower: String,
        resolvedUsername: String,
        secUid: String,
        userId: String,
        allowedUsernames: Set<String>
    ) {
        val altKey = resolvedUsername.trim().removePrefix("@").lowercase()
        if (secUid.isNotBlank()) {
            secUidByUsername[primaryKeyLower] = secUid
            if (altKey.isNotBlank() && altKey != "creator") secUidByUsername[altKey] = secUid
        }
        if (userId.isNotBlank() && !userId.equals(primaryKeyLower, ignoreCase = true)) {
            userIdByUsername[primaryKeyLower] = userId
            if (altKey.isNotBlank() && altKey != "creator") userIdByUsername[altKey] = userId
        }
        if (allowedUsernames.isNotEmpty()) {
            canonicalUsernameByQuery[primaryKeyLower] = allowedUsernames
            if (altKey.isNotBlank() && altKey != "creator") canonicalUsernameByQuery[altKey] = allowedUsernames
        }
        if (altKey.isNotBlank() && altKey != "creator" && altKey != primaryKeyLower) {
            pendingProfileRefs[primaryKeyLower]?.let { pendingProfileRefs[altKey] = it }
            profileFallbackQueues[primaryKeyLower]?.let { profileFallbackQueues[altKey] = it }
        }
    }

    private fun popNextProfileStage(keyLower: String, finishedCursor: String? = null): String? {
        val queue = profileFallbackQueues[keyLower] ?: return null
        synchronized(queue) {
            while (queue.isNotEmpty()) {
                val candidate = queue.removeFirst()
                if (candidate.isNotBlank() && candidate != finishedCursor) {
                    return candidate
                }
            }
            return null
        }
    }

    private suspend fun fetchUserProfileInternal(
        username: String,
        cursor: String?,
        forceNetwork: Boolean = false
    ): UserProfileResult? = coroutineScope {
        val clean = username.trim().removePrefix("@")
        val keyLower = clean.lowercase()
        val hintAuthor = authorHintByUsername[keyLower]
        val activeRegion = prefs.getSettings().contentRegion

        // 0. Paginate all uploaded creator videos via direct TikTok /api/creator/item_list/
        if (!cursor.isNullOrBlank() && cursor.startsWith("creator:")) {
            val creatorCursor = cursor.removePrefix("creator:")
            var effectiveSecUid = secUidByUsername[keyLower]
                ?.takeIf { it.isNotBlank() }
                ?: hintAuthor?.secUid?.takeIf { it.isNotBlank() }
                .orEmpty()
            var htmlAuthorFallback: Author? = null
            if (effectiveSecUid.isBlank()) {
                val profileUrl = ScraperConfig.TIKTOK_PROFILE_URL_TEMPLATE.format(urlEncode(clean))
                val htmlProf = runCatching {
                    fetchAndParseHtmlProfile(profileUrl, clean, forceNetwork = false)
                }.getOrNull()
                htmlAuthorFallback = htmlProf?.author
                if (!htmlProf?.author?.secUid.isNullOrBlank()) {
                    effectiveSecUid = htmlProf!!.author.secUid
                    secUidByUsername[keyLower] = effectiveSecUid
                }
            }

            val page = if (effectiveSecUid.isNotBlank()) {
                fetchTikTokCreatorItemList(
                    secUid = effectiveSecUid,
                    username = clean,
                    cursor = creatorCursor,
                    count = ScraperConfig.PROFILE_CREATOR_PAGE_SIZE,
                    forceNetwork = forceNetwork
                )
            } else {
                FeedPage(emptyList(), null, false)
            }

            if (page.videos.isNotEmpty()) {
                val baseAuthor = hintAuthor ?: htmlAuthorFallback ?: Author(
                    id = userIdByUsername[keyLower]?.ifBlank { clean } ?: clean,
                    username = clean,
                    displayName = clean,
                    secUid = effectiveSecUid
                )
                val firstCreatorAuthor = page.videos.firstOrNull {
                    it.author.username != "creator" && it.author.avatarUrl.isNotBlank()
                }?.author
                val resolvedAuthor = Author(
                    id = firstCreatorAuthor?.id?.ifBlank { baseAuthor.id } ?: baseAuthor.id,
                    username = firstCreatorAuthor?.username?.ifBlank { baseAuthor.username } ?: baseAuthor.username,
                    displayName = firstCreatorAuthor?.displayName?.ifBlank { baseAuthor.displayName } ?: baseAuthor.displayName,
                    avatarUrl = baseAuthor.avatarUrl.ifBlank { firstCreatorAuthor?.avatarUrl.orEmpty() },
                    bio = baseAuthor.bio.ifBlank { firstCreatorAuthor?.bio.orEmpty() },
                    verified = baseAuthor.verified || (firstCreatorAuthor?.verified == true),
                    secUid = effectiveSecUid.ifBlank { firstCreatorAuthor?.secUid.orEmpty() },
                    followingCount = maxOf(baseAuthor.followingCount, firstCreatorAuthor?.followingCount ?: 0L),
                    followersCount = maxOf(baseAuthor.followersCount, firstCreatorAuthor?.followersCount ?: 0L),
                    likesCount = maxOf(baseAuthor.likesCount, firstCreatorAuthor?.likesCount ?: 0L),
                    videoCount = maxOf(baseAuthor.videoCount, firstCreatorAuthor?.videoCount ?: 0L)
                )
                val fixedVideos = page.videos.map { item ->
                    if (item.author.username == "creator" || item.author.avatarUrl.isBlank()) {
                        item.copy(author = resolvedAuthor)
                    } else item
                }
                val hasNext = page.hasMore && !page.nextCursor.isNullOrBlank() && page.nextCursor != creatorCursor
                val nextStage = if (hasNext) "creator:${page.nextCursor}" else popNextProfileStage(keyLower, cursor)
                return@coroutineScope UserProfileResult(
                    author = resolvedAuthor,
                    videos = fixedVideos,
                    nextCursor = nextStage,
                    hasMore = nextStage != null
                )
            }

            // Secondary fallback if /api/creator/item_list/ returned empty on a later page
            val fallbackResp = runCatching {
                webSessionClient.fetchUserPostsPage(
                    username = clean,
                    secUidHint = effectiveSecUid,
                    userIdHint = userIdByUsername[keyLower].orEmpty(),
                    cursor = creatorCursor,
                    count = ScraperConfig.PROFILE_CREATOR_PAGE_SIZE,
                    forceReload = false,
                    regionCode = activeRegion.code,
                    timeZone = activeRegion.timeZone
                )
            }.getOrNull()
            val fallbackRoot = fallbackResp?.rawApiJson?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            val fallbackArr = fallbackRoot?.optJSONArray("itemList")
            val fallbackVideos = if (fallbackArr != null && fallbackArr.length() > 0) {
                parseTikTokJsonArray(fallbackArr)
            } else emptyList()
            val nextCursorApi = fallbackRoot?.optCleanString("cursor").orEmpty()
            val minFallbackSec = fallbackVideos.mapNotNull { it.createdAt.takeIf { ts -> ts > 0L } }.minOrNull()
            val hasMorePrevFb = fallbackRoot?.optBoolean("hasMorePrevious", false) == true
            val hasMorePostFb = fallbackRoot?.optBoolean("hasMore", false) == true
            val nextPrefixCursor = when {
                fallbackVideos.isNotEmpty() && hasMorePrevFb && minFallbackSec != null -> {
                    val rawMs = minFallbackSec * 1000L
                    val ms = (if (rawMs.toString() == creatorCursor) rawMs - 1L else rawMs).toString()
                    if (ms != creatorCursor) "creator:$ms" else popNextProfileStage(keyLower, cursor)
                }
                hasMorePostFb && nextCursorApi.isNotBlank() && nextCursorApi != creatorCursor ->
                    "wv:$nextCursorApi"
                else -> popNextProfileStage(keyLower, cursor)
            }
            val fbAuthor = fallbackVideos.firstOrNull { it.author.username != "creator" }?.author
                ?: hintAuthor
                ?: Author(id = clean, username = clean, displayName = clean, secUid = effectiveSecUid)
            return@coroutineScope UserProfileResult(
                author = fbAuthor,
                videos = fallbackVideos.map { item ->
                    if (item.author.username == "creator" || item.author.avatarUrl.isBlank()) {
                        item.copy(author = fbAuthor)
                    } else item
                },
                nextCursor = nextPrefixCursor,
                hasMore = nextPrefixCursor != null
            )
        }

        // 1. Paginate uploaded creator videos via warmed WebView /api/post/item_list/ session
        if (!cursor.isNullOrBlank() && cursor.startsWith("wv:")) {
            val wvCursor = cursor.removePrefix("wv:")
            val cachedSecUid = secUidByUsername[keyLower] ?: hintAuthor?.secUid.orEmpty()
            val cachedUserId = userIdByUsername[keyLower] ?: hintAuthor?.id.orEmpty()
            var resp = webSessionClient.fetchUserPostsPage(
                username = clean,
                secUidHint = cachedSecUid,
                userIdHint = cachedUserId,
                cursor = wvCursor,
                count = ScraperConfig.PROFILE_WEB_PAGE_SIZE,
                forceReload = false,
                regionCode = activeRegion.code,
                timeZone = activeRegion.timeZone
            )
            var root = resp?.rawApiJson?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            var itemArray = root?.optJSONArray("itemList")
            if ((itemArray == null || itemArray.length() == 0) &&
                (wvCursor == "0" || (root == null && resp?.domVideoRefs.isNullOrEmpty()))
            ) {
                resp = webSessionClient.fetchUserPostsPage(
                    username = clean,
                    secUidHint = cachedSecUid,
                    userIdHint = cachedUserId,
                    cursor = wvCursor,
                    count = ScraperConfig.PROFILE_WEB_PAGE_SIZE,
                    forceReload = true,
                    regionCode = activeRegion.code,
                    timeZone = activeRegion.timeZone
                )
                root = resp?.rawApiJson?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                itemArray = root?.optJSONArray("itemList")
            }

            if (resp != null && resp.secUid.isNotBlank()) {
                secUidByUsername[keyLower] = resp.secUid
            }
            if (resp != null && resp.userId.isNotBlank()) {
                userIdByUsername[keyLower] = resp.userId
            }

            val allowed = canonicalUsernameByQuery[keyLower].orEmpty() + clean
            val effectiveSecUid = (resp?.secUid ?: cachedSecUid).trim()
            val parsedAuthor = resp?.userDetailJson?.takeIf { it.isNotBlank() }?.let { rawDetail ->
                runCatching {
                    val ui = JSONObject(rawDetail)
                    val u = ui.optJSONObject("user") ?: return@runCatching null
                    val stats = ui.optJSONObject("stats")
                    val uname = u.optCleanString("uniqueId").ifBlank { clean }
                    buildAuthorFromUserJson(u, stats, uname)
                }.getOrNull()
            }
            var parsedVideosRaw = if (itemArray != null && itemArray.length() > 0) {
                parseTikTokJsonArray(itemArray).filter { v ->
                    allowed.any { it.equals(v.author.username, ignoreCase = true) } ||
                        (effectiveSecUid.isNotBlank() && v.author.secUid == effectiveSecUid) ||
                        v.author.username == "creator"
                }
            } else emptyList()

            val apiHasMore = root?.optBoolean("hasMore", false) ?: false
            val apiHasMorePrev = root?.optBoolean("hasMorePrevious", false) ?: false
            val nextRawCursor = root?.optCleanString("cursor").orEmpty()
            val hasNextWeb = apiHasMore &&
                nextRawCursor.isNotBlank() &&
                nextRawCursor != wvCursor &&
                (parsedVideosRaw.isNotEmpty() || root != null)

            if (parsedVideosRaw.isEmpty() && !resp?.domVideoRefs.isNullOrEmpty()) {
                val ownDomRefs = resp!!.domVideoRefs.filter { ref ->
                    allowed.any { it.equals(ref.first, ignoreCase = true) }
                }
                if (ownDomRefs.isNotEmpty()) {
                    val cachedFromIndex = ownDomRefs.mapNotNull { videoIndex[it.second] }
                    val uncachedRefs = ownDomRefs.filterNot { videoIndex.containsKey(it.second) }
                    val batch = uncachedRefs.take(ScraperConfig.PROFILE_INITIAL_BATCH_SIZE)
                    val hydratedBatch = if (batch.isNotEmpty()) hydrateVideoRefs(batch) else emptyList()
                    parsedVideosRaw = (cachedFromIndex + hydratedBatch).distinctBy { it.id }
                    if (uncachedRefs.size > ScraperConfig.PROFILE_INITIAL_BATCH_SIZE) {
                        val existingPending = pendingProfileRefs[keyLower].orEmpty()
                        val mergedPending = (existingPending + uncachedRefs.drop(ScraperConfig.PROFILE_INITIAL_BATCH_SIZE)).distinctBy { it.second }
                        pendingProfileRefs[keyLower] = mergedPending
                        val q = profileFallbackQueues.getOrPut(keyLower) { ArrayDeque() }
                        synchronized(q) {
                            if (!q.any { it.startsWith("refs_") }) {
                                q.add("refs_${existingPending.size}")
                            }
                        }
                    }
                }
            }

            val minWvSec = parsedVideosRaw.mapNotNull { it.createdAt.takeIf { ts -> ts > 0L } }.minOrNull()
            val nextStage = when {
                hasNextWeb -> "wv:$nextRawCursor"
                wvCursor == "0" && apiHasMorePrev && minWvSec != null -> "creator:${minWvSec * 1000L}"
                else -> popNextProfileStage(keyLower, cursor)
            }

            val resolvedAuthor = parsedVideosRaw.firstOrNull { it.author.username != "creator" }?.author
                ?: parsedAuthor
                ?: hintAuthor
                ?: Author(
                    id = cachedUserId.ifBlank { clean },
                    username = clean,
                    displayName = clean,
                    secUid = effectiveSecUid
                )
            val parsedVideos = parsedVideosRaw.map { item ->
                if (item.author.username == "creator" || item.author.avatarUrl.isBlank()) {
                    item.copy(author = resolvedAuthor)
                } else item
            }

            return@coroutineScope UserProfileResult(
                author = resolvedAuthor,
                videos = parsedVideos,
                nextCursor = nextStage,
                hasMore = nextStage != null
            )
        }

        // 2. Fallback pagination of pending SSR/embed/DOM video refs
        if (!cursor.isNullOrBlank() && cursor.startsWith("refs_")) {
            val offset = cursor.removePrefix("refs_").toIntOrNull() ?: 0
            val allRefs = pendingProfileRefs[keyLower].orEmpty()
            val slice = allRefs.drop(offset).take(ScraperConfig.PROFILE_INITIAL_BATCH_SIZE)
            val videos = if (slice.isNotEmpty()) hydrateVideoRefs(slice) else emptyList()
            val nextOffset = offset + slice.size
            val hasMoreRefs = slice.isNotEmpty() && nextOffset < allRefs.size
            val nextStage = if (hasMoreRefs) "refs_$nextOffset" else popNextProfileStage(keyLower, cursor)
            val author = videos.firstOrNull()?.author ?: hintAuthor ?: Author(
                id = clean,
                username = clean,
                displayName = clean
            )
            return@coroutineScope UserProfileResult(
                author = author,
                videos = videos,
                nextCursor = nextStage,
                hasMore = nextStage != null
            )
        }

        // 3. Fallback pagination via mirror API if available on the user's network
        if (!cursor.isNullOrBlank() && cursor.startsWith("mirror:")) {
            val mirrorCursor = cursor.removePrefix("mirror:")
            val cachedSecUid = secUidByUsername[keyLower] ?: hintAuthor?.secUid.orEmpty()
            val page = fetchUserPostsMirror(clean, mirrorCursor, secUid = cachedSecUid)
            val allowed = canonicalUsernameByQuery[keyLower].orEmpty() + clean
            val ownVideos = page.videos.filter { v ->
                allowed.any { it.equals(v.author.username, ignoreCase = true) } ||
                    (cachedSecUid.isNotBlank() && v.author.secUid == cachedSecUid) ||
                    v.author.username == "creator"
            }
            val author = ownVideos.firstOrNull { it.author.username != "creator" }?.author ?: hintAuthor ?: Author(
                id = clean,
                username = clean,
                displayName = clean,
                secUid = cachedSecUid
            )
            val hasNextMirror = page.hasMore && !page.nextCursor.isNullOrBlank() && page.nextCursor != mirrorCursor && ownVideos.isNotEmpty()
            val nextStage = if (hasNextMirror) "mirror:${page.nextCursor}" else popNextProfileStage(keyLower, cursor)
            return@coroutineScope UserProfileResult(
                author = author,
                videos = ownVideos.map { item ->
                    if (item.author.username == "creator" || item.author.avatarUrl.isBlank()) {
                        item.copy(author = author)
                    } else item
                },
                nextCursor = nextStage,
                hasMore = nextStage != null
            )
        }

        // Initial profile load (cursor == null):
        // Run HTML SSR + /embed/@user + tikwm /user/info + tikwm /user/posts (and /api/creator/item_list/ if secUid known) in parallel!
        profileFallbackQueues.remove(keyLower)
        pendingProfileRefs.remove(keyLower)
        val compactTag = clean.lowercase().replace(Regex("[^\\p{L}0-9_]"), "")
        val profileUrl = ScraperConfig.TIKTOK_PROFILE_URL_TEMPLATE.format(urlEncode(clean))
        val preknownSecUid = secUidByUsername[keyLower]
            ?.takeIf { it.isNotBlank() }
            ?: hintAuthor?.secUid?.takeIf { it.isNotBlank() }
            .orEmpty()

        val immediateCreatorDeferred = if (preknownSecUid.isNotBlank()) {
            async {
                runCatching {
                    fetchTikTokCreatorItemList(
                        secUid = preknownSecUid,
                        username = clean,
                        cursor = "0",
                        count = ScraperConfig.PROFILE_CREATOR_PAGE_SIZE,
                        forceNetwork = forceNetwork
                    )
                }.getOrNull()
            }
        } else null

        val htmlProfileDeferred = async {
            runCatching { fetchAndParseHtmlProfile(profileUrl, clean, forceNetwork = forceNetwork) }.getOrNull()
        }
        val embedProfileDeferred = async {
            runCatching { fetchEmbedProfilePage(clean, forceNetwork = forceNetwork) }.getOrNull()
        }
        val userInfoMirrorDeferred = async {
            runCatching { fetchUserInfoMirror(clean, secUid = preknownSecUid) }.getOrNull()
        }
        val mirrorPostsDeferred = async {
            fetchUserPostsMirror(clean, null, secUid = preknownSecUid)
        }

        val htmlProfile = htmlProfileDeferred.await()
        val embedProfile = embedProfileDeferred.await()
        val mirrorUserInfo = userInfoMirrorDeferred.await()
        var postsPage = mirrorPostsDeferred.await()

        var resolvedSecUid = preknownSecUid
            .ifBlank { htmlProfile?.author?.secUid.orEmpty() }
            .ifBlank { mirrorUserInfo?.secUid.orEmpty() }
            .ifBlank { embedProfile?.secUid.orEmpty() }
            .ifBlank { secUidByUsername[keyLower].orEmpty() }

        val resolvedUserId = (htmlProfile?.author?.id?.takeIf { !it.equals(clean, ignoreCase = true) } ?: "")
            .ifBlank { mirrorUserInfo?.id?.takeIf { !it.equals(clean, ignoreCase = true) }.orEmpty() }
            .ifBlank { embedProfile?.author?.id?.takeIf { !it.equals(clean, ignoreCase = true) }.orEmpty() }
            .ifBlank { hintAuthor?.id?.takeIf { !it.equals(clean, ignoreCase = true) }.orEmpty() }
            .ifBlank { userIdByUsername[keyLower].orEmpty() }

        if (resolvedSecUid.isNotBlank()) {
            secUidByUsername[keyLower] = resolvedSecUid
        }
        if (resolvedUserId.isNotBlank()) {
            userIdByUsername[keyLower] = resolvedUserId
        }

        if (postsPage.videos.isEmpty() && resolvedSecUid.isNotBlank() && resolvedSecUid != preknownSecUid) {
            postsPage = fetchUserPostsMirror(clean, null, secUid = resolvedSecUid)
        }

        // Fetch /api/creator/item_list/ using resolved secUid if not already fetched in parallel above
        var creatorPage = immediateCreatorDeferred?.await()
        if ((creatorPage == null || creatorPage.videos.isEmpty()) && resolvedSecUid.isNotBlank()) {
            creatorPage = runCatching {
                fetchTikTokCreatorItemList(
                    secUid = resolvedSecUid,
                    username = clean,
                    cursor = "0",
                    count = ScraperConfig.PROFILE_CREATOR_PAGE_SIZE,
                    forceNetwork = forceNetwork
                )
            }.getOrNull()
        }

        val fastCanonicalHandle = htmlProfile?.author?.username?.takeIf { it.isNotBlank() }
            ?: mirrorUserInfo?.username?.takeIf { it.isNotBlank() }
            ?: embedProfile?.author?.username?.takeIf { it.isNotBlank() }
            ?: hintAuthor?.username?.takeIf { it.isNotBlank() }
            ?: ""
        val fastAllowed = listOf(clean, fastCanonicalHandle)
            .map { it.trim().removePrefix("@") }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .toSet()

        var creatorVideos = creatorPage?.videos.orEmpty().filter { v ->
            fastAllowed.any { it.equals(v.author.username, ignoreCase = true) } ||
                (resolvedSecUid.isNotBlank() && v.author.secUid == resolvedSecUid) ||
                v.author.username == "creator" ||
                v.author.secUid.isBlank()
        }
        val htmlOwnVideos = htmlProfile?.videos.orEmpty().filter { v ->
            fastAllowed.any { it.equals(v.author.username, ignoreCase = true) } ||
                (resolvedSecUid.isNotBlank() && v.author.secUid == resolvedSecUid) ||
                v.author.username == "creator"
        }
        val initialMirrorVideos = postsPage.videos.filter { v ->
            fastAllowed.any { it.equals(v.author.username, ignoreCase = true) } ||
                (resolvedSecUid.isNotBlank() && v.author.secUid == resolvedSecUid) ||
                v.author.username == "creator"
        }

        val expectedFastVideoCount = maxOf(
            htmlProfile?.author?.videoCount ?: 0L,
            mirrorUserInfo?.videoCount ?: 0L,
            hintAuthor?.videoCount ?: 0L,
            embedProfile?.author?.videoCount ?: 0L
        )
        val preliminaryUniqueCount = (creatorVideos + initialMirrorVideos + htmlOwnVideos).distinctBy { it.id }.size
        val hasActivePaginationSoFar = (creatorPage?.hasMore == true && creatorVideos.isNotEmpty()) ||
            (postsPage.hasMore && initialMirrorVideos.isNotEmpty())

        // Run Googlebot SSR + signed WebView /api/post/item_list/ if initial sources returned 0 videos, unknown total count, or came up short without more pages
        val needWebFallback = preliminaryUniqueCount == 0 ||
            (!hasActivePaginationSoFar && (
                expectedFastVideoCount <= 0L ||
                    preliminaryUniqueCount < expectedFastVideoCount ||
                    preliminaryUniqueCount < 20
                ))

        var gbData: GooglebotProfileData? = null
        var matchedSearchAuthor: Author? = null
        var tagOwnRefs: List<Pair<String, String>> = emptyList()
        var webResp: WebSessionPageResponse? = null

        if (needWebFallback) {
            val gbMobileProfileDeferred = async {
                runCatching { fetchGooglebotProfilePage(profileUrl, clean, forceNetwork) }.getOrNull()
            }
            val userSearchDeferred = async {
                if (resolvedSecUid.isBlank()) fetchUserSearchApi(clean, count = 10) else emptyList()
            }
            val tagOwnRefsDeferred = async {
                if (preliminaryUniqueCount == 0 && compactTag.isNotBlank()) {
                    fetchTagRefsFromHtml(
                        ScraperConfig.TIKTOK_TAG_URL_TEMPLATE.format(urlEncode(compactTag)),
                        forceNetwork = forceNetwork
                    )
                } else emptyList()
            }

            gbData = gbMobileProfileDeferred.await()
            val searchedUsers = userSearchDeferred.await()
            matchedSearchAuthor = searchedUsers.firstOrNull {
                it.username.equals(clean, ignoreCase = true)
            }
            tagOwnRefs = tagOwnRefsDeferred.await()

            if (resolvedSecUid.isBlank() && !matchedSearchAuthor?.secUid.isNullOrBlank()) {
                resolvedSecUid = matchedSearchAuthor!!.secUid
                secUidByUsername[keyLower] = resolvedSecUid
                creatorPage = runCatching {
                    fetchTikTokCreatorItemList(
                        secUid = resolvedSecUid,
                        username = clean,
                        cursor = "0",
                        count = ScraperConfig.PROFILE_CREATOR_PAGE_SIZE,
                        forceNetwork = forceNetwork
                    )
                }.getOrNull()
                creatorVideos = creatorPage?.videos.orEmpty().filter { v ->
                    fastAllowed.any { it.equals(v.author.username, ignoreCase = true) } ||
                        (resolvedSecUid.isNotBlank() && v.author.secUid == resolvedSecUid) ||
                        v.author.username == "creator" ||
                        v.author.secUid.isBlank()
                }
                if (postsPage.videos.isEmpty()) {
                    postsPage = fetchUserPostsMirror(clean, null, secUid = resolvedSecUid)
                }
            }

            val updatedCount = (creatorVideos + postsPage.videos + htmlOwnVideos).distinctBy { it.id }.size
            val updatedExpected = maxOf(expectedFastVideoCount, gbData?.author?.videoCount ?: 0L)
            val stillShort = updatedCount == 0 ||
                (updatedExpected > 0L && updatedCount < updatedExpected && !postsPage.hasMore && creatorPage?.hasMore != true) ||
                (updatedCount < 10 && !postsPage.hasMore && creatorPage?.hasMore != true)

            if (stillShort) {
                webResp = runCatching {
                    webSessionClient.fetchUserPostsPage(
                        username = clean,
                        secUidHint = resolvedSecUid,
                        userIdHint = resolvedUserId,
                        cursor = "0",
                        count = ScraperConfig.PROFILE_WEB_PAGE_SIZE,
                        forceReload = forceNetwork,
                        regionCode = activeRegion.code,
                        timeZone = activeRegion.timeZone
                    )
                }.getOrNull()
            }
        }

        val canonicalHandle = fastCanonicalHandle
            .ifBlank { gbData?.canonicalUsername.orEmpty() }

        val allowedUsernames = listOf(clean, canonicalHandle)
            .map { it.trim().removePrefix("@") }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .toSet()
        canonicalUsernameByQuery[keyLower] = allowedUsernames

        if (webResp != null && webResp.secUid.isNotBlank()) {
            resolvedSecUid = resolvedSecUid.ifBlank { webResp.secUid }
            secUidByUsername[keyLower] = webResp.secUid
        }
        if (webResp != null && webResp.userId.isNotBlank()) {
            userIdByUsername[keyLower] = webResp.userId
        }

        val mirrorOwnVideos = postsPage.videos.filter { v ->
            allowedUsernames.any { it.equals(v.author.username, ignoreCase = true) } ||
                (resolvedSecUid.isNotBlank() && v.author.secUid == resolvedSecUid) ||
                v.author.username == "creator"
        }

        val webRoot = webResp?.rawApiJson?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
        val webAuthor = webResp?.userDetailJson?.takeIf { it.isNotBlank() }?.let { rawDetail ->
            runCatching {
                val ui = JSONObject(rawDetail)
                val u = ui.optJSONObject("user") ?: return@runCatching null
                val stats = ui.optJSONObject("stats")
                val uname = u.optCleanString("uniqueId").ifBlank { clean }
                buildAuthorFromUserJson(u, stats, uname)
            }.getOrNull()
        }

        val effectiveSecUid = resolvedSecUid.ifBlank { webResp?.secUid.orEmpty() }
        val rawWebVideos = webRoot?.optJSONArray("itemList")?.let { arr ->
            parseTikTokJsonArray(arr)
        }.orEmpty()
        val webOwnVideos = rawWebVideos.filter { v ->
            allowedUsernames.any { it.equals(v.author.username, ignoreCase = true) } ||
                (effectiveSecUid.isNotBlank() && v.author.secUid == effectiveSecUid) ||
                v.author.username == "creator"
        }
        val webHasMore = webRoot?.optBoolean("hasMore", false) == true
        val webHasMorePrevious = webRoot?.optBoolean("hasMorePrevious", false) == true
        val webNextCursor = webRoot?.optCleanString("cursor").orEmpty()
        val webMinCreateMs = webOwnVideos.mapNotNull { it.createdAt.takeIf { ts -> ts > 0L } }.minOrNull()
            ?.let { (it * 1000L).toString() }.orEmpty()

        val combinedOwnRefs = LinkedHashMap<String, Pair<String, String>>()
        embedProfile?.ownRefs.orEmpty().forEach { ref ->
            combinedOwnRefs.putIfAbsent(ref.second, ref)
        }
        gbData?.ownRefs.orEmpty().forEach { ref ->
            combinedOwnRefs.putIfAbsent(ref.second, ref)
        }
        webResp?.domVideoRefs.orEmpty().forEach { ref ->
            if (allowedUsernames.any { it.equals(ref.first, ignoreCase = true) }) {
                combinedOwnRefs.putIfAbsent(ref.second, ref)
            }
        }
        tagOwnRefs.forEach { ref ->
            if (allowedUsernames.any { it.equals(ref.first, ignoreCase = true) }) {
                combinedOwnRefs.putIfAbsent(ref.second, ref)
            }
        }
        val allOwnRefs = combinedOwnRefs.values.toList()

        val firstVideoAuthor = (
            webOwnVideos.firstOrNull { it.author.username != "creator" }
                ?: mirrorOwnVideos.firstOrNull { it.author.username != "creator" }
                ?: creatorVideos.firstOrNull { it.author.username != "creator" }
                ?: htmlOwnVideos.firstOrNull()
            )?.author

        val baseAuthor = mirrorUserInfo
            ?: htmlProfile?.author
            ?: webAuthor
            ?: embedProfile?.author
            ?: hintAuthor
            ?: gbData?.author
            ?: matchedSearchAuthor
            ?: Author(id = clean, username = canonicalHandle.ifBlank { clean }, displayName = clean)

        val preliminaryVideoCount = maxOf(
            mirrorUserInfo?.videoCount ?: 0L,
            htmlProfile?.author?.videoCount ?: 0L,
            webAuthor?.videoCount ?: 0L,
            firstVideoAuthor?.videoCount ?: 0L,
            baseAuthor.videoCount,
            gbData?.author?.videoCount ?: 0L
        )

        // Hydrate /embed/ or SSR/DOM refs whenever primary feeds returned empty OR fewer posts than author.videoCount
        val directIds = (webOwnVideos + mirrorOwnVideos + creatorVideos + htmlOwnVideos).map { it.id }.toSet()
        val missingOwnRefs = allOwnRefs.filterNot { directIds.contains(it.second) }
        val shouldHydrateRefs = missingOwnRefs.isNotEmpty() &&
            (directIds.isEmpty() || preliminaryVideoCount <= 0L || directIds.size < preliminaryVideoCount || directIds.size < 20)
        val hydratedOwnVideos = if (shouldHydrateRefs) {
            val initialBatchRefs = missingOwnRefs.take(ScraperConfig.PROFILE_INITIAL_BATCH_SIZE)
            if (missingOwnRefs.size > ScraperConfig.PROFILE_INITIAL_BATCH_SIZE) {
                pendingProfileRefs[keyLower] = missingOwnRefs
            } else {
                pendingProfileRefs.remove(keyLower)
            }
            hydrateVideoRefs(initialBatchRefs)
        } else {
            pendingProfileRefs.remove(keyLower)
            emptyList()
        }

        val resolvedFirstVideoAuthor = firstVideoAuthor
            ?: hydratedOwnVideos.firstOrNull { it.author.username != "creator" }?.author

        val resolvedAuthor = Author(
            id = resolvedFirstVideoAuthor?.id?.ifBlank { baseAuthor.id } ?: baseAuthor.id,
            username = resolvedFirstVideoAuthor?.username?.ifBlank { baseAuthor.username } ?: baseAuthor.username,
            displayName = (mirrorUserInfo?.displayName?.takeIf { it.isNotBlank() && !it.equals(clean, ignoreCase = true) }
                ?: htmlProfile?.author?.displayName?.takeIf { it.isNotBlank() }
                ?: resolvedFirstVideoAuthor?.displayName?.ifBlank { baseAuthor.displayName }
                ?: baseAuthor.displayName),
            avatarUrl = mirrorUserInfo?.avatarUrl?.takeIf { it.isNotBlank() }
                ?: htmlProfile?.author?.avatarUrl?.takeIf { it.isNotBlank() }
                ?: webAuthor?.avatarUrl?.takeIf { it.isNotBlank() }
                ?: embedProfile?.author?.avatarUrl?.takeIf { it.isNotBlank() }
                ?: resolvedFirstVideoAuthor?.avatarUrl?.takeIf { it.isNotBlank() }
                ?: baseAuthor.avatarUrl.takeIf { it.isNotBlank() }
                ?: matchedSearchAuthor?.avatarUrl.orEmpty(),
            bio = mirrorUserInfo?.bio?.takeIf { it.isNotBlank() }
                ?: htmlProfile?.author?.bio?.takeIf { it.isNotBlank() }
                ?: webAuthor?.bio?.takeIf { it.isNotBlank() }
                ?: embedProfile?.author?.bio?.takeIf { it.isNotBlank() }
                ?: resolvedFirstVideoAuthor?.bio?.takeIf { it.isNotBlank() }
                ?: baseAuthor.bio.takeIf { it.isNotBlank() }
                ?: gbData?.author?.bio.orEmpty(),
            verified = (mirrorUserInfo?.verified == true) ||
                (htmlProfile?.author?.verified == true) ||
                (webAuthor?.verified == true) ||
                (embedProfile?.author?.verified == true) ||
                (resolvedFirstVideoAuthor?.verified == true) ||
                baseAuthor.verified,
            secUid = effectiveSecUid
                .ifBlank { resolvedFirstVideoAuthor?.secUid.orEmpty() }
                .ifBlank { baseAuthor.secUid },
            followingCount = maxOf(
                mirrorUserInfo?.followingCount ?: 0L,
                htmlProfile?.author?.followingCount ?: 0L,
                webAuthor?.followingCount ?: 0L,
                embedProfile?.author?.followingCount ?: 0L,
                resolvedFirstVideoAuthor?.followingCount ?: 0L,
                baseAuthor.followingCount,
                gbData?.author?.followingCount ?: 0L
            ),
            followersCount = maxOf(
                mirrorUserInfo?.followersCount ?: 0L,
                htmlProfile?.author?.followersCount ?: 0L,
                webAuthor?.followersCount ?: 0L,
                embedProfile?.author?.followersCount ?: 0L,
                resolvedFirstVideoAuthor?.followersCount ?: 0L,
                baseAuthor.followersCount,
                gbData?.author?.followersCount ?: 0L
            ),
            likesCount = maxOf(
                mirrorUserInfo?.likesCount ?: 0L,
                htmlProfile?.author?.likesCount ?: 0L,
                webAuthor?.likesCount ?: 0L,
                embedProfile?.author?.likesCount ?: 0L,
                resolvedFirstVideoAuthor?.likesCount ?: 0L,
                baseAuthor.likesCount,
                gbData?.author?.likesCount ?: 0L
            ),
            videoCount = maxOf(
                preliminaryVideoCount,
                resolvedFirstVideoAuthor?.videoCount ?: 0L
            )
        )

        if (resolvedAuthor.secUid.isNotBlank()) {
            secUidByUsername[keyLower] = resolvedAuthor.secUid
        }

        val persistedHistory = allowedUsernames.flatMap { uname ->
            prefs.getAuthorVideoHistory(uname)
        }.filter { v ->
            allowedUsernames.any { it.equals(v.author.username, ignoreCase = true) }
        }
        val inMemoryAuthorVideos = allowedUsernames.flatMap { uname ->
            authorVideoMap[uname.lowercase()]?.values?.toList().orEmpty()
        }.filter { v ->
            allowedUsernames.any { it.equals(v.author.username, ignoreCase = true) }
        }

        // Preserve creator timeline order cleanly, preferring richer web/mirror items first
        val combined = LinkedHashMap<String, VideoItem>()
        webOwnVideos.forEach { combined.putIfAbsent(it.id, it) }
        mirrorOwnVideos.forEach { combined.putIfAbsent(it.id, it) }
        creatorVideos.forEach { combined.putIfAbsent(it.id, it) }
        hydratedOwnVideos.forEach { combined.putIfAbsent(it.id, it) }
        htmlOwnVideos.forEach { combined.putIfAbsent(it.id, it) }
        (persistedHistory + inMemoryAuthorVideos)
            .sortedWith(compareByDescending<VideoItem> { it.createdAt }.thenByDescending { it.stats.viewsCount })
            .forEach { combined.putIfAbsent(it.id, it) }

        if (combined.isEmpty()) {
            val searchFallback = runCatching { fetchSearchFeedApi(clean, null)?.videos.orEmpty() }.getOrDefault(emptyList())
            searchFallback.filter { v ->
                allowedUsernames.any { it.equals(v.author.username, ignoreCase = true) } ||
                    (resolvedAuthor.secUid.isNotBlank() && v.author.secUid == resolvedAuthor.secUid)
            }.forEach { combined.putIfAbsent(it.id, it) }

            if (combined.isEmpty() && compactTag.isNotBlank()) {
                val discoverFallback = runCatching {
                    fetchDiscoverWithRelated(
                        ScraperConfig.TIKTOK_DISCOVER_URL_TEMPLATE.format(urlEncode(compactTag))
                    ).videos
                }.getOrDefault(emptyList())
                discoverFallback.filter { v ->
                    allowedUsernames.any { it.equals(v.author.username, ignoreCase = true) } ||
                        (resolvedAuthor.secUid.isNotBlank() && v.author.secUid == resolvedAuthor.secUid)
                }.forEach { combined.putIfAbsent(it.id, it) }
            }

            if (combined.isEmpty()) {
                videoIndex.values.filter { v ->
                    allowedUsernames.any { it.equals(v.author.username, ignoreCase = true) } ||
                        (resolvedAuthor.secUid.isNotBlank() && v.author.secUid == resolvedAuthor.secUid)
                }.forEach { combined.putIfAbsent(it.id, it) }
            }
        }

        val finalVideos = combined.values.map { item ->
            if (item.author.username == "creator" ||
                (item.author.avatarUrl.isBlank() && resolvedAuthor.avatarUrl.isNotBlank())
            ) {
                item.copy(author = resolvedAuthor)
            } else item
        }

        if (finalVideos.isNotEmpty()) {
            prefs.recordAuthorVideos(resolvedAuthor.username, finalVideos)
        }

        // Build chained multi-source pagination queue so partial primary sources continue into secondary ones automatically
        val stageQueue = ArrayDeque<String>()
        val hasCompleteCount = resolvedAuthor.videoCount > 0L && finalVideos.size >= resolvedAuthor.videoCount
        if (webHasMore && webNextCursor.isNotBlank() && webNextCursor != "0" && (webOwnVideos.isNotEmpty() || webRoot != null)) {
            stageQueue.add("wv:$webNextCursor")
        }
        if (mirrorOwnVideos.isNotEmpty() && postsPage.hasMore && !postsPage.nextCursor.isNullOrBlank()) {
            stageQueue.add("mirror:${postsPage.nextCursor}")
        }
        if (creatorVideos.isNotEmpty() && creatorPage?.hasMore == true && !creatorPage.nextCursor.isNullOrBlank()) {
            stageQueue.add("creator:${creatorPage.nextCursor}")
        } else if (webOwnVideos.isNotEmpty() && webHasMorePrevious && webMinCreateMs.isNotBlank()) {
            stageQueue.add("creator:$webMinCreateMs")
        }
        if (shouldHydrateRefs && pendingProfileRefs.containsKey(keyLower)) {
            stageQueue.add("refs_${ScraperConfig.PROFILE_INITIAL_BATCH_SIZE}")
        }
        if (webResp == null && (!hasCompleteCount || finalVideos.isEmpty())) {
            stageQueue.add("wv:0")
        }
        if (mirrorOwnVideos.isEmpty() && !isEndpointBlocked("tikwm_user_posts") && (!hasCompleteCount || finalVideos.isEmpty())) {
            stageQueue.add("mirror:0")
        }
        if (creatorVideos.isEmpty() && resolvedAuthor.secUid.isNotBlank() && (!hasCompleteCount || finalVideos.isEmpty())) {
            stageQueue.add("creator:0")
        }

        val nextCursorValue = if (stageQueue.isNotEmpty()) stageQueue.removeFirst() else null
        if (stageQueue.isNotEmpty()) {
            profileFallbackQueues[keyLower] = stageQueue
        } else {
            profileFallbackQueues.remove(keyLower)
        }
        aliasProfileState(
            primaryKeyLower = keyLower,
            resolvedUsername = resolvedAuthor.username,
            secUid = resolvedAuthor.secUid,
            userId = resolvedAuthor.id,
            allowedUsernames = allowedUsernames
        )

        UserProfileResult(
            author = resolvedAuthor,
            videos = finalVideos,
            nextCursor = nextCursorValue,
            hasMore = nextCursorValue != null
        )
    }

    override suspend fun getComments(
        videoId: String,
        cursor: String?
    ): Result<List<Comment>> = withContext(Dispatchers.IO) {
        val cacheKey = "comments_${videoId}_${cursor ?: "0"}"
        readCachedComments(cacheKey)?.let { return@withContext Result.success(it) }

        runCatching {
            val directComments = fetchTikTokCommentsApi(videoId, cursor)
            if (directComments != null) {
                saveCachedComments(cacheKey, directComments)
                return@runCatching directComments
            }
            if (isEndpointBlocked("tikwm_comments")) {
                return@runCatching emptyList()
            }
            val url = ScraperConfig.API_COMMENT_LIST.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("url", "${ScraperConfig.TIKTOK_WEB_BASE}/video/$videoId")
                ?.addQueryParameter("count", "20")
                ?.addQueryParameter("cursor", cursor ?: "0")
                ?.build()
                ?: throw ScraperException("Invalid comments endpoint URL")
            val root = apiGetJson(url.toString(), endpointKey = "tikwm_comments")
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

    override suspend fun clearFeedCache() {
        withContext(Dispatchers.IO) {
            memoryCache.keys.filter {
                it.startsWith("foryou_") || it.startsWith("cat_") || it.startsWith("unified_search_")
            }.forEach { key ->
                memoryCache.remove(key)
                runCatching { File(scraperCacheDir, "${md5(key)}.json").delete() }
            }
            categoryRefPools.clear()
        }
    }

    override suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val before = getCacheSizeBytes()
        memoryCache.clear()
        videoIndex.clear()
        authorVideoMap.clear()
        blockedEndpointsUntil.clear()
        pendingProfileRefs.clear()
        profileFallbackQueues.clear()
        categoryRefPools.clear()
        secUidByUsername.clear()
        userIdByUsername.clear()
        canonicalUsernameByQuery.clear()
        webSessionClient.clearSession()
        prefs.clearSeenHistory()
        scraperCacheDir.listFiles()?.forEach { it.deleteRecursively() }
        runCatching { okHttpClient.cache?.evictAll() }
        before
    }

    override suspend fun getCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        dirSize(httpCacheDir) + dirSize(scraperCacheDir)
    }

    // -------------------------------------------------------------------------
    // GOOGLEBOT SSR & MOBILE API-DATA HYDRATION HELPERS
    // -------------------------------------------------------------------------

    private fun fetchGooglebotProfilePage(
        url: String,
        requestedUsername: String,
        forceNetwork: Boolean = false
    ): GooglebotProfileData? {
        val html = runCatching {
            executeGooglebotGet(
                url = url,
                userAgent = ScraperConfig.HEADER_GOOGLEBOT_MOBILE_USER_AGENT_VALUE,
                bypassHttpCache = forceNetwork
            )
        }.getOrNull().orEmpty()

        val mobileData = if (html.isNotBlank()) {
            parseGooglebotProfileHtml(html, url, requestedUsername)
        } else null

        if (mobileData != null && mobileData.ownRefs.isNotEmpty()) {
            return mobileData
        }

        // Fallback to Desktop Googlebot UA if Mobile Googlebot returned 0 videos
        val desktopHtml = runCatching {
            executeGooglebotGet(
                url = url,
                userAgent = ScraperConfig.HEADER_GOOGLEBOT_DESKTOP_USER_AGENT_VALUE,
                bypassHttpCache = forceNetwork
            )
        }.getOrNull().orEmpty()

        if (desktopHtml.isBlank()) return mobileData
        val desktopData = parseGooglebotProfileHtml(desktopHtml, url, requestedUsername)
        return if (desktopData.ownRefs.isNotEmpty()) desktopData else (mobileData ?: desktopData)
    }

    private fun parseGooglebotProfileHtml(
        html: String,
        url: String,
        requestedUsername: String
    ): GooglebotProfileData {
        val doc = Jsoup.parse(html, url)

        var canonicalHandle = doc.selectFirst("link[rel=canonical]")
            ?.attr("href")
            ?.substringAfter("/@", "")
            ?.substringBefore("?")
            ?.substringBefore("/")
            ?.trim()
            .orEmpty()

        var displayName = ""
        var bio = ""
        var followersCount = 0L
        var likesCount = 0L
        var followingCount = 0L

        doc.selectFirst(ScraperConfig.SELECTOR_PROFILE_PAGE_LD)?.data()?.let { raw ->
            runCatching {
                val root = JSONObject(raw)
                val entity = root.optJSONObject("mainEntity") ?: root
                val alt = entity.optCleanString("alternateName").removePrefix("@")
                if (canonicalHandle.isBlank() && alt.isNotBlank()) canonicalHandle = alt
                displayName = entity.optCleanString("name")
                bio = entity.optCleanString("description")

                val statsArr = entity.optJSONArray("interactionStatistic")
                if (statsArr != null) {
                    for (i in 0 until statsArr.length()) {
                        val st = statsArr.optJSONObject(i) ?: continue
                        val typeObj = st.opt("interactionType")
                        val typeStr = when (typeObj) {
                            is JSONObject -> typeObj.optCleanString("@type")
                            is String -> typeObj
                            else -> ""
                        }.lowercase()
                        val count = st.optLong("userInteractionCount", 0L)
                        when {
                            typeStr.contains("follow") -> followersCount = maxOf(followersCount, count)
                            typeStr.contains("like") -> likesCount = maxOf(likesCount, count)
                        }
                    }
                }
            }
        }

        if (displayName.isBlank()) {
            displayName = doc.selectFirst(ScraperConfig.SELECTOR_USER_SUBTITLE)?.text()?.trim().orEmpty()
        }
        if (bio.isBlank()) {
            bio = doc.selectFirst(ScraperConfig.SELECTOR_USER_BIO)?.text()?.trim().orEmpty()
        }
        if (followingCount == 0L) {
            followingCount = parseCompactStatText(doc.selectFirst(ScraperConfig.SELECTOR_FOLLOWING_COUNT)?.text())
        }
        if (followersCount == 0L) {
            followersCount = parseCompactStatText(doc.selectFirst(ScraperConfig.SELECTOR_FOLLOWERS_COUNT)?.text())
        }
        if (likesCount == 0L) {
            likesCount = parseCompactStatText(doc.selectFirst(ScraperConfig.SELECTOR_LIKES_COUNT)?.text())
        }

        val resolvedUsername = canonicalHandle.ifBlank { requestedUsername }
        val allowed = setOf(requestedUsername.lowercase(), resolvedUsername.lowercase())
        val ownRefs = extractVideoRefsFromHtml(html, doc, allowedUsernames = allowed)

        val author = Author(
            id = resolvedUsername,
            username = resolvedUsername,
            displayName = displayName.ifBlank { resolvedUsername },
            avatarUrl = "",
            bio = bio,
            followingCount = followingCount,
            followersCount = followersCount,
            likesCount = likesCount
        )
        return GooglebotProfileData(
            canonicalUsername = resolvedUsername,
            author = author,
            ownRefs = ownRefs
        )
    }

    private fun fetchTagRefsFromHtml(
        url: String,
        forceNetwork: Boolean = false
    ): List<Pair<String, String>> {
        val html = runCatching {
            executeGooglebotGet(
                url = url,
                userAgent = ScraperConfig.HEADER_GOOGLEBOT_MOBILE_USER_AGENT_VALUE,
                bypassHttpCache = forceNetwork
            )
        }.getOrNull().orEmpty()
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, url)
        val mobileRefs = extractVideoRefsFromHtml(html, doc, allowedUsernames = null)
        if (mobileRefs.isNotEmpty()) return mobileRefs

        val desktopHtml = runCatching {
            executeGooglebotGet(
                url = url,
                userAgent = ScraperConfig.HEADER_GOOGLEBOT_DESKTOP_USER_AGENT_VALUE,
                bypassHttpCache = forceNetwork
            )
        }.getOrNull().orEmpty()
        if (desktopHtml.isBlank()) return emptyList()
        return extractVideoRefsFromHtml(desktopHtml, Jsoup.parse(desktopHtml, url), allowedUsernames = null)
    }

    private fun extractVideoRefsFromHtml(
        html: String,
        doc: Document,
        allowedUsernames: Set<String>?
    ): List<Pair<String, String>> {
        val refs = LinkedHashMap<String, Pair<String, String>>()

        fun addIfValid(rawUser: String, rawId: String) {
            val u = rawUser.trim().removePrefix("@")
            val id = rawId.trim()
            if (u.isBlank() || id.length < 15) return
            if (allowedUsernames != null && !allowedUsernames.contains(u.lowercase())) return
            refs.putIfAbsent(id, u to id)
        }

        doc.select(ScraperConfig.SELECTOR_VIDEO_CARD_LINKS).forEach { el ->
            val href = el.attr("href").ifBlank { el.absUrl("href") }
            val match = VIDEO_PATH_REGEX.find(href)
            if (match != null) {
                addIfValid(match.groupValues[1], match.groupValues[2])
            }
        }
        VIDEO_PATH_REGEX.findAll(html).forEach { match ->
            addIfValid(match.groupValues[1], match.groupValues[2])
        }
        val unescapedHtml = html.replace("\\/", "/").replace("\\u002F", "/")
        if (unescapedHtml != html) {
            VIDEO_PATH_REGEX.findAll(unescapedHtml).forEach { match ->
                addIfValid(match.groupValues[1], match.groupValues[2])
            }
        }
        return refs.values.toList()
    }

    private suspend fun hydrateVideoRefs(
        refs: List<Pair<String, String>>,
        concurrency: Int = ScraperConfig.HYDRATION_CONCURRENCY
    ): List<VideoItem> = coroutineScope {
        if (refs.isEmpty()) return@coroutineScope emptyList()
        val semaphore = Semaphore(concurrency)
        refs.map { (username, videoId) ->
            async {
                semaphore.withPermit {
                    runCatching { fetchVideoDetailFromMobileHtml(username, videoId) }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun fetchVideoDetailFromMobileHtml(username: String, videoId: String): VideoItem? {
        videoIndex[videoId]?.let { cached ->
            if (cached.videoUrl.isNotBlank()) return cached
        }
        val cleanUser = username.trim().removePrefix("@")
        val url = ScraperConfig.TIKTOK_VIDEO_SHARE_TEMPLATE.format(urlEncode(cleanUser), videoId)

        fun parseDetailHtml(html: String): VideoItem? {
            if (html.isBlank()) return null
            val doc = Jsoup.parse(html, url)

            doc.selectFirst(ScraperConfig.SELECTOR_API_DATA_SCRIPT)?.data()?.let { raw ->
                val parsed = parseApiDataScriptVideos(raw).firstOrNull()
                if (parsed != null) {
                    indexVideos(listOf(parsed))
                    return parsed
                }
            }

            doc.selectFirst(ScraperConfig.SELECTOR_UNIVERSAL_DATA_SCRIPT)?.data()?.let { raw ->
                val parsed = runCatching {
                    val root = JSONObject(raw)
                    val itemStruct = root.optJSONObject(ScraperConfig.JSON_KEY_DEFAULT_SCOPE)
                        ?.optJSONObject("webapp.video-detail")
                        ?.optJSONObject("itemInfo")
                        ?.optJSONObject("itemStruct")
                        ?: return@runCatching null
                    parseSingleTikTokItem(itemStruct, fallbackId = videoId)
                }.getOrNull()
                if (parsed != null) {
                    indexVideos(listOf(parsed))
                    return parsed
                }
            }
            return null
        }

        val cookieHtml = runCatching {
            executeGet(
                url = url,
                acceptJson = false,
                userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
                referer = ScraperConfig.HEADER_REFERER_VALUE
            )
        }.getOrNull().orEmpty()

        parseDetailHtml(cookieHtml)?.let { return it }

        val cookielessHtml = runCatching {
            executeGooglebotGet(
                url = url,
                userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
                bypassHttpCache = true
            )
        }.getOrNull().orEmpty()

        parseDetailHtml(cookielessHtml)?.let { return it }

        val photoUrl = "${ScraperConfig.TIKTOK_WEB_BASE}/@${urlEncode(cleanUser)}/photo/$videoId"
        val photoCookieHtml = runCatching {
            executeGet(
                url = photoUrl,
                acceptJson = false,
                userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
                referer = ScraperConfig.HEADER_REFERER_VALUE
            )
        }.getOrNull().orEmpty()
        parseDetailHtml(photoCookieHtml)?.let { return it }

        val photoCookielessHtml = runCatching {
            executeGooglebotGet(
                url = photoUrl,
                userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
                bypassHttpCache = true
            )
        }.getOrNull().orEmpty()
        return parseDetailHtml(photoCookielessHtml)
    }

    private fun parseCompactStatText(raw: String?): Long {
        val text = raw?.trim()?.uppercase() ?: return 0L
        if (text.isBlank()) return 0L
        return runCatching {
            when {
                text.endsWith("B") -> (text.dropLast(1).toDouble() * 1_000_000_000.0).toLong()
                text.endsWith("M") -> (text.dropLast(1).toDouble() * 1_000_000.0).toLong()
                text.endsWith("K") -> (text.dropLast(1).toDouble() * 1_000.0).toLong()
                else -> text.replace(",", "").toLong()
            }
        }.getOrDefault(0L)
    }

    // -------------------------------------------------------------------------
    // INTERNAL NETWORK & PARSING HELPERS
    // -------------------------------------------------------------------------

    private fun fetchAutocompleteKeywords(cleanQuery: String): List<String> {
        if (cleanQuery.isBlank()) return emptyList()
        return runCatching {
            val url = ScraperConfig.API_SEARCH_SUGGEST.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("client", "firefox")
                ?.addQueryParameter("ds", "yt")
                ?.addQueryParameter("q", "tiktok $cleanQuery")
                ?.build()
                ?.toString()
                ?: return emptyList()
            val raw = executeGet(
                url = url,
                acceptJson = true,
                userAgent = ScraperConfig.HEADER_USER_AGENT_VALUE,
                referer = "https://www.google.com/"
            )
            val arr = JSONArray(raw)
            val suggestions = arr.optJSONArray(1) ?: return emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until suggestions.length()) {
                val cleaned = suggestions.optString(i, "")
                    .replace(Regex("^tiktok\\s+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s+tiktok$", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (cleaned.length >= 2 && !cleaned.equals(cleanQuery, ignoreCase = true)) {
                    list.add(cleaned)
                }
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun fetchTikTokSearchSuggestKeywords(cleanQuery: String): List<String> {
        if (cleanQuery.isBlank()) return emptyList()
        return runCatching {
            val url = ScraperConfig.TIKTOK_SEARCH_SUGGEST_API.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("aid", "1988")
                ?.addQueryParameter("keyword", cleanQuery)
                ?.build()
                ?.toString()
                ?: return emptyList()
            val raw = executeGet(
                url = url,
                acceptJson = true,
                userAgent = ScraperConfig.HEADER_USER_AGENT_VALUE,
                referer = ScraperConfig.HEADER_REFERER_VALUE
            )
            if (raw.isBlank() || isCloudflareChallenge(raw)) return emptyList()
            val sugList = JSONObject(raw).optJSONArray("sug_list") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until sugList.length()) {
                val content = sugList.optJSONObject(i)?.optCleanString("content").orEmpty()
                if (content.isNotBlank()) out.add(content)
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun fetchUserSearchApi(keywords: String, count: Int = 15): List<Author> =
        fetchUserSearchPageApi(keywords, count, "0").users

    private fun fetchUserSearchPageApi(
        keywords: String,
        count: Int = 20,
        cursor: String = "0"
    ): UsersPage {
        if (isEndpointBlocked("tikwm_user_search")) return UsersPage(emptyList())
        return runCatching {
            val url = ScraperConfig.API_USER_SEARCH.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("keywords", keywords)
                ?.addQueryParameter("count", count.toString())
                ?.addQueryParameter("cursor", cursor)
                ?.build()
                ?.toString()
                ?: return UsersPage(emptyList())
            val root = apiGetJson(url, endpointKey = "tikwm_user_search")
            val data = root.optJSONObject("data") ?: return UsersPage(emptyList())
            val userList = data.optJSONArray("user_list") ?: return UsersPage(emptyList())
            val result = ArrayList<Author>(userList.length())
            for (i in 0 until userList.length()) {
                val entry = userList.optJSONObject(i) ?: continue
                val u = entry.optJSONObject("user") ?: entry
                val stats = entry.optJSONObject("stats")
                val uname = u.optCleanString("uniqueId").ifBlank { u.optCleanString("unique_id") }
                if (uname.isBlank()) continue
                result.add(buildAuthorFromUserJson(u, stats, uname))
            }
            val nextC = data.optCleanString("cursor").takeIf { it.isNotBlank() && it != cursor }
            val hasMore = data.optBoolean("hasMore", false) && nextC != null
            UsersPage(users = result, nextCursor = if (hasMore) nextC else null, hasMore = hasMore)
        }.getOrDefault(UsersPage(emptyList()))
    }

    private fun fetchChallengeSearchApi(
        keywords: String,
        count: Int = 20,
        cursor: String = "0"
    ): ChallengeSearchResponse {
        if (isEndpointBlocked("tikwm_challenge_search")) return ChallengeSearchResponse()
        return runCatching {
            val url = ScraperConfig.API_CHALLENGE_SEARCH.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("keywords", keywords)
                ?.addQueryParameter("count", count.toString())
                ?.addQueryParameter("cursor", cursor)
                ?.build()
                ?.toString()
                ?: return ChallengeSearchResponse()
            val root = apiGetJson(url, endpointKey = "tikwm_challenge_search")
            val data = root.optJSONObject("data") ?: return ChallengeSearchResponse()
            val list = data.optJSONArray("challenge_list") ?: return ChallengeSearchResponse()
            val out = ArrayList<ChallengeEntry>(list.length())
            for (i in 0 until list.length()) {
                val c = list.optJSONObject(i) ?: continue
                val id = c.optCleanString("id")
                val name = c.optCleanString("cha_name")
                if (id.isBlank() || name.isBlank()) continue
                out.add(
                    ChallengeEntry(
                        id = id,
                        chaName = name,
                        userCount = c.optInt("user_count", 0),
                        viewCount = c.optLong("view_count", 0L)
                    )
                )
            }
            val nextC = data.optCleanString("cursor")
            val hasMore = data.optBoolean("hasMore", false) && nextC.isNotBlank() && nextC != cursor
            ChallengeSearchResponse(challenges = out, nextCursor = nextC, hasMore = hasMore)
        }.getOrDefault(ChallengeSearchResponse())
    }

    private fun fetchMatchingChallengesForQuery(
        cleanQuery: String,
        tokens: List<String>
    ): ChallengeSearchResponse {
        val compact = cleanQuery.lowercase().replace(Regex("[^\\p{L}0-9_]"), "")
        val variants = linkedSetOf(cleanQuery)
        if (tokens.size >= 3) {
            variants.add(tokens.take(2).joinToString(" "))
            variants.add(tokens.take(2).joinToString(""))
        }
        if (compact.isNotBlank()) {
            variants.add(compact)
        }
        tokens.filter { it.length >= 4 }.take(2).forEach { variants.add(it) }
        val byId = LinkedHashMap<String, ScoredChallenge>()
        var nextCursor = ""
        var hasMore = false
        for ((idx, variant) in variants.take(3).withIndex()) {
            val res = fetchChallengeSearchApi(variant, count = 20, cursor = "0")
            if (idx == 0) {
                nextCursor = res.nextCursor
                hasMore = res.hasMore
            }
            res.challenges.forEach { ch ->
                val s = scoreChallengeMatch(ch.chaName, ch.userCount, tokens, compact)
                val prev = byId[ch.id]
                if (prev == null || s > prev.score) {
                    byId[ch.id] = ScoredChallenge(ch, s)
                }
            }
            val topScore = byId.values.maxOfOrNull { it.score } ?: 0
            if (byId.size >= 8 && topScore >= 90) break
        }
        val sorted = byId.values
            .sortedWith(compareByDescending<ScoredChallenge> { it.score }.thenByDescending { it.entry.userCount })
            .map { it.entry }
        return ChallengeSearchResponse(challenges = sorted, nextCursor = nextCursor, hasMore = hasMore)
    }

    private fun fetchChallengePostsApi(
        challengeId: String,
        cursor: String = "0",
        count: Int = 18
    ): FeedPage {
        if (challengeId.isBlank() || isEndpointBlocked("tikwm_challenge_posts")) {
            return FeedPage(emptyList(), null, false)
        }
        return runCatching {
            val url = ScraperConfig.API_CHALLENGE_POSTS.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("challenge_id", challengeId)
                ?.addQueryParameter("count", count.toString())
                ?.addQueryParameter("cursor", cursor)
                ?.build()
                ?.toString()
                ?: return FeedPage(emptyList(), null, false)
            val root = apiGetJson(url, endpointKey = "tikwm_challenge_posts")
            extractFeedPageFromMirrorJson(root)
        }.getOrDefault(FeedPage(emptyList(), null, false))
    }

    private fun fetchUserInfoMirror(username: String, secUid: String = ""): Author? {
        if (isEndpointBlocked("tikwm_user_info")) return null
        return runCatching {
            val builder = ScraperConfig.API_USER_INFO.toHttpUrlOrNull()?.newBuilder() ?: return null
            if (username.isNotBlank()) builder.addQueryParameter("unique_id", username)
            if (secUid.isNotBlank()) builder.addQueryParameter("sec_uid", secUid)
            val root = apiGetJson(builder.build().toString(), endpointKey = "tikwm_user_info")
            val data = root.optJSONObject("data") ?: return null
            val u = data.optJSONObject("user") ?: data
            val stats = data.optJSONObject("stats")
            val uname = u.optCleanString("uniqueId")
                .ifBlank { u.optCleanString("unique_id") }
                .ifBlank { username }
            buildAuthorFromUserJson(u, stats, uname)
        }.getOrNull()
    }

    private fun fetchUserPostsMirror(username: String, cursor: String?, secUid: String = ""): FeedPage {
        if (isEndpointBlocked("tikwm_user_posts")) return FeedPage(emptyList(), null, false)
        return runCatching {
            val formBuilder = FormBody.Builder()
                .add("count", "35")
                .add("cursor", cursor ?: "0")
            if (username.isNotBlank()) formBuilder.add("unique_id", username)
            if (secUid.isNotBlank()) formBuilder.add("sec_uid", secUid)
            val root = apiPostJson(ScraperConfig.API_USER_POSTS, formBuilder.build(), endpointKey = "tikwm_user_posts")
            extractFeedPageFromMirrorJson(root)
        }.getOrDefault(FeedPage(emptyList(), null, false))
    }

    private fun fetchTikTokCreatorItemList(
        secUid: String,
        username: String,
        cursor: String = "0",
        count: Int = ScraperConfig.PROFILE_CREATOR_PAGE_SIZE,
        forceNetwork: Boolean = false
    ): FeedPage {
        if (secUid.isBlank()) return FeedPage(emptyList(), null, false)
        val safeCount = count.coerceIn(1, ScraperConfig.PROFILE_CREATOR_PAGE_SIZE)
        val url = ScraperConfig.TIKTOK_CREATOR_ITEM_LIST.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("aid", "1988")
            ?.addQueryParameter("secUid", secUid)
            ?.addQueryParameter("count", safeCount.toString())
            ?.addQueryParameter("cursor", cursor)
            ?.addQueryParameter("type", "1")
            ?.build()
            ?.toString()
            ?: return FeedPage(emptyList(), null, false)

        val referer = ScraperConfig.TIKTOK_PROFILE_URL_TEMPLATE.format(urlEncode(username))

        fun tryRequest(userAgent: String, bypassCache: Boolean): FeedPage? {
            return runCatching {
                val body = executeGet(
                    url = url,
                    acceptJson = true,
                    userAgent = userAgent,
                    referer = referer,
                    bypassHttpCache = bypassCache
                )
                if (body.isBlank() || isCloudflareChallenge(body)) return@runCatching null
                val root = JSONObject(body)
                val itemList = root.optJSONArray("itemList") ?: return@runCatching null
                val videos = parseTikTokJsonArray(itemList)
                if (videos.isEmpty()) {
                    val hasPrevEmpty = root.optBoolean("hasMorePrevious", false)
                    return@runCatching FeedPage(emptyList(), null, hasPrevEmpty)
                }
                val hasMorePrev = root.optBoolean("hasMorePrevious", false)
                val hasMoreFlag = root.optBoolean("hasMore", false)
                val apiCursor = root.optCleanString("cursor")
                val minCreateSec = videos.mapNotNull { it.createdAt.takeIf { ts -> ts > 0L } }.minOrNull()
                    ?: videos.lastOrNull()?.createdAt?.takeIf { it > 0L }
                val nextMs = minCreateSec?.let { ts ->
                    val raw = ts * 1000L
                    (if (raw.toString() == cursor) raw - 1L else raw).toString()
                }
                val chosenNext = when {
                    hasMorePrev && !nextMs.isNullOrBlank() && nextMs != cursor -> nextMs
                    hasMoreFlag && apiCursor.isNotBlank() && apiCursor != "0" && apiCursor != cursor -> apiCursor
                    else -> null
                }
                FeedPage(
                    videos = videos,
                    nextCursor = chosenNext,
                    hasMore = chosenNext != null
                )
            }.getOrNull()
        }

        tryRequest(ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE, forceNetwork)?.let { page ->
            if (page.videos.isNotEmpty()) return page
        }

        ensureTikTokSessionCookies(force = true)
        tryRequest(ScraperConfig.HEADER_USER_AGENT_VALUE, true)?.let { page ->
            if (page.videos.isNotEmpty()) return page
        }

        return FeedPage(emptyList(), null, false)
    }

    private fun fetchTikTokPostItemList(secUid: String, cursor: String): List<VideoItem> {
        return runCatching {
            ensureTikTokSessionCookies()
            val url = ScraperConfig.TIKTOK_POST_ITEM_LIST.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("aid", "1988")
                ?.addQueryParameter("count", "30")
                ?.addQueryParameter("secUid", secUid)
                ?.addQueryParameter("cursor", cursor)
                ?.build()
                ?.toString()
                ?: return emptyList()
            val body = executeGet(
                url = url,
                acceptJson = true,
                userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
                referer = ScraperConfig.HEADER_REFERER_VALUE
            )
            if (body.isBlank() || isCloudflareChallenge(body)) return emptyList()
            val itemList = JSONObject(body).optJSONArray("itemList") ?: return emptyList()
            parseTikTokJsonArray(itemList)
        }.getOrDefault(emptyList())
    }

    private fun fetchTikTokExploreApi(
        categoryType: Int,
        cursor: String?,
        region: ContentRegion = prefs.getSettings().contentRegion,
        forceNetwork: Boolean = false
    ): FeedPage? {
        return runCatching {
            ensureTikTokSessionCookies(force = forceNetwork)
            val url = ScraperConfig.TIKTOK_EXPLORE_API.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("aid", "1988")
                ?.addQueryParameter("count", ScraperConfig.DEFAULT_PAGE_SIZE.toString())
                ?.addQueryParameter("categoryType", categoryType.toString())
                ?.addQueryParameter("region", region.code)
                ?.build()
                ?.toString()
                ?: return null
            var body = executeGet(
                url = url,
                acceptJson = true,
                userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
                referer = ScraperConfig.TIKTOK_EXPLORE_URL,
                acceptLanguage = region.acceptLanguage,
                bypassHttpCache = forceNetwork
            )
            var root = runCatching { JSONObject(body) }.getOrNull()
            var itemList = root?.optJSONArray("itemList")
            if (itemList == null || itemList.length() == 0) {
                ensureTikTokSessionCookies(force = true)
                body = executeGet(
                    url = url,
                    acceptJson = true,
                    userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
                    referer = ScraperConfig.TIKTOK_EXPLORE_URL,
                    acceptLanguage = region.acceptLanguage,
                    bypassHttpCache = true
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

    private fun fetchTrendingFeedApi(
        regionCode: String = "BD",
        cursor: String? = null,
        count: Int = ScraperConfig.DEFAULT_PAGE_SIZE,
        forceNetwork: Boolean = false
    ): FeedPage? {
        if (isEndpointBlocked("tikwm_feed_list")) return null
        return runCatching {
            val url = ScraperConfig.API_TRENDING_FEED.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("region", regionCode)
                ?.addQueryParameter("count", count.toString())
                ?.apply {
                    if (!cursor.isNullOrBlank() && cursor != "0" && !cursor.startsWith("explore_") && !cursor.startsWith("tag_")) {
                        addQueryParameter("cursor", cursor)
                    }
                }
                ?.build()
                ?.toString()
                ?: return@runCatching null
            val root = apiGetJson(url, endpointKey = "tikwm_feed_list", bypassHttpCache = forceNetwork)
            extractFeedPageFromMirrorJson(root)
        }.onFailure { lastApiError = it.message ?: it.javaClass.simpleName }.getOrNull()
    }

    private suspend fun fetchRegionTagOrSearchVideos(
        tag: String,
        offset: Int = 0,
        forceNetwork: Boolean = false
    ): List<VideoItem> {
        val searchResult = fetchSearchFeedApi(
            keywords = tag,
            cursor = offset.toString(),
            count = 15
        )?.videos.orEmpty()
        if (searchResult.isNotEmpty()) return searchResult
        val refs = fetchTagRefsFromHtml(
            ScraperConfig.TIKTOK_TAG_URL_TEMPLATE.format(urlEncode(tag)),
            forceNetwork = forceNetwork
        ).drop(offset).take(12)
        return hydrateVideoRefs(refs)
    }

    private fun interleaveWithRatio(
        primary: List<VideoItem>,
        secondary: List<VideoItem>,
        primaryPerBatch: Int = 3
    ): List<VideoItem> {
        if (secondary.isEmpty()) return primary
        if (primary.isEmpty()) return secondary
        val out = ArrayList<VideoItem>(primary.size + secondary.size)
        val seen = HashSet<String>()
        var pIdx = 0
        var sIdx = 0
        while (pIdx < primary.size || sIdx < secondary.size) {
            var addedPrimary = 0
            while (pIdx < primary.size && addedPrimary < primaryPerBatch) {
                val item = primary[pIdx++]
                if (seen.add(item.id)) {
                    out.add(item)
                    addedPrimary++
                }
            }
            if (sIdx < secondary.size) {
                val mixItem = secondary[sIdx++]
                if (seen.add(mixItem.id)) {
                    out.add(mixItem)
                }
            }
        }
        return out
    }

    private fun fetchSearchFeedApi(
        keywords: String,
        cursor: String?,
        count: Int = ScraperConfig.SEARCH_PAGE_SIZE
    ): FeedPage? {
        if (isEndpointBlocked("tikwm_feed_search")) return null
        val cleanCursor = cursor?.takeUnless { it.startsWith("explore_") || it.startsWith("tag_") } ?: "0"
        return runCatching {
            val form = FormBody.Builder()
                .add("keywords", keywords)
                .add("count", count.toString())
                .add("cursor", cleanCursor)
                .add("web", "1")
                .add("hd", "1")
                .build()
            val root = apiPostJson(ScraperConfig.API_SEARCH_FEED, form, endpointKey = "tikwm_feed_search")
            extractFeedPageFromMirrorJson(root)
        }.getOrNull()?.takeIf { it.videos.isNotEmpty() }
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
            if (isCloudflareChallenge(body)) return null
            val commentsArray = JSONObject(body).optJSONArray("comments") ?: return null
            parseTikTokComments(commentsArray)
        }.getOrNull()
    }

    private fun apiPostJson(url: String, form: FormBody, endpointKey: String): JSONObject {
        repeat(2) { attempt ->
            val text = executePost(url, form)
            if (isCloudflareChallenge(text)) {
                markEndpointBlocked(endpointKey)
                throw ScraperException("Endpoint protected by Cloudflare challenge")
            }
            val root = runCatching { JSONObject(text) }
                .getOrElse { throw ScraperException("Invalid JSON response from mirror API") }
            val code = root.optInt("code", -1)
            if (code == 0) return root
            val msg = root.optCleanString("msg")
            if (attempt == 0 && (msg.contains("limit", ignoreCase = true) || msg.contains("frequent", ignoreCase = true))) {
                Thread.sleep(1350L)
                return@repeat
            }
            throw ScraperException("API error ($code): ${msg.ifBlank { "Unknown error" }}")
        }
        throw ScraperException("API rate limit exceeded")
    }

    private fun apiGetJson(
        url: String,
        endpointKey: String,
        bypassHttpCache: Boolean = false
    ): JSONObject {
        repeat(2) { attempt ->
            val text = executeGet(
                url = url,
                acceptJson = true,
                userAgent = ScraperConfig.HEADER_USER_AGENT_VALUE,
                referer = "${ScraperConfig.PUBLIC_API_BASE.removeSuffix("/api")}/",
                bypassHttpCache = bypassHttpCache || attempt > 0
            )
            if (isCloudflareChallenge(text)) {
                markEndpointBlocked(endpointKey)
                throw ScraperException("Endpoint protected by Cloudflare challenge")
            }
            val root = runCatching { JSONObject(text) }
                .getOrElse { throw ScraperException("Invalid JSON response from mirror API") }
            val code = root.optInt("code", -1)
            if (code == 0) return root
            val msg = root.optCleanString("msg")
            if (attempt == 0 && (msg.contains("limit", ignoreCase = true) || msg.contains("frequent", ignoreCase = true))) {
                Thread.sleep(1350L)
                return@repeat
            }
            throw ScraperException("API error ($code): ${msg.ifBlank { "Unknown error" }}")
        }
        throw ScraperException("API rate limit exceeded")
    }

    private fun fetchDiscoverWithRelated(url: String): DiscoverResult {
        val html = executeGet(
            url = url,
            acceptJson = false,
            userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
            referer = ScraperConfig.HEADER_REFERER_VALUE
        )
        val doc: Document = Jsoup.parse(html, url)
        val videos = LinkedHashMap<String, VideoItem>()
        val related = LinkedHashSet<String>()

        doc.selectFirst(ScraperConfig.SELECTOR_API_DATA_SCRIPT)?.data()?.let { raw ->
            parseApiDataScriptVideos(raw).forEach { videos[it.id] = it }
        }
        doc.selectFirst(ScraperConfig.SELECTOR_UNIVERSAL_DATA_SCRIPT)?.data()?.let { raw ->
            parseEmbeddedJsonVideos(raw).forEach { videos.putIfAbsent(it.id, it) }
            runCatching {
                val root = JSONObject(raw)
                val kap = root.optJSONObject(ScraperConfig.JSON_KEY_DEFAULT_SCOPE)
                    ?.optJSONObject("webapp.kap-detail")
                val words = kap?.optJSONArray("relatedWords")
                if (words != null) {
                    for (i in 0 until words.length()) {
                        val w = words.optJSONObject(i)?.optCleanString("word").orEmpty()
                        if (w.isNotBlank()) related.add(w)
                    }
                }
            }
        }
        doc.select("a[href*=/discover/]").forEach { el ->
            val slug = el.attr("href").substringAfter("/discover/").substringBefore("?")
            val phrase = slug.replace("-", " ").trim()
            if (phrase.length > 2) related.add(phrase)
        }
        return DiscoverResult(videos.values.toList(), related.toList())
    }

    private fun fetchAndParseHtmlProfile(
        url: String,
        username: String,
        forceNetwork: Boolean = false
    ): UserProfileResult? {
        fun tryParseProfileHtml(html: String): UserProfileResult? {
            if (html.isBlank() || isCloudflareChallenge(html)) return null
            val doc: Document = Jsoup.parse(html, url)
            val apiDataVideos = doc.selectFirst(ScraperConfig.SELECTOR_API_DATA_SCRIPT)?.data()
                ?.let { parseApiDataScriptVideos(it) }.orEmpty()

            val universalRaw = doc.selectFirst(ScraperConfig.SELECTOR_UNIVERSAL_DATA_SCRIPT)?.data().orEmpty()
            if (universalRaw.isNotBlank()) {
                val parsed = runCatching {
                    val root = JSONObject(universalRaw)
                    val userDetail = root.optJSONObject(ScraperConfig.JSON_KEY_DEFAULT_SCOPE)
                        ?.optJSONObject(ScraperConfig.JSON_KEY_WEBAPP_USER_DETAIL)
                    val userInfo = userDetail?.optJSONObject(ScraperConfig.JSON_KEY_USER_INFO)
                    val userObj = userInfo?.optJSONObject("user") ?: return@runCatching null
                    val statsObj = userInfo.optJSONObject(ScraperConfig.JSON_KEY_STATS)
                        ?: userInfo.optJSONObject("statsV2")
                    val author = buildAuthorFromUserJson(userObj, statsObj, username)
                    val sigiVideos = if (apiDataVideos.isEmpty()) {
                        doc.selectFirst(ScraperConfig.SELECTOR_SIGI_STATE_SCRIPT)?.data()
                            ?.let { parseEmbeddedJsonVideos(it) }.orEmpty()
                    } else emptyList()
                    val videos = apiDataVideos
                        .ifEmpty { parseEmbeddedJsonVideos(universalRaw) }
                        .ifEmpty { sigiVideos }
                    UserProfileResult(author = author, videos = videos, nextCursor = null, hasMore = false)
                }.getOrNull()
                if (parsed != null) return parsed
            }

            // Fallback: extract secUid directly if JSON wrapper was partial
            val fallbackSecUid = SEC_UID_JSON_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?: SEC_UID_TOKEN_REGEX.find(html)?.groupValues?.getOrNull(1).orEmpty()
            if (fallbackSecUid.isNotBlank()) {
                return UserProfileResult(
                    author = Author(id = username, username = username, displayName = username, secUid = fallbackSecUid),
                    videos = apiDataVideos,
                    nextCursor = null,
                    hasMore = false
                )
            }
            return null
        }

        val primaryHtml = runCatching {
            executeGet(
                url = url,
                acceptJson = false,
                userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
                referer = ScraperConfig.HEADER_REFERER_VALUE,
                bypassHttpCache = forceNetwork
            )
        }.getOrNull().orEmpty()

        tryParseProfileHtml(primaryHtml)?.let { return it }

        val cookielessHtml = runCatching {
            executeGooglebotGet(
                url = url,
                userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
                bypassHttpCache = true
            )
        }.getOrNull().orEmpty()

        return tryParseProfileHtml(cookielessHtml)
    }

    private fun fetchEmbedProfilePage(
        username: String,
        forceNetwork: Boolean = false
    ): EmbedProfileData? {
        val clean = username.trim().removePrefix("@")
        if (clean.isBlank()) return null
        val url = ScraperConfig.TIKTOK_EMBED_PROFILE_TEMPLATE.format(urlEncode(clean))
        val html = runCatching {
            executeGet(
                url = url,
                acceptJson = false,
                userAgent = ScraperConfig.HEADER_USER_AGENT_VALUE,
                referer = ScraperConfig.HEADER_REFERER_VALUE,
                bypassHttpCache = forceNetwork
            )
        }.getOrNull()?.takeIf { it.isNotBlank() && !isCloudflareChallenge(it) }
            ?: runCatching {
                executeGooglebotGet(
                    url = url,
                    userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
                    bypassHttpCache = true
                )
            }.getOrNull().orEmpty()
        if (html.isBlank()) return null

        val doc = Jsoup.parse(html, url)
        val rawState = doc.selectFirst(ScraperConfig.SELECTOR_FRONTITY_STATE_SCRIPT)?.data().orEmpty()
        if (rawState.isBlank()) return null

        return runCatching {
            val root = JSONObject(rawState)
            val dataObj = root.optJSONObject("source")?.optJSONObject("data") ?: return@runCatching null
            var profileNode: JSONObject? = dataObj.optJSONObject("/embed/@$clean")
            if (profileNode == null) {
                val keys = dataObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val candidate = dataObj.optJSONObject(key) ?: continue
                    if (candidate.has("userInfo") || candidate.has("videoList")) {
                        profileNode = candidate
                        break
                    }
                }
            }
            val node = profileNode ?: return@runCatching null
            val userInfo = node.optJSONObject("userInfo")
            val resolvedUsername = userInfo?.optCleanString("uniqueId")?.ifBlank { clean } ?: clean
            val author = if (userInfo != null) {
                Author(
                    id = userInfo.optCleanString("id").ifBlank { resolvedUsername },
                    username = resolvedUsername,
                    displayName = userInfo.optCleanString("nickname").ifBlank { resolvedUsername },
                    avatarUrl = normalizeUrl(userInfo.optCleanString("avatarThumbUrl")),
                    bio = userInfo.optCleanString("signature"),
                    verified = userInfo.optBoolean("verified", false),
                    followingCount = userInfo.optLong("followingCount", 0L),
                    followersCount = userInfo.optLong("followerCount", 0L),
                    likesCount = userInfo.optLong("heartCount", 0L)
                )
            } else {
                Author(id = resolvedUsername, username = resolvedUsername, displayName = resolvedUsername)
            }

            val refs = LinkedHashMap<String, Pair<String, String>>()
            val videoList = node.optJSONArray("videoList")
            if (videoList != null) {
                for (i in 0 until videoList.length()) {
                    val item = videoList.optJSONObject(i) ?: continue
                    val vid = item.optCleanString("id")
                    if (vid.length >= 15) {
                        refs.putIfAbsent(vid, resolvedUsername to vid)
                    }
                }
            }
            val embedSecUid = SEC_UID_TOKEN_REGEX.find(rawState)?.groupValues?.getOrNull(1).orEmpty()
            EmbedProfileData(
                author = author,
                secUid = embedSecUid,
                ownRefs = refs.values.toList()
            )
        }.getOrNull()
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
        } else null
        val hasMore = when {
            data !is JSONObject -> videos.isNotEmpty()
            data.has("hasMore") -> data.optBoolean("hasMore", videos.isNotEmpty())
            data.has("has_more") -> data.optBoolean("has_more", videos.isNotEmpty())
            else -> videos.isNotEmpty()
        }
        return FeedPage(videos = videos, nextCursor = nextCursor, hasMore = hasMore)
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
                    .ifBlank { userObj.optCleanString("avatar") }
            ),
            bio = userObj.optCleanString("signature"),
            verified = userObj.optBoolean("verified", false),
            secUid = userObj.optCleanString("secUid").ifBlank { userObj.optCleanString("sec_uid") },
            followingCount = statsObj?.optLong("followingCount", 0L)
                ?.takeIf { it > 0L } ?: userObj.optLong("following_count", 0L),
            followersCount = statsObj?.optLong("followerCount", 0L)
                ?.takeIf { it > 0L } ?: userObj.optLong("follower_count", 0L),
            likesCount = (statsObj?.optLong("heartCount", 0L) ?: 0L).takeIf { it > 0L }
                ?: statsObj?.optLong("heart", 0L)
                ?: userObj.optLong("total_favorited", 0L),
            videoCount = statsObj?.optLong("videoCount", 0L)
                ?.takeIf { it > 0L } ?: userObj.optLong("aweme_count", 0L)
        )
    }

    private fun parseApiDataScriptVideos(rawJson: String): List<VideoItem> {
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(rawJson)
            root.optJSONObject("videoDetail")
                ?.optJSONObject("itemInfo")
                ?.optJSONObject("itemStruct")
                ?.let { itemStruct ->
                    parseSingleTikTokItem(itemStruct, fallbackId = itemStruct.optCleanString("id"))
                        ?.let { return@runCatching listOf(it) }
                }
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

    private fun extractImagePostUrls(obj: JSONObject): List<String> {
        val imagePost = obj.optJSONObject("imagePost") ?: obj.optJSONObject("image_post_info")
        val imagesArr = imagePost?.optJSONArray("images") ?: return emptyList()
        val urls = mutableListOf<String>()
        for (i in 0 until imagesArr.length()) {
            val raw = imagesArr.opt(i) ?: continue
            if (raw is String) {
                val u = normalizeUrl(raw)
                if (u.startsWith("http")) urls.add(u)
                continue
            }
            val imgObj = raw as? JSONObject ?: continue
            val urlHolder = imgObj.optJSONObject("imageURL")
                ?: imgObj.optJSONObject("image_url")
                ?: imgObj.optJSONObject("displayImage")
                ?: imgObj.optJSONObject("display_image")
                ?: imgObj
            val arr = urlHolder.optJSONArray("urlList")
                ?: urlHolder.optJSONArray("url_list")
                ?: urlHolder.optJSONArray("UrlList")
            if (arr != null) {
                for (j in 0 until arr.length()) {
                    val u = normalizeUrl(arr.optString(j, ""))
                    if (u.startsWith("http")) {
                        urls.add(u)
                        break
                    }
                }
            }
        }
        return urls.distinct()
    }

    private fun parseSingleTikTokItem(obj: JSONObject, fallbackId: String): VideoItem? {
        val id = obj.optCleanString("id")
            .ifBlank { obj.optCleanString("aweme_id") }
            .ifBlank { fallbackId }
        val videoObj = obj.optJSONObject("video")
        val imageUrls = extractImagePostUrls(obj)
        if (videoObj == null && imageUrls.isEmpty()) return null

        val h264Urls = mutableListOf<String>()
        val otherBitrateUrls = mutableListOf<String>()
        val bitrateInfo = videoObj?.optJSONArray("bitrateInfo") ?: videoObj?.optJSONArray("bit_rate")
        if (bitrateInfo != null) {
            for (b in 0 until bitrateInfo.length()) {
                val entry = bitrateInfo.optJSONObject(b) ?: continue
                val codec = entry.optCleanString("CodecType")
                    .ifBlank { entry.optCleanString("gear_name") }
                    .lowercase()
                val playAddrObj = entry.optJSONObject("PlayAddr") ?: entry.optJSONObject("play_addr")
                val urlArr = playAddrObj?.optJSONArray("UrlList")
                    ?: playAddrObj?.optJSONArray("urlList")
                    ?: playAddrObj?.optJSONArray("url_list")
                val firstUrl = urlArr?.optString(0)
                    ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                    .orEmpty()
                if (firstUrl.isNotBlank()) {
                    if (codec.contains("h264") || codec.contains("avc") || entry.optInt("is_bytevc1", 0) == 0) {
                        h264Urls.add(firstUrl)
                    } else {
                        otherBitrateUrls.add(firstUrl)
                    }
                }
            }
        }
        val playAddr = if (videoObj != null) {
            normalizeUrl(
                extractVideoMediaUrl(videoObj, "playAddr")
                    .ifBlank { extractVideoMediaUrl(videoObj, "PlayAddrStruct") }
                    .ifBlank { extractVideoMediaUrl(videoObj, "play_addr") }
            )
        } else ""
        val downloadAddr = if (videoObj != null) {
            normalizeUrl(
                extractVideoMediaUrl(videoObj, "downloadAddr")
                    .ifBlank { extractVideoMediaUrl(videoObj, "download_addr") }
            )
        } else ""
        val primaryUrl = h264Urls.firstOrNull()
            ?: playAddr.ifBlank { downloadAddr }.ifBlank { otherBitrateUrls.firstOrNull().orEmpty() }
        if (primaryUrl.isBlank() && imageUrls.isEmpty()) return null

        val authorObj = obj.optJSONObject("author")
        val authorStatsObj = obj.optJSONObject("authorStats") ?: obj.optJSONObject("author_stats")
        val username = authorObj?.optCleanString("uniqueId").orEmpty()
            .ifBlank { authorObj?.optCleanString("unique_id").orEmpty() }
            .ifBlank { "creator" }
        val displayName = authorObj?.optCleanString("nickname").orEmpty().ifBlank { username }
        val musicObj = obj.optJSONObject("music") ?: obj.optJSONObject("music_info")
        val statsObj = obj.optJSONObject("stats") ?: obj.optJSONObject("statistics")
        val desc = obj.optCleanString("desc").ifBlank { obj.optCleanString("title") }
        val musicPlayUrl = normalizeUrl(
            musicObj?.let { m ->
                extractVideoMediaUrl(m, "playUrl")
                    .ifBlank { extractVideoMediaUrl(m, "play_url") }
                    .ifBlank { extractVideoMediaUrl(m, "play") }
            }.orEmpty()
        )
        val effectiveAudioUrl = if (imageUrls.isNotEmpty()) {
            musicPlayUrl.ifBlank { primaryUrl }
        } else {
            musicPlayUrl
        }
        val imagePostCover = obj.optJSONObject("imagePost")?.optJSONObject("cover")?.let { c ->
            val arr = c.optJSONObject("imageURL")?.optJSONArray("urlList")
                ?: c.optJSONArray("urlList")
                ?: c.optJSONArray("url_list")
            arr?.optString(0, "").orEmpty()
        }.orEmpty()

        val rawCover = if (videoObj != null) {
            extractVideoMediaUrl(videoObj, "originCover")
                .ifBlank { extractVideoMediaUrl(videoObj, "origin_cover") }
                .ifBlank { extractVideoMediaUrl(videoObj, "cover") }
                .ifBlank { extractVideoMediaUrl(videoObj, "dynamicCover") }
                .ifBlank { extractVideoMediaUrl(videoObj, "dynamic_cover") }
        } else ""

        val avatarUrl = normalizeUrl(
            authorObj?.let { a ->
                extractVideoMediaUrl(a, "avatarLarger")
                    .ifBlank { extractVideoMediaUrl(a, "avatarMedium") }
                    .ifBlank { extractVideoMediaUrl(a, "avatarThumb") }
                    .ifBlank { extractVideoMediaUrl(a, "avatar_larger") }
                    .ifBlank { extractVideoMediaUrl(a, "avatar_medium") }
                    .ifBlank { extractVideoMediaUrl(a, "avatar_thumb") }
            }.orEmpty()
        )

        return VideoItem(
            id = id,
            videoUrl = if (imageUrls.isNotEmpty()) effectiveAudioUrl else primaryUrl,
            hdVideoUrl = if (imageUrls.isNotEmpty()) effectiveAudioUrl else (h264Urls.firstOrNull() ?: playAddr.ifBlank { primaryUrl }),
            sdVideoUrl = if (imageUrls.isNotEmpty()) effectiveAudioUrl else (h264Urls.lastOrNull() ?: downloadAddr.ifBlank { playAddr }.ifBlank { primaryUrl }),
            coverUrl = normalizeUrl(rawCover.ifBlank { imagePostCover }.ifBlank { imageUrls.firstOrNull().orEmpty() }),
            caption = stripTrailingHashtags(desc).ifBlank { desc },
            hashtags = extractHashtags(desc),
            author = Author(
                id = authorObj?.optCleanString("id")
                    ?.ifBlank { authorObj.optCleanString("uid") }
                    .orEmpty().ifBlank { username },
                username = username,
                displayName = displayName,
                avatarUrl = avatarUrl,
                bio = authorObj?.optCleanString("signature").orEmpty(),
                verified = authorObj?.optBoolean("verified", false) == true ||
                    authorObj?.optInt("verification_type", 0) == 1,
                secUid = authorObj?.optCleanString("secUid").orEmpty()
                    .ifBlank { authorObj?.optCleanString("sec_uid").orEmpty() },
                followingCount = authorStatsObj?.optLong("followingCount", 0L)?.takeIf { it > 0L }
                    ?: authorObj?.optLong("followingCount", 0L)?.takeIf { it > 0L }
                    ?: authorObj?.optLong("following_count", 0L) ?: 0L,
                followersCount = authorStatsObj?.optLong("followerCount", 0L)?.takeIf { it > 0L }
                    ?: authorObj?.optLong("followerCount", 0L)?.takeIf { it > 0L }
                    ?: authorObj?.optLong("follower_count", 0L) ?: 0L,
                likesCount = authorStatsObj?.optLong("heartCount", 0L)?.takeIf { it > 0L }
                    ?: authorStatsObj?.optLong("heart", 0L)?.takeIf { it > 0L }
                    ?: authorObj?.optLong("heartCount", 0L)?.takeIf { it > 0L }
                    ?: authorObj?.optLong("total_favorited", 0L) ?: 0L,
                videoCount = authorStatsObj?.optLong("videoCount", 0L)?.takeIf { it > 0L }
                    ?: authorObj?.optLong("videoCount", 0L)?.takeIf { it > 0L }
                    ?: authorObj?.optLong("aweme_count", 0L) ?: 0L
            ),
            music = MusicInfo(
                id = musicObj?.optCleanString("id").orEmpty(),
                title = musicObj?.optCleanString("title").orEmpty().ifBlank { "Original Sound" },
                author = musicObj?.optCleanString("authorName").orEmpty()
                    .ifBlank { musicObj?.optCleanString("author").orEmpty() },
                coverUrl = normalizeUrl(
                    musicObj?.let { m ->
                        extractVideoMediaUrl(m, "coverLarge")
                            .ifBlank { extractVideoMediaUrl(m, "coverMedium") }
                            .ifBlank { extractVideoMediaUrl(m, "cover_large") }
                            .ifBlank { extractVideoMediaUrl(m, "cover_thumb") }
                    }.orEmpty()
                )
            ),
            stats = VideoStats(
                likesCount = statsObj?.optLong("diggCount", 0L)?.takeIf { it > 0L }
                    ?: statsObj?.optLong("digg_count", 0L) ?: 0L,
                commentsCount = statsObj?.optLong("commentCount", 0L)?.takeIf { it > 0L }
                    ?: statsObj?.optLong("comment_count", 0L) ?: 0L,
                sharesCount = statsObj?.optLong("shareCount", 0L)?.takeIf { it > 0L }
                    ?: statsObj?.optLong("share_count", 0L) ?: 0L,
                viewsCount = statsObj?.optLong("playCount", 0L)?.takeIf { it > 0L }
                    ?: statsObj?.optLong("play_count", 0L) ?: 0L
            ),
            shareUrl = if (imageUrls.isNotEmpty()) {
                "${ScraperConfig.TIKTOK_WEB_BASE}/@$username/photo/$id"
            } else {
                ScraperConfig.TIKTOK_VIDEO_SHARE_TEMPLATE.format(username, id)
            },
            createdAt = obj.optLong("createTime", 0L).takeIf { it > 0L } ?: obj.optLong("create_time", 0L),
            imageUrls = imageUrls,
            audioUrl = effectiveAudioUrl
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
            val imagesArr = obj.optJSONArray("images")
            val imageUrls = if (imagesArr != null && imagesArr.length() > 0) {
                (0 until imagesArr.length()).mapNotNull { idx ->
                    val u = normalizeUrl(imagesArr.optString(idx, ""))
                    u.takeIf { it.startsWith("http") }
                }.distinct()
            } else emptyList()
            if (id.isBlank() || (playable.isBlank() && imageUrls.isEmpty())) continue
            val authorObj = obj.optJSONObject("author")
            val username = authorObj?.optCleanString("unique_id").orEmpty()
                .ifBlank { authorObj?.optCleanString("uniqueId").orEmpty() }
                .ifBlank { "creator" }
            val displayName = authorObj?.optCleanString("nickname").orEmpty().ifBlank { username }
            val musicInfoObj = obj.optJSONObject("music_info")
            val musicPlay = normalizeUrl(
                musicInfoObj?.optCleanString("play").orEmpty()
                    .ifBlank { obj.optCleanString("music") }
            )
            val audioTrack = if (imageUrls.isNotEmpty()) musicPlay.ifBlank { playable } else musicPlay
            val rawCaption = obj.optCleanString("title").ifBlank { obj.optCleanString("desc") }
            val rawCover = normalizeUrl(
                obj.optCleanString("origin_cover")
                    .ifBlank { obj.optCleanString("cover") }
                    .ifBlank { obj.optCleanString("ai_dynamic_cover") }
            ).ifBlank { imageUrls.firstOrNull().orEmpty() }
            result.add(
                VideoItem(
                    id = id,
                    videoUrl = if (imageUrls.isNotEmpty()) audioTrack else playable,
                    hdVideoUrl = if (imageUrls.isNotEmpty()) audioTrack else hdPlay.ifBlank { playable },
                    sdVideoUrl = if (imageUrls.isNotEmpty()) audioTrack else wmPlay.ifBlank { playable },
                    coverUrl = rawCover,
                    caption = stripTrailingHashtags(rawCaption).ifBlank { rawCaption },
                    hashtags = extractHashtags(rawCaption),
                    author = Author(
                        id = authorObj?.optCleanString("id").orEmpty().ifBlank { username },
                        username = username,
                        displayName = displayName,
                        avatarUrl = normalizeUrl(
                            authorObj?.optCleanString("avatar").orEmpty()
                                .ifBlank { authorObj?.optCleanString("avatar_thumb").orEmpty() }
                        ),
                        verified = authorObj?.optInt("verified", 0) == 1 ||
                            authorObj?.optBoolean("verified", false) == true
                    ),
                    music = MusicInfo(
                        id = musicInfoObj?.optCleanString("id").orEmpty(),
                        title = musicInfoObj?.optCleanString("title")
                            ?.ifBlank { obj.optCleanString("music") }
                            .orEmpty()
                            .ifBlank { "Original Sound - $displayName" },
                        author = musicInfoObj?.optCleanString("author").orEmpty().ifBlank { displayName },
                        coverUrl = normalizeUrl(musicInfoObj?.optCleanString("cover").orEmpty())
                    ),
                    stats = VideoStats(
                        likesCount = obj.optLong("digg_count", 0L),
                        commentsCount = obj.optLong("comment_count", 0L),
                        sharesCount = obj.optLong("share_count", 0L),
                        viewsCount = obj.optLong("play_count", 0L)
                    ),
                    shareUrl = if (imageUrls.isNotEmpty()) {
                        "${ScraperConfig.TIKTOK_WEB_BASE}/@$username/photo/$id"
                    } else {
                        ScraperConfig.TIKTOK_VIDEO_SHARE_TEMPLATE.format(username, id)
                    },
                    createdAt = obj.optLong("create_time", 0L),
                    imageUrls = imageUrls,
                    audioUrl = audioTrack
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
            if (itemModule != null) {
                val list = mutableListOf<VideoItem>()
                val keys = itemModule.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = itemModule.optJSONObject(key) ?: continue
                    parseSingleTikTokItem(obj, fallbackId = key)?.let { list.add(it) }
                }
                return@runCatching list
            }
            val exploreItems = root.optJSONObject(ScraperConfig.JSON_KEY_DEFAULT_SCOPE)
                ?.optJSONObject(ScraperConfig.JSON_KEY_WEBAPP_EXPLORE)
                ?.optJSONArray("itemList")
            if (exploreItems != null) return@runCatching parseTikTokJsonArray(exploreItems)
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
                ?.optJSONArray("url_list")?.optString(0).orEmpty()
            val uniqueId = userObj?.optCleanString("unique_id").orEmpty().ifBlank { "user" }
            list.add(
                Comment(
                    id = obj.optCleanString("cid").ifBlank { "$i-${text.hashCode()}" },
                    authorName = userObj?.optCleanString("nickname").orEmpty().ifBlank { uniqueId },
                    authorUsername = uniqueId,
                    authorAvatarUrl = normalizeUrl(avatarThumb),
                    text = text,
                    likesCount = obj.optLong("digg_count", 0L)
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
            val uniqueId = userObj?.optCleanString("unique_id").orEmpty().ifBlank { "user" }
            list.add(
                Comment(
                    id = obj.optCleanString("id").ifBlank { "$i-${text.hashCode()}" },
                    authorName = userObj?.optCleanString("nickname").orEmpty().ifBlank { uniqueId },
                    authorUsername = uniqueId,
                    authorAvatarUrl = normalizeUrl(userObj?.optCleanString("avatar").orEmpty()),
                    text = text,
                    likesCount = obj.optLong("digg_count", 0L),
                    createdAtText = obj.optCleanString("create_time_str")
                )
            )
        }
        return list
    }

    private fun executeGooglebotGet(
        url: String,
        userAgent: String,
        bypassHttpCache: Boolean = false
    ): String {
        val builder = Request.Builder()
            .url(url)
            .header(ScraperConfig.HEADER_USER_AGENT_KEY, userAgent)
            .header(ScraperConfig.HEADER_ACCEPT_LANGUAGE_KEY, ScraperConfig.HEADER_ACCEPT_LANGUAGE_VALUE)
            .header(ScraperConfig.HEADER_ACCEPT_KEY, ScraperConfig.HEADER_ACCEPT_HTML)
            .get()
        if (bypassHttpCache) {
            builder.cacheControl(CacheControl.FORCE_NETWORK)
        }
        googlebotHttpClient.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ScraperException("HTTP ${response.code} fetching $url")
            if (body.isBlank()) throw ScraperException("Empty response from $url")
            return body
        }
    }

    private fun executeGet(
        url: String,
        acceptJson: Boolean,
        userAgent: String = ScraperConfig.HEADER_USER_AGENT_VALUE,
        referer: String = ScraperConfig.HEADER_REFERER_VALUE,
        acceptLanguage: String = prefs.getSettings().contentRegion.acceptLanguage,
        bypassHttpCache: Boolean = false
    ): String {
        if (url.startsWith(ScraperConfig.PUBLIC_API_BASE)) throttleApi()
        val builder = Request.Builder()
            .url(url)
            .header(ScraperConfig.HEADER_USER_AGENT_KEY, userAgent)
            .header(ScraperConfig.HEADER_ACCEPT_LANGUAGE_KEY, acceptLanguage)
            .header(ScraperConfig.HEADER_REFERER_KEY, referer)
            .header(
                ScraperConfig.HEADER_ACCEPT_KEY,
                if (acceptJson) ScraperConfig.HEADER_ACCEPT_JSON else ScraperConfig.HEADER_ACCEPT_HTML
            )
            .get()
        if (bypassHttpCache) {
            builder.cacheControl(CacheControl.FORCE_NETWORK)
        }
        okHttpClient.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 403 && isCloudflareChallenge(body)) return body
            if (!response.isSuccessful) throw ScraperException("HTTP ${response.code} fetching $url")
            if (body.isBlank()) throw ScraperException("Empty response from $url")
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
            .cacheControl(CacheControl.FORCE_NETWORK)
            .post(formBody)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 403 && isCloudflareChallenge(body)) return body
            if (!response.isSuccessful) throw ScraperException("HTTP ${response.code} posting to $url")
            if (body.isBlank()) throw ScraperException("Empty response from $url")
            return body
        }
    }

    private fun indexVideos(videos: List<VideoItem>) {
        videos.forEach { video ->
            videoIndex[video.id] = video
            val uname = video.author.username.lowercase().trim()
            if (uname.isNotBlank()) {
                val map = authorVideoMap.getOrPut(uname) { ConcurrentHashMap() }
                map[video.id] = video
            }
        }
    }

    private fun scoreChallengeMatch(
        chaName: String,
        userCount: Int,
        tokens: List<String>,
        compactQuery: String
    ): Int {
        val name = chaName.lowercase()
        var score = 0
        if (name == compactQuery) score += 200
        if (compactQuery.isNotBlank() && name.contains(compactQuery)) score += 100
        var matched = 0
        tokens.forEach { t ->
            if (name.contains(t)) {
                matched++
                score += 45
            }
        }
        if (tokens.size > 1 && matched == tokens.size) score += 90
        if (userCount >= 1000) score += 20
        else if (userCount >= 50) score += 10
        else if (userCount == 0) score -= 25
        return score
    }

    private fun scoreVideoMatch(
        video: VideoItem,
        tokens: List<String>,
        rawQuery: String,
        isDirectSearch: Boolean = false
    ): Int {
        val qLower = rawQuery.lowercase()
        val compactQ = qLower.replace(Regex("\\s+"), "")
        val caption = video.caption.lowercase()
        val hashtags = video.hashtags.joinToString(" ").lowercase()
        val uname = video.author.username.lowercase()
        val dname = video.author.displayName.lowercase()
        val music = video.music.displayText.lowercase()
        val haystack = "$caption $hashtags $uname $dname $music"

        var score = if (isDirectSearch) 70 else 0
        if (uname == qLower || dname == qLower) score += 220
        if (caption.contains(qLower) || (compactQ.isNotBlank() && hashtags.contains(compactQ))) score += 130
        var matchedTokens = 0
        tokens.forEach { t ->
            if (haystack.contains(t)) matchedTokens++
            if (uname.contains(t) || dname.contains(t)) score += 35
            if (hashtags.contains(t)) score += 30
            if (caption.contains(t)) score += 28
            if (music.contains(t)) score += 12
        }
        if (tokens.size > 1 && matchedTokens == tokens.size) score += 150
        else if (tokens.size >= 2 && matchedTokens >= 2) score += 60
        return score
    }

    private fun parseWebSearchVideos(res: WebSearchSessionResponse): ParsedWebSearchResult {
        return parseWebSearchResponse(res)
    }

    private fun parseWebSearchResponse(res: WebSearchSessionResponse): ParsedWebSearchResult {
        val videos = LinkedHashMap<String, VideoItem>()
        val users = LinkedHashMap<String, Author>()
        val hashtags = LinkedHashMap<String, HashtagResult>()
        val liveStreams = LinkedHashMap<String, LiveStreamItem>()
        var nextOffset = -1
        var hasMore = false

        fun parseSearchJsonPayload(rawJson: String) {
            if (rawJson.isBlank()) return
            runCatching {
                val root = JSONObject(rawJson)
                val cursorVal = root.optInt("cursor", -1)
                    .takeIf { it >= 0 }
                    ?: root.optJSONObject("extra")?.optInt("cursor", -1)
                    ?: -1
                val moreFlag = root.optInt("has_more", 0) == 1 ||
                    root.optBoolean("has_more", false) ||
                    root.optInt("hasMore", 0) == 1 ||
                    root.optBoolean("hasMore", false)
                if (moreFlag) {
                    hasMore = true
                    if (cursorVal > nextOffset) {
                        nextOffset = cursorVal
                    }
                }

                root.optJSONArray("item_list")?.let { arr ->
                    parseTikTokJsonArray(arr).forEach { videos.putIfAbsent(it.id, it) }
                }
                root.optJSONArray("aweme_list")?.let { arr ->
                    parseTikTokJsonArray(arr).forEach { videos.putIfAbsent(it.id, it) }
                }

                root.optJSONArray("data")?.let { dataArr ->
                    for (i in 0 until dataArr.length()) {
                        val entry = dataArr.optJSONObject(i) ?: continue
                        val itemObj = entry.optJSONObject("item")
                            ?: entry.optJSONObject("aweme_info")
                            ?: entry.optJSONObject("common")?.optJSONObject("item")
                            ?: entry.takeIf { it.has("video") && (it.has("id") || it.has("aweme_id")) }
                        if (itemObj != null) {
                            parseSingleTikTokItem(
                                itemObj,
                                fallbackId = itemObj.optCleanString("id").ifBlank { itemObj.optCleanString("aweme_id") }
                            )?.let {
                                videos.putIfAbsent(it.id, it)
                            }
                        }
                        val nestedArr = entry.optJSONArray("item_list")
                            ?: entry.optJSONArray("items")
                            ?: entry.optJSONArray("aweme_list")
                        if (nestedArr != null) {
                            parseTikTokJsonArray(nestedArr).forEach { videos.putIfAbsent(it.id, it) }
                        }
                        entry.optJSONArray("user_list")?.let { uArr ->
                            for (uIdx in 0 until uArr.length()) {
                                val uEntry = uArr.optJSONObject(uIdx) ?: continue
                                val userInfo = uEntry.optJSONObject("user_info") ?: uEntry.optJSONObject("user") ?: uEntry
                                val uname = userInfo.optCleanString("unique_id").ifBlank { userInfo.optCleanString("uniqueId") }
                                if (uname.isNotBlank()) {
                                    users.putIfAbsent(uname.lowercase(), buildAuthorFromUserJson(userInfo, uEntry.optJSONObject("stats"), uname))
                                }
                            }
                        }
                        entry.optJSONArray("challenge_list")?.let { cArr ->
                            for (cIdx in 0 until cArr.length()) {
                                val cObj = cArr.optJSONObject(cIdx) ?: continue
                                val cInfo = cObj.optJSONObject("challenge_info") ?: cObj
                                val chaName = cInfo.optCleanString("cha_name")
                                if (chaName.isNotBlank()) {
                                    val tag = "#$chaName"
                                    val vc = cInfo.optLong("view_count", 0L)
                                    hashtags.putIfAbsent(
                                        tag.lowercase(),
                                        HashtagResult(
                                            tag = tag,
                                            viewCountText = "${formatCompactCount(vc)} views",
                                            videoCount = cInfo.optInt("user_count", 0),
                                            challengeId = cInfo.optCleanString("cid").ifBlank { cInfo.optCleanString("id") },
                                            viewCount = vc
                                        )
                                    )
                                }
                            }
                        }
                        val liveRoom = entry.optJSONObject("live_info") ?: entry.optJSONObject("room")
                        if (liveRoom != null) {
                            val roomId = liveRoom.optCleanString("room_id").ifBlank { liveRoom.optCleanString("id") }
                            val ownerObj = liveRoom.optJSONObject("owner") ?: entry.optJSONObject("author")
                            val hostName = ownerObj?.optCleanString("unique_id").orEmpty().ifBlank { ownerObj?.optCleanString("uniqueId").orEmpty() }
                            if (roomId.isNotBlank() && hostName.isNotBlank()) {
                                val host = buildAuthorFromUserJson(ownerObj ?: JSONObject(), null, hostName)
                                liveStreams.putIfAbsent(
                                    roomId,
                                    LiveStreamItem(
                                        id = "live_$roomId",
                                        roomId = roomId,
                                        title = liveRoom.optCleanString("title").ifBlank { "${host.displayName} LIVE" },
                                        host = host,
                                        viewerCount = liveRoom.optLong("user_count", 0L),
                                        coverUrl = normalizeUrl(liveRoom.optCleanString("cover_url"))
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        parseSearchJsonPayload(res.rawGeneralJson)
        parseSearchJsonPayload(res.rawItemJson)
        parseSearchJsonPayload(res.rawLiveJson)

        if (hasMore && nextOffset <= 0 && videos.isNotEmpty()) {
            nextOffset = videos.size
        }

        return ParsedWebSearchResult(
            videos = videos.values.toList(),
            users = users.values.toList(),
            hashtags = hashtags.values.toList(),
            liveStreams = liveStreams.values.toList(),
            nextOffset = nextOffset,
            hasMore = hasMore
        )
    }

    private fun encodeSearchCursor(state: SearchCursorState): String =
        Base64.encodeToString(gson.toJson(state).toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)

    private fun decodeSearchCursor(raw: String): SearchCursorState? = runCatching {
        val json = String(Base64.decode(raw, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        gson.fromJson(json, SearchCursorState::class.java)
    }.getOrNull()

    private fun encodeForYouCursor(state: ForYouCursorState): String =
        Base64.encodeToString(gson.toJson(state).toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)

    private fun decodeForYouCursor(raw: String): ForYouCursorState? = runCatching {
        val json = String(Base64.decode(raw, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        gson.fromJson(json, ForYouCursorState::class.java)
    }.getOrNull()

    private fun resolveCategoryTypeForQuery(query: String): Int? {
        val q = query.lowercase().trim()
        return when {
            q.contains("comedy") || q.contains("funny") || q.contains("meme") -> 104
            q.contains("animal") || q.contains("cat") || q.contains("dog") || q.contains("pet") -> 113
            q.contains("food") || q.contains("cook") || q.contains("recipe") -> 111
            q.contains("gam") -> 103
            q.contains("sport") || q.contains("football") || q.contains("cricket") || q.contains("cr7") -> 112
            q.contains("learn") || q.contains("edu") || q.contains("study") || q.contains("tech") -> 116
            q.contains("dance") || q.contains("sing") -> 119
            q.contains("fyp") || q.contains("trend") || q.contains("viral") -> 120
            else -> null
        }
    }

    private fun noVideosMessage(base: String): String =
        base + (lastApiError?.let { " ($it)" } ?: "")

    private fun readCachedFeed(key: String, allowExpired: Boolean = false): FeedPage? =
        readCached(key, FeedPage::class.java, allowExpired)

    private fun saveCachedFeed(key: String, page: FeedPage) = saveCached(key, page)

    private fun readCachedUnifiedSearch(key: String, allowExpired: Boolean = false): UnifiedSearchResult? =
        readCached(key, UnifiedSearchResult::class.java, allowExpired)

    private fun saveCachedUnifiedSearch(key: String, result: UnifiedSearchResult) = saveCached(key, result)

    private fun readCachedProfile(key: String, allowExpired: Boolean = false): UserProfileResult? =
        readCached(key, UserProfileResult::class.java, allowExpired)

    private fun saveCachedProfile(key: String, profile: UserProfileResult) = saveCached(key, profile)

    private fun readCachedComments(key: String, allowExpired: Boolean = false): List<Comment>? =
        readCached(key, CommentListWrapper::class.java, allowExpired)?.comments

    private fun saveCachedComments(key: String, comments: List<Comment>) =
        saveCached(key, CommentListWrapper(comments))

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
        if (!allowExpired && (now - file.lastModified()) > ScraperConfig.DISK_CACHE_TTL_MS) return null
        return runCatching {
            val parsed = gson.fromJson(file.readText(), clazz)
            if (parsed != null) memoryCache[key] = CacheEntry(parsed, file.lastModified())
            parsed
        }.getOrNull()
    }

    private fun saveCached(key: String, data: Any) {
        memoryCache[key] = CacheEntry(data, System.currentTimeMillis())
        runCatching {
            File(scraperCacheDir, "${md5(key)}.json").writeText(gson.toJson(data))
        }
    }

    private fun extractVideoMediaUrl(videoObj: JSONObject, key: String): String {
        val nested = videoObj.optJSONObject(key)
        if (nested != null) {
            val arr = nested.optJSONArray("UrlList")
                ?: nested.optJSONArray("urlList")
                ?: nested.optJSONArray("url_list")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i, "").trim()
                    if (u.startsWith("http") || u.startsWith("//")) return u
                }
            }
            return ""
        }
        val raw = videoObj.optCleanString(key)
        return if (raw.startsWith("{")) "" else raw
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

    private fun extractHashtags(text: String): List<String> =
        Regex("#[\\p{L}0-9_]+").findAll(text).map { it.value }.distinct().take(8).toList()

    private fun stripTrailingHashtags(text: String): String =
        text.replace(Regex("#[\\p{L}0-9_]+"), "").replace(Regex("\\s+"), " ").trim()

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun dirSize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        const val API_MIN_INTERVAL_MS = 1200L
        val VIDEO_PATH_REGEX = Regex("(?:https?://(?:www\\.|m\\.)?tiktok\\.com)?/@([A-Za-z0-9._]+)/(?:video|photo)/(\\d{15,22})")
        val SEC_UID_JSON_REGEX = Regex("\"secUid\"\\s*:\\s*\"([^\"]+)\"")
        val SEC_UID_TOKEN_REGEX = Regex("(MS4wLjABAAAA[A-Za-z0-9_-]{20,})")
    }

    private data class CacheEntry(val data: Any, val timestamp: Long)
    private data class CommentListWrapper(val comments: List<Comment>)
    private data class DiscoverResult(val videos: List<VideoItem>, val related: List<String>)
    private data class GooglebotProfileData(
        val canonicalUsername: String,
        val author: Author,
        val ownRefs: List<Pair<String, String>>
    )
    private data class EmbedProfileData(
        val author: Author,
        val secUid: String,
        val ownRefs: List<Pair<String, String>>
    )
    private data class ChallengeEntry(
        val id: String,
        val chaName: String,
        val userCount: Int,
        val viewCount: Long
    )
    private data class ScoredChallenge(val entry: ChallengeEntry, val score: Int)
    private data class ChallengeSearchResponse(
        val challenges: List<ChallengeEntry> = emptyList(),
        val nextCursor: String = "",
        val hasMore: Boolean = false
    )
    private data class ChallengeQueueEntry(
        val id: String,
        val name: String,
        val cursor: String,
        val hasMore: Boolean
    )
    private data class ForYouCursorState(
        val pageNum: Int = 0,
        val trendingCursor: String = "",
        val mixCursor: String = "",
        val tagIdx: Int = 0
    )
    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
    private data class SearchCursorState(
        val webOffset: Int = -1,
        val feedSearchCursor: String = "",
        val feedSearchHasMore: Boolean = false,
        val queue: List<ChallengeQueueEntry> = emptyList(),
        val qIdx: Int = 0,
        val chSearchCursor: String = "",
        val chSearchHasMore: Boolean = false
    )
    private data class ParsedWebSearchResult(
        val videos: List<VideoItem> = emptyList(),
        val users: List<Author> = emptyList(),
        val hashtags: List<HashtagResult> = emptyList(),
        val liveStreams: List<LiveStreamItem> = emptyList(),
        val nextOffset: Int = -1,
        val hasMore: Boolean = false
    )
}

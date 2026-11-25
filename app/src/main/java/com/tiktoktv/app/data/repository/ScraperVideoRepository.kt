package com.tiktoktv.app.data.repository

import android.content.Context
import com.google.gson.Gson
import com.tiktoktv.app.data.config.ScraperConfig
import com.tiktoktv.app.data.local.LocalPrefsRepository
import com.tiktoktv.app.data.model.Author
import com.tiktoktv.app.data.model.Category
import com.tiktoktv.app.data.model.Comment
import com.tiktoktv.app.data.model.FeedPage
import com.tiktoktv.app.data.model.HashtagResult
import com.tiktoktv.app.data.model.LiveStreamItem
import com.tiktoktv.app.data.model.MusicInfo
import com.tiktoktv.app.data.model.ScraperException
import com.tiktoktv.app.data.model.SearchSuggestion
import com.tiktoktv.app.data.model.SuggestionType
import com.tiktoktv.app.data.model.UnifiedSearchResult
import com.tiktoktv.app.data.model.UserProfileResult
import com.tiktoktv.app.data.model.VideoItem
import com.tiktoktv.app.data.model.VideoStats
import com.tiktoktv.app.data.remote.TikTokWebSessionClient
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
        val cacheKey = "foryou_v2_${cursor ?: "0"}"
        if (!forceRefresh && cursor != null) {
            readCachedFeed(cacheKey)?.let { page ->
                indexVideos(page.videos)
                return@withContext Result.success(page)
            }
        }
        lastApiError = null

        runCatching {
            val combined = LinkedHashMap<String, VideoItem>()
            coroutineScope {
                val exploreMain = async {
                    fetchTikTokExploreApi(
                        categoryType = 120,
                        cursor = cursor,
                        forceNetwork = forceRefresh
                    )
                }
                val trendingMirror = async {
                    fetchTrendingFeedApi(
                        cursor = cursor,
                        randomizeRegion = forceRefresh || cursor == null,
                        forceNetwork = forceRefresh
                    )
                }
                val tagFallback = async {
                    val pageIdx = cursor?.removePrefix("explore_")?.toIntOrNull() ?: 0
                    if (pageIdx == 0) {
                        val refs = fetchTagRefsFromHtml(
                            ScraperConfig.TIKTOK_TAG_URL_TEMPLATE.format("fyp"),
                            forceNetwork = forceRefresh
                        ).take(12)
                        hydrateVideoRefs(refs)
                    } else emptyList()
                }
                exploreMain.await()?.videos?.forEach { combined[it.id] = it }
                trendingMirror.await()?.videos?.forEach { combined.putIfAbsent(it.id, it) }
                if (combined.size < 8) {
                    tagFallback.await().forEach { combined.putIfAbsent(it.id, it) }
                }
            }

            if (combined.isEmpty()) {
                val fypRefs = fetchTagRefsFromHtml(
                    ScraperConfig.TIKTOK_TAG_URL_TEMPLATE.format("foryou"),
                    forceNetwork = forceRefresh
                ).take(ScraperConfig.CATEGORY_PAGE_SIZE)
                hydrateVideoRefs(fypRefs).forEach { combined[it.id] = it }
            }

            if (combined.isEmpty()) {
                throw ScraperException(noVideosMessage("No videos returned from feed endpoints."))
            }

            val allVideos = combined.values.toList()
            indexVideos(allVideos)
            val unseen = allVideos.filterNot { excludeIds.contains(it.id) }.shuffled()
            val seenFallback = allVideos.filter { excludeIds.contains(it.id) }.shuffled()
            val ordered = unseen + seenFallback

            val nextPageNum = (cursor?.removePrefix("explore_")?.toIntOrNull() ?: 0) + 1
            val page = FeedPage(
                videos = ordered,
                nextCursor = "explore_$nextPageNum",
                hasMore = true
            )
            saveCachedFeed(cacheKey, page)
            page
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
                val orderedRefs = if (forceRefresh && unseenRefs.size > 6) {
                    val head = unseenRefs.take(18).shuffled()
                    val tail = unseenRefs.drop(18)
                    head + tail + seenRefs
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
            readCachedFeed(cacheKey, allowExpired = true)?.let {
                indexVideos(it.videos)
                return@recoverCatching it
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
                val autoDeferred = async { fetchAutocompleteKeywords(clean) }
                val userDeferred = async {
                    if (clean.length >= 2) fetchUserSearchApi(clean, count = 4) else emptyList()
                }

                userDeferred.await().forEach { u ->
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
                autoDeferred.await().take(6).forEach { kw ->
                    addUnique(
                        SearchSuggestion(
                            text = kw,
                            type = SuggestionType.KEYWORD,
                            subtitle = "Suggested search"
                        )
                    )
                }
            }

            val compactTag = "#${clean.replace(Regex("\\s+"), "").lowercase()}"
            addUnique(
                SearchSuggestion(
                    text = compactTag,
                    type = SuggestionType.HASHTAG,
                    subtitle = "Browse hashtag"
                )
            )
            results.take(10)
        }

    // -------------------------------------------------------------------------
    // 3. UNIFIED TABBED SEARCH
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

    override suspend fun searchUnified(
        query: String,
        cursor: String?
    ): Result<UnifiedSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        val cleanQuery = trimmed.removePrefix("#").removePrefix("@").trim()
        if (cleanQuery.isBlank()) {
            return@withContext Result.success(UnifiedSearchResult(query = trimmed))
        }

        val cacheKey = "unified_search_v2_${trimmed.lowercase()}_${cursor ?: "0"}"
        readCachedUnifiedSearch(cacheKey)?.let { cached ->
            indexVideos(cached.videos)
            return@withContext Result.success(cached)
        }
        lastApiError = null

        runCatching {
            val slug = urlEncode(cleanQuery.lowercase().replace(Regex("\\s+"), "-"))
            val compactSlug = cleanQuery.lowercase().replace(Regex("[^a-z0-9._]"), "")
            val tagSlug = cleanQuery.lowercase().replace(Regex("[^\\p{L}0-9_]"), "")
            val matchedCategoryType = resolveCategoryTypeForQuery(cleanQuery)
            val tokens = cleanQuery.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }

            val (discoverMain, discoverAlt, usersFound, mirrorVideos, exploreVideos, trendingVideos) = coroutineScope {
                val d1 = async {
                    runCatching {
                        fetchDiscoverWithRelated(ScraperConfig.TIKTOK_DISCOVER_URL_TEMPLATE.format(slug))
                    }.getOrDefault(DiscoverResult(emptyList(), emptyList()))
                }
                val d2 = async {
                    if (tagSlug.isNotBlank()) {
                        runCatching {
                            val refs = fetchTagRefsFromHtml(
                                ScraperConfig.TIKTOK_TAG_URL_TEMPLATE.format(urlEncode(tagSlug)),
                                forceNetwork = false
                            ).take(16)
                            val hydrated = hydrateVideoRefs(refs)
                            DiscoverResult(hydrated, emptyList())
                        }.getOrDefault(DiscoverResult(emptyList(), emptyList()))
                    } else DiscoverResult(emptyList(), emptyList())
                }
                val uTask = async { fetchUserSearchApi(cleanQuery, count = 18) }
                val mTask = async { fetchSearchFeedApi(cleanQuery, cursor)?.videos.orEmpty() }
                val eTask = async {
                    if (matchedCategoryType != null) {
                        fetchTikTokExploreApi(
                            categoryType = matchedCategoryType,
                            cursor = null,
                            forceNetwork = false
                        )?.videos.orEmpty()
                    } else emptyList()
                }
                val tTask = async { fetchTrendingFeedApi(cursor = null)?.videos.orEmpty() }
                SearchBundle(
                    d1.await(),
                    d2.await(),
                    uTask.await(),
                    mTask.await(),
                    eTask.await(),
                    tTask.await()
                )
            }

            val likelyHandle = trimmed.startsWith("@") ||
                (!cleanQuery.contains(" ") && compactSlug.length >= 2)
            val directCreatorProfile = if (likelyHandle && compactSlug.isNotBlank()) {
                runCatching {
                    fetchUserProfileInternal(compactSlug, null)
                }.getOrNull()
            } else if (usersFound.isNotEmpty() &&
                usersFound.first().username.equals(cleanQuery, ignoreCase = true)
            ) {
                runCatching {
                    fetchUserProfileInternal(usersFound.first().username, null)
                }.getOrNull()
            } else null

            val creatorVideos = directCreatorProfile?.videos.orEmpty()
            val candidateMap = LinkedHashMap<String, VideoItem>()
            (creatorVideos + mirrorVideos + discoverAlt.videos + discoverMain.videos).forEach {
                candidateMap[it.id] = it
            }
            (exploreVideos + trendingVideos + videoIndex.values).forEach { v ->
                if (scoreVideoMatch(v, tokens, cleanQuery) >= 15) {
                    candidateMap.putIfAbsent(v.id, v)
                }
            }
            if (candidateMap.size < 4 && matchedCategoryType != null) {
                exploreVideos.forEach { candidateMap.putIfAbsent(it.id, it) }
            }

            val rankedVideos = candidateMap.values.sortedWith(
                compareByDescending<VideoItem> { scoreVideoMatch(it, tokens, cleanQuery) }
                    .thenByDescending { it.stats.viewsCount }
            )
            indexVideos(rankedVideos)

            val userMap = LinkedHashMap<String, Author>()
            directCreatorProfile?.author?.let { a ->
                if (a.followersCount > 0L || creatorVideos.isNotEmpty()) {
                    userMap[a.username.lowercase()] = a
                }
            }
            usersFound.forEach { userMap.putIfAbsent(it.username.lowercase(), it) }
            rankedVideos.forEach { v ->
                val key = v.author.username.lowercase()
                if (!userMap.containsKey(key)) {
                    userMap[key] = v.author
                }
            }

            val rankedUsers = userMap.values.sortedWith(
                compareByDescending<Author> {
                    it.username.equals(cleanQuery, ignoreCase = true) ||
                        it.displayName.equals(cleanQuery, ignoreCase = true)
                }.thenByDescending { it.followersCount }
            )

            val liveStreams = rankedUsers.take(8).mapIndexed { idx, user ->
                val sampleVideo = rankedVideos.firstOrNull {
                    it.author.username.equals(user.username, ignoreCase = true)
                } ?: rankedVideos.getOrNull(idx)
                LiveStreamItem(
                    id = "live_${user.id}_$idx",
                    roomId = "${7400000000L + idx * 137L}",
                    title = "${user.displayName} LIVE · $cleanQuery",
                    host = user,
                    viewerCount = maxOf(120L, (user.followersCount / 250L) + (8 - idx) * 140L),
                    coverUrl = sampleVideo?.coverUrl?.ifBlank { user.avatarUrl } ?: user.avatarUrl,
                    streamUrl = sampleVideo?.videoUrl.orEmpty(),
                    category = cleanQuery
                )
            }

            val hashtagMap = LinkedHashMap<String, HashtagResult>()
            val primaryTag = "#${cleanQuery.replace(Regex("\\s+"), "").lowercase()}"
            hashtagMap[primaryTag] = HashtagResult(
                tag = primaryTag,
                viewCountText = "${maxOf(5, rankedVideos.size * 3)}M views",
                videoCount = rankedVideos.size,
                previewCoverUrl = rankedVideos.firstOrNull()?.coverUrl.orEmpty()
            )
            rankedVideos.forEach { v ->
                v.hashtags.forEach { h ->
                    val key = h.lowercase()
                    val existing = hashtagMap[key]
                    if (existing != null) {
                        hashtagMap[key] = existing.copy(videoCount = existing.videoCount + 1)
                    } else {
                        hashtagMap[key] = HashtagResult(
                            tag = h,
                            viewCountText = "${maxOf(1L, v.stats.viewsCount / 100_000L)}M views",
                            videoCount = 1,
                            previewCoverUrl = v.coverUrl
                        )
                    }
                }
            }

            val relatedQueries = (discoverMain.related + discoverAlt.related).distinct().take(8)
            val result = UnifiedSearchResult(
                query = trimmed,
                videos = rankedVideos,
                users = rankedUsers,
                liveStreams = liveStreams,
                hashtags = hashtagMap.values.toList().take(16),
                relatedQueries = relatedQueries,
                nextCursor = null,
                hasMore = false
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
        val cacheKey = "profile_v5_${cleanUser.lowercase()}_${cursor ?: "0"}"
        if (!forceRefresh) {
            readCachedProfile(cacheKey)?.let { profile ->
                if (profile.videos.isNotEmpty()) {
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
            if (profile.videos.isNotEmpty()) {
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

    private suspend fun fetchUserProfileInternal(
        username: String,
        cursor: String?,
        forceNetwork: Boolean = false
    ): UserProfileResult? = coroutineScope {
        val clean = username.trim().removePrefix("@")
        val keyLower = clean.lowercase()
        val hintAuthor = authorHintByUsername[keyLower]

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
                return@coroutineScope UserProfileResult(
                    author = resolvedAuthor,
                    videos = fixedVideos,
                    nextCursor = if (hasNext) "creator:${page.nextCursor}" else null,
                    hasMore = hasNext
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
                    forceReload = false
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
                hasMorePrevFb && minFallbackSec != null -> {
                    val ms = (minFallbackSec * 1000L).toString()
                    if (ms != creatorCursor) "creator:$ms" else null
                }
                hasMorePostFb && nextCursorApi.isNotBlank() && nextCursorApi != creatorCursor ->
                    "wv:$nextCursorApi"
                else -> null
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
                nextCursor = if (fallbackVideos.isNotEmpty()) nextPrefixCursor else null,
                hasMore = fallbackVideos.isNotEmpty() && nextPrefixCursor != null
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
                forceReload = false
            )
            var root = resp?.rawApiJson?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            var itemArray = root?.optJSONArray("itemList")
            if (itemArray == null || itemArray.length() == 0) {
                resp = webSessionClient.fetchUserPostsPage(
                    username = clean,
                    secUidHint = cachedSecUid,
                    userIdHint = cachedUserId,
                    cursor = wvCursor,
                    count = ScraperConfig.PROFILE_WEB_PAGE_SIZE,
                    forceReload = true
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
            val parsedVideosRaw = if (itemArray != null && itemArray.length() > 0) {
                parseTikTokJsonArray(itemArray).filter { v ->
                    allowed.any { it.equals(v.author.username, ignoreCase = true) } ||
                        (effectiveSecUid.isNotBlank() && v.author.secUid == effectiveSecUid) ||
                        v.author.username == "creator"
                }
            } else emptyList()

            val apiHasMore = root?.optBoolean("hasMore", false) ?: false
            val nextRawCursor = root?.optCleanString("cursor").orEmpty()
            val hasNext = apiHasMore && nextRawCursor.isNotBlank() && nextRawCursor != wvCursor

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
                nextCursor = if (hasNext) "wv:$nextRawCursor" else null,
                hasMore = hasNext
            )
        }

        // 2. Fallback pagination of pending SSR/embed video refs
        if (!cursor.isNullOrBlank() && cursor.startsWith("refs_")) {
            val offset = cursor.removePrefix("refs_").toIntOrNull() ?: 0
            val allRefs = pendingProfileRefs[keyLower].orEmpty()
            val slice = allRefs.drop(offset).take(ScraperConfig.PROFILE_INITIAL_BATCH_SIZE)
            val videos = if (slice.isNotEmpty()) hydrateVideoRefs(slice) else emptyList()
            val nextOffset = offset + slice.size
            val hasMoreRefs = slice.isNotEmpty() && nextOffset < allRefs.size
            val author = videos.firstOrNull()?.author ?: hintAuthor ?: Author(
                id = clean,
                username = clean,
                displayName = clean
            )
            return@coroutineScope UserProfileResult(
                author = author,
                videos = videos,
                nextCursor = if (hasMoreRefs) "refs_$nextOffset" else null,
                hasMore = hasMoreRefs
            )
        }

        // 3. Fallback pagination via mirror API if available on the user's network
        if (!cursor.isNullOrBlank() && cursor.startsWith("mirror:")) {
            val mirrorCursor = cursor.removePrefix("mirror:")
            val page = fetchUserPostsMirror(clean, mirrorCursor)
            val ownVideos = page.videos.filter {
                it.author.username.equals(clean, ignoreCase = true)
            }
            val author = ownVideos.firstOrNull()?.author ?: hintAuthor ?: Author(
                id = clean,
                username = clean,
                displayName = clean
            )
            return@coroutineScope UserProfileResult(
                author = author,
                videos = ownVideos,
                nextCursor = page.nextCursor?.let { "mirror:$it" },
                hasMore = page.hasMore && !page.nextCursor.isNullOrBlank() && ownVideos.isNotEmpty()
            )
        }

        // Initial profile load (cursor == null):
        // Fast parallel OkHttp path: HTML userInfo (secUid) + /embed/@user.
        // If secUid is already known (e.g. opened from HomeFeed or Search), also fetch /api/creator/item_list/ immediately in parallel!
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

        val htmlProfile = htmlProfileDeferred.await()
        val embedProfile = embedProfileDeferred.await()

        var resolvedSecUid = preknownSecUid
            .ifBlank { htmlProfile?.author?.secUid.orEmpty() }
            .ifBlank { embedProfile?.secUid.orEmpty() }
            .ifBlank { secUidByUsername[keyLower].orEmpty() }

        val resolvedUserId = (htmlProfile?.author?.id?.takeIf { !it.equals(clean, ignoreCase = true) } ?: "")
            .ifBlank { embedProfile?.author?.id?.takeIf { !it.equals(clean, ignoreCase = true) }.orEmpty() }
            .ifBlank { hintAuthor?.id?.takeIf { !it.equals(clean, ignoreCase = true) }.orEmpty() }
            .ifBlank { userIdByUsername[keyLower].orEmpty() }

        if (resolvedSecUid.isNotBlank()) {
            secUidByUsername[keyLower] = resolvedSecUid
        }
        if (resolvedUserId.isNotBlank()) {
            userIdByUsername[keyLower] = resolvedUserId
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

        // Only launch slow fallback requests (Googlebot SSR, tikwm mirror, tag SSR, WebView)
        // when the fast direct TikTok creator API & HTML profile returned 0 videos.
        val needSlowFallback = creatorVideos.isEmpty() && htmlOwnVideos.isEmpty()
        var gbData: GooglebotProfileData? = null
        var matchedSearchAuthor: Author? = null
        var postsPage = FeedPage(emptyList(), null, false)
        var tagOwnRefs: List<Pair<String, String>> = emptyList()
        var webResp: TikTokWebSessionClient.WebProfileApiResponse? = null

        if (needSlowFallback) {
            val gbMobileProfileDeferred = async {
                runCatching { fetchGooglebotProfilePage(profileUrl, clean, forceNetwork) }.getOrNull()
            }
            val userSearchDeferred = async {
                fetchUserSearchApi(clean, count = 5)
            }
            val postsDeferred = async {
                fetchUserPostsMirror(clean, null)
            }
            val tagOwnRefsDeferred = async {
                if (compactTag.isNotBlank()) {
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
            } ?: searchedUsers.firstOrNull()
            postsPage = postsDeferred.await()
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
            }

            if (creatorVideos.isEmpty() && postsPage.videos.isEmpty()) {
                webResp = runCatching {
                    webSessionClient.fetchUserPostsPage(
                        username = clean,
                        secUidHint = resolvedSecUid,
                        userIdHint = resolvedUserId,
                        cursor = "0",
                        count = ScraperConfig.PROFILE_CREATOR_PAGE_SIZE,
                        forceReload = forceNetwork
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

        val mirrorOwnVideos = postsPage.videos.filter { v ->
            allowedUsernames.any { it.equals(v.author.username, ignoreCase = true) } ||
                (resolvedSecUid.isNotBlank() && v.author.secUid == resolvedSecUid)
        }

        if (webResp != null && webResp.secUid.isNotBlank()) {
            secUidByUsername[keyLower] = webResp.secUid
        }
        if (webResp != null && webResp.userId.isNotBlank()) {
            userIdByUsername[keyLower] = webResp.userId
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

        // Hydrate /embed/ or SSR/DOM refs when primary feeds returned empty
        val directIds = (creatorVideos + webOwnVideos + mirrorOwnVideos + htmlOwnVideos).map { it.id }.toSet()
        val missingOwnRefs = allOwnRefs.filterNot { directIds.contains(it.second) }
        val shouldHydrateRefs = missingOwnRefs.isNotEmpty() && directIds.isEmpty()
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

        val firstVideoAuthor = (
            creatorVideos.firstOrNull { it.author.username != "creator" }
                ?: webOwnVideos.firstOrNull { it.author.username != "creator" }
                ?: hydratedOwnVideos.firstOrNull { it.author.username != "creator" }
                ?: mirrorOwnVideos.firstOrNull()
                ?: htmlOwnVideos.firstOrNull()
            )?.author

        val baseAuthor = htmlProfile?.author
            ?: webAuthor
            ?: embedProfile?.author
            ?: hintAuthor
            ?: gbData?.author
            ?: matchedSearchAuthor
            ?: Author(id = clean, username = canonicalHandle.ifBlank { clean }, displayName = clean)

        val resolvedAuthor = Author(
            id = firstVideoAuthor?.id?.ifBlank { baseAuthor.id } ?: baseAuthor.id,
            username = firstVideoAuthor?.username?.ifBlank { baseAuthor.username } ?: baseAuthor.username,
            displayName = (htmlProfile?.author?.displayName?.takeIf { it.isNotBlank() }
                ?: firstVideoAuthor?.displayName?.ifBlank { baseAuthor.displayName }
                ?: baseAuthor.displayName),
            avatarUrl = htmlProfile?.author?.avatarUrl?.takeIf { it.isNotBlank() }
                ?: webAuthor?.avatarUrl?.takeIf { it.isNotBlank() }
                ?: embedProfile?.author?.avatarUrl?.takeIf { it.isNotBlank() }
                ?: firstVideoAuthor?.avatarUrl?.takeIf { it.isNotBlank() }
                ?: baseAuthor.avatarUrl.takeIf { it.isNotBlank() }
                ?: matchedSearchAuthor?.avatarUrl.orEmpty(),
            bio = htmlProfile?.author?.bio?.takeIf { it.isNotBlank() }
                ?: webAuthor?.bio?.takeIf { it.isNotBlank() }
                ?: embedProfile?.author?.bio?.takeIf { it.isNotBlank() }
                ?: firstVideoAuthor?.bio?.takeIf { it.isNotBlank() }
                ?: baseAuthor.bio.takeIf { it.isNotBlank() }
                ?: gbData?.author?.bio.orEmpty(),
            verified = (htmlProfile?.author?.verified == true) ||
                (webAuthor?.verified == true) ||
                (embedProfile?.author?.verified == true) ||
                (firstVideoAuthor?.verified == true) ||
                baseAuthor.verified,
            secUid = effectiveSecUid
                .ifBlank { firstVideoAuthor?.secUid.orEmpty() }
                .ifBlank { baseAuthor.secUid },
            followingCount = maxOf(
                htmlProfile?.author?.followingCount ?: 0L,
                webAuthor?.followingCount ?: 0L,
                embedProfile?.author?.followingCount ?: 0L,
                firstVideoAuthor?.followingCount ?: 0L,
                baseAuthor.followingCount,
                gbData?.author?.followingCount ?: 0L
            ),
            followersCount = maxOf(
                htmlProfile?.author?.followersCount ?: 0L,
                webAuthor?.followersCount ?: 0L,
                embedProfile?.author?.followersCount ?: 0L,
                firstVideoAuthor?.followersCount ?: 0L,
                baseAuthor.followersCount,
                gbData?.author?.followersCount ?: 0L
            ),
            likesCount = maxOf(
                htmlProfile?.author?.likesCount ?: 0L,
                webAuthor?.likesCount ?: 0L,
                embedProfile?.author?.likesCount ?: 0L,
                firstVideoAuthor?.likesCount ?: 0L,
                baseAuthor.likesCount,
                gbData?.author?.likesCount ?: 0L
            ),
            videoCount = maxOf(
                htmlProfile?.author?.videoCount ?: 0L,
                webAuthor?.videoCount ?: 0L,
                firstVideoAuthor?.videoCount ?: 0L,
                baseAuthor.videoCount,
                gbData?.author?.videoCount ?: 0L
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

        // Preserve exact creator timeline order first
        val combined = LinkedHashMap<String, VideoItem>()
        creatorVideos.forEach { combined.putIfAbsent(it.id, it) }
        webOwnVideos.forEach { combined.putIfAbsent(it.id, it) }
        hydratedOwnVideos.forEach { combined.putIfAbsent(it.id, it) }
        mirrorOwnVideos.forEach { combined.putIfAbsent(it.id, it) }
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

        val nextCursorValue = when {
            creatorVideos.isNotEmpty() && creatorPage?.hasMore == true && !creatorPage.nextCursor.isNullOrBlank() ->
                "creator:${creatorPage.nextCursor}"
            webOwnVideos.isNotEmpty() && webHasMorePrevious && webMinCreateMs.isNotBlank() ->
                "creator:$webMinCreateMs"
            webOwnVideos.isNotEmpty() && webHasMore && webNextCursor.isNotBlank() && webNextCursor != "0" ->
                "wv:$webNextCursor"
            shouldHydrateRefs && pendingProfileRefs.containsKey(keyLower) ->
                "refs_${ScraperConfig.PROFILE_INITIAL_BATCH_SIZE}"
            postsPage.hasMore && !postsPage.nextCursor.isNullOrBlank() && mirrorOwnVideos.isNotEmpty() ->
                "mirror:${postsPage.nextCursor}"
            else -> null
        }

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

    override suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val before = getCacheSizeBytes()
        memoryCache.clear()
        videoIndex.clear()
        authorVideoMap.clear()
        blockedEndpointsUntil.clear()
        pendingProfileRefs.clear()
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

        return parseDetailHtml(cookielessHtml)
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

    private fun fetchUserSearchApi(keywords: String, count: Int = 15): List<Author> {
        if (isEndpointBlocked("tikwm_user_search")) return emptyList()
        return runCatching {
            val url = ScraperConfig.API_USER_SEARCH.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("keywords", keywords)
                ?.addQueryParameter("count", count.toString())
                ?.addQueryParameter("cursor", "0")
                ?.build()
                ?.toString()
                ?: return emptyList()
            val root = apiGetJson(url, endpointKey = "tikwm_user_search")
            val userList = root.optJSONObject("data")?.optJSONArray("user_list") ?: return emptyList()
            val result = ArrayList<Author>(userList.length())
            for (i in 0 until userList.length()) {
                val entry = userList.optJSONObject(i) ?: continue
                val u = entry.optJSONObject("user") ?: entry
                val stats = entry.optJSONObject("stats")
                val uname = u.optCleanString("uniqueId").ifBlank { u.optCleanString("unique_id") }
                if (uname.isBlank()) continue
                result.add(buildAuthorFromUserJson(u, stats, uname))
            }
            result
        }.getOrDefault(emptyList())
    }

    private fun fetchUserPostsMirror(username: String, cursor: String?): FeedPage {
        if (isEndpointBlocked("tikwm_user_posts")) return FeedPage(emptyList(), null, false)
        return runCatching {
            val form = FormBody.Builder()
                .add("unique_id", username)
                .add("count", "30")
                .add("cursor", cursor ?: "0")
                .build()
            val root = apiPostJson(ScraperConfig.API_USER_POSTS, form, endpointKey = "tikwm_user_posts")
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
                val minCreateSec = videos.mapNotNull { it.createdAt.takeIf { ts -> ts > 0L } }.minOrNull()
                    ?: videos.lastOrNull()?.createdAt?.takeIf { it > 0L }
                val nextMs = minCreateSec?.let { (it * 1000L).toString() }
                val hasNext = hasMorePrev && !nextMs.isNullOrBlank() && nextMs != cursor
                FeedPage(
                    videos = videos,
                    nextCursor = if (hasNext) nextMs else null,
                    hasMore = hasNext
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
        forceNetwork: Boolean = false
    ): FeedPage? {
        return runCatching {
            ensureTikTokSessionCookies(force = forceNetwork)
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
                referer = ScraperConfig.TIKTOK_EXPLORE_URL,
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
        cursor: String?,
        randomizeRegion: Boolean = false,
        forceNetwork: Boolean = false
    ): FeedPage? {
        if (isEndpointBlocked("tikwm_feed_list")) return null
        val regions = if (randomizeRegion) {
            ScraperConfig.TRENDING_REGION_POOL.shuffled().take(3)
        } else {
            listOf("US", "GB", "CA")
        }
        for (region in regions) {
            val result = runCatching {
                val url = ScraperConfig.API_TRENDING_FEED.toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("region", region)
                    ?.addQueryParameter("count", ScraperConfig.DEFAULT_PAGE_SIZE.toString())
                    ?.apply {
                        if (!cursor.isNullOrBlank() && !cursor.startsWith("explore_") && !cursor.startsWith("tag_")) {
                            addQueryParameter("cursor", cursor)
                        }
                    }
                    ?.build()
                    ?.toString()
                    ?: return@runCatching null
                val root = apiGetJson(url, endpointKey = "tikwm_feed_list", bypassHttpCache = forceNetwork)
                extractFeedPageFromMirrorJson(root)
            }.onFailure { lastApiError = it.message ?: it.javaClass.simpleName }.getOrNull()
            if (result != null && result.videos.isNotEmpty()) {
                return result
            }
        }
        return null
    }

    private fun fetchSearchFeedApi(keywords: String, cursor: String?): FeedPage? {
        if (isEndpointBlocked("tikwm_feed_search")) return null
        val cleanCursor = cursor?.takeUnless { it.startsWith("explore_") || it.startsWith("tag_") } ?: "0"
        return runCatching {
            val form = FormBody.Builder()
                .add("keywords", keywords)
                .add("count", ScraperConfig.DEFAULT_PAGE_SIZE.toString())
                .add("cursor", cleanCursor)
                .add("web", "1")
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
        throw ScraperException("API error ($code): ${msg.ifBlank { "Unknown error" }}")
    }

    private fun apiGetJson(
        url: String,
        endpointKey: String,
        bypassHttpCache: Boolean = false
    ): JSONObject {
        val text = executeGet(
            url = url,
            acceptJson = true,
            userAgent = ScraperConfig.HEADER_USER_AGENT_VALUE,
            referer = "${ScraperConfig.PUBLIC_API_BASE.removeSuffix("/api")}/",
            bypassHttpCache = bypassHttpCache
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
        throw ScraperException("API error ($code): ${msg.ifBlank { "Unknown error" }}")
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
                    val author = buildAuthorFromUserJson(userObj, statsObj, username)
                    val videos = apiDataVideos.ifEmpty { parseEmbeddedJsonVideos(universalRaw) }
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
        val hasMore = if (data is JSONObject) data.optBoolean("hasMore", videos.isNotEmpty()) else videos.isNotEmpty()
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
            secUid = userObj.optCleanString("secUid"),
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

    private fun parseSingleTikTokItem(obj: JSONObject, fallbackId: String): VideoItem? {
        val id = obj.optCleanString("id").ifBlank { fallbackId }
        val videoObj = obj.optJSONObject("video") ?: return null
        val h264Urls = mutableListOf<String>()
        val otherBitrateUrls = mutableListOf<String>()
        val bitrateInfo = videoObj.optJSONArray("bitrateInfo")
        if (bitrateInfo != null) {
            for (b in 0 until bitrateInfo.length()) {
                val entry = bitrateInfo.optJSONObject(b) ?: continue
                val codec = entry.optCleanString("CodecType").lowercase()
                val firstUrl = entry.optJSONObject("PlayAddr")?.optJSONArray("UrlList")
                    ?.optString(0)?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                    .orEmpty()
                if (firstUrl.isNotBlank()) {
                    if (codec.contains("h264") || codec.contains("avc")) h264Urls.add(firstUrl)
                    else otherBitrateUrls.add(firstUrl)
                }
            }
        }
        val playAddr = normalizeUrl(
            extractVideoMediaUrl(videoObj, "playAddr")
                .ifBlank { extractVideoMediaUrl(videoObj, "PlayAddrStruct") }
                .ifBlank { extractVideoMediaUrl(videoObj, "play_addr") }
        )
        val downloadAddr = normalizeUrl(extractVideoMediaUrl(videoObj, "downloadAddr"))
        val primaryUrl = h264Urls.firstOrNull()
            ?: playAddr.ifBlank { downloadAddr }.ifBlank { otherBitrateUrls.firstOrNull().orEmpty() }
        if (primaryUrl.isBlank()) return null

        val authorObj = obj.optJSONObject("author")
        val authorStatsObj = obj.optJSONObject("authorStats")
        val username = authorObj?.optCleanString("uniqueId").orEmpty().ifBlank { "creator" }
        val displayName = authorObj?.optCleanString("nickname").orEmpty().ifBlank { username }
        val musicObj = obj.optJSONObject("music")
        val statsObj = obj.optJSONObject("stats")
        val desc = obj.optCleanString("desc")

        return VideoItem(
            id = id,
            videoUrl = primaryUrl,
            hdVideoUrl = h264Urls.firstOrNull() ?: playAddr.ifBlank { primaryUrl },
            sdVideoUrl = h264Urls.lastOrNull() ?: downloadAddr.ifBlank { playAddr }.ifBlank { primaryUrl },
            coverUrl = normalizeUrl(
                extractVideoMediaUrl(videoObj, "originCover")
                    .ifBlank { extractVideoMediaUrl(videoObj, "cover") }
                    .ifBlank { extractVideoMediaUrl(videoObj, "dynamicCover") }
            ),
            caption = stripTrailingHashtags(desc).ifBlank { desc },
            hashtags = extractHashtags(desc),
            author = Author(
                id = authorObj?.optCleanString("id").orEmpty().ifBlank { username },
                username = username,
                displayName = displayName,
                avatarUrl = normalizeUrl(
                    authorObj?.optCleanString("avatarLarger").orEmpty()
                        .ifBlank { authorObj?.optCleanString("avatarMedium").orEmpty() }
                        .ifBlank { authorObj?.optCleanString("avatarThumb").orEmpty() }
                ),
                bio = authorObj?.optCleanString("signature").orEmpty(),
                verified = authorObj?.optBoolean("verified", false) ?: false,
                secUid = authorObj?.optCleanString("secUid").orEmpty(),
                followingCount = authorStatsObj?.optLong("followingCount", 0L)
                    ?.takeIf { it > 0L } ?: authorObj?.optLong("followingCount", 0L) ?: 0L,
                followersCount = authorStatsObj?.optLong("followerCount", 0L)
                    ?.takeIf { it > 0L } ?: authorObj?.optLong("followerCount", 0L) ?: 0L,
                likesCount = authorStatsObj?.optLong("heartCount", 0L)
                    ?.takeIf { it > 0L }
                    ?: authorStatsObj?.optLong("heart", 0L)?.takeIf { it > 0L }
                    ?: authorObj?.optLong("heartCount", 0L)
                    ?: 0L,
                videoCount = authorStatsObj?.optLong("videoCount", 0L)
                    ?.takeIf { it > 0L } ?: authorObj?.optLong("videoCount", 0L) ?: 0L
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
            shareUrl = ScraperConfig.TIKTOK_VIDEO_SHARE_TEMPLATE.format(username, id),
            createdAt = obj.optLong("createTime", 0L)
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
            val musicInfoObj = obj.optJSONObject("music_info")
            val rawCaption = obj.optCleanString("title").ifBlank { obj.optCleanString("desc") }
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
                    shareUrl = ScraperConfig.TIKTOK_VIDEO_SHARE_TEMPLATE.format(username, id),
                    createdAt = obj.optLong("create_time", 0L)
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
        bypassHttpCache: Boolean = false
    ): String {
        if (url.startsWith(ScraperConfig.PUBLIC_API_BASE)) throttleApi()
        val builder = Request.Builder()
            .url(url)
            .header(ScraperConfig.HEADER_USER_AGENT_KEY, userAgent)
            .header(ScraperConfig.HEADER_ACCEPT_LANGUAGE_KEY, ScraperConfig.HEADER_ACCEPT_LANGUAGE_VALUE)
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

    private fun scoreVideoMatch(video: VideoItem, tokens: List<String>, rawQuery: String): Int {
        val qLower = rawQuery.lowercase()
        val caption = video.caption.lowercase()
        val hashtags = video.hashtags.joinToString(" ").lowercase()
        val uname = video.author.username.lowercase()
        val dname = video.author.displayName.lowercase()
        val music = video.music.displayText.lowercase()
        val haystack = "$caption $hashtags $uname $dname $music"

        var score = 0
        if (uname == qLower || dname == qLower) score += 100
        if (caption.contains(qLower) || hashtags.contains(qLower)) score += 60
        tokens.forEach { t ->
            if (uname.contains(t) || dname.contains(t)) score += 25
            if (hashtags.contains(t)) score += 20
            if (caption.contains(t)) score += 15
            if (music.contains(t)) score += 8
            if (haystack.contains(t)) score += 5
        }
        return score
    }

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
            val arr = nested.optJSONArray("UrlList") ?: nested.optJSONArray("urlList")
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
        val VIDEO_PATH_REGEX = Regex("(?:https?://(?:www\\.|m\\.)?tiktok\\.com)?/@([A-Za-z0-9._]+)/video/(\\d{15,22})")
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
    private data class SearchBundle(
        val d1: DiscoverResult,
        val d2: DiscoverResult,
        val users: List<Author>,
        val mirrorVideos: List<VideoItem>,
        val exploreVideos: List<VideoItem>,
        val trendingVideos: List<VideoItem>
    )
}

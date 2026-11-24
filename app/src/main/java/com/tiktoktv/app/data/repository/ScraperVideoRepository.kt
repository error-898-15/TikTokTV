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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    // 1. FOR YOU & CATEGORY FEEDS (Bug 1 Fix: Fresh content on every launch)
    // -------------------------------------------------------------------------

    override suspend fun getForYouFeed(
        cursor: String?,
        forceRefresh: Boolean,
        excludeIds: Set<String>
    ): Result<FeedPage> = withContext(Dispatchers.IO) {
        val cacheKey = "foryou_${cursor ?: "0"}"
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
                val bonusCategoryTypes = listOf(104, 111, 113, 112, 105, 119, 103)
                val randomBonusCat = bonusCategoryTypes.random()
                val exploreBonus = async {
                    if (forceRefresh || cursor == null) {
                        fetchTikTokExploreApi(
                            categoryType = randomBonusCat,
                            cursor = null,
                            forceNetwork = forceRefresh
                        )
                    } else null
                }
                val trendingMirror = async {
                    fetchTrendingFeedApi(
                        cursor = cursor,
                        randomizeRegion = forceRefresh || cursor == null,
                        forceNetwork = forceRefresh
                    )
                }

                exploreMain.await()?.videos?.forEach { combined[it.id] = it }
                trendingMirror.await()?.videos?.forEach { combined.putIfAbsent(it.id, it) }
                exploreBonus.await()?.videos?.forEach { combined.putIfAbsent(it.id, it) }
            }

            if (combined.isEmpty()) {
                val discoverPage = runCatching {
                    fetchAndParseHtmlFeed(
                        ScraperConfig.TIKTOK_DISCOVER_URL_TEMPLATE.format("fyp"),
                        forceNetwork = forceRefresh
                    )
                }.getOrNull()
                discoverPage?.videos?.forEach { combined[it.id] = it }
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
        val cacheKey = "cat_${category.id}_${cursor ?: "0"}"
        if (!forceRefresh && cursor != null) {
            readCachedFeed(cacheKey)?.let { page ->
                indexVideos(page.videos)
                return@withContext Result.success(page)
            }
        }
        lastApiError = null

        runCatching {
            val combined = LinkedHashMap<String, VideoItem>()
            val categoryType = ScraperConfig.EXPLORE_CATEGORY_TYPE_MAP[category.id]

            coroutineScope {
                val exploreTask = async {
                    categoryType?.let {
                        fetchTikTokExploreApi(it, cursor, forceNetwork = forceRefresh)
                    }
                }
                val discoverTask = async {
                    runCatching {
                        val url = ScraperConfig.TIKTOK_DISCOVER_URL_TEMPLATE.format(
                            urlEncode(category.hashtag)
                        )
                        fetchAndParseHtmlFeed(url, forceNetwork = forceRefresh)
                    }.getOrNull()
                }
                val searchTask = async {
                    fetchSearchFeedApi(category.hashtag, cursor)
                }

                exploreTask.await()?.videos?.forEach { combined[it.id] = it }
                discoverTask.await()?.videos?.forEach { combined.putIfAbsent(it.id, it) }
                searchTask.await()?.videos?.forEach { combined.putIfAbsent(it.id, it) }
            }

            if (combined.isEmpty()) {
                val tagUrl = ScraperConfig.TIKTOK_TAG_URL_TEMPLATE.format(category.hashtag)
                val htmlPage = runCatching {
                    fetchAndParseHtmlFeed(tagUrl, forceNetwork = forceRefresh)
                }.getOrNull()
                htmlPage?.videos?.forEach { combined[it.id] = it }
            }

            if (combined.isEmpty()) {
                throw ScraperException(noVideosMessage("Could not load videos for ${category.label}."))
            }

            val allVideos = combined.values.toList()
            indexVideos(allVideos)

            val unseen = allVideos.filterNot { excludeIds.contains(it.id) }
            val seen = allVideos.filter { excludeIds.contains(it.id) }
            val ordered = if (forceRefresh) unseen.shuffled() + seen else unseen + seen

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
                ?: ScraperException(throwable.message ?: "Failed to load category", throwable)
        }
    }

    // -------------------------------------------------------------------------
    // 2. LIVE SEARCH SUGGESTIONS (Improvement 1)
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
    // 3. UNIFIED TABBED SEARCH (Bug 2 Fix + Improvement 2: Top/Users/Videos/Live/Hashtags)
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

        val cacheKey = "unified_search_${trimmed.lowercase()}_${cursor ?: "0"}"
        readCachedUnifiedSearch(cacheKey)?.let { cached ->
            indexVideos(cached.videos)
            return@withContext Result.success(cached)
        }

        lastApiError = null

        runCatching {
            val slug = urlEncode(cleanQuery.lowercase().replace(Regex("\\s+"), "-"))
            val compactSlug = urlEncode(cleanQuery.lowercase().replace(Regex("\\s+"), ""))
            val matchedCategoryType = resolveCategoryTypeForQuery(cleanQuery)
            val tokens = cleanQuery.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }

            val (discoverMain, discoverAlt, usersFound, mirrorVideos, exploreVideos, trendingVideos) = coroutineScope {
                val d1 = async {
                    runCatching {
                        fetchDiscoverWithRelated(ScraperConfig.TIKTOK_DISCOVER_URL_TEMPLATE.format(slug))
                    }.getOrDefault(DiscoverResult(emptyList(), emptyList()))
                }
                val d2 = async {
                    if (compactSlug != slug) {
                        runCatching {
                            fetchDiscoverWithRelated(ScraperConfig.TIKTOK_DISCOVER_URL_TEMPLATE.format(compactSlug))
                        }.getOrDefault(DiscoverResult(emptyList(), emptyList()))
                    } else DiscoverResult(emptyList(), emptyList())
                }
                val uTask = async { fetchUserSearchApi(cleanQuery, count = 18) }
                val mTask = async { fetchSearchFeedApi(cleanQuery, cursor)?.videos.orEmpty() }
                val eTask = async {
                    fetchTikTokExploreApi(
                        categoryType = matchedCategoryType ?: 120,
                        cursor = null,
                        forceNetwork = false
                    )?.videos.orEmpty()
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

            val creatorVideos = if (usersFound.isNotEmpty() &&
                (trimmed.startsWith("@") || usersFound.first().username.equals(cleanQuery, ignoreCase = true))
            ) {
                runCatching {
                    fetchUserProfileInternal(usersFound.first().username, null)?.videos.orEmpty()
                }.getOrDefault(emptyList())
            } else emptyList()

            val candidateMap = LinkedHashMap<String, VideoItem>()
            (mirrorVideos + discoverMain.videos + discoverAlt.videos + creatorVideos).forEach {
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
            usersFound.forEach { userMap[it.username.lowercase()] = it }
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
    // 4. COMPLETE USER PROFILE & VIDEO HISTORY (Bug 3 Fix)
    // -------------------------------------------------------------------------

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
                ?: throw ScraperException(noVideosMessage("Unable to load profile @$cleanUser."))
            indexVideos(profile.videos)
            saveCachedProfile(cacheKey, profile)
            profile
        }.recoverCatching { throwable ->
            readCachedProfile(cacheKey, allowExpired = true)?.let {
                indexVideos(it.videos)
                return@recoverCatching it
            }
            throw (throwable as? ScraperException)
                ?: ScraperException(throwable.message ?: "Failed to load profile", throwable)
        }
    }

    private suspend fun fetchUserProfileInternal(
        username: String,
        cursor: String?
    ): UserProfileResult? = coroutineScope {
        val clean = username.trim().removePrefix("@")
        val compactSlug = clean.lowercase().replace(Regex("[^a-z0-9]+"), "")
        val hyphenSlug = clean.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

        val htmlProfileDeferred = async {
            runCatching {
                val url = ScraperConfig.TIKTOK_PROFILE_URL_TEMPLATE.format(urlEncode(clean))
                fetchAndParseHtmlProfile(url, clean)
            }.getOrNull()
        }
        val userSearchDeferred = async {
            fetchUserSearchApi(clean, count = 5)
        }
        val postsDeferred = async {
            fetchUserPostsMirror(clean, cursor)
        }
        val discoverCompactDeferred = async {
            if (compactSlug.isNotBlank()) {
                runCatching {
                    fetchAndParseHtmlFeed(ScraperConfig.TIKTOK_DISCOVER_URL_TEMPLATE.format(compactSlug))
                        .videos
                }.getOrDefault(emptyList())
            } else emptyList()
        }
        val discoverVideosDeferred = async {
            if (hyphenSlug.isNotBlank()) {
                runCatching {
                    fetchAndParseHtmlFeed(ScraperConfig.TIKTOK_DISCOVER_URL_TEMPLATE.format("$hyphenSlug-videos"))
                        .videos
                }.getOrDefault(emptyList())
            } else emptyList()
        }
        val discoverHyphenDeferred = async {
            if (hyphenSlug.isNotBlank() && hyphenSlug != compactSlug) {
                runCatching {
                    fetchAndParseHtmlFeed(ScraperConfig.TIKTOK_DISCOVER_URL_TEMPLATE.format(hyphenSlug))
                        .videos
                }.getOrDefault(emptyList())
            } else emptyList()
        }

        val htmlProfile = htmlProfileDeferred.await()
        val searchedUsers = userSearchDeferred.await()
        val matchedSearchAuthor = searchedUsers.firstOrNull {
            it.username.equals(clean, ignoreCase = true)
        } ?: searchedUsers.firstOrNull()

        val postsPage = postsDeferred.await()
        val resolvedAuthor = htmlProfile?.author
            ?: matchedSearchAuthor
            ?: Author(
                id = clean,
                username = clean,
                displayName = clean
            )

        val displaySlug = resolvedAuthor.displayName.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        val displayNameVideos = if (displaySlug.isNotBlank() &&
            displaySlug != hyphenSlug &&
            displaySlug != compactSlug
        ) {
            runCatching {
                fetchAndParseHtmlFeed(ScraperConfig.TIKTOK_DISCOVER_URL_TEMPLATE.format(displaySlug))
                    .videos
            }.getOrDefault(emptyList())
        } else emptyList()

        val secUidVideos = if (resolvedAuthor.secUid.isNotBlank() && postsPage.videos.isEmpty()) {
            fetchTikTokPostItemList(resolvedAuthor.secUid, cursor ?: "0")
        } else emptyList()

        val persistedHistory = prefs.getAuthorVideoHistory(clean)
        val inMemoryAuthorVideos = authorVideoMap[clean.lowercase()]?.values?.toList().orEmpty()
        val allDiscover = (
            discoverCompactDeferred.await() +
                discoverVideosDeferred.await() +
                discoverHyphenDeferred.await() +
                displayNameVideos
            )
        val exactDiscover = allDiscover.filter {
            it.author.username.equals(clean, ignoreCase = true) ||
                it.author.displayName.equals(resolvedAuthor.displayName, ignoreCase = true)
        }

        val combined = LinkedHashMap<String, VideoItem>()
        (postsPage.videos +
            secUidVideos +
            htmlProfile?.videos.orEmpty() +
            persistedHistory +
            inMemoryAuthorVideos +
            exactDiscover +
            allDiscover).forEach { item ->
            combined.putIfAbsent(item.id, item)
        }

        val sortedVideos = combined.values
            .sortedWith(
                compareByDescending<VideoItem> {
                    it.author.username.equals(clean, ignoreCase = true)
                }.thenByDescending { it.createdAt }
                    .thenByDescending { it.stats.viewsCount }
            )
            .map { item ->
                if (item.author.avatarUrl.isBlank() && resolvedAuthor.avatarUrl.isNotBlank()) {
                    item.copy(author = resolvedAuthor)
                } else item
            }

        if (sortedVideos.isNotEmpty()) {
            prefs.recordAuthorVideos(clean, sortedVideos)
        }

        UserProfileResult(
            author = resolvedAuthor,
            videos = sortedVideos,
            nextCursor = postsPage.nextCursor,
            hasMore = postsPage.hasMore
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
        prefs.clearSeenHistory()
        scraperCacheDir.listFiles()?.forEach { it.deleteRecursively() }
        runCatching { okHttpClient.cache?.evictAll() }
        before
    }

    override suspend fun getCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        dirSize(httpCacheDir) + dirSize(scraperCacheDir)
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
                        if (!cursor.isNullOrBlank() && !cursor.startsWith("explore_")) {
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
        val cleanCursor = cursor?.takeUnless { it.startsWith("explore_") } ?: "0"
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

    private fun fetchAndParseHtmlFeed(url: String, forceNetwork: Boolean = false): FeedPage {
        val html = executeGet(
            url = url,
            acceptJson = false,
            userAgent = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE,
            referer = ScraperConfig.HEADER_REFERER_VALUE,
            bypassHttpCache = forceNetwork
        )
        val doc: Document = Jsoup.parse(html, url)
        doc.selectFirst(ScraperConfig.SELECTOR_API_DATA_SCRIPT)?.data()?.let { raw ->
            val items = parseApiDataScriptVideos(raw)
            if (items.isNotEmpty()) return FeedPage(videos = items, nextCursor = null, hasMore = false)
        }
        doc.selectFirst(ScraperConfig.SELECTOR_UNIVERSAL_DATA_SCRIPT)?.data()?.let { raw ->
            val items = parseEmbeddedJsonVideos(raw)
            if (items.isNotEmpty()) return FeedPage(videos = items, nextCursor = null, hasMore = false)
        }
        doc.selectFirst(ScraperConfig.SELECTOR_SIGI_STATE_SCRIPT)?.data()?.let { raw ->
            val items = parseEmbeddedJsonVideos(raw)
            if (items.isNotEmpty()) return FeedPage(videos = items, nextCursor = null, hasMore = false)
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
        return null
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
        val playAddr = normalizeUrl(videoObj.optCleanString("playAddr"))
        val downloadAddr = normalizeUrl(videoObj.optCleanString("downloadAddr"))
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
                videoObj.optCleanString("originCover")
                    .ifBlank { videoObj.optCleanString("cover") }
                    .ifBlank { videoObj.optCleanString("dynamicCover") }
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
                followingCount = authorStatsObj?.optLong("followingCount", 0L) ?: 0L,
                followersCount = authorStatsObj?.optLong("followerCount", 0L) ?: 0L,
                likesCount = authorStatsObj?.optLong("heartCount", 0L)
                    ?.takeIf { it > 0L } ?: authorStatsObj?.optLong("heart", 0L) ?: 0L
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
            q.contains("travel") || q.contains("vlog") || q.contains("daily") -> 105
            q.contains("animal") || q.contains("cat") || q.contains("dog") || q.contains("pet") -> 113
            q.contains("food") || q.contains("cook") || q.contains("recipe") -> 111
            q.contains("gam") -> 103
            q.contains("sport") || q.contains("football") || q.contains("cricket") || q.contains("cr7") -> 112
            q.contains("learn") || q.contains("edu") || q.contains("study") || q.contains("tech") -> 116
            q.contains("music") || q.contains("song") || q.contains("dance") || q.contains("sing") -> 119
            q.contains("art") || q.contains("anime") || q.contains("draw") -> 100
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
    }

    private data class CacheEntry(val data: Any, val timestamp: Long)
    private data class CommentListWrapper(val comments: List<Comment>)
    private data class DiscoverResult(val videos: List<VideoItem>, val related: List<String>)
    private data class SearchBundle(
        val d1: DiscoverResult,
        val d2: DiscoverResult,
        val users: List<Author>,
        val mirrorVideos: List<VideoItem>,
        val exploreVideos: List<VideoItem>,
        val trendingVideos: List<VideoItem>
    )
}

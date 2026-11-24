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

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val list = cookieStore.getOrPut(host) { mutableListOf() }
            cookies.forEach { incoming ->
                list.removeAll { it.name == incoming.name }
                list.add(incoming)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host]?.filter { it.expiresAt >= System.currentTimeMillis() }
                ?: emptyList()
        }
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(ScraperConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(ScraperConfig.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .cache(Cache(httpCacheDir, ScraperConfig.HTTP_DISK_CACHE_BYTES))
        .followRedirects(true)
        .build()

    override suspend fun getForYouFeed(
        cursor: String?,
        forceRefresh: Boolean
    ): Result<FeedPage> = withContext(Dispatchers.IO) {
        val cacheKey = "foryou_${cursor ?: "0"}"
        if (!forceRefresh) {
            readCachedFeed(cacheKey)?.let { return@withContext Result.success(it) }
        }

        runCatching {
            val apiResult = fetchTrendingFeedApi(cursor)
            if (apiResult != null && apiResult.videos.isNotEmpty()) {
                saveCachedFeed(cacheKey, apiResult)
                return@runCatching apiResult
            }

            val htmlPage = fetchAndParseHtmlFeed(ScraperConfig.TIKTOK_EXPLORE_URL)
            if (htmlPage.videos.isNotEmpty()) {
                saveCachedFeed(cacheKey, htmlPage)
                return@runCatching htmlPage
            }

            throw ScraperException("No videos returned from feed endpoints.")
        }.recoverCatching { throwable ->
            readCachedFeed(cacheKey, allowExpired = true)?.let { return@recoverCatching it }
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
            readCachedFeed(cacheKey)?.let { return@withContext Result.success(it) }
        }

        runCatching {
            val apiResult = fetchSearchFeedApi(category.hashtag, cursor)
            if (apiResult != null && apiResult.videos.isNotEmpty()) {
                saveCachedFeed(cacheKey, apiResult)
                return@runCatching apiResult
            }

            val tagUrl = ScraperConfig.TIKTOK_TAG_URL_TEMPLATE.format(category.hashtag)
            val htmlPage = fetchAndParseHtmlFeed(tagUrl)
            if (htmlPage.videos.isNotEmpty()) {
                saveCachedFeed(cacheKey, htmlPage)
                return@runCatching htmlPage
            }

            throw ScraperException("Could not load videos for ${category.label}.")
        }.recoverCatching { throwable ->
            readCachedFeed(cacheKey, allowExpired = true)?.let { return@recoverCatching it }
            throw (throwable as? ScraperException)
                ?: ScraperException(throwable.message ?: "Failed to load category", throwable)
        }
    }

    override suspend fun searchVideos(
        query: String,
        cursor: String?
    ): Result<FeedPage> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().removePrefix("#")
        if (cleanQuery.isBlank()) {
            return@withContext Result.success(FeedPage(emptyList(), null, false))
        }
        val cacheKey = "search_${cleanQuery.lowercase()}_${cursor ?: "0"}"
        readCachedFeed(cacheKey)?.let { return@withContext Result.success(it) }

        runCatching {
            val apiResult = fetchSearchFeedApi(cleanQuery, cursor)
            if (apiResult != null && apiResult.videos.isNotEmpty()) {
                saveCachedFeed(cacheKey, apiResult)
                return@runCatching apiResult
            }

            val tagUrl = ScraperConfig.TIKTOK_TAG_URL_TEMPLATE.format(cleanQuery.replace(" ", ""))
            val htmlPage = fetchAndParseHtmlFeed(tagUrl)
            if (htmlPage.videos.isNotEmpty()) {
                saveCachedFeed(cacheKey, htmlPage)
                return@runCatching htmlPage
            }

            FeedPage(emptyList(), null, false)
        }.recoverCatching { throwable ->
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
        val cacheKey = "profile_${cleanUser}_${cursor ?: "0"}"
        if (!forceRefresh) {
            readCachedProfile(cacheKey)?.let { return@withContext Result.success(it) }
        }

        runCatching {
            val apiProfile = fetchUserProfileApi(cleanUser, cursor)
            if (apiProfile != null) {
                saveCachedProfile(cacheKey, apiProfile)
                return@runCatching apiProfile
            }

            val url = ScraperConfig.TIKTOK_PROFILE_URL_TEMPLATE.format(cleanUser)
            val htmlProfile = fetchAndParseHtmlProfile(url, cleanUser)
            if (htmlProfile != null) {
                saveCachedProfile(cacheKey, htmlProfile)
                return@runCatching htmlProfile
            }

            throw ScraperException("Unable to load profile @$cleanUser.")
        }.recoverCatching { throwable ->
            readCachedProfile(cacheKey, allowExpired = true)?.let { return@recoverCatching it }
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
            val url = ScraperConfig.API_COMMENT_LIST.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("url", "${ScraperConfig.TIKTOK_WEB_BASE}/video/$videoId")
                ?.addQueryParameter("count", "20")
                ?.addQueryParameter("cursor", cursor ?: "0")
                ?.build()
                ?: throw ScraperException("Invalid comments endpoint URL")

            val jsonText = executeGet(url.toString(), acceptJson = true)
            val root = JSONObject(jsonText)
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
        scraperCacheDir.listFiles()?.forEach { it.deleteRecursively() }
        runCatching { okHttpClient.cache?.evictAll() }
        before
    }

    override suspend fun getCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        dirSize(httpCacheDir) + dirSize(scraperCacheDir)
    }

    private fun fetchTrendingFeedApi(cursor: String?): FeedPage? {
        return runCatching {
            val form = FormBody.Builder()
                .add("region", "US")
                .add("count", ScraperConfig.DEFAULT_PAGE_SIZE.toString())
                .apply {
                    if (!cursor.isNullOrBlank()) add("cursor", cursor)
                }
                .build()

            val body = executePost(ScraperConfig.API_TRENDING_FEED, form)
            val root = JSONObject(body)
            if (root.optInt("code", -1) != 0) return null

            val data = root.opt("data")
            val list = when (data) {
                is JSONArray -> data
                is JSONObject -> data.optJSONArray("videos") ?: JSONArray()
                else -> JSONArray()
            }
            val videos = parseApiVideoArray(list)
            val nextCursor = if (data is JSONObject) {
                data.optString("cursor").takeIf { it.isNotBlank() && it != "0" }
            } else {
                (videos.lastOrNull()?.id)?.let { "${System.currentTimeMillis() / 1000}" }
            }
            val hasMore = if (data is JSONObject) data.optBoolean("hasMore", videos.isNotEmpty()) else videos.isNotEmpty()
            FeedPage(videos = videos, nextCursor = nextCursor, hasMore = hasMore)
        }.getOrNull()
    }

    private fun fetchSearchFeedApi(keywords: String, cursor: String?): FeedPage? {
        return runCatching {
            val form = FormBody.Builder()
                .add("keywords", keywords)
                .add("count", ScraperConfig.DEFAULT_PAGE_SIZE.toString())
                .add("cursor", cursor ?: "0")
                .add("web", "1")
                .add("hd", "1")
                .build()

            val body = executePost(ScraperConfig.API_SEARCH_FEED, form)
            val root = JSONObject(body)
            if (root.optInt("code", -1) != 0) return null

            val data = root.optJSONObject("data") ?: return null
            val list = data.optJSONArray("videos") ?: JSONArray()
            val videos = parseApiVideoArray(list)
            val nextCursor = data.optString("cursor").takeIf { it.isNotBlank() && it != "0" }
            val hasMore = data.optBoolean("hasMore", false)
            FeedPage(videos = videos, nextCursor = nextCursor, hasMore = hasMore)
        }.getOrNull()
    }

    private fun fetchUserProfileApi(username: String, cursor: String?): UserProfileResult? {
        return runCatching {
            val infoUrl = ScraperConfig.API_USER_INFO.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("unique_id", username)
                ?.build()
                ?.toString()
                ?: return null

            val infoJson = JSONObject(executeGet(infoUrl, acceptJson = true))
            val infoData = infoJson.optJSONObject("data")
            val userObj = infoData?.optJSONObject("user")
            val statsObj = infoData?.optJSONObject("stats")

            val postsForm = FormBody.Builder()
                .add("unique_id", username)
                .add("count", "30")
                .add("cursor", cursor ?: "0")
                .build()
            val postsJson = JSONObject(executePost(ScraperConfig.API_USER_POSTS, postsForm))
            val postsData = postsJson.optJSONObject("data")
            val videoArray = postsData?.optJSONArray("videos") ?: JSONArray()
            val videos = parseApiVideoArray(videoArray)

            if (userObj == null && videos.isEmpty()) return null

            val firstVideoAuthor = videos.firstOrNull()?.author
            val author = Author(
                id = userObj?.optString("id").orEmpty().ifBlank { firstVideoAuthor?.id ?: username },
                username = userObj?.optString("uniqueId").orEmpty().ifBlank { username },
                displayName = userObj?.optString("nickname").orEmpty()
                    .ifBlank { firstVideoAuthor?.displayName ?: username },
                avatarUrl = normalizeUrl(
                    userObj?.optString("avatarLarger")
                        ?.takeIf { it.isNotBlank() }
                        ?: userObj?.optString("avatarMedium")
                        ?: firstVideoAuthor?.avatarUrl.orEmpty()
                ),
                bio = userObj?.optString("signature").orEmpty(),
                verified = userObj?.optBoolean("verified", false) ?: false,
                followingCount = statsObj?.optLong("followingCount", 0L) ?: 0L,
                followersCount = statsObj?.optLong("followerCount", 0L) ?: 0L,
                likesCount = statsObj?.optLong("heartCount", 0L)?.takeIf { it > 0L }
                    ?: statsObj?.optLong("heart", 0L) ?: 0L
            )

            val enrichedVideos = videos.map { item ->
                if (item.author.avatarUrl.isBlank()) item.copy(author = author) else item
            }

            UserProfileResult(
                author = author,
                videos = enrichedVideos,
                nextCursor = postsData?.optString("cursor")?.takeIf { it.isNotBlank() && it != "0" },
                hasMore = postsData?.optBoolean("hasMore", false) ?: false
            )
        }.getOrNull()
    }

    private fun fetchAndParseHtmlFeed(url: String): FeedPage {
        val html = executeGet(url, acceptJson = false)
        val doc: Document = Jsoup.parse(html, url)

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
        if (ogVideo.isNotBlank()) {
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
        val html = executeGet(url, acceptJson = false)
        val doc: Document = Jsoup.parse(html, url)

        doc.selectFirst(ScraperConfig.SELECTOR_UNIVERSAL_DATA_SCRIPT)?.data()?.let { raw ->
            val root = runCatching { JSONObject(raw) }.getOrNull()
            val defaultScope = root?.optJSONObject(ScraperConfig.JSON_KEY_DEFAULT_SCOPE)
            val userDetail = defaultScope?.optJSONObject(ScraperConfig.JSON_KEY_WEBAPP_USER_DETAIL)
            val userInfo = userDetail?.optJSONObject(ScraperConfig.JSON_KEY_USER_INFO)
            val userObj = userInfo?.optJSONObject("user")
            val statsObj = userInfo?.optJSONObject(ScraperConfig.JSON_KEY_STATS)
            val videos = parseEmbeddedJsonVideos(raw)

            if (userObj != null) {
                val author = Author(
                    id = userObj.optString("id", username),
                    username = userObj.optString("uniqueId", username),
                    displayName = userObj.optString("nickname", username),
                    avatarUrl = userObj.optString("avatarLarger")
                        .ifBlank { userObj.optString("avatarMedium") },
                    bio = userObj.optString("signature", ""),
                    verified = userObj.optBoolean("verified", false),
                    followingCount = statsObj?.optLong("followingCount", 0L) ?: 0L,
                    followersCount = statsObj?.optLong("followerCount", 0L) ?: 0L,
                    likesCount = statsObj?.optLong("heartCount", 0L) ?: 0L
                )
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
            val videos = parseEmbeddedJsonVideos(
                doc.selectFirst(ScraperConfig.SELECTOR_SIGI_STATE_SCRIPT)?.data().orEmpty()
            )
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

    private fun parseApiVideoArray(array: JSONArray): List<VideoItem> {
        val result = ArrayList<VideoItem>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("video_id").ifBlank { obj.optString("aweme_id") }
            val play = normalizeUrl(obj.optString("play"))
            val hdPlay = normalizeUrl(obj.optString("hdplay"))
            val wmPlay = normalizeUrl(obj.optString("wmplay"))
            val playable = play.ifBlank { hdPlay }.ifBlank { wmPlay }
            if (id.isBlank() || playable.isBlank()) continue

            val authorObj = obj.optJSONObject("author")
            val username = authorObj?.optString("unique_id").orEmpty().ifBlank { "creator" }
            val displayName = authorObj?.optString("nickname").orEmpty().ifBlank { username }
            val avatar = normalizeUrl(authorObj?.optString("avatar").orEmpty())
            val authorId = authorObj?.optString("id").orEmpty().ifBlank { username }

            val musicInfoObj = obj.optJSONObject("music_info")
            val musicTitle = musicInfoObj?.optString("title")
                ?.ifBlank { obj.optString("music") }
                .orEmpty()
                .ifBlank { "Original Sound - $displayName" }
            val musicAuthor = musicInfoObj?.optString("author").orEmpty().ifBlank { displayName }
            val musicCover = normalizeUrl(musicInfoObj?.optString("cover").orEmpty())

            val rawCaption = obj.optString("title").orEmpty()
            val hashtags = extractHashtags(rawCaption)
            val cleanCaption = stripTrailingHashtags(rawCaption)

            result.add(
                VideoItem(
                    id = id,
                    videoUrl = playable,
                    hdVideoUrl = hdPlay.ifBlank { playable },
                    sdVideoUrl = wmPlay.ifBlank { playable },
                    coverUrl = normalizeUrl(
                        obj.optString("origin_cover")
                            .ifBlank { obj.optString("cover") }
                            .ifBlank { obj.optString("ai_dynamic_cover") }
                    ),
                    caption = cleanCaption.ifBlank { rawCaption },
                    hashtags = hashtags,
                    author = Author(
                        id = authorId,
                        username = username,
                        displayName = displayName,
                        avatarUrl = avatar,
                        verified = authorObj?.optInt("verified", 0) == 1
                    ),
                    music = MusicInfo(
                        id = musicInfoObj?.optString("id").orEmpty(),
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
                ?: return emptyList()

            val userModule = root.optJSONObject(ScraperConfig.JSON_KEY_USER_MODULE)
                ?.optJSONObject(ScraperConfig.JSON_KEY_USERS)

            val list = mutableListOf<VideoItem>()
            val keys = itemModule.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = itemModule.optJSONObject(key) ?: continue
                val id = obj.optString("id", key)
                val videoObj = obj.optJSONObject("video") ?: continue
                val playAddr = videoObj.optString("playAddr")
                    .ifBlank { videoObj.optString("downloadAddr") }
                if (playAddr.isBlank()) continue

                val authorKey = obj.optString("author")
                val authorObj = obj.optJSONObject("author")
                    ?: userModule?.optJSONObject(authorKey)
                val username = authorObj?.optString("uniqueId").orEmpty()
                    .ifBlank { authorKey.ifBlank { "creator" } }
                val displayName = authorObj?.optString("nickname").orEmpty().ifBlank { username }
                val avatar = authorObj?.optString("avatarLarger")
                    ?.ifBlank { authorObj.optString("avatarMedium") }
                    .orEmpty()

                val musicObj = obj.optJSONObject("music")
                val statsObj = obj.optJSONObject("stats")
                val desc = obj.optString("desc", "")

                list.add(
                    VideoItem(
                        id = id,
                        videoUrl = playAddr,
                        hdVideoUrl = playAddr,
                        sdVideoUrl = playAddr,
                        coverUrl = videoObj.optString("originCover")
                            .ifBlank { videoObj.optString("cover") },
                        caption = stripTrailingHashtags(desc).ifBlank { desc },
                        hashtags = extractHashtags(desc),
                        author = Author(
                            id = authorObj?.optString("id").orEmpty().ifBlank { username },
                            username = username,
                            displayName = displayName,
                            avatarUrl = avatar,
                            bio = authorObj?.optString("signature").orEmpty(),
                            verified = authorObj?.optBoolean("verified", false) ?: false
                        ),
                        music = MusicInfo(
                            id = musicObj?.optString("id").orEmpty(),
                            title = musicObj?.optString("title").orEmpty().ifBlank { "Original Sound" },
                            author = musicObj?.optString("authorName").orEmpty(),
                            coverUrl = musicObj?.optString("coverLarge").orEmpty()
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
            list
        }.getOrDefault(emptyList())
    }

    private fun parseApiComments(array: JSONArray): List<Comment> {
        val list = ArrayList<Comment>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val userObj = obj.optJSONObject("user")
            val text = obj.optString("text").trim()
            if (text.isBlank()) continue
            list.add(
                Comment(
                    id = obj.optString("id").ifBlank { "$i-${text.hashCode()}" },
                    authorName = userObj?.optString("nickname").orEmpty()
                        .ifBlank { userObj?.optString("unique_id").orEmpty().ifBlank { "User" } },
                    authorUsername = userObj?.optString("unique_id").orEmpty().ifBlank { "user" },
                    authorAvatarUrl = normalizeUrl(userObj?.optString("avatar").orEmpty()),
                    text = text,
                    likesCount = obj.optLong("digg_count", 0L),
                    createdAtText = obj.optString("create_time_str").orEmpty()
                )
            )
        }
        return list
    }

    private fun executeGet(url: String, acceptJson: Boolean): String {
        val request = Request.Builder()
            .url(url)
            .header(ScraperConfig.HEADER_USER_AGENT_KEY, ScraperConfig.HEADER_USER_AGENT_VALUE)
            .header(ScraperConfig.HEADER_ACCEPT_LANGUAGE_KEY, ScraperConfig.HEADER_ACCEPT_LANGUAGE_VALUE)
            .header(ScraperConfig.HEADER_REFERER_KEY, ScraperConfig.HEADER_REFERER_VALUE)
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
        val request = Request.Builder()
            .url(url)
            .header(ScraperConfig.HEADER_USER_AGENT_KEY, ScraperConfig.HEADER_USER_AGENT_VALUE)
            .header(ScraperConfig.HEADER_ACCEPT_LANGUAGE_KEY, ScraperConfig.HEADER_ACCEPT_LANGUAGE_VALUE)
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

    private fun normalizeUrl(raw: String): String {
        if (raw.isBlank()) return ""
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "${ScraperConfig.PUBLIC_API_BASE.removeSuffix("/api")}$raw"
            else -> raw
        }
    }

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

    private data class CacheEntry(val data: Any, val timestamp: Long)
    private data class CommentListWrapper(val comments: List<Comment>)
}

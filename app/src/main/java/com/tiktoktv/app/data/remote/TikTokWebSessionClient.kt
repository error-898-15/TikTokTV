package com.tiktoktv.app.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tiktoktv.app.data.config.ScraperConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WebSessionPageResponse(
    val rawApiJson: String,
    val secUid: String,
    val userId: String,
    val userDetailJson: String,
    val domVideoRefs: List<Pair<String, String>> = emptyList()
)

data class WebSessionBatchResponse(
    val rawPages: List<String>,
    val secUid: String,
    val userId: String,
    val userDetailJson: String,
    val domVideoRefs: List<Pair<String, String>> = emptyList()
)

data class WebSearchSessionResponse(
    val rawGeneralJson: String = "",
    val rawItemJson: String = "",
    val rawUserJson: String = "",
    val rawLiveJson: String = "",
    val domVideoRefs: List<Pair<String, String>> = emptyList()
)

/**
 * Headless Chromium WebView bridge that executes TikTok's own webmssdk / byted_acrawler
 * signing context on the device, captures TikTok's own /api/post/item_list/ responses,
 * and paginates ALL public videos beyond the ~30-video SSR HTML limit.
 */
class TikTokWebSessionClient(
    private val context: Context,
    private val onCookiesSynced: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val sessionMutex = Mutex()
    private val pendingResults = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private var webView: WebView? = null

    @Volatile
    private var currentUsername: String = ""

    @Volatile
    private var currentSecUid: String = ""

    @Volatile
    private var currentUserId: String = ""

    @Volatile
    private var currentUserDetailJson: String = ""

    @Volatile
    private var currentPageReady: Boolean = false

    @Volatile
    private var currentUseDesktopUa: Boolean = false

    @Volatile
    private var currentDomRefs: List<Pair<String, String>> = emptyList()

    @Volatile
    private var capturedPostParams: Map<String, String> = emptyMap()

    @Volatile
    private var capturedSearchParams: Map<String, String> = emptyMap()

    @Volatile
    private var currentSearchQuery: String = ""

    @Volatile
    private var currentSearchType: String = ""

    @Volatile
    private var pageLoadDeferred: CompletableDeferred<Boolean>? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun ensureWebViewOnMain(useDesktopUa: Boolean): WebView? = withContext(Dispatchers.Main) {
        val targetUa = if (useDesktopUa) {
            ScraperConfig.HEADER_USER_AGENT_VALUE
        } else {
            ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE
        }

        fun applyOffscreenLayout(target: WebView, desktop: Boolean) {
            val w = if (desktop) 1440 else 1080
            val h = if (desktop) 900 else 1920
            val wSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            target.measure(wSpec, hSpec)
            target.layout(0, 0, w, h)
        }

        webView?.let { existing ->
            existing.onResume()
            existing.resumeTimers()
            if (existing.settings.userAgentString != targetUa) {
                existing.settings.userAgentString = targetUa
                applyOffscreenLayout(existing, useDesktopUa)
            }
            return@withContext existing
        }

        runCatching {
            val view = WebView(appContext)
            applyOffscreenLayout(view, useDesktopUa)
            view.onResume()
            view.resumeTimers()

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(view, true)

            with(view.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = targetUa
                loadsImagesAutomatically = false
                blockNetworkImage = true
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
            }

            view.addJavascriptInterface(BridgeObject(), "TikTokTvBridge")
            view.webChromeClient = WebChromeClient()
            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(v, url, favicon)
                    v?.evaluateJavascript(NETWORK_SNIFFER_BOOTSTRAP_JS, null)
                }

                override fun onPageFinished(v: WebView?, url: String?) {
                    super.onPageFinished(v, url)
                    if (url.isNullOrBlank() || url == "about:blank") return
                    if (v == null) {
                        pageLoadDeferred?.complete(true)
                        return
                    }
                    v.evaluateJavascript(NETWORK_SNIFFER_BOOTSTRAP_JS, null)
                    v.evaluateJavascript(WAF_CHECK_JS) { raw ->
                        val cleaned = raw?.trim()?.trim('"') ?: "READY"
                        if (cleaned != "WAF") {
                            pageLoadDeferred?.complete(true)
                        }
                    }
                }

                override fun shouldOverrideUrlLoading(
                    v: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return true
                    return shouldBlockNavigation(uri)
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean {
                    val uri = runCatching { Uri.parse(url ?: return true) }.getOrNull() ?: return true
                    return shouldBlockNavigation(uri)
                }

                override fun shouldInterceptRequest(
                    v: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val uri = request?.url ?: return null
                    val urlStr = uri.toString()

                    if (urlStr.contains("/api/post/item_list/")) {
                        capturePostQueryTemplate(uri)
                        return null
                    }
                    if (urlStr.contains("/api/creator/item_list/")) {
                        return null
                    }
                    if (urlStr.contains("/api/search/general/full/") || urlStr.contains("/api/search/item/full/")) {
                        captureSearchQueryTemplate(uri)
                        return null
                    }

                    val pathLower = uri.path?.lowercase().orEmpty()
                    if (BLOCKED_EXTENSIONS.any { pathLower.endsWith(it) }) {
                        return emptyResponse()
                    }

                    val hostLower = uri.host?.lowercase().orEmpty()
                    if ((hostLower.startsWith("mon") && hostLower.contains("tiktokv")) ||
                        pathLower.contains("monitor_browser")
                    ) {
                        return emptyResponse()
                    }
                    return null
                }
            }

            webView = view
            view
        }.getOrNull()
    }

    private fun shouldBlockNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return true
        val host = uri.host?.lowercase().orEmpty()
        return !host.endsWith("tiktok.com")
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun capturePostQueryTemplate(uri: Uri) {
        runCatching {
            val names = uri.queryParameterNames ?: return
            val map = LinkedHashMap<String, String>()
            for (name in names) {
                if (name in STRIPPED_QUERY_KEYS) continue
                val value = uri.getQueryParameter(name) ?: continue
                map[name] = value
            }
            if (map.isNotEmpty()) {
                capturedPostParams = map
            }
        }
    }

    private fun captureSearchQueryTemplate(uri: Uri) {
        runCatching {
            val names = uri.queryParameterNames ?: return
            val map = LinkedHashMap<String, String>()
            for (name in names) {
                if (name in STRIPPED_SEARCH_KEYS) continue
                val value = uri.getQueryParameter(name) ?: continue
                map[name] = value
            }
            if (map.isNotEmpty()) {
                capturedSearchParams = map
            }
        }
    }

    suspend fun fetchUserPostsPage(
        username: String,
        secUidHint: String = "",
        userIdHint: String = "",
        cursor: String = "0",
        count: Int = ScraperConfig.PROFILE_WEB_PAGE_SIZE,
        forceReload: Boolean = false,
        regionCode: String = "BD",
        timeZone: String = "Asia/Dhaka"
    ): WebSessionPageResponse? = sessionMutex.withLock {
        val cleanUser = username.trim().removePrefix("@")
        if (cleanUser.isBlank()) return@withLock null

        val isInitialPage = cursor == "0"
        val preferDesktop = if (!isInitialPage && currentUsername.equals(cleanUser, ignoreCase = true)) {
            currentUseDesktopUa
        } else {
            true
        }

        val firstAttempt = executeFetchUserPostsLocked(
            cleanUser = cleanUser,
            secUidHint = secUidHint,
            userIdHint = userIdHint,
            cursor = cursor,
            count = count,
            forceReload = forceReload,
            useDesktopUa = preferDesktop,
            regionCode = regionCode,
            timeZone = timeZone
        )

        val shouldRetryAltUa = !hasNonEmptyItemList(firstAttempt?.rawApiJson) &&
            (isInitialPage || firstAttempt == null || firstAttempt.rawApiJson.isBlank())
        if (shouldRetryAltUa) {
            val altAttempt = executeFetchUserPostsLocked(
                cleanUser = cleanUser,
                secUidHint = firstAttempt?.secUid?.ifBlank { secUidHint } ?: secUidHint,
                userIdHint = firstAttempt?.userId?.ifBlank { userIdHint } ?: userIdHint,
                cursor = cursor,
                count = count,
                forceReload = true,
                useDesktopUa = !preferDesktop,
                regionCode = regionCode,
                timeZone = timeZone
            )
            if (altAttempt != null &&
                (hasNonEmptyItemList(altAttempt.rawApiJson) || altAttempt.domVideoRefs.isNotEmpty())
            ) {
                return@withLock altAttempt
            }
            if (firstAttempt != null) return@withLock firstAttempt
            return@withLock altAttempt
        }

        return@withLock firstAttempt
    }


    suspend fun fetchUserPostsPagesBatch(
        username: String,
        secUidHint: String = "",
        userIdHint: String = "",
        startCursor: String = "0",
        count: Int = ScraperConfig.PROFILE_WEB_PAGE_SIZE,
        maxPages: Int = 3,
        forceReload: Boolean = false,
        regionCode: String = "BD",
        timeZone: String = "Asia/Dhaka"
    ): WebSessionBatchResponse? = sessionMutex.withLock {
        val cleanUser = username.trim().removePrefix("@")
        if (cleanUser.isBlank()) return@withLock null

        val isInitialPage = startCursor == "0"
        val preferDesktop = if (!isInitialPage && currentUsername.equals(cleanUser, ignoreCase = true)) {
            currentUseDesktopUa
        } else {
            true
        }

        suspend fun runBatch(useDesktop: Boolean, reload: Boolean): WebSessionBatchResponse? {
            val ready = ensureProfileSessionLocked(cleanUser, reload, useDesktop, regionCode)
            if (!ready && secUidHint.isBlank()) {
                return if (currentDomRefs.isNotEmpty() || currentUserDetailJson.isNotBlank()) {
                    WebSessionBatchResponse(
                        rawPages = emptyList(),
                        secUid = currentSecUid,
                        userId = currentUserId,
                        userDetailJson = currentUserDetailJson,
                        domVideoRefs = currentDomRefs
                    )
                } else null
            }

            val effectiveSecUid = currentSecUid.ifBlank { secUidHint }
            val effectiveUserId = currentUserId.ifBlank { userIdHint }
            if (effectiveSecUid.isBlank()) return null

            val capturedJson = JSONObject().apply {
                capturedPostParams.forEach { (k, v) -> put(k, v) }
            }.toString()

            val rawResult = evalAsyncJs(
                scriptBody = buildFetchPostBatchScript(
                    secUid = effectiveSecUid,
                    userId = effectiveUserId,
                    startCursor = startCursor,
                    count = count,
                    maxPages = maxPages.coerceIn(1, 5),
                    capturedTemplateJson = capturedJson,
                    fallbackSdkUrl = ScraperConfig.WEBMSSDK_FALLBACK_URL,
                    isDesktopUa = useDesktop,
                    allowCapturedFirstPage = !reload,
                    regionCode = regionCode,
                    timeZone = timeZone
                ),
                timeoutMs = ScraperConfig.WEB_SESSION_TIMEOUT_MS + (maxPages * 3_500L),
                useDesktopUa = useDesktop
            )
            syncCookiesFromManager()

            val parsed = rawResult?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            val pagesArr = parsed.optJSONArray("pages")
            val rawPages = mutableListOf<String>()
            if (pagesArr != null) {
                for (i in 0 until pagesArr.length()) {
                    val item = pagesArr.optString(i, "").trim()
                    if (item.startsWith("{")) rawPages.add(item)
                }
            }
            val fetchedDomRefs = parseDomRefs(parsed.optJSONArray("domRefs"))
            val combinedDomRefs = (currentDomRefs + fetchedDomRefs).distinctBy { it.second }
            if (combinedDomRefs.isNotEmpty()) {
                currentDomRefs = combinedDomRefs
            }
            if (rawPages.isEmpty() && combinedDomRefs.isEmpty() && currentUserDetailJson.isBlank()) {
                return null
            }
            return WebSessionBatchResponse(
                rawPages = rawPages,
                secUid = effectiveSecUid,
                userId = effectiveUserId,
                userDetailJson = currentUserDetailJson,
                domVideoRefs = combinedDomRefs
            )
        }

        val first = runBatch(preferDesktop, forceReload)
        val hasAnyItems = first?.rawPages?.any { hasNonEmptyItemList(it) } == true
        if (!hasAnyItems && (isInitialPage || first == null || first.rawPages.isEmpty())) {
            val alt = runBatch(!preferDesktop, true)
            if (alt != null && (alt.rawPages.any { hasNonEmptyItemList(it) } || alt.domVideoRefs.isNotEmpty())) {
                return@withLock alt
            }
        }
        return@withLock first
    }

    suspend fun searchTikTokWeb(
        query: String,
        searchType: String = "general",
        offset: Int = 0,
        count: Int = ScraperConfig.SEARCH_PAGE_SIZE,
        regionCode: String = "BD",
        timeZone: String = "Asia/Dhaka"
    ): WebSearchSessionResponse? = sessionMutex.withLock {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withLock null

        val view = ensureWebViewOnMain(useDesktopUa = true) ?: return@withLock null
        val normalizedType = if (searchType == "video") "item" else searchType
        val isNewSearch = !currentSearchQuery.equals(cleanQuery, ignoreCase = true) ||
            (offset == 0 && currentSearchType != normalizedType) ||
            offset == 0 ||
            !currentUseDesktopUa
        if (isNewSearch) {
            currentUsername = ""
            currentSecUid = ""
            currentUserId = ""
            currentUserDetailJson = ""
            currentPageReady = false
            currentUseDesktopUa = true
            currentSearchQuery = cleanQuery
            currentSearchType = normalizedType
            capturedSearchParams = emptyMap()

            val targetUrl = if (normalizedType == "item") {
                ScraperConfig.TIKTOK_SEARCH_VIDEO_URL_TEMPLATE.format(Uri.encode(cleanQuery))
            } else {
                ScraperConfig.TIKTOK_SEARCH_URL_TEMPLATE.format(Uri.encode(cleanQuery))
            }
            val deferred = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                pageLoadDeferred = null
                view.stopLoading()
                pageLoadDeferred = deferred
                view.loadUrl(targetUrl)
            }
            withTimeoutOrNull(13_000L) { deferred.await() }
            pageLoadDeferred = null
        }

        val capturedSearchJson = JSONObject().apply {
            capturedSearchParams.forEach { (k, v) -> put(k, v) }
        }.toString()

        val rawResult = evalAsyncJs(
            scriptBody = buildSearchWebScript(
                query = cleanQuery,
                searchType = normalizedType,
                offset = offset,
                count = count,
                regionCode = regionCode,
                timeZone = timeZone,
                capturedTemplateJson = capturedSearchJson,
                fallbackSdkUrl = ScraperConfig.WEBMSSDK_FALLBACK_URL
            ),
            timeoutMs = 16_000L,
            useDesktopUa = true
        )
        syncCookiesFromManager()

        val parsed = rawResult?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@withLock null
        val generalJson = parsed.optString("generalJson", "")
        val itemJson = parsed.optString("itemJson", "")
        val userJson = parsed.optString("userJson", "")
        val liveJson = parsed.optString("liveJson", "")
        val domRefs = parseDomRefs(parsed.optJSONArray("domRefs"))

        if (generalJson.isBlank() && itemJson.isBlank() && userJson.isBlank() && liveJson.isBlank() && domRefs.isEmpty()) {
            return@withLock null
        }
        return@withLock WebSearchSessionResponse(
            rawGeneralJson = generalJson,
            rawItemJson = itemJson,
            rawUserJson = userJson,
            rawLiveJson = liveJson,
            domVideoRefs = domRefs
        )
    }

    private suspend fun executeFetchUserPostsLocked(
        cleanUser: String,
        secUidHint: String,
        userIdHint: String,
        cursor: String,
        count: Int,
        forceReload: Boolean,
        useDesktopUa: Boolean,
        regionCode: String,
        timeZone: String
    ): WebSessionPageResponse? {
        val ready = ensureProfileSessionLocked(cleanUser, forceReload, useDesktopUa, regionCode)
        if (!ready && secUidHint.isBlank()) {
            return if (currentDomRefs.isNotEmpty() || currentUserDetailJson.isNotBlank()) {
                WebSessionPageResponse(
                    rawApiJson = "",
                    secUid = currentSecUid,
                    userId = currentUserId,
                    userDetailJson = currentUserDetailJson,
                    domVideoRefs = currentDomRefs
                )
            } else null
        }

        val effectiveSecUid = currentSecUid.ifBlank { secUidHint }
        val effectiveUserId = currentUserId.ifBlank { userIdHint }
        if (effectiveSecUid.isBlank()) {
            return if (currentDomRefs.isNotEmpty() || currentUserDetailJson.isNotBlank()) {
                WebSessionPageResponse(
                    rawApiJson = "",
                    secUid = "",
                    userId = effectiveUserId,
                    userDetailJson = currentUserDetailJson,
                    domVideoRefs = currentDomRefs
                )
            } else null
        }

        val capturedJson = JSONObject().apply {
            capturedPostParams.forEach { (k, v) -> put(k, v) }
        }.toString()

        val rawResult = evalAsyncJs(
            scriptBody = buildFetchPostListScript(
                secUid = effectiveSecUid,
                userId = effectiveUserId,
                cursor = cursor,
                count = count,
                capturedTemplateJson = capturedJson,
                fallbackSdkUrl = ScraperConfig.WEBMSSDK_FALLBACK_URL,
                isDesktopUa = useDesktopUa,
                allowCapturedFirstPage = !forceReload,
                regionCode = regionCode,
                timeZone = timeZone
            ),
            timeoutMs = ScraperConfig.WEB_SESSION_TIMEOUT_MS,
            useDesktopUa = useDesktopUa
        )
        syncCookiesFromManager()

        val parsed = rawResult?.let { runCatching { JSONObject(it) }.getOrNull() }
        val body = parsed?.optString("body", "").orEmpty()
        val fetchedDomRefs = parseDomRefs(parsed?.optJSONArray("domRefs"))
        val combinedDomRefs = (currentDomRefs + fetchedDomRefs).distinctBy { it.second }
        if (combinedDomRefs.isNotEmpty()) {
            currentDomRefs = combinedDomRefs
        }

        val validJsonBody = if (body.isNotBlank() && body.trimStart().startsWith("{")) body else ""
        if (validJsonBody.isBlank() && combinedDomRefs.isEmpty() && currentUserDetailJson.isBlank()) {
            return null
        }

        return WebSessionPageResponse(
            rawApiJson = validJsonBody,
            secUid = effectiveSecUid,
            userId = effectiveUserId,
            userDetailJson = currentUserDetailJson,
            domVideoRefs = combinedDomRefs
        )
    }

    private fun hasNonEmptyItemList(rawApiJson: String?): Boolean {
        if (rawApiJson.isNullOrBlank()) return false
        return runCatching {
            val arr = JSONObject(rawApiJson).optJSONArray("itemList")
            arr != null && arr.length() > 0
        }.getOrDefault(false)
    }

    private fun parseDomRefs(arr: JSONArray?): List<Pair<String, String>> {
        if (arr == null || arr.length() == 0) return emptyList()
        val seen = LinkedHashMap<String, Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val u = item.optString("u", "").trim().removePrefix("@")
            val id = item.optString("id", "").trim()
            if (u.isNotBlank() && id.length >= 15) {
                seen.putIfAbsent(id, u to id)
            }
        }
        return seen.values.toList()
    }

    private suspend fun ensureProfileSessionLocked(
        cleanUser: String,
        forceReload: Boolean,
        useDesktopUa: Boolean,
        regionCode: String = "BD"
    ): Boolean {
        if (!forceReload &&
            currentPageReady &&
            currentUsername.equals(cleanUser, ignoreCase = true) &&
            currentUseDesktopUa == useDesktopUa &&
            currentSecUid.isNotBlank() &&
            webView != null
        ) {
            return true
        }

        val view = ensureWebViewOnMain(useDesktopUa) ?: return false
        currentUsername = cleanUser
        currentUseDesktopUa = useDesktopUa
        currentSecUid = ""
        currentUserId = ""
        currentUserDetailJson = ""
        currentDomRefs = emptyList()
        currentPageReady = false
        capturedPostParams = emptyMap()

        val deferred = CompletableDeferred<Boolean>()
        val targetUrl = ScraperConfig.TIKTOK_PROFILE_URL_TEMPLATE.format(Uri.encode(cleanUser))

        withContext(Dispatchers.Main) {
            pageLoadDeferred = null
            view.stopLoading()
            view.evaluateJavascript(
                "window.__TT_CAPTURED_FIRST_POST_PAGE='';window.__TT_CAPTURED_POST_URL='';window.__TT_CAPTURED_POST_PAGES=[];window.__TT_CAPTURED_USER_DETAIL='';window.__TT_RETURNED_CURSORS={};",
                null
            )
            pageLoadDeferred = deferred
            view.loadUrl(targetUrl)
        }

        withTimeoutOrNull(11_000L) { deferred.await() }
        pageLoadDeferred = null

        var readyJson = evalAsyncJs(
            scriptBody = buildWaitForContextScript(ScraperConfig.WEBMSSDK_FALLBACK_URL, regionCode),
            timeoutMs = 10_000L,
            useDesktopUa = useDesktopUa
        )
        val missingSecUid = readyJson.isNullOrBlank() || runCatching {
            JSONObject(readyJson).optString("secUid", "").isBlank()
        }.getOrDefault(true)
        // If SlardarWAF, late SPA hydration, or redirect delayed secUid, wait briefly and retry once
        if (missingSecUid) {
            delay(600L)
            val retryReadyJson = evalAsyncJs(
                scriptBody = buildWaitForContextScript(ScraperConfig.WEBMSSDK_FALLBACK_URL, regionCode),
                timeoutMs = 10_000L,
                useDesktopUa = useDesktopUa
            )
            if (!retryReadyJson.isNullOrBlank()) {
                readyJson = retryReadyJson
            }
        }
        syncCookiesFromManager()

        if (!readyJson.isNullOrBlank()) {
            runCatching {
                val obj = JSONObject(readyJson)
                val secUid = obj.optString("secUid", "").trim()
                val userId = obj.optString("userId", "").trim()
                val detail = obj.optString("userDetailJson", "")
                val domRefs = parseDomRefs(obj.optJSONArray("domRefs"))
                if (secUid.isNotBlank()) currentSecUid = secUid
                if (userId.isNotBlank()) currentUserId = userId
                if (detail.isNotBlank()) currentUserDetailJson = detail
                if (domRefs.isNotEmpty()) currentDomRefs = domRefs
                currentPageReady = secUid.isNotBlank()
            }
        }
        return currentPageReady
    }

    private suspend fun evalAsyncJs(
        scriptBody: String,
        timeoutMs: Long,
        useDesktopUa: Boolean = currentUseDesktopUa
    ): String? {
        val view = ensureWebViewOnMain(useDesktopUa) ?: return null
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingResults[requestId] = deferred

        val wrappedJs = """
            (function() {
              const __reqId = ${JSONObject.quote(requestId)};
              const __finish = function(val) {
                try {
                  const out = (typeof val === 'string') ? val : JSON.stringify(val || {});
                  if (window.TikTokTvBridge && typeof window.TikTokTvBridge.onResult === 'function') {
                    window.TikTokTvBridge.onResult(__reqId, out);
                  }
                } catch (err) {
                  if (window.TikTokTvBridge && typeof window.TikTokTvBridge.onResult === 'function') {
                    window.TikTokTvBridge.onResult(__reqId, JSON.stringify({ error: String(err) }));
                  }
                }
              };
              Promise.resolve().then(async function() {
                $scriptBody
              }).then(__finish).catch(function(err) {
                __finish({ error: String(err && err.message ? err.message : err) });
              });
            })();
        """.trimIndent()

        return try {
            withContext(Dispatchers.Main) {
                view.evaluateJavascript(wrappedJs, null)
            }
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pendingResults.remove(requestId)
        }
    }

    private fun syncCookiesFromManager() {
        runCatching {
            val raw = CookieManager.getInstance().getCookie(ScraperConfig.TIKTOK_WEB_BASE)
            if (!raw.isNullOrBlank()) {
                onCookiesSynced(raw)
            }
        }
    }

    suspend fun clearSession() {
        sessionMutex.withLock {
            currentUsername = ""
            currentSecUid = ""
            currentUserId = ""
            currentUserDetailJson = ""
            currentDomRefs = emptyList()
            currentPageReady = false
            currentUseDesktopUa = false
            currentSearchQuery = ""
            currentSearchType = ""
            capturedPostParams = emptyMap()
            capturedSearchParams = emptyMap()
            withContext(Dispatchers.Main) {
                runCatching {
                    webView?.stopLoading()
                    webView?.loadUrl("about:blank")
                }
            }
        }
    }

    private inner class BridgeObject {
        @JavascriptInterface
        fun onResult(requestId: String, payload: String) {
            pendingResults.remove(requestId)?.complete(payload)
        }
    }

    private fun buildSearchWebScript(
        query: String,
        searchType: String,
        offset: Int,
        count: Int,
        regionCode: String,
        timeZone: String,
        capturedTemplateJson: String,
        fallbackSdkUrl: String
    ): String = """
        $NETWORK_SNIFFER_BOOTSTRAP_JS
        const sleep = (ms) => new Promise(r => setTimeout(r, ms));
        const capturedTemplate = ${if (capturedTemplateJson.isBlank()) "{}" else capturedTemplateJson};
        const collectDomRefs = () => {
          const out = [];
          const seen = new Set();
          const rx = /\/@([A-Za-z0-9._]+)\/(?:video|photo)\/(\d{15,22})/g;
          try {
            document.querySelectorAll('a[href*="/video/"], a[href*="/photo/"]').forEach(a => {
              const href = a.getAttribute('href') || a.href || '';
              rx.lastIndex = 0;
              const m = rx.exec(href);
              if (m && !seen.has(m[2])) {
                seen.add(m[2]);
                out.push({ u: m[1], id: m[2] });
              }
            });
          } catch (e) {}
          return out;
        };

        for (let i = 0; i < 24; i++) {
          const hasCap = Boolean(window.__TT_CAPTURED_SEARCH_GENERAL || window.__TT_CAPTURED_SEARCH_ITEM);
          const ac = window.byted_acrawler;
          if (hasCap && ac) break;
          if (i === 10 && !ac) {
            try {
              const s = document.createElement('script');
              s.src = ${JSONObject.quote(fallbackSdkUrl)};
              s.async = true;
              s.onload = () => {
                try {
                  if (window.byted_acrawler && typeof window.byted_acrawler.init === 'function') {
                    window.byted_acrawler.init({
                      aid: 1988,
                      dfp: false,
                      boe: false,
                      intercept: true,
                      enablePathList: [
                        '/api/search/general/full/',
                        '/api/search/item/full/',
                        '/api/search/user/full/',
                        '/api/search/live/full/',
                        '/api/post/item_list/'
                      ],
                      region: ${JSONObject.quote(regionCode)},
                      isSDK: false
                    });
                  }
                } catch (e) {}
              };
              document.head.appendChild(s);
            } catch (e) {}
          }
          await sleep(150);
        }

        let ds = {};
        try {
          const el = document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');
          if (el && el.textContent) {
            ds = (JSON.parse(el.textContent) || {})['__DEFAULT_SCOPE__'] || {};
          }
        } catch (e) {}
        const appCtx = ds['webapp.app-context'] || {};
        const msTokenMatch = document.cookie.match(/(?:^|;\s*)msToken=([^;]+)/);

        const buildSearchParams = () => {
          const p = new URLSearchParams();
          for (const [k, v] of Object.entries(capturedTemplate || {})) {
            if (v !== undefined && v !== null && String(v).length > 0) {
              p.set(k, String(v));
            }
          }
          const defaults = {
            WebIdLastTime: String(appCtx.webIdCreatedTime || Math.floor(Date.now() / 1000)),
            aid: '1988',
            app_language: String(appCtx.language || 'en'),
            app_name: 'tiktok_web',
            browser_language: String(navigator.language || 'en-US'),
            browser_name: String(navigator.appCodeName || 'Mozilla'),
            browser_online: String(navigator.onLine),
            browser_platform: String(navigator.platform || 'Win32'),
            browser_version: String(navigator.appVersion || '5.0'),
            channel: 'tiktok_web',
            cookie_enabled: String(navigator.cookieEnabled),
            device_platform: 'web_pc',
            focus_state: 'true',
            from_page: 'search',
            history_len: String(window.history.length || 1),
            is_fullscreen: 'false',
            is_page_visible: 'true',
            language: String(appCtx.language || 'en'),
            os: 'windows',
            priority_region: ${JSONObject.quote(regionCode)},
            region: String(appCtx.region || ${JSONObject.quote(regionCode)}),
            screen_height: String(screen.height || 1080),
            screen_width: String(screen.width || 1920),
            search_source: 'normal_search',
            tz_name: ${JSONObject.quote(timeZone)},
            user_is_login: 'false',
            webcast_language: String(appCtx.language || 'en')
          };
          for (const [k, v] of Object.entries(defaults)) {
            if (!p.has(k) && v) p.set(k, v);
          }
          if (appCtx.wid && !p.has('device_id')) p.set('device_id', String(appCtx.wid));
          if (appCtx.odinId && !p.has('odinId')) p.set('odinId', String(appCtx.odinId));
          if (window.__TT_CAPTURED_SEARCH_ID && !p.has('search_id')) {
            p.set('search_id', String(window.__TT_CAPTURED_SEARCH_ID));
          }
          p.set('keyword', ${JSONObject.quote(query)});
          p.set('offset', String($offset));
          p.set('cursor', String($offset));
          p.set('count', String($count));
          if (msTokenMatch && msTokenMatch[1]) p.set('msToken', decodeURIComponent(msTokenMatch[1]));
          return p.toString();
        };

        const hasSearchItems = (txt) => {
          if (!txt || !txt.trim().startsWith('{')) return false;
          try {
            const j = JSON.parse(txt);
            return (Array.isArray(j.data) && j.data.length > 0) ||
                   (Array.isArray(j.item_list) && j.item_list.length > 0) ||
                   (Array.isArray(j.aweme_list) && j.aweme_list.length > 0);
          } catch (e) { return false; }
        };

        const signedFetch = async (endpointPath) => {
          try {
            const qs = buildSearchParams();
            const fullUrl = 'https://www.tiktok.com' + endpointPath + '?' + qs;
            let targetUrl = fullUrl;
            const ac = window.byted_acrawler;
            if (ac) {
              try {
                let sig = null;
                if (typeof ac.frontierSign === 'function') sig = await Promise.resolve(ac.frontierSign(fullUrl));
                else if (typeof ac.sign === 'function') sig = await Promise.resolve(ac.sign({ url: fullUrl }));
                if (sig && typeof sig === 'object') {
                  const sp = new URLSearchParams(qs);
                  for (const [k, v] of Object.entries(sig)) {
                    if (v) sp.set(k, String(v));
                  }
                  targetUrl = 'https://www.tiktok.com' + endpointPath + '?' + sp.toString();
                } else if (typeof sig === 'string' && sig.length > 0) {
                  targetUrl = fullUrl + (sig.includes('=') ? '&' + sig.replace(/^\?/, '') : '&_signature=' + encodeURIComponent(sig));
                }
              } catch (e) {}
            }
            const resp = await window.fetch(targetUrl, { method: 'GET', credentials: 'include' });
            const txt = await resp.text();
            if (txt && txt.trim().startsWith('{')) return txt;
          } catch (e) {}
          return '';
        };

        let generalJson = ($offset === 0 && window.__TT_CAPTURED_SEARCH_GENERAL) ? window.__TT_CAPTURED_SEARCH_GENERAL : '';
        let itemJson = ($offset === 0 && window.__TT_CAPTURED_SEARCH_ITEM) ? window.__TT_CAPTURED_SEARCH_ITEM : '';
        let userJson = ($offset === 0 && window.__TT_CAPTURED_SEARCH_USER) ? window.__TT_CAPTURED_SEARCH_USER : '';
        let liveJson = ($offset === 0 && window.__TT_CAPTURED_SEARCH_LIVE) ? window.__TT_CAPTURED_SEARCH_LIVE : '';

        if ($offset > 0) {
          window.__TT_CAPTURED_SEARCH_LATEST = '';
          itemJson = await signedFetch('/api/search/item/full/');
          if (!hasSearchItems(itemJson)) {
            generalJson = await signedFetch('/api/search/general/full/');
          }
          if (!hasSearchItems(itemJson) && !hasSearchItems(generalJson)) {
            try {
              window.scrollTo(0, document.body ? document.body.scrollHeight : 4000);
              for (let s = 0; s < 10; s++) {
                if (window.__TT_CAPTURED_SEARCH_LATEST) break;
                await sleep(150);
              }
              if (window.__TT_CAPTURED_SEARCH_LATEST) {
                itemJson = window.__TT_CAPTURED_SEARCH_LATEST;
              }
            } catch (e) {}
          }
        } else {
          if (!generalJson && ${JSONObject.quote(searchType)} === 'general') {
            generalJson = await signedFetch('/api/search/general/full/');
          }
          if (!hasSearchItems(itemJson)) {
            itemJson = await signedFetch('/api/search/item/full/');
          }
          if (!liveJson) {
            liveJson = await signedFetch('/api/search/live/full/');
          }
        }

        return {
          generalJson: generalJson || '',
          itemJson: itemJson || '',
          userJson: userJson || '',
          liveJson: liveJson || '',
          domRefs: collectDomRefs()
        };
    """.trimIndent()

    private fun buildWaitForContextScript(
        fallbackSdkUrl: String,
        regionCode: String = "BD"
    ): String = """
        $NETWORK_SNIFFER_BOOTSTRAP_JS
        const sleep = (ms) => new Promise(r => setTimeout(r, ms));
        const collectDomRefs = () => {
          const out = [];
          const seen = new Set();
          const rx = /\/@([A-Za-z0-9._]+)\/(?:video|photo)\/(\d{15,22})/g;
          const add = (u, id) => {
            if (!u || !id || seen.has(id)) return;
            seen.add(id);
            out.push({ u, id });
          };
          try {
            document.querySelectorAll('a[href*="/video/"], a[href*="/photo/"]').forEach(a => {
              const href = a.getAttribute('href') || a.href || '';
              rx.lastIndex = 0;
              const m = rx.exec(href);
              if (m) add(m[1], m[2]);
            });
          } catch (e) {}
          return out;
        };

        const dismissModals = () => {
          try {
            const selectors = [
              '[data-e2e="modal-close-inner-button"]',
              'div[role="dialog"] button[aria-label="Close"]',
              'div[role="dialog"] [data-e2e="modal-close-inner-button"]'
            ];
            for (const sel of selectors) {
              document.querySelectorAll(sel).forEach(btn => { try { btn.click(); } catch (e) {} });
            }
            if (document.body && document.body.style && document.body.style.overflow === 'hidden') {
              document.body.style.overflow = 'auto';
            }
            if (document.documentElement && document.documentElement.style && document.documentElement.style.overflow === 'hidden') {
              document.documentElement.style.overflow = 'auto';
            }
          } catch (e) {}
        };
        let sdkInjected = false;
        let result = { secUid: '', userId: '', userDetailJson: '', mssdkReady: false, domRefs: [] };
        let secUidFirstSeenAt = -1;

        for (let i = 0; i < 50; i++) {
          let ds = null;
          try {
            const el = document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');
            if (el && el.textContent) {
              ds = (JSON.parse(el.textContent) || {})['__DEFAULT_SCOPE__'] || null;
            }
          } catch (e) {}
          if (!ds && window.__UNIVERSAL_DATA_FOR_REHYDRATION__) {
            ds = window.__UNIVERSAL_DATA_FOR_REHYDRATION__['__DEFAULT_SCOPE__'] || null;
          }
          if (ds) {
            const ud = ds['webapp.user-detail'] || {};
            const ui = ud.userInfo || {};
            const u = ui.user || {};
            if (u.secUid) result.secUid = String(u.secUid);
            if (u.id) result.userId = String(u.id);
            if (ui && (u.secUid || u.uniqueId)) {
              result.userDetailJson = JSON.stringify(ui);
            }
          }
          if (!result.secUid && window.__TT_CAPTURED_USER_DETAIL) {
            try {
              const cud = JSON.parse(window.__TT_CAPTURED_USER_DETAIL);
              const ui = cud.userInfo || cud;
              const u = (ui && ui.user) || {};
              if (u.secUid) result.secUid = String(u.secUid);
              if (u.id) result.userId = String(u.id);
              if (ui && (u.secUid || u.uniqueId)) {
                result.userDetailJson = JSON.stringify(ui);
              }
            } catch (e) {}
          }
          if (result.secUid && secUidFirstSeenAt < 0) {
            secUidFirstSeenAt = i;
          }

          const ac = window.byted_acrawler;
          const mssdkReady = Boolean(
            window.MSSDK_INITIALIZED ||
            (ac && (typeof ac.frontierSign === 'function' || typeof ac.sign === 'function'))
          );
          result.mssdkReady = mssdkReady;
          const hasCapturedPage = Boolean(
            window.__TT_CAPTURED_FIRST_POST_PAGE ||
            (Array.isArray(window.__TT_CAPTURED_POST_PAGES) && window.__TT_CAPTURED_POST_PAGES.some(p => p && p.itemCount > 0))
          );
          const waitedEnoughAfterSecUid = secUidFirstSeenAt >= 0 && (i - secUidFirstSeenAt) >= 8;

          if (result.secUid && (hasCapturedPage || (mssdkReady && waitedEnoughAfterSecUid))) {
            break;
          }
          if (result.secUid && (i === 6 || i === 18) && !hasCapturedPage) {
            dismissModals();
            try {
              window.scrollBy(0, 800);
              window.dispatchEvent(new Event('scroll'));
            } catch (e) {}
          }

          if (i === 10 && !mssdkReady && !sdkInjected) {
            sdkInjected = true;
            try {
              const existingSdk = document.querySelector('script[src*="webmssdk"]');
              const sdkUrl = (existingSdk && existingSdk.getAttribute('src')) || ${JSONObject.quote(fallbackSdkUrl)};
              if (!window.byted_acrawler) {
                const s = document.createElement('script');
                s.src = sdkUrl;
                s.async = true;
                s.onload = function() {
                  try {
                    if (window.byted_acrawler && typeof window.byted_acrawler.init === 'function') {
                      window.byted_acrawler.init({
                        aid: 1988,
                        dfp: false,
                        boe: false,
                        intercept: true,
                        enablePathList: ['/api/post/item_list/', '/api/post/item_list/v1/', '/api/creator/item_list/', '/api/challenge/item_list/', '/api/explore/item_list/'],
                        region: ${JSONObject.quote(regionCode)},
                        isSDK: false
                      });
                      window.MSSDK_INITIALIZED = true;
                    }
                  } catch (err) {}
                };
                document.head.appendChild(s);
              }
            } catch (e) {}
          }
          await sleep(100);
        }
        result.domRefs = collectDomRefs();
        return result;
    """.trimIndent()

    private fun buildFetchPostListScript(
        secUid: String,
        userId: String,
        cursor: String,
        count: Int,
        capturedTemplateJson: String,
        fallbackSdkUrl: String,
        isDesktopUa: Boolean,
        allowCapturedFirstPage: Boolean,
        regionCode: String = "BD",
        timeZone: String = "Asia/Dhaka"
    ): String = """
        ${buildFetchPostCoreScript(
            secUid = secUid,
            userId = userId,
            count = count,
            capturedTemplateJson = capturedTemplateJson,
            fallbackSdkUrl = fallbackSdkUrl,
            isDesktopUa = isDesktopUa,
            regionCode = regionCode,
            timeZone = timeZone
        )}
        const res = await fetchSingleCursorPage(${JSONObject.quote(cursor)}, $allowCapturedFirstPage);
        return { status: res.status, body: res.body || '', domRefs: collectDomRefs() };
    """.trimIndent()

    private fun buildFetchPostBatchScript(
        secUid: String,
        userId: String,
        startCursor: String,
        count: Int,
        maxPages: Int,
        capturedTemplateJson: String,
        fallbackSdkUrl: String,
        isDesktopUa: Boolean,
        allowCapturedFirstPage: Boolean,
        regionCode: String = "BD",
        timeZone: String = "Asia/Dhaka"
    ): String = """
        ${buildFetchPostCoreScript(
            secUid = secUid,
            userId = userId,
            count = count,
            capturedTemplateJson = capturedTemplateJson,
            fallbackSdkUrl = fallbackSdkUrl,
            isDesktopUa = isDesktopUa,
            regionCode = regionCode,
            timeZone = timeZone
        )}
        const pages = [];
        let nextCur = ${JSONObject.quote(startCursor)};
        let allowFirst = $allowCapturedFirstPage;
        for (let pIdx = 0; pIdx < $maxPages; pIdx++) {
          const res = await fetchSingleCursorPage(nextCur, allowFirst);
          allowFirst = false;
          if (!res || !res.body || !isValidPostJson(res.body)) break;
          pages.push(res.body);
          try {
            const parsed = JSON.parse(res.body);
            const hasItems = Array.isArray(parsed.itemList) && parsed.itemList.length > 0;
            const hasMore = Boolean(parsed.hasMore);
            const nextVal = parsed.cursor !== undefined && parsed.cursor !== null ? String(parsed.cursor) : '';
            if (!hasItems || !hasMore || !nextVal || nextVal === '0' || nextVal === nextCur) {
              break;
            }
            nextCur = nextVal;
            await sleep(60);
          } catch (e) {
            break;
          }
        }
        return { pages, domRefs: collectDomRefs() };
    """.trimIndent()

    private fun buildFetchPostCoreScript(
        secUid: String,
        userId: String,
        count: Int,
        capturedTemplateJson: String,
        fallbackSdkUrl: String,
        isDesktopUa: Boolean,
        regionCode: String,
        timeZone: String
    ): String = """
        $NETWORK_SNIFFER_BOOTSTRAP_JS
        const sleep = (ms) => new Promise(r => setTimeout(r, ms));
        const dismissModals = () => {
          try {
            const selectors = [
              '[data-e2e="modal-close-inner-button"]',
              'div[role="dialog"] button[aria-label="Close"]',
              'div[role="dialog"] [data-e2e="modal-close-inner-button"]'
            ];
            for (const sel of selectors) {
              document.querySelectorAll(sel).forEach(btn => { try { btn.click(); } catch (e) {} });
            }
            if (document.body && document.body.style && document.body.style.overflow === 'hidden') {
              document.body.style.overflow = 'auto';
            }
            if (document.documentElement && document.documentElement.style && document.documentElement.style.overflow === 'hidden') {
              document.documentElement.style.overflow = 'auto';
            }
          } catch (e) {}
        };
        const collectDomRefs = () => {
          const out = [];
          const seen = new Set();
          const rx = /\/@([A-Za-z0-9._]+)\/(?:video|photo)\/(\d{15,22})/g;
          const add = (u, id) => {
            if (!u || !id || seen.has(id)) return;
            seen.add(id);
            out.push({ u, id });
          };
          try {
            document.querySelectorAll('a[href*="/video/"], a[href*="/photo/"]').forEach(a => {
              const href = a.getAttribute('href') || a.href || '';
              rx.lastIndex = 0;
              const m = rx.exec(href);
              if (m) add(m[1], m[2]);
            });
          } catch (e) {}
          return out;
        };
        const isValidPostJson = (raw) => {
          if (!raw || typeof raw !== 'string') return false;
          const trimmed = raw.trim();
          if (!trimmed.startsWith('{')) return false;
          try {
            const j = JSON.parse(trimmed);
            return Array.isArray(j.itemList);
          } catch (e) {
            return false;
          }
        };
        const hasItemsInJson = (raw) => {
          if (!isValidPostJson(raw)) return false;
          try {
            const j = JSON.parse(raw);
            return Array.isArray(j.itemList) && j.itemList.length > 0;
          } catch (e) {
            return false;
          }
        };

        window.__TT_RETURNED_CURSORS = window.__TT_RETURNED_CURSORS || {};
        const pickCapturedPage = (targetCursor) => {
          const pages = Array.isArray(window.__TT_CAPTURED_POST_PAGES) ? window.__TT_CAPTURED_POST_PAGES : [];
          for (const p of pages) {
            if (!p || !hasItemsInJson(p.body)) continue;
            if (p.reqCursor === targetCursor && !window.__TT_RETURNED_CURSORS['body_' + p.key]) {
              window.__TT_RETURNED_CURSORS['body_' + p.key] = true;
              return p.body;
            }
          }
          return '';
        };

        const ensureSigner = async () => {
          for (let i = 0; i < 16; i++) {
            const ac = window.byted_acrawler;
            if (window.MSSDK_INITIALIZED || (ac && (typeof ac.frontierSign === 'function' || typeof ac.sign === 'function'))) {
              return true;
            }
            if (i === 6 && !document.querySelector('script[data-tv-mssdk="1"]') && !window.byted_acrawler) {
              try {
                const existingSdk = document.querySelector('script[src*="webmssdk"]');
                const s = document.createElement('script');
                s.src = (existingSdk && existingSdk.getAttribute('src')) || ${JSONObject.quote(fallbackSdkUrl)};
                s.setAttribute('data-tv-mssdk', '1');
                s.onload = () => {
                  try {
                    if (window.byted_acrawler && typeof window.byted_acrawler.init === 'function') {
                      window.byted_acrawler.init({
                        aid: 1988,
                        dfp: false,
                        boe: false,
                        intercept: true,
                        enablePathList: ['/api/post/item_list/', '/api/post/item_list/v1/', '/api/creator/item_list/'],
                        region: ${JSONObject.quote(regionCode)},
                        isSDK: false
                      });
                      window.MSSDK_INITIALIZED = true;
                    }
                  } catch (e) {}
                };
                document.head.appendChild(s);
              } catch (e) {}
            }
            await sleep(100);
          }
          return false;
        };

        let ds = {};
        try {
          const el = document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');
          if (el && el.textContent) {
            ds = (JSON.parse(el.textContent) || {})['__DEFAULT_SCOPE__'] || {};
          }
        } catch (e) {}
        const appCtx = ds['webapp.app-context'] || {};
        const bizCtx = ds['webapp.biz-context'] || {};

        const buildParams = (reqCursor, platformOverride) => {
          const verifyMatch = document.cookie.match(/(?:^|;\s*)s_v_web_id=([^;]+)/);
          const msTokenMatch = document.cookie.match(/(?:^|;\s*)msToken=([^;]+)/);
          const params = new URLSearchParams();
          const defaults = {
            WebIdLastTime: String(appCtx.webIdCreatedTime || Math.floor(Date.now() / 1000)),
            aid: '1988',
            app_language: String(appCtx.language || 'en'),
            app_name: 'tiktok_web',
            browser_language: String(navigator.language || 'en-US'),
            browser_name: String(navigator.appCodeName || 'Mozilla'),
            browser_online: String(navigator.onLine),
            browser_platform: String(navigator.platform || (${if (isDesktopUa) "'Win32'" else "'Linux aarch64'"})),
            browser_version: String(navigator.appVersion || '5.0'),
            channel: 'tiktok_web',
            cookie_enabled: String(navigator.cookieEnabled),
            coverFormat: '2',
            data_collection_enabled: 'true',
            device_id: String(appCtx.wid || ''),
            device_platform: platformOverride || ${if (isDesktopUa) "'web_pc'" else "'web_mobile'"},
            focus_state: 'true',
            from_page: 'user',
            history_len: String(window.history.length || 1),
            is_fullscreen: 'false',
            is_page_visible: 'true',
            language: String(appCtx.language || 'en'),
            os: ${if (isDesktopUa) "'windows'" else "'android'"},
            priority_region: '',
            referer: String(document.referrer || ''),
            region: String(appCtx.region || ${JSONObject.quote(regionCode)}),
            root_referer: String(document.referrer || ''),
            screen_height: String(screen.height || 1080),
            screen_width: String(screen.width || 1920),
            tz_name: String((Intl.DateTimeFormat && Intl.DateTimeFormat().resolvedOptions().timeZone) || ${JSONObject.quote(timeZone)}),
            user_is_login: 'false',
            webcast_language: String(appCtx.language || 'en')
          };
          if (appCtx.odinId) defaults.odinId = String(appCtx.odinId);
          if (verifyMatch && verifyMatch[1]) defaults.verifyFp = verifyMatch[1];
          if (msTokenMatch && msTokenMatch[1]) defaults.msToken = decodeURIComponent(msTokenMatch[1]);

          for (const [k, v] of Object.entries(defaults)) {
            if (v !== undefined && v !== null) params.set(k, String(v));
          }
          try {
            const captured = JSON.parse(${JSONObject.quote(capturedTemplateJson)});
            for (const [k, v] of Object.entries(captured || {})) {
              if (v !== undefined && v !== null && String(v).length > 0) {
                params.set(k, String(v));
              }
            }
          } catch (e) {}
          try {
            if (window.__TT_CAPTURED_POST_URL) {
              const u = new URL(window.__TT_CAPTURED_POST_URL, window.location.origin);
              u.searchParams.forEach((v, k) => {
                if (v && k !== 'X-Bogus' && k !== 'X-Gnarly' && k !== '_signature' && k !== 'cursor' && k !== 'count') {
                  params.set(k, v);
                }
              });
            }
          } catch (e) {}

          if (platformOverride) {
            params.set('device_platform', platformOverride);
            if (platformOverride === 'web_mobile') {
              params.set('os', 'android');
              params.set('browser_platform', 'Linux aarch64');
            } else {
              params.set('os', 'windows');
              params.set('browser_platform', 'Win32');
            }
          }
          params.delete('X-Bogus');
          params.delete('X-Gnarly');
          params.delete('_signature');
          params.delete('type');
          params.set('secUid', ${JSONObject.quote(secUid)});
          params.set('count', String($count));
          params.set('cursor', String(reqCursor));
          return params;
        };

        const baseUrl = 'https://www.tiktok.com/api/post/item_list/';
        const tryFetch = async (qs, useRelative = false, useNative = false) => {
          try {
            const prefix = useRelative ? '/api/post/item_list/?' : (baseUrl + '?');
            const fetchFn = (useNative && typeof window.__TT_ORIG_FETCH === 'function')
              ? window.__TT_ORIG_FETCH
              : window.fetch;
            const resp = await fetchFn(prefix + qs, {
              method: 'GET',
              credentials: 'include'
            });
            const text = await resp.text();
            return { status: resp.status, body: text || '' };
          } catch (e) {
            return { status: 0, body: '' };
          }
        };

        const tryXhr = (qs, useRelative = false) => new Promise((resolve) => {
          try {
            const prefix = useRelative ? '/api/post/item_list/?' : (baseUrl + '?');
            const xhr = new XMLHttpRequest();
            xhr.open('GET', prefix + qs, true);
            xhr.withCredentials = true;
            xhr.onreadystatechange = function() {
              if (xhr.readyState === 4) {
                resolve({ status: xhr.status || 0, body: xhr.responseText || '' });
              }
            };
            xhr.onerror = function() { resolve({ status: 0, body: '' }); };
            xhr.send(null);
          } catch (e) {
            resolve({ status: 0, body: '' });
          }
        });

        const signWithFrontier = async (qs) => {
          const ac = window.byted_acrawler;
          if (!ac) return null;
          const candidates = [baseUrl + '?' + qs, '/api/post/item_list/?' + qs, qs];
          for (const target of candidates) {
            try {
              let sig = null;
              if (typeof ac.frontierSign === 'function') {
                sig = await Promise.resolve(ac.frontierSign(target)).catch(() => null);
                if (!sig) {
                  sig = await Promise.resolve(ac.frontierSign({ url: target })).catch(() => null);
                }
              } else if (typeof ac.sign === 'function') {
                sig = await Promise.resolve(ac.sign({ url: target })).catch(() => null);
              }
              if (!sig) continue;
              const signedParams = new URLSearchParams(qs);
              let added = false;
              if (typeof sig === 'object') {
                for (const [k, v] of Object.entries(sig)) {
                  if (v) {
                    signedParams.set(k, String(v));
                    added = true;
                  }
                }
              } else if (typeof sig === 'string' && sig.length > 0) {
                if (sig.includes('=') || sig.includes('&')) {
                  const extra = new URLSearchParams(sig.replace(/^\?/, ''));
                  extra.forEach((v, k) => {
                    if (v) { signedParams.set(k, v); added = true; }
                  });
                } else {
                  signedParams.set('_signature', sig);
                  added = true;
                }
              }
              if (added) return signedParams.toString();
            } catch (e) {}
          }
          return null;
        };

        const tryCreatorList = async (reqCursor) => {
          try {
            const cParams = new URLSearchParams({
              aid: '1988',
              secUid: ${JSONObject.quote(secUid)},
              count: String(Math.min(30, Math.max(1, $count))),
              cursor: String(reqCursor),
              type: '1'
            });
            const cUrl = 'https://www.tiktok.com/api/creator/item_list/?' + cParams.toString();
            const resp = await window.fetch(cUrl, {
              method: 'GET',
              credentials: 'include'
            });
            const txt = await resp.text();
            if (hasItemsInJson(txt)) {
              return { status: resp.status, body: txt };
            }
          } catch (e) {}
          return null;
        };

        const fetchAttempt = async (reqCursor, platformOverride) => {
          const qs = buildParams(reqCursor, platformOverride).toString();
          let r = await tryFetch(qs, false, false);
          if (hasItemsInJson(r.body)) return r;

          const signedQs = await signWithFrontier(qs);
          if (signedQs) {
            const rNative = await tryFetch(signedQs, false, true);
            if (hasItemsInJson(rNative.body)) return rNative;
            if (!isValidPostJson(r.body) && isValidPostJson(rNative.body)) r = rNative;

            const rSignedXhr = await tryXhr(signedQs, false);
            if (hasItemsInJson(rSignedXhr.body)) return rSignedXhr;
            if (!isValidPostJson(r.body) && isValidPostJson(rSignedXhr.body)) r = rSignedXhr;
          }

          const rRel = await tryFetch(qs, true, false);
          if (hasItemsInJson(rRel.body)) return rRel;
          if (!isValidPostJson(r.body) && isValidPostJson(rRel.body)) r = rRel;

          if (!hasItemsInJson(r.body)) {
            await sleep(150);
            const rXhr = await tryXhr(qs, true);
            if (hasItemsInJson(rXhr.body) || (!isValidPostJson(r.body) && isValidPostJson(rXhr.body))) {
              r = rXhr;
            }
          }
          return r;
        };

        const fetchSingleCursorPage = async (reqCursor, allowFirstCap) => {
          if (allowFirstCap && reqCursor === '0') {
            let cap = pickCapturedPage('0');
            if (!cap && !hasItemsInJson(window.__TT_CAPTURED_FIRST_POST_PAGE)) {
              for (let w = 0; w < 8; w++) {
                cap = pickCapturedPage('0');
                if (cap || hasItemsInJson(window.__TT_CAPTURED_FIRST_POST_PAGE)) break;
                await sleep(100);
              }
            }
            if (!cap && hasItemsInJson(window.__TT_CAPTURED_FIRST_POST_PAGE) && !window.__TT_RETURNED_CURSORS['first']) {
              window.__TT_RETURNED_CURSORS['first'] = true;
              cap = window.__TT_CAPTURED_FIRST_POST_PAGE;
            }
            if (cap) {
              return { status: 200, body: cap };
            }
          } else if (reqCursor !== '0') {
            const alreadyCaptured = pickCapturedPage(reqCursor);
            if (alreadyCaptured) {
              return { status: 200, body: alreadyCaptured };
            }
          }

          await ensureSigner();

          let res = await fetchAttempt(reqCursor, null);
          if (!hasItemsInJson(res.body)) {
            const altPlatform = ${if (isDesktopUa) "'web_mobile'" else "'web_pc'"};
            const altRes = await fetchAttempt(reqCursor, altPlatform);
            if (hasItemsInJson(altRes.body) || (!isValidPostJson(res.body) && isValidPostJson(altRes.body))) {
              res = altRes;
            }
          }

          if (!hasItemsInJson(res.body)) {
            try {
              dismissModals();
              const pagesBefore = Array.isArray(window.__TT_CAPTURED_POST_PAGES) ? window.__TT_CAPTURED_POST_PAGES.length : 0;
              for (let step = 0; step < 5; step++) {
                const links = document.querySelectorAll('a[href*="/video/"], a[href*="/photo/"]');
                if (links && links.length > 0) {
                  try { links[links.length - 1].scrollIntoView({ behavior: 'instant', block: 'end' }); } catch (e) {}
                }
                const scrollTarget = Math.max(
                  document.body ? document.body.scrollHeight : 0,
                  document.documentElement ? document.documentElement.scrollHeight : 0,
                  (step + 1) * 1800
                );
                window.scrollTo(0, scrollTarget);
                if (document.documentElement) document.documentElement.scrollTop = scrollTarget;
                window.dispatchEvent(new Event('scroll'));
                for (let w = 0; w < 7; w++) {
                  await sleep(120);
                  const pages = Array.isArray(window.__TT_CAPTURED_POST_PAGES) ? window.__TT_CAPTURED_POST_PAGES : [];
                  let matched = null;
                  for (let idx = pages.length - 1; idx >= 0; idx--) {
                    const p = pages[idx];
                    if (p && hasItemsInJson(p.body) && !window.__TT_RETURNED_CURSORS['body_' + p.key] && (idx >= pagesBefore || p.reqCursor === reqCursor)) {
                      matched = p;
                      break;
                    }
                  }
                  if (matched) {
                    window.__TT_RETURNED_CURSORS['body_' + matched.key] = true;
                    res = { status: 200, body: matched.body };
                    break;
                  }
                }
                if (hasItemsInJson(res.body)) break;
              }
            } catch (e) {}
          }

          if (!hasItemsInJson(res.body)) {
            const creatorRes = await tryCreatorList(reqCursor);
            if (creatorRes && hasItemsInJson(creatorRes.body)) {
              res = creatorRes;
            }
          }
          return res;
        };
    """.trimIndent()

    private companion object {
        val STRIPPED_QUERY_KEYS = setOf("X-Bogus", "X-Gnarly", "_signature", "cursor", "count")
        val STRIPPED_SEARCH_KEYS = setOf("X-Bogus", "X-Gnarly", "_signature", "offset", "cursor", "count")
        val BLOCKED_EXTENSIONS = listOf(
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".ico",
            ".mp4", ".m4a", ".mp3", ".webm", ".m3u8", ".ts",
            ".woff", ".woff2", ".ttf", ".otf"
        )

        private const val WAF_CHECK_JS = """
            (function() {
              try {
                var hasUniversal = !!(document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__') || window.__UNIVERSAL_DATA_FOR_REHYDRATION__);
                var text = (document.body && document.body.innerText) ? document.body.innerText : '';
                var isWaf = !!(document.getElementById('wci') || (text.indexOf('Please wait...') !== -1 && !hasUniversal));
                return isWaf ? 'WAF' : 'READY';
              } catch (e) {
                return 'READY';
              }
            })();
        """

        private const val NETWORK_SNIFFER_BOOTSTRAP_JS = """
            (function() {
              if (window.__TT_NET_HOOKED) return;
              window.__TT_NET_HOOKED = true;

              const isPostItemApi = (u) => typeof u === 'string' && u.indexOf('/api/post/item_list/') !== -1;
              const isCreatorItemApi = (u) => typeof u === 'string' && u.indexOf('/api/creator/item_list/') !== -1;
              const isPostApi = (u) => isPostItemApi(u) || isCreatorItemApi(u);
              const isUserDetailApi = (u) => typeof u === 'string' && u.indexOf('/api/user/detail/') !== -1;
              const isSearchApi = (u) => typeof u === 'string' && u.indexOf('/api/search/') !== -1;
              window.__TT_CAPTURED_POST_PAGES = window.__TT_CAPTURED_POST_PAGES || [];

              const recordPayload = (url, text) => {
                try {
                  if (url && isPostItemApi(url)) {
                    window.__TT_CAPTURED_POST_URL = url;
                  }
                  if (!text || typeof text !== 'string' || !text.trim().startsWith('{')) return;
                  const parsed = JSON.parse(text);
                  if (url && isUserDetailApi(url) && (parsed.userInfo || parsed.user)) {
                    window.__TT_CAPTURED_USER_DETAIL = text;
                  }
                  if (url && isSearchApi(url)) {
                    try {
                      const sid = (parsed.log_pb && parsed.log_pb.impr_id) || (parsed.extra && parsed.extra.search_id) || parsed.search_id || '';
                      if (sid) window.__TT_CAPTURED_SEARCH_ID = String(sid);
                    } catch (e) {}
                    window.__TT_CAPTURED_SEARCH_LATEST = text;
                    if (url.indexOf('/api/search/general/full/') !== -1 && !window.__TT_CAPTURED_SEARCH_GENERAL) {
                      window.__TT_CAPTURED_SEARCH_GENERAL = text;
                    } else if (url.indexOf('/api/search/item/full/') !== -1 && !window.__TT_CAPTURED_SEARCH_ITEM) {
                      window.__TT_CAPTURED_SEARCH_ITEM = text;
                    } else if (url.indexOf('/api/search/user/full/') !== -1 && !window.__TT_CAPTURED_SEARCH_USER) {
                      window.__TT_CAPTURED_SEARCH_USER = text;
                    } else if (url.indexOf('/api/search/live/full/') !== -1 && !window.__TT_CAPTURED_SEARCH_LIVE) {
                      window.__TT_CAPTURED_SEARCH_LIVE = text;
                    }
                  }
                  if (url && isPostApi(url) && Array.isArray(parsed.itemList) && parsed.itemList.length > 0) {
                    let reqCursor = '0';
                    try {
                      const parsedUrl = new URL(url, window.location.origin);
                      reqCursor = parsedUrl.searchParams.get('cursor') || '0';
                    } catch (e) {}
                    const apiType = isPostItemApi(url) ? 'post' : 'creator';
                    const key = apiType + '_' + reqCursor + '_' + (parsed.cursor || '');
                    const pages = window.__TT_CAPTURED_POST_PAGES;
                    if (!pages.some(p => p && p.key === key)) {
                      pages.push({
                        key,
                        apiType,
                        reqCursor,
                        respCursor: String(parsed.cursor || ''),
                        hasMore: Boolean(parsed.hasMore || parsed.hasMorePrevious),
                        itemCount: parsed.itemList.length,
                        body: text
                      });
                    }
                    if (isPostItemApi(url) && (!window.__TT_CAPTURED_FIRST_POST_PAGE || reqCursor === '0')) {
                      window.__TT_CAPTURED_FIRST_POST_PAGE = text;
                    } else if (!window.__TT_CAPTURED_FIRST_POST_PAGE) {
                      window.__TT_CAPTURED_FIRST_POST_PAGE = text;
                    }
                  }
                } catch (e) {}
              };

              try {
                const origFetch = window.__TT_ORIG_FETCH || window.fetch;
                if (!window.__TT_ORIG_FETCH && typeof origFetch === 'function') {
                  window.__TT_ORIG_FETCH = origFetch;
                }
                if (typeof origFetch === 'function') {
                  window.fetch = function(input, init) {
                    const url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                    const p = origFetch.apply(this, arguments);
                    if (isPostApi(url) || isUserDetailApi(url) || isSearchApi(url)) {
                      p.then(resp => {
                        try {
                          resp.clone().text().then(txt => recordPayload(resp.url || url, txt)).catch(() => {});
                        } catch (e) {}
                      }).catch(() => {});
                    }
                    return p;
                  };
                }
              } catch (e) {}

              try {
                const origOpen = XMLHttpRequest.prototype.open;
                const origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                  this.__ttUrl = (typeof url === 'string') ? url : String(url || '');
                  return origOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                  if (isPostApi(this.__ttUrl) || isUserDetailApi(this.__ttUrl) || isSearchApi(this.__ttUrl)) {
                    this.addEventListener('load', () => {
                      try { recordPayload(this.responseURL || this.__ttUrl, this.responseText); } catch (e) {}
                    });
                  }
                  return origSend.apply(this, arguments);
                };
              } catch (e) {}
            })();
        """
    }
}

package com.tiktoktv.app.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
    private var pageLoadDeferred: CompletableDeferred<Boolean>? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun ensureWebViewOnMain(useDesktopUa: Boolean): WebView? = withContext(Dispatchers.Main) {
        val targetUa = if (useDesktopUa) {
            ScraperConfig.HEADER_USER_AGENT_VALUE
        } else {
            ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE
        }

        webView?.let { existing ->
            if (existing.settings.userAgentString != targetUa) {
                existing.settings.userAgentString = targetUa
                if (useDesktopUa) {
                    existing.layout(0, 0, 1440, 900)
                } else {
                    existing.layout(0, 0, 1080, 1920)
                }
            }
            return@withContext existing
        }

        runCatching {
            val view = WebView(appContext)
            if (useDesktopUa) {
                view.layout(0, 0, 1440, 900)
            } else {
                view.layout(0, 0, 1080, 1920)
            }

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

                    if (urlStr.contains("/api/post/item_list/") || urlStr.contains("/api/creator/item_list/")) {
                        capturePostQueryTemplate(uri)
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

    suspend fun fetchUserPostsPage(
        username: String,
        secUidHint: String = "",
        userIdHint: String = "",
        cursor: String = "0",
        count: Int = ScraperConfig.PROFILE_WEB_PAGE_SIZE,
        forceReload: Boolean = false
    ): WebSessionPageResponse? = sessionMutex.withLock {
        val cleanUser = username.trim().removePrefix("@")
        if (cleanUser.isBlank()) return@withLock null

        val isInitialPage = cursor == "0"
        val preferDesktop = if (!isInitialPage && currentUsername.equals(cleanUser, ignoreCase = true)) {
            currentUseDesktopUa
        } else {
            false
        }

        val firstAttempt = executeFetchUserPostsLocked(
            cleanUser = cleanUser,
            secUidHint = secUidHint,
            userIdHint = userIdHint,
            cursor = cursor,
            count = count,
            forceReload = forceReload,
            useDesktopUa = preferDesktop
        )

        if (isInitialPage && !preferDesktop && !hasNonEmptyItemList(firstAttempt?.rawApiJson)) {
            val desktopAttempt = executeFetchUserPostsLocked(
                cleanUser = cleanUser,
                secUidHint = firstAttempt?.secUid?.ifBlank { secUidHint } ?: secUidHint,
                userIdHint = firstAttempt?.userId?.ifBlank { userIdHint } ?: userIdHint,
                cursor = cursor,
                count = count,
                forceReload = true,
                useDesktopUa = true
            )
            if (desktopAttempt != null &&
                (hasNonEmptyItemList(desktopAttempt.rawApiJson) || desktopAttempt.domVideoRefs.isNotEmpty())
            ) {
                return@withLock desktopAttempt
            }
            if (firstAttempt != null) return@withLock firstAttempt
            return@withLock desktopAttempt
        }

        return@withLock firstAttempt
    }

    private suspend fun executeFetchUserPostsLocked(
        cleanUser: String,
        secUidHint: String,
        userIdHint: String,
        cursor: String,
        count: Int,
        forceReload: Boolean,
        useDesktopUa: Boolean
    ): WebSessionPageResponse? {
        val ready = ensureProfileSessionLocked(cleanUser, forceReload, useDesktopUa)
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
                allowCapturedFirstPage = !forceReload
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
        useDesktopUa: Boolean
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
            pageLoadDeferred = deferred
            view.loadUrl(targetUrl)
        }

        withTimeoutOrNull(14_000L) { deferred.await() }
        pageLoadDeferred = null

        var readyJson = evalAsyncJs(
            scriptBody = buildWaitForContextScript(ScraperConfig.WEBMSSDK_FALLBACK_URL),
            timeoutMs = 10_000L,
            useDesktopUa = useDesktopUa
        )
        // If SlardarWAF or a late SPA redirect wiped out the first evalAsyncJs context, retry once
        if (readyJson.isNullOrBlank()) {
            delay(1_200L)
            readyJson = evalAsyncJs(
                scriptBody = buildWaitForContextScript(ScraperConfig.WEBMSSDK_FALLBACK_URL),
                timeoutMs = 9_000L,
                useDesktopUa = useDesktopUa
            )
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
            capturedPostParams = emptyMap()
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

    private fun buildWaitForContextScript(fallbackSdkUrl: String): String = """
        $NETWORK_SNIFFER_BOOTSTRAP_JS
        const sleep = (ms) => new Promise(r => setTimeout(r, ms));
        const collectDomRefs = () => {
          const out = [];
          const seen = new Set();
          const rx = /\/@([A-Za-z0-9._]+)\/video\/(\d{15,22})/g;
          const add = (u, id) => {
            if (!u || !id || seen.has(id)) return;
            seen.add(id);
            out.push({ u, id });
          };
          try {
            document.querySelectorAll('a[href*="/video/"]').forEach(a => {
              const href = a.getAttribute('href') || a.href || '';
              rx.lastIndex = 0;
              const m = rx.exec(href);
              if (m) add(m[1], m[2]);
            });
          } catch (e) {}
          return out;
        };

        let sdkInjected = false;
        let result = { secUid: '', userId: '', userDetailJson: '', mssdkReady: false, domRefs: [] };
        let secUidFirstSeenAt = -1;

        for (let i = 0; i < 45; i++) {
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
          if (result.secUid && secUidFirstSeenAt < 0) {
            secUidFirstSeenAt = i;
          }

          const ac = window.byted_acrawler;
          const mssdkReady = Boolean(
            window.MSSDK_INITIALIZED ||
            (ac && (typeof ac.frontierSign === 'function' || typeof ac.sign === 'function'))
          );
          result.mssdkReady = mssdkReady;
          const hasCapturedPage = Boolean(window.__TT_CAPTURED_FIRST_POST_PAGE);
          const waitedEnoughAfterSecUid = secUidFirstSeenAt >= 0 && (i - secUidFirstSeenAt) >= 16;

          if (result.secUid && (hasCapturedPage || (mssdkReady && waitedEnoughAfterSecUid))) {
            break;
          }

          if (i === 14 && !mssdkReady && !sdkInjected) {
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
                        region: 'US',
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
          await sleep(150);
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
        allowCapturedFirstPage: Boolean
    ): String = """
        $NETWORK_SNIFFER_BOOTSTRAP_JS
        const sleep = (ms) => new Promise(r => setTimeout(r, ms));
        const collectDomRefs = () => {
          const out = [];
          const seen = new Set();
          const rx = /\/@([A-Za-z0-9._]+)\/video\/(\d{15,22})/g;
          const add = (u, id) => {
            if (!u || !id || seen.has(id)) return;
            seen.add(id);
            out.push({ u, id });
          };
          try {
            document.querySelectorAll('a[href*="/video/"]').forEach(a => {
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

        if (${allowCapturedFirstPage} && ${JSONObject.quote(cursor)} === '0') {
          if (!hasItemsInJson(window.__TT_CAPTURED_FIRST_POST_PAGE)) {
            for (let w = 0; w < 10; w++) {
              if (hasItemsInJson(window.__TT_CAPTURED_FIRST_POST_PAGE)) break;
              await sleep(150);
            }
          }
          if (hasItemsInJson(window.__TT_CAPTURED_FIRST_POST_PAGE)) {
            return { status: 200, body: window.__TT_CAPTURED_FIRST_POST_PAGE, domRefs: collectDomRefs() };
          }
        }

        const ensureSigner = async () => {
          for (let i = 0; i < 20; i++) {
            const ac = window.byted_acrawler;
            if (window.MSSDK_INITIALIZED || (ac && (typeof ac.frontierSign === 'function' || typeof ac.sign === 'function'))) {
              return true;
            }
            if (i === 10 && !document.querySelector('script[data-tv-mssdk="1"]') && !window.byted_acrawler) {
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
                        region: 'US',
                        isSDK: false
                      });
                      window.MSSDK_INITIALIZED = true;
                    }
                  } catch (e) {}
                };
                document.head.appendChild(s);
              } catch (e) {}
            }
            await sleep(150);
          }
          return false;
        };
        await ensureSigner();

        let ds = {};
        try {
          const el = document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');
          if (el && el.textContent) {
            ds = (JSON.parse(el.textContent) || {})['__DEFAULT_SCOPE__'] || {};
          }
        } catch (e) {}
        const appCtx = ds['webapp.app-context'] || {};
        const bizCtx = ds['webapp.biz-context'] || {};
        const verifyMatch = document.cookie.match(/(?:^|;\s*)s_v_web_id=([^;]+)/);
        const msTokenMatch = document.cookie.match(/(?:^|;\s*)msToken=([^;]+)/);

        const buildParams = (platformOverride) => {
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
            coverFormat: String((bizCtx.videoCoverSettings && bizCtx.videoCoverSettings.format) || '2'),
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
            region: String(appCtx.region || 'US'),
            root_referer: String(document.referrer || ''),
            screen_height: String(screen.height || 1080),
            screen_width: String(screen.width || 1920),
            tz_name: String((Intl.DateTimeFormat && Intl.DateTimeFormat().resolvedOptions().timeZone) || 'America/New_York'),
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

          if (platformOverride) params.set('device_platform', platformOverride);
          params.delete('X-Bogus');
          params.delete('X-Gnarly');
          params.delete('_signature');
          params.set('secUid', ${JSONObject.quote(secUid)});
          if (${JSONObject.quote(userId)}) {
            params.set('userId', ${JSONObject.quote(userId)});
          }
          params.set('count', String($count));
          params.set('cursor', ${JSONObject.quote(cursor)});
          return params;
        };

        const tryCreatorList = async () => {
          try {
            const cParams = new URLSearchParams({
              aid: '1988',
              secUid: ${JSONObject.quote(secUid)},
              count: String(Math.min(15, Math.max(1, $count))),
              cursor: ${JSONObject.quote(cursor)},
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

        const creatorRes = await tryCreatorList();
        if (creatorRes && hasItemsInJson(creatorRes.body)) {
          return { status: creatorRes.status, body: creatorRes.body, domRefs: collectDomRefs() };
        }

        const baseUrl = 'https://www.tiktok.com/api/post/item_list/';
        const tryFetch = async (qs) => {
          try {
            const resp = await window.fetch(baseUrl + '?' + qs, {
              method: 'GET',
              credentials: 'include'
            });
            const text = await resp.text();
            return { status: resp.status, body: text || '' };
          } catch (e) {
            return { status: 0, body: '' };
          }
        };

        const tryXhr = (qs) => new Promise((resolve) => {
          try {
            const xhr = new XMLHttpRequest();
            xhr.open('GET', baseUrl + '?' + qs, true);
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
                sig = await Promise.resolve(ac.frontierSign(target));
              } else if (typeof ac.sign === 'function') {
                sig = await Promise.resolve(ac.sign({ url: target }));
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

        const fetchAttempt = async (platformOverride) => {
          const qs = buildParams(platformOverride).toString();
          let r = await tryFetch(qs);
          if (hasItemsInJson(r.body)) return r;

          const signedQs = await signWithFrontier(qs);
          if (signedQs) {
            const rSignedFetch = await tryFetch(signedQs);
            if (hasItemsInJson(rSignedFetch.body) || (!isValidPostJson(r.body) && isValidPostJson(rSignedFetch.body))) {
              r = rSignedFetch;
            }
            if (!hasItemsInJson(r.body)) {
              const rSignedXhr = await tryXhr(signedQs);
              if (hasItemsInJson(rSignedXhr.body) || (!isValidPostJson(r.body) && isValidPostJson(rSignedXhr.body))) {
                r = rSignedXhr;
              }
            }
          }

          if (!isValidPostJson(r.body)) {
            await sleep(300);
            const rXhr = await tryXhr(qs);
            if (hasItemsInJson(rXhr.body) || isValidPostJson(rXhr.body)) {
              r = rXhr;
            }
          }
          return r;
        };

        let res = await fetchAttempt(null);
        if (!hasItemsInJson(res.body)) {
          const altPlatform = ${if (isDesktopUa) "'web_mobile'" else "'web_pc'"};
          const altRes = await fetchAttempt(altPlatform);
          if (hasItemsInJson(altRes.body) || (!isValidPostJson(res.body) && isValidPostJson(altRes.body))) {
            res = altRes;
          }
        }

        return { status: res.status, body: res.body || '', domRefs: collectDomRefs() };
    """.trimIndent()

    private companion object {
        val STRIPPED_QUERY_KEYS = setOf("X-Bogus", "X-Gnarly", "_signature", "cursor", "count")
        val BLOCKED_EXTENSIONS = listOf(
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".ico",
            ".mp4", ".m4a", ".mp3", ".webm", ".m3u8", ".ts",
            ".woff", ".woff2", ".ttf", ".otf", ".css"
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

              const isPostApi = (u) => typeof u === 'string' && (
                u.indexOf('/api/post/item_list/') !== -1 ||
                u.indexOf('/api/creator/item_list/') !== -1
              );

              const recordPayload = (url, text) => {
                try {
                  if (url && isPostApi(url)) {
                    window.__TT_CAPTURED_POST_URL = url;
                  }
                  if (!text || typeof text !== 'string' || !text.trim().startsWith('{')) return;
                  const parsed = JSON.parse(text);
                  if (Array.isArray(parsed.itemList) && parsed.itemList.length > 0) {
                    if (!window.__TT_CAPTURED_FIRST_POST_PAGE) {
                      window.__TT_CAPTURED_FIRST_POST_PAGE = text;
                    }
                  }
                } catch (e) {}
              };

              try {
                const origFetch = window.fetch;
                if (typeof origFetch === 'function') {
                  window.fetch = function(input, init) {
                    const url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                    const p = origFetch.apply(this, arguments);
                    if (isPostApi(url)) {
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
                  if (isPostApi(this.__ttUrl)) {
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

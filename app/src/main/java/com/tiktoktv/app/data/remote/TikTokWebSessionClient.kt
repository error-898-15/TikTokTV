package com.tiktoktv.app.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tiktoktv.app.data.config.ScraperConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WebSessionPageResponse(
    val rawApiJson: String,
    val secUid: String,
    val userId: String,
    val userDetailJson: String
)

/**
 * Headless Chromium WebView bridge that executes TikTok's own webmssdk / byted_acrawler
 * signing context on the device to paginate ALL public videos from /api/post/item_list/
 * beyond the ~30-video SSR HTML limit.
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
    private var capturedPostParams: Map<String, String> = emptyMap()

    @Volatile
    private var pageLoadDeferred: CompletableDeferred<Boolean>? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun ensureWebViewOnMain(): WebView? = withContext(Dispatchers.Main) {
        webView?.let { return@withContext it }
        runCatching {
            val view = WebView(appContext)
            view.layout(0, 0, 1080, 1920)
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(view, true)

            with(view.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = ScraperConfig.HEADER_MOBILE_USER_AGENT_VALUE
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
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) {
                    super.onPageFinished(v, url)
                    pageLoadDeferred?.complete(true)
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

        val ready = ensureProfileSessionLocked(cleanUser, forceReload)
        if (!ready && secUidHint.isBlank()) return@withLock null

        val effectiveSecUid = currentSecUid.ifBlank { secUidHint }
        val effectiveUserId = currentUserId.ifBlank { userIdHint }
        if (effectiveSecUid.isBlank()) return@withLock null

        val capturedJson = JSONObject().apply {
            capturedPostParams.forEach { (k, v) -> put(k, v) }
        }.toString()

        val rawResult = evalAsyncJs(
            buildFetchPostListScript(
                secUid = effectiveSecUid,
                userId = effectiveUserId,
                cursor = cursor,
                count = count,
                capturedTemplateJson = capturedJson,
                fallbackSdkUrl = ScraperConfig.WEBMSSDK_FALLBACK_URL
            ),
            timeoutMs = ScraperConfig.WEB_SESSION_TIMEOUT_MS
        ) ?: return@withLock null

        syncCookiesFromManager()

        val parsed = runCatching { JSONObject(rawResult) }.getOrNull() ?: return@withLock null
        val body = parsed.optString("body", "")
        if (body.isBlank() || !body.trimStart().startsWith("{")) {
            return@withLock null
        }
        return@withLock WebSessionPageResponse(
            rawApiJson = body,
            secUid = effectiveSecUid,
            userId = effectiveUserId,
            userDetailJson = currentUserDetailJson
        )
    }

    private suspend fun ensureProfileSessionLocked(
        cleanUser: String,
        forceReload: Boolean
    ): Boolean {
        if (!forceReload &&
            currentPageReady &&
            currentUsername.equals(cleanUser, ignoreCase = true) &&
            currentSecUid.isNotBlank() &&
            webView != null
        ) {
            return true
        }

        val view = ensureWebViewOnMain() ?: return false
        currentUsername = cleanUser
        currentSecUid = ""
        currentUserId = ""
        currentUserDetailJson = ""
        currentPageReady = false
        capturedPostParams = emptyMap()

        val deferred = CompletableDeferred<Boolean>()
        pageLoadDeferred = deferred

        val targetUrl = ScraperConfig.TIKTOK_PROFILE_URL_TEMPLATE.format(Uri.encode(cleanUser))
        withContext(Dispatchers.Main) {
            view.stopLoading()
            view.loadUrl(targetUrl)
        }

        withTimeoutOrNull(12_000L) { deferred.await() }
        pageLoadDeferred = null

        val readyJson = evalAsyncJs(
            buildWaitForContextScript(ScraperConfig.WEBMSSDK_FALLBACK_URL),
            timeoutMs = 8_000L
        )
        syncCookiesFromManager()

        if (!readyJson.isNullOrBlank()) {
            runCatching {
                val obj = JSONObject(readyJson)
                val secUid = obj.optString("secUid", "").trim()
                val userId = obj.optString("userId", "").trim()
                val detail = obj.optString("userDetailJson", "")
                if (secUid.isNotBlank()) currentSecUid = secUid
                if (userId.isNotBlank()) currentUserId = userId
                if (detail.isNotBlank()) currentUserDetailJson = detail
                currentPageReady = secUid.isNotBlank()
            }
        }
        return currentPageReady
    }

    private suspend fun evalAsyncJs(scriptBody: String, timeoutMs: Long): String? {
        val view = ensureWebViewOnMain() ?: return null
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
            currentPageReady = false
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
        const sleep = (ms) => new Promise(r => setTimeout(r, ms));
        let sdkInjected = false;
        let result = { secUid: '', userId: '', userDetailJson: '', mssdkReady: false };

        for (let i = 0; i < 35; i++) {
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

          const ac = window.byted_acrawler;
          const mssdkReady = Boolean(
            window.MSSDK_INITIALIZED ||
            (ac && (typeof ac.frontierSign === 'function' || typeof ac.sign === 'function'))
          );
          result.mssdkReady = mssdkReady;

          if (result.secUid && mssdkReady) {
            break;
          }

          if (i === 12 && !mssdkReady && !sdkInjected) {
            sdkInjected = true;
            try {
              const s = document.createElement('script');
              s.src = ${JSONObject.quote(fallbackSdkUrl)};
              s.async = true;
              s.onload = function() {
                try {
                  if (window.byted_acrawler && typeof window.byted_acrawler.init === 'function') {
                    window.byted_acrawler.init({
                      aid: 1988,
                      dfp: false,
                      boe: false,
                      intercept: true,
                      enablePathList: ['/api/post/item_list/', '/api/post/item_list/v1/', '/api/challenge/item_list/', '/api/explore/item_list/'],
                      region: 'US',
                      isSDK: false
                    });
                    window.MSSDK_INITIALIZED = true;
                  }
                } catch (err) {}
              };
              document.head.appendChild(s);
            } catch (e) {}
          }
          await sleep(150);
        }
        return result;
    """.trimIndent()

    private fun buildFetchPostListScript(
        secUid: String,
        userId: String,
        cursor: String,
        count: Int,
        capturedTemplateJson: String,
        fallbackSdkUrl: String
    ): String = """
        const sleep = (ms) => new Promise(r => setTimeout(r, ms));
        const ensureSigner = async () => {
          for (let i = 0; i < 20; i++) {
            const ac = window.byted_acrawler;
            if (window.MSSDK_INITIALIZED || (ac && typeof ac.frontierSign === 'function')) {
              return true;
            }
            if (i === 10 && !document.querySelector('script[data-tv-mssdk="1"]')) {
              try {
                const s = document.createElement('script');
                s.src = ${JSONObject.quote(fallbackSdkUrl)};
                s.setAttribute('data-tv-mssdk', '1');
                s.onload = () => {
                  try {
                    if (window.byted_acrawler && typeof window.byted_acrawler.init === 'function') {
                      window.byted_acrawler.init({
                        aid: 1988,
                        dfp: false,
                        boe: false,
                        intercept: true,
                        enablePathList: ['/api/post/item_list/', '/api/post/item_list/v1/'],
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
        const verifyMatch = document.cookie.match(/s_v_web_id=([^;]+)/);

        const params = new URLSearchParams();
        const defaults = {
          WebIdLastTime: String(appCtx.webIdCreatedTime || Math.floor(Date.now() / 1000)),
          aid: '1988',
          app_language: String(appCtx.language || 'en'),
          app_name: 'tiktok_web',
          browser_language: String(navigator.language || 'en-US'),
          browser_name: String(navigator.appCodeName || 'Mozilla'),
          browser_online: String(navigator.onLine),
          browser_platform: String(navigator.platform || 'Linux aarch64'),
          browser_version: String(navigator.appVersion || '5.0'),
          channel: 'tiktok_web',
          cookie_enabled: String(navigator.cookieEnabled),
          coverFormat: String((bizCtx.videoCoverSettings && bizCtx.videoCoverSettings.format) || '2'),
          data_collection_enabled: 'true',
          device_id: String(appCtx.wid || ''),
          device_platform: 'web_mobile',
          focus_state: 'true',
          from_page: 'user',
          history_len: String(window.history.length || 1),
          is_fullscreen: 'false',
          is_page_visible: 'true',
          language: String(appCtx.language || 'en'),
          os: 'android',
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

        params.delete('X-Bogus');
        params.delete('X-Gnarly');
        params.delete('_signature');
        params.set('secUid', ${JSONObject.quote(secUid)});
        if (${JSONObject.quote(userId)}) {
          params.set('userId', ${JSONObject.quote(userId)});
        }
        params.set('count', String($count));
        params.set('cursor', ${JSONObject.quote(cursor)});

        const baseUrl = 'https://www.tiktok.com/api/post/item_list/';
        const tryFetch = async (qs) => {
          const resp = await window.fetch(baseUrl + '?' + qs, {
            method: 'GET',
            credentials: 'include'
          });
          const text = await resp.text();
          return { status: resp.status, body: text || '' };
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

        let qs = params.toString();
        let res = await tryFetch(qs);

        if (!res.body || !res.body.trim().startsWith('{')) {
          try {
            const ac = window.byted_acrawler;
            if (ac && typeof ac.frontierSign === 'function') {
              const sig = await Promise.resolve(ac.frontierSign(qs));
              const signedParams = new URLSearchParams(qs);
              if (sig && typeof sig === 'object') {
                for (const [k, v] of Object.entries(sig)) {
                  if (v) signedParams.set(k, String(v));
                }
              } else if (typeof sig === 'string' && sig.length > 0) {
                const extra = new URLSearchParams(sig.replace(/^\?/, ''));
                extra.forEach((v, k) => signedParams.set(k, v));
              }
              res = await tryFetch(signedParams.toString());
              if (!res.body || !res.body.trim().startsWith('{')) {
                res = await tryXhr(signedParams.toString());
              }
            }
          } catch (e) {}
        }

        if (!res.body || !res.body.trim().startsWith('{')) {
          await sleep(400);
          res = await tryXhr(qs);
        }

        return { status: res.status, body: res.body || '' };
    """.trimIndent()

    private companion object {
        val STRIPPED_QUERY_KEYS = setOf("X-Bogus", "X-Gnarly", "_signature", "cursor", "count")
        val BLOCKED_EXTENSIONS = listOf(
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".ico",
            ".mp4", ".m4a", ".mp3", ".webm", ".m3u8", ".ts",
            ".woff", ".woff2", ".ttf", ".otf", ".css"
        )
    }
}

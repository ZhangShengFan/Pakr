package com.webviewapp

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: TopProgressBar
    private lateinit var overlay: View

    private val handler = Handler(Looper.getMainLooper())
    private var overlayVisible = false
    private var isFirstLoad = true

    private val timeoutRunnable = Runnable { hideOverlay() }

    // 错误页状态：防止 onPageFinished 在错误页上执行主题色注入
    private var isShowingError = false
    private var failedUrl: String? = null

    // 按需权限：存储待处理的 web 权限请求
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingGeoCallback: android.webkit.GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if ("{{NO_SCREENSHOT}}" == "true") {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        setContentView(R.layout.activity_main)
        webView     = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        overlay     = findViewById(R.id.overlay)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setColorSchemeColors(
            android.graphics.Color.parseColor("#6366F1")
        )
        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }
        showOverlay()
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            databaseEnabled                  = true
            useWideViewPort                  = true
            loadWithOverviewMode             = true
            setSupportZoom(false)
            builtInZoomControls              = false
            displayZoomControls              = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                // 用户重试或跳转新页面时，重置错误状态
                if (url != failedUrl) {
                    isShowingError = false
                    failedUrl = null
                }
                if (isFirstLoad) showOverlay()
            }

            override fun onPageFinished(view: WebView, url: String) {
                swipeRefresh.isRefreshing = false
                if (!isShowingError) fetchThemeColor(view)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
                    return true
                }
                return false
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    hideOverlay()
                    isShowingError = true
                    failedUrl = request.url.toString()
                    val errDesc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        when (error.errorCode) {
                            android.webkit.WebViewClient.ERROR_HOST_LOOKUP       -> "域名解析失败，请检查网络连接"
                            android.webkit.WebViewClient.ERROR_INTERNET_DISCONNECTED -> "网络未连接，请打开 Wi-Fi 或移动数据"
                            android.webkit.WebViewClient.ERROR_CONNECT            -> "无法连接到服务器"
                            android.webkit.WebViewClient.ERROR_TIMEOUT            -> "连接超时，请稍后重试"
                            android.webkit.WebViewClient.ERROR_CONNECTION_RESET   -> "连接被重置"
                            android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "SSL 握手失败，证书可能有问题"
                            android.webkit.WebViewClient.ERROR_SSL_FAILED         -> "SSL 连接失败"
                            android.webkit.WebViewClient.ERROR_TOO_MANY_REQUESTS  -> "请求过多，请稍后重试"
                            android.webkit.WebViewClient.ERROR_UNKNOWN            -> "未知错误"
                            else -> error.description?.toString()?.takeIf { it.isNotBlank() } ?: "加载失败（错误码 ${error.errorCode}）"
                        }
                    } else {
                        "网络连接失败，请检查网络后重试"
                    }
                    view.loadDataWithBaseURL(failedUrl, errorHtml(failedUrl, errDesc), "text/html", "UTF-8", null)
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.setProgress(newProgress)
                if (newProgress >= 75 && isFirstLoad) {
                    hideOverlay()
                    isFirstLoad = false
                }
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                val androidPerms = mutableListOf<String>()
                for (res in request.resources) {
                    when (res) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            androidPerms.add(android.Manifest.permission.CAMERA)
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            androidPerms.add(android.Manifest.permission.RECORD_AUDIO)
                    }
                }
                val toRequest = androidPerms.filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (toRequest.isEmpty()) {
                    request.grant(request.resources)
                } else {
                    pendingWebPermissionRequest = request
                    ActivityCompat.requestPermissions(
                        this@MainActivity, toRequest.toTypedArray(), PERMISSION_REQUEST_CODE
                    )
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: android.webkit.GeolocationPermissions.Callback
            ) {
                val perms = arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
                val toRequest = perms.filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (toRequest.isEmpty()) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoCallback = callback
                    pendingGeoOrigin   = origin
                    ActivityCompat.requestPermissions(
                        this@MainActivity, toRequest.toTypedArray(), PERMISSION_REQUEST_CODE + 1
                    )
                }
            }
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                try {
                    startActivityForResult(fileChooserParams.createIntent(), FILE_CHOOSER_REQUEST)
                    fileChooserCallbackRef = filePathCallback
                } catch (e: Exception) {
                    filePathCallback.onReceiveValue(null)
                }
                return true
            }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val uri = Uri.parse(url)
                val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                val req = DownloadManager.Request(uri).apply {
                    setMimeType(mimetype)
                    addRequestHeader("User-Agent", userAgent)
                    setDescription("正在下载...")
                    setTitle(filename)
                    allowScanningByMediaScanner()
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(req)
                android.widget.Toast.makeText(this, "开始下载：$filename", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            }
        }
        // 键盘弹出适配：FLAG_FULLSCREEN 下 adjustResize 失效，手动监听 Insets
        val rootView = window.decorView.rootView
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val imeHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val navHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            webView.setPadding(0, 0, 0, if (imeHeight > 0) imeHeight - navHeight else 0)
            insets
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onThemeColor(hex: String) {
                try {
                    val color = android.graphics.Color.parseColor(hex)
                    runOnUiThread { progressBar.setBarColor(color) }
                } catch (e: Exception) {}
            }
        }, "ThemeBridge")

        // ── NativeBridge：供网页调用的原生功能 ──
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun vibrate(ms: Long) {
                try {
                    val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if (android.os.Build.VERSION.SDK_INT >= 26)
                        v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(1,2000), VibrationEffect.DEFAULT_AMPLITUDE))
                    else @Suppress("DEPRECATION") v.vibrate(ms.coerceIn(1,2000))
                } catch (e: Exception) {}
            }

            @JavascriptInterface
            fun share(title: String, text: String, url: String) {
                runOnUiThread {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, title)
                        putExtra(Intent.EXTRA_TEXT, if (url.isNotEmpty()) "$text\n$url" else text)
                    }
                    startActivity(Intent.createChooser(intent, title))
                }
            }

            @JavascriptInterface
            fun toast(msg: String) {
                runOnUiThread {
                    android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            }

            @JavascriptInterface
            fun getAppVersion(): String = APP_VERSION

            @JavascriptInterface
            fun openExternal(url: String) {
                runOnUiThread {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
                }
            }

            @JavascriptInterface
            fun back() {
                runOnUiThread { if (webView.canGoBack()) webView.goBack() }
            }

            @JavascriptInterface
            fun getPermissions(): String = "camera,microphone,location,storage"
        }, "NativeBridge")
        webView.loadUrl(APP_URL)
    }

    private fun fetchThemeColor(view: WebView) {
        val js = """
            (function() {
                var m = document.querySelector('meta[name="theme-color"]');
                if (m && m.content) { ThemeBridge.onThemeColor(m.content); return; }
                var el = document.elementFromPoint(window.innerWidth/2, 1);
                if (el) {
                    var bg = getComputedStyle(el).backgroundColor;
                    var r = bg.match(/rgba?\((\d+),(\d+),(\d+)/);
                    if (r) ThemeBridge.onThemeColor(
                        '#' + [r[1],r[2],r[3]].map(function(x){
                            return ('0' + parseInt(x).toString(16)).slice(-2);
                        }).join('')
                    );
                }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun showOverlay() {
        if (overlayVisible) return
        overlayVisible = true
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        progressBar.setProgress(0)
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, 10_000L)
    }

    private fun hideOverlay() {
        if (!overlayVisible) return
        handler.removeCallbacks(timeoutRunnable)
        overlayVisible = false
        overlay.animate().alpha(0f).setDuration(300).withEndAction {
            overlay.visibility = View.GONE
        }.start()
    }

    private fun errorHtml(url: String?, errDesc: String? = null) = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
        <body style="margin:0;display:flex;align-items:center;justify-content:center;
        height:100vh;font-family:-apple-system,sans-serif;flex-direction:column;background:#fff;color:#1a1a1a;padding:32px;box-sizing:border-box;text-align:center;">
        <svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="#d1d5db" stroke-width="1.5" style="margin-bottom:8px;flex-shrink:0">
          <circle cx="12" cy="12" r="9"/>
          <path d="M4.93 4.93l14.14 14.14"/>
        </svg>
        <p style="margin:12px 0 6px;font-size:17px;font-weight:600;color:#111;">网页加载失败</p>
        <p style="margin:0 0 28px;font-size:13px;color:#888;max-width:260px;line-height:1.6">${errDesc ?: "网络连接失败，请检查网络后重试"}</p>
        <button onclick="window.location.replace('${url ?: "about:blank"}')"
          style="padding:13px 36px;border:none;border-radius:999px;
          background:#111;color:#fff;font-size:15px;cursor:pointer;font-family:-apple-system,sans-serif;font-weight:500;
          -webkit-tap-highlight-color:transparent;active:opacity:0.8;">重试</button>
        </body></html>
    """.trimIndent()

    private var backPressedTime = 0L
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            val now = System.currentTimeMillis()
            if (now - backPressedTime < 2000) {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            } else {
                backPressedTime = now
                android.widget.Toast.makeText(this, "再按一次退出", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); webView.destroy(); super.onDestroy() }

    private var fileChooserCallbackRef: ValueCallback<Array<Uri>>? = null

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                val req = pendingWebPermissionRequest
                if (req != null) {
                    pendingWebPermissionRequest = null
                    if (grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                        req.grant(req.resources)
                    } else {
                        req.deny()
                    }
                }
            }
            PERMISSION_REQUEST_CODE + 1 -> {
                val cb     = pendingGeoCallback
                val origin = pendingGeoOrigin
                pendingGeoCallback = null
                pendingGeoOrigin   = null
                if (cb != null && origin != null) {
                    val granted = grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
                    cb.invoke(origin, granted, false)
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            fileChooserCallbackRef?.onReceiveValue(
                if (resultCode == RESULT_OK && data != null)
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                else null
            )
            fileChooserCallbackRef = null
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    companion object {
        const val APP_URL = "{{APP_URL}}"
        const val APP_VERSION = "{{VERSION_NAME}}"
        private const val FILE_CHOOSER_REQUEST = 1001
        const val PERMISSION_REQUEST_CODE = 1002
    }


}

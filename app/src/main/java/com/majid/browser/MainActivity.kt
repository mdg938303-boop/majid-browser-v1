package com.majid.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class MainActivity : Activity() {

    /** One browser tab. Incognito is a per-tab property (not a global flag). */
    private class Tab(val web: WebView, val incognito: Boolean) {
        var title: String = ""
        var url: String = ""
        var loading: Boolean = false
        var progress: Int = 0
        var desktop: Boolean = false
        val defaultUa: String = web.settings.userAgentString
    }

    companion object {
        private const val ERROR_TITLE = "Page can't be opened"
        private const val REQ_FILE = 11
        private const val REQ_PERM = 12
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
        private val ENGINE_KEYS = arrayOf("google", "duckduckgo", "bing")
        private val ENGINE_NAMES = arrayOf("Google", "DuckDuckGo", "Bing")
    }

    private val bg = Color.rgb(16, 17, 27)
    private val bgIncognito = Color.rgb(6, 6, 10)
    private val panel = Color.rgb(30, 32, 48)
    private val purple = Color.rgb(111, 99, 245)
    private val white = Color.WHITE

    private lateinit var frame: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var address: EditText
    private lateinit var webContainer: FrameLayout
    private lateinit var titleText: TextView
    private lateinit var brandText: TextView
    private lateinit var tabsText: TextView
    private lateinit var reloadBtn: TextView
    private lateinit var starBtn: TextView
    private lateinit var progress: ProgressBar

    private val tabs = mutableListOf<Tab>()
    private var active = 0

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var permCallback: ((Boolean) -> Unit)? = null
    private var permAny = false

    private val prefs by lazy { getSharedPreferences("majid_browser", Context.MODE_PRIVATE) }

    // ───────────────────────────── lifecycle ─────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        buildUi()
        val external = intent?.data?.toString()
        newTab(if (external != null && isWebUrl(external)) external else homeUrl())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val u = intent.data?.toString()
        if (u != null && isWebUrl(u)) newTab(u)
    }

    override fun onPause() {
        tabs.forEach { it.web.onPause() }
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        current()?.web?.onResume()
    }

    override fun onDestroy() {
        permCallback?.invoke(false)
        fileCallback?.onReceiveValue(null)
        tabs.toList().forEach { destroyTab(it) }
        tabs.clear()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) {
            hideCustomView()
            return
        }
        val t = current()
        if (t != null && t.web.canGoBack()) t.web.goBack()
        else if (tabs.size > 1) closeTab(active)
        else super.onBackPressed()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_PERM) {
            val cb = permCallback
            permCallback = null
            val ok = if (permAny) grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            else grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            cb?.invoke(ok)
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    /** Ask for Android runtime permissions, then report whether they were granted. */
    private fun withPermissions(perms: Array<String>, any: Boolean = false, onResult: (Boolean) -> Unit) {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            onResult(true)
            return
        }
        permCallback?.invoke(false)
        permCallback = onResult
        permAny = any
        requestPermissions(missing.toTypedArray(), REQ_PERM)
    }

    // ───────────────────────────── UI building ─────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, size: Float = 14f, color: Int = white): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
        }

    private fun button(s: String, action: () -> Unit): TextView =
        label(s, 18f).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { action() }
            isClickable = true
        }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun buildUi() {
        frame = FrameLayout(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            // Android 15 (targetSdk 35) forces edge-to-edge: pad for system bars + keyboard.
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                    v.setPadding(i.left, i.top, i.right, i.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(
                        insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                    )
                }
                insets
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(8))
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        brandText = label("MAJID", 11f, 0xFF9B91FF.toInt()).apply { gravity = Gravity.START }
        titleText = label("Browser", 20f).apply {
            gravity = Gravity.START
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        brand.addView(brandText)
        brand.addView(titleText)
        tabsText = button("1") { showTabs() }
        header.addView(brand)
        header.addView(button("＋") { newTab(homeUrl()) })
        header.addView(tabsText)
        root.addView(header)

        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(5), dp(12), dp(8))
        }
        address = EditText(this).apply {
            hint = "Search or enter website"
            textSize = 15f
            isSingleLine = true
            setSelectAllOnFocus(true)
            setTextColor(white)
            setHintTextColor(0xFFB7B8C7.toInt())
            setPadding(dp(14), 0, dp(14), 0)
            setBackgroundColor(panel)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnFocusChangeListener { _, focused ->
                if (!focused) setText(current()?.url ?: "")
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    loadInput(text.toString()); true
                } else false
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        reloadBtn = button("↻") {
            current()?.let {
                if (it.loading) {
                    it.web.stopLoading()
                    it.loading = false
                    refreshUi()
                } else it.web.reload()
            }
        }
        addressRow.addView(address)
        addressRow.addView(reloadBtn)
        root.addView(addressRow)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(purple)
            layoutParams = LinearLayout.LayoutParams(-1, dp(3))
        }
        root.addView(progress)

        webContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        root.addView(webContainer)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(panel)
        }
        starBtn = button("☆") { toggleBookmark() }
        nav.addView(button("‹") { goBack() }, weightParams())
        nav.addView(button("›") { current()?.web?.let { if (it.canGoForward()) it.goForward() } }, weightParams())
        nav.addView(button("⌂") { current()?.web?.loadUrl(homeUrl()) }, weightParams())
        nav.addView(starBtn, weightParams())
        nav.addView(button("⋮") { showMenu() }, weightParams())
        root.addView(nav)

        // Don't let the address bar grab focus (and the keyboard) on launch.
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.requestFocus()

        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, dp(52), 1f)

    /** Sync every piece of chrome with the active tab. */
    private fun refreshUi() {
        val t = current() ?: return
        if (!address.hasFocus()) address.setText(t.url)
        titleText.text = when {
            t.title.isNotBlank() -> t.title
            t.loading -> "Loading…"
            else -> "Majid Browser"
        }
        brandText.text = if (t.incognito) "MAJID · INCOGNITO" else "MAJID"
        root.setBackgroundColor(if (t.incognito) bgIncognito else bg)
        reloadBtn.text = if (t.loading) "✕" else "↻"
        progress.progress = t.progress
        progress.visibility = if (t.loading) View.VISIBLE else View.GONE
        starBtn.text = if (isBookmarked(t.url)) "★" else "☆"
        tabsText.text = tabs.size.toString()
    }

    // ───────────────────────────── tabs ─────────────────────────────

    private fun current(): Tab? = tabs.getOrNull(active)

    private fun isActive(t: Tab) = tabs.getOrNull(active) === t

    private fun newTab(url: String?, incognito: Boolean = false): Tab {
        val tab = Tab(WebView(this), incognito)
        configure(tab)
        tabs.add(tab)
        showTab(tabs.lastIndex)
        if (url != null) {
            tab.url = url
            tab.web.loadUrl(url)
        }
        refreshUi()
        return tab
    }

    private fun showTab(index: Int) {
        if (index !in tabs.indices) return
        active = index
        webContainer.removeAllViews()
        val t = tabs[index]
        webContainer.addView(t.web, FrameLayout.LayoutParams(-1, -1))
        t.web.onResume()
        refreshUi()
    }

    private fun destroyTab(t: Tab) {
        (t.web.parent as? ViewGroup)?.removeView(t.web)
        t.web.stopLoading()
        if (t.incognito) {
            t.web.clearHistory()
            t.web.clearCache(true)
        }
        t.web.destroy()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val t = tabs.removeAt(index)
        destroyTab(t)
        if (tabs.isEmpty()) {
            newTab(homeUrl())
            return
        }
        active = when {
            index < active -> active - 1
            else -> active.coerceAtMost(tabs.lastIndex)
        }
        showTab(active)
    }

    private fun showTabs() {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        lateinit var dialog: AlertDialog
        tabs.forEachIndexed { i, t ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = (if (t.incognito) "🕶 " else "") +
                t.title.ifBlank { t.url.ifBlank { "New tab" } }
            val tv = TextView(this).apply {
                text = name
                textSize = 15f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (i == active) purple else Color.BLACK)
                if (i == active) typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { dialog.dismiss(); showTab(i) }
            }
            val close = TextView(this).apply {
                text = "✕"
                textSize = 18f
                setTextColor(Color.DKGRAY)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener {
                    dialog.dismiss()
                    closeTab(i)
                    showTabs()
                }
            }
            row.addView(tv)
            row.addView(close)
            list.addView(row)
        }
        dialog = AlertDialog.Builder(this).setTitle("Open tabs (${tabs.size})")
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("New tab") { _, _ -> newTab(homeUrl()) }
            .setNeutralButton("Incognito") { _, _ -> newTab(homeUrl(), incognito = true) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun goBack() {
        val t = current() ?: return
        if (t.web.canGoBack()) t.web.goBack() else toast("No previous page")
    }

    // ───────────────────────────── WebView setup ─────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(tab: Tab) {
        val w = tab.web
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = !tab.incognito
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mediaPlaybackRequiresUserGesture = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.cacheMode = if (tab.incognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, !tab.incognito)

        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                tab.url = url ?: tab.url
                tab.title = ""
                tab.loading = true
                tab.progress = 0
                if (isActive(tab)) refreshUi()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tab.url = url ?: tab.url
                tab.loading = false
                tab.progress = 100
                if (tab.title.isBlank()) tab.title = view?.title ?: ""
                if (!tab.incognito && tab.title != ERROR_TITLE && isWebUrl(tab.url)) {
                    addHistory(tab.title.ifBlank { tab.url }, tab.url)
                }
                if (isActive(tab)) refreshUi()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val url = uri.toString()
                return when (uri.scheme?.lowercase()) {
                    "http", "https", "about" -> false
                    "intent" -> {
                        if (request.hasGesture()) openIntentUrl(view, url)
                        true
                    }
                    "tel", "mailto", "sms", "smsto", "geo", "market" -> {
                        if (request.hasGesture()) {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                            } catch (_: Exception) {
                                toast("No app found to open this link")
                            }
                        }
                        true
                    }
                    else -> true // file:, content:, javascript: from links, etc. are blocked
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                val failing = request.url.toString()
                val hint = if (failing.startsWith("http://")) "Plain HTTP sites are blocked for your safety." else ""
                showErrorPage(view, failing, error.description.toString(), hint)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                showErrorPage(view, error.url ?: tab.url, "This site's security certificate is not trusted.", "")
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                tab.progress = newProgress
                if (isActive(tab)) {
                    progress.progress = newProgress
                    progress.visibility = if (tab.loading && newProgress < 100) View.VISIBLE else View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                tab.title = title ?: ""
                if (isActive(tab)) refreshUi()
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(fileChooserParams.createIntent(), REQ_FILE)
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    toast("No file picker available")
                    false
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || callback == null) return
                showCustomView(view, callback)
            }

            override fun onHideCustomView() = hideCustomView()

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                runOnUiThread { askSitePermission(request) }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (origin == null || callback == null) return
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Location")
                    .setMessage("$origin wants to know your location.")
                    .setPositiveButton("Allow") { _, _ ->
                        withPermissions(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                            any = true
                        ) { ok -> callback.invoke(origin, ok, false) }
                    }
                    .setNegativeButton("Block") { _, _ -> callback.invoke(origin, false, false) }
                    .setOnCancelListener { callback.invoke(origin, false, false) }
                    .show()
            }
        }

        w.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType, tab.url)
        }
    }

    private fun showErrorPage(view: WebView, failingUrl: String, message: String, hint: String) {
        val m = TextUtils.htmlEncode(message)
        val u = TextUtils.htmlEncode(failingUrl)
        val h = TextUtils.htmlEncode(hint)
        val html = """
            <html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>$ERROR_TITLE</title>
            <style>
              body{font-family:sans-serif;background:#10111b;color:#fff;padding:28px;text-align:center}
              a{color:#9b91ff}
              .u{color:#9b91ff;word-break:break-all}
            </style></head><body>
            <h2>$ERROR_TITLE</h2>
            <p>$m</p>
            <p class="u">$u</p>
            <p>$h</p>
            <p><a href="$u">Try again</a></p>
            </body></html>
        """.trimIndent()
        view.loadDataWithBaseURL(failingUrl, html, "text/html", "UTF-8", failingUrl)
    }

    private fun openIntentUrl(view: WebView, url: String) {
        try {
            val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.component = null
            i.selector = null
            startActivity(i)
        } catch (_: Exception) {
            val fallback = try {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            } catch (_: Exception) {
                null
            }
            if (fallback != null && isWebUrl(fallback)) view.loadUrl(fallback)
            else toast("No app found to open this link")
        }
    }

    // ───────────────────── site permissions / fullscreen video ─────────────────────

    private fun askSitePermission(request: PermissionRequest) {
        val needed = mutableListOf<String>()
        val names = mutableListOf<String>()
        val allowed = mutableListOf<String>()
        for (r in request.resources) {
            when (r) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                    needed += Manifest.permission.CAMERA; names += "camera"; allowed += r
                }
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                    needed += Manifest.permission.RECORD_AUDIO; names += "microphone"; allowed += r
                }
            }
        }
        if (allowed.isEmpty()) {
            request.deny()
            return
        }
        val host = request.origin?.host ?: "This site"
        AlertDialog.Builder(this)
            .setTitle("Permission request")
            .setMessage("$host wants to use your ${names.joinToString(" and ")}.")
            .setPositiveButton("Allow") { _, _ ->
                withPermissions(needed.toTypedArray()) { ok ->
                    if (ok) request.grant(allowed.toTypedArray()) else request.deny()
                }
            }
            .setNegativeButton("Block") { _, _ -> request.deny() }
            .setOnCancelListener { request.deny() }
            .show()
    }

    private fun showCustomView(v: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            cb.onCustomViewHidden()
            return
        }
        customView = v
        customCallback = cb
        v.setBackgroundColor(Color.BLACK)
        frame.addView(v, FrameLayout.LayoutParams(-1, -1))
        root.visibility = View.GONE
        setFullscreenMode(true)
    }

    private fun hideCustomView() {
        val v = customView ?: return
        frame.removeView(v)
        customView = null
        root.visibility = View.VISIBLE
        setFullscreenMode(false)
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    private fun setFullscreenMode(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (on) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                c.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // ───────────────────────────── downloads ─────────────────────────────

    private fun startDownload(url: String, ua: String?, cd: String?, mime: String?, referer: String?) {
        if (!isWebUrl(url)) {
            toast("This download type isn't supported")
            return
        }
        if (Build.VERSION.SDK_INT <= 28) {
            withPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { ok ->
                if (ok) enqueueDownload(url, ua, cd, mime, referer) else toast("Storage permission denied")
            }
        } else {
            enqueueDownload(url, ua, cd, mime, referer)
        }
    }

    private fun enqueueDownload(url: String, ua: String?, cd: String?, mime: String?, referer: String?) {
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val r = DownloadManager.Request(Uri.parse(url))
            if (!mime.isNullOrEmpty()) r.setMimeType(mime)
            if (!ua.isNullOrEmpty()) r.addRequestHeader("User-Agent", ua)
            CookieManager.getInstance().getCookie(url)?.let { r.addRequestHeader("Cookie", it) }
            if (!referer.isNullOrEmpty()) r.addRequestHeader("Referer", referer)
            r.setTitle(name)
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
            toast("Downloading $name")
        } catch (_: Exception) {
            toast("Download could not start")
        }
    }

    // ───────────────────────────── navigation / search ─────────────────────────────

    private fun isWebUrl(u: String): Boolean {
        val l = u.lowercase()
        return l.startsWith("http://") || l.startsWith("https://")
    }

    private fun engine(): String {
        val e = prefs.getString("engine", "google") ?: "google"
        return if (e in ENGINE_KEYS) e else "google"
    }

    private fun homeUrl() = when (engine()) {
        "duckduckgo" -> "https://duckduckgo.com"
        "bing" -> "https://www.bing.com"
        else -> "https://www.google.com"
    }

    private fun searchUrl(q: String): String {
        val enc = URLEncoder.encode(q, "UTF-8")
        return when (engine()) {
            "duckduckgo" -> "https://duckduckgo.com/?q=$enc"
            "bing" -> "https://www.bing.com/search?q=$enc"
            else -> "https://www.google.com/search?q=$enc"
        }
    }

    /** Turn whatever the user typed into a safe http(s) URL or a search. */
    private fun resolveInput(input: String): String? {
        val v = input.trim()
        if (v.isEmpty()) return null
        val l = v.lowercase()
        if (isWebUrl(v)) return v
        val blocked = listOf("javascript:", "file:", "data:", "content:", "intent:", "about:", "view-source:")
        return if (blocked.any { l.startsWith(it) } || v.contains("://") || v.contains(" ") || !v.contains(".")) {
            searchUrl(v)
        } else "https://$v"
    }

    private fun loadInput(input: String) {
        val url = resolveInput(input) ?: return
        current()?.web?.loadUrl(url)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus()
        current()?.web?.requestFocus()
    }

    // ───────────────────────────── bookmarks & history ─────────────────────────────

    private fun readList(key: String): MutableList<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val raw = prefs.getString(key, "") ?: ""
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += o.optString("t") to o.getString("u")
            }
        } catch (_: Exception) {
            // Migrate the v1.0 newline-separated format.
            raw.lines().filter { it.isNotBlank() }.forEach { out += it to it }
        }
        return out
    }

    private fun writeList(key: String, list: List<Pair<String, String>>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.first).put("u", it.second)) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun addHistory(title: String, url: String) {
        val list = readList("history")
        list.removeAll { it.second == url }
        list.add(0, title to url)
        writeList("history", list.take(200))
    }

    private fun isBookmarked(url: String) = url.isNotEmpty() && readList("bookmarks").any { it.second == url }

    private fun toggleBookmark() {
        val t = current() ?: return
        if (!isWebUrl(t.url)) {
            toast("Nothing to bookmark")
            return
        }
        val list = readList("bookmarks")
        if (list.any { it.second == t.url }) {
            list.removeAll { it.second == t.url }
            toast("Bookmark removed")
        } else {
            list.add(t.title.ifBlank { t.url } to t.url)
            toast("Bookmark saved")
        }
        writeList("bookmarks", list.take(500))
        refreshUi()
    }

    private fun showSaved(title: String, key: String) {
        val items = readList(key)
        if (items.isEmpty()) {
            AlertDialog.Builder(this).setTitle(title).setMessage("Nothing saved yet")
                .setPositiveButton("OK", null).show()
            return
        }
        val labels = items.map { (t, u) -> if (t.isNotBlank() && t != u) "$t\n$u" else u }.toTypedArray()
        val d = AlertDialog.Builder(this).setTitle("$title (long-press to delete)")
            .setItems(labels) { _, which ->
                val u = items[which].second
                if (isWebUrl(u)) current()?.web?.loadUrl(u)
            }
            .setPositiveButton("Clear all") { _, _ -> prefs.edit().remove(key).apply(); refreshUi() }
            .setNegativeButton("Close", null)
            .create()
        d.show()
        d.listView.setOnItemLongClickListener { _, _, pos, _ ->
            items.removeAt(pos)
            writeList(key, items)
            d.dismiss()
            refreshUi()
            showSaved(title, key)
            true
        }
    }

    // ───────────────────────────── menu ─────────────────────────────

    private fun showMenu() {
        val t = current()
        val items = arrayOf(
            "New tab",
            "New incognito tab",
            "Bookmarks",
            "History",
            "Desktop site: " + if (t?.desktop == true) "ON" else "OFF",
            "Search engine",
            "Share page",
            "Close current tab",
            "Clear browsing data"
        )
        AlertDialog.Builder(this).setTitle("Majid Browser").setItems(items) { _, which ->
            when (which) {
                0 -> newTab(homeUrl())
                1 -> {
                    newTab(homeUrl(), incognito = true)
                    toast("Incognito tab (basic mode)")
                }
                2 -> showSaved("Bookmarks", "bookmarks")
                3 -> showSaved("History", "history")
                4 -> toggleDesktop()
                5 -> chooseEngine()
                6 -> current()?.url?.takeIf { isWebUrl(it) }?.let { url ->
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }, "Share page"
                        )
                    )
                }
                7 -> closeTab(active)
                8 -> confirmClearData()
            }
        }.show()
    }

    private fun toggleDesktop() {
        val t = current() ?: return
        t.desktop = !t.desktop
        t.web.settings.userAgentString = if (t.desktop) DESKTOP_UA else t.defaultUa
        t.web.settings.useWideViewPort = true
        t.web.settings.loadWithOverviewMode = true
        t.web.reload()
        toast(if (t.desktop) "Desktop site on" else "Mobile site on")
    }

    private fun chooseEngine() {
        val cur = ENGINE_KEYS.indexOf(engine()).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Search engine")
            .setSingleChoiceItems(ENGINE_NAMES, cur) { d, which ->
                prefs.edit().putString("engine", ENGINE_KEYS[which]).apply()
                d.dismiss()
            }.show()
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this).setTitle("Clear browsing data")
            .setMessage("Clears history, cache, cookies and site data (you will be signed out of sites). Bookmarks are kept.")
            .setPositiveButton("Clear") { _, _ ->
                prefs.edit().remove("history").apply()
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                tabs.forEach { it.web.clearHistory(); it.web.clearCache(true) }
                toast("Browsing data cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

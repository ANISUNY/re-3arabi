package com.witanime

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element

class WitAnime : MainAPI() {
    override var mainUrl = "https://witanime.site"
    override var name = "WitAnime"
    override val hasMainPage = true
    override var lang = "ar"

    private val cfKiller = com.lagradost.cloudstream3.network.CloudflareKiller()

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.106 Mobile Safari/537.36"

    companion object {
        @Volatile var isWebViewOpen = false
        @Volatile var lastWebViewOpenTime = 0L
    }

    // ======================= Mega WebView interceptor =======================
    object PlayerAccess {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var isMonitoring = false
        private var lastHookedPlayer: Any? = null
        private var activeDialog: Dialog? = null
        private var isLoopStarted = false

        private val monitorRunnable = object : Runnable {
            override fun run() {
                if (!isMonitoring) return
                hookPlayerListener()
                handler.postDelayed(this, 1000L)
            }
        }

        fun startMonitoring() {
            if (isLoopStarted) return
            isLoopStarted = true
            isMonitoring = true
            handler.post(monitorRunnable)
        }

        private fun findFragmentRecursive(
            fragment: androidx.fragment.app.Fragment,
            packageName: String
        ): androidx.fragment.app.Fragment? {
            if (fragment.javaClass.name.startsWith(packageName)) return fragment
            try {
                for (child in fragment.childFragmentManager.fragments) {
                    if (child != null) {
                        findFragmentRecursive(child, packageName)?.let { return it }
                    }
                }
            } catch (e: Exception) {
            }
            return null
        }

        fun getActiveActivity(): Activity? {
            return try {
                val atClass = Class.forName("android.app.ActivityThread")
                val at = atClass.getMethod("currentActivityThread").invoke(null)
                val field = atClass.getDeclaredField("mActivities")
                field.isAccessible = true
                val activities = field.get(at) as Map<*, *>
                var result: Activity? = null
                for (rec in activities.values) {
                    if (rec == null) continue
                    val f = rec.javaClass.getDeclaredField("activity")
                    f.isAccessible = true
                    val act = f.get(rec) as? Activity
                    if (act != null && !act.isFinishing && !act.isDestroyed) {
                        result = act
                        break
                    }
                }
                result
            } catch (e: Exception) {
                null
            }
        }

        fun getPlayerFragment(): Any? {
            val activity = getActiveActivity() ?: return null
            return try {
                val fragments = (activity as? androidx.fragment.app.FragmentActivity)
                    ?.supportFragmentManager?.fragments ?: return null
                for (f in fragments) {
                    if (f != null) {
                        findFragmentRecursive(f, "com.lagradost.cloudstream3.ui.player")?.let { return it }
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
        }

        fun currentPlayer(): Any? {
            val fragment = getPlayerFragment() as? androidx.fragment.app.Fragment ?: return null
            return try {
                val f = fragment.javaClass.getDeclaredField("player")
                f.isAccessible = true
                f.get(fragment)
            } catch (e: Exception) {
                try {
                    fragment.javaClass.getMethod("getPlayer").invoke(fragment)
                } catch (ex: Exception) {
                    null
                }
            }
        }

        fun getAppContext(): Context? = try {
            val c = Class.forName("android.app.ActivityThread")
            c.getMethod("currentApplication").invoke(null) as? Context
        } catch (e: Exception) {
            null
        }

        fun pausePlayer() {
            val player = currentPlayer() ?: return
            try {
                val m = player.javaClass.methods.firstOrNull { it.name == "handleEvent" } ?: return
                val types = m.parameterTypes
                if (types.isEmpty()) return
                val pause = types[0].enumConstants?.firstOrNull {
                    it.toString().contains("Pause", ignoreCase = true)
                }
                if (types.size == 2) {
                    val src = types[1].enumConstants?.firstOrNull {
                        it.toString().contains("Sync", ignoreCase = true)
                    } ?: types[1].enumConstants?.firstOrNull {
                        it.toString().contains("UI", ignoreCase = true)
                    }
                    m.invoke(player, pause, src)
                } else {
                    m.invoke(player, pause)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun getRealExoPlayer(player: Any): Any? {
            val rootName = player.javaClass.name
            if (rootName.contains("Player", true) && !rootName.contains("Cache", true)) {
                try {
                    if (player.javaClass.methods.any { it.name == "addListener" }) return player
                } catch (e: Exception) {
                }
            }
            try {
                for (f in player.javaClass.declaredFields) {
                    f.isAccessible = true
                    val v = f.get(player) ?: continue
                    val cn = v.javaClass.name
                    if (cn.contains("Player", true) && !cn.contains("Cache", true)) {
                        try {
                            if (v.javaClass.methods.any { it.name == "addListener" }) return v
                        } catch (ex: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
            }
            return null
        }

        private fun hookPlayerListener() {
            val raw = currentPlayer() ?: return
            val player = getRealExoPlayer(raw) ?: return
            if (player === lastHookedPlayer) return
            lastHookedPlayer = player
            try {
                val listenerClass = try {
                    Class.forName("androidx.media3.common.Player\$Listener")
                } catch (e: Exception) {
                    Class.forName("com.google.android.exoplayer2.Player\$Listener")
                }
                val addListener = player.javaClass.getMethod("addListener", listenerClass)
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass),
                    object : java.lang.reflect.InvocationHandler {
                        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                            when (method.name) {
                                "equals" -> return proxy === (args?.get(0))
                                "hashCode" -> return System.identityHashCode(proxy)
                                "toString" -> return "ExoPlayerProxyListener"
                            }
                            handler.post { checkMegaPlayback(player) }
                            val rt = method.returnType
                            if (rt == Boolean::class.javaPrimitiveType || rt == Boolean::class.java) return false
                            if (rt?.isPrimitive == true) return 0
                            return null
                        }
                    }
                )
                addListener.invoke(player, proxy)
            } catch (e: Exception) {
                android.util.Log.e("WitAnimeScanner", "hook failed", e)
            }
        }

        private fun findUrlInObject(obj: Any, depth: Int = 0): String? {
            if (depth > 3) return null
            try {
                for (f in obj.javaClass.declaredFields) {
                    f.isAccessible = true
                    val v = f.get(obj) ?: continue
                    if (v is String && v.contains("mega-webview://")) return v
                    if (v is Uri && v.toString().contains("mega-webview://")) return v.toString()
                    val pkg = v.javaClass.`package`?.name ?: ""
                    if (pkg.contains("lagradost") || pkg.contains("media3") || pkg.contains("exoplayer") || pkg.contains("google")) {
                        findUrlInObject(v, depth + 1)?.let { return it }
                    }
                }
            } catch (e: Exception) {
            }
            return null
        }

        private fun getPlayingUrl(player: Any): String? = try {
            val item = player.javaClass.getMethod("getCurrentMediaItem").invoke(player)
            val lc = item?.javaClass?.getDeclaredField("localConfiguration")?.also { it.isAccessible = true }?.get(item)
            val uri = lc?.javaClass?.getDeclaredField("uri")?.also { it.isAccessible = true }?.get(lc) as? Uri
            uri?.toString() ?: findUrlInObject(player)
        } catch (e: Exception) {
            findUrlInObject(player)
        }

        private fun checkMegaPlayback(player: Any) {
            val now = System.currentTimeMillis()
            synchronized(WitAnime::class.java) {
                if (WitAnime.isWebViewOpen || (now - WitAnime.lastWebViewOpenTime) < 10000L || activeDialog?.isShowing == true) return
                val playing = getPlayingUrl(player) ?: return
                if (playing.contains("mega-webview://")) {
                    WitAnime.isWebViewOpen = true
                    WitAnime.lastWebViewOpenTime = now
                    pausePlayer()
                    val real = playing.substringAfter("mega-webview://")
                    CoroutineScope(Dispatchers.Main).launch { openMegaPlayer(real) }
                }
            }
        }

        // تحويل رابط Mega إلى مشغّل embed النظيف بدل صفحة الموقع الكاملة
        private fun megaEmbed(u: String): String {
            Regex("""mega\.(?:nz|io)/(?:file|embed)/([A-Za-z0-9_-]+)#([A-Za-z0-9_-]+)""").find(u)?.let {
                return "https://mega.nz/embed/${it.groupValues[1]}#${it.groupValues[2]}"
            }
            Regex("""mega\.(?:nz|io)/#!([A-Za-z0-9_-]+)!([A-Za-z0-9_-]+)""").find(u)?.let {
                return "https://mega.nz/embed/${it.groupValues[1]}#${it.groupValues[2]}"
            }
            return u
        }

        private suspend fun openMegaPlayer(megaUrl: String) {
            withContext(Dispatchers.Main) {
                if (activeDialog?.isShowing == true) return@withContext
                val fragment = getPlayerFragment() as? androidx.fragment.app.Fragment
                val activity = fragment?.activity ?: getActiveActivity()

                if (activity != null) {
                    try {
                        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                        activeDialog = dialog
                        val originalOrientation = activity.requestedOrientation
                        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        dialog.setOnDismissListener {
                            activity.requestedOrientation = originalOrientation
                            activeDialog = null
                            synchronized(WitAnime::class.java) {
                                WitAnime.isWebViewOpen = false
                                WitAnime.lastWebViewOpenTime = System.currentTimeMillis()
                            }
                            lastHookedPlayer = null
                        }

                        val frame = FrameLayout(activity)
                        frame.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        val webView = WebView(activity)
                        webView.layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        webView.settings.javaScriptEnabled = true
                        webView.settings.domStorageEnabled = true
                        webView.settings.mediaPlaybackRequiresUserGesture = false
                        webView.settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript(
                                    "setTimeout(function(){var v=document.querySelector('video');if(v){v.play();}},2500);",
                                    null
                                )
                            }
                        }
                        webView.webChromeClient = WebChromeClient()
                        webView.loadUrl(megaEmbed(megaUrl))
                        frame.addView(webView)

                        val density = activity.resources.displayMetrics.density
                        val close = android.widget.Button(activity).apply {
                            text = "✕"
                            setTextColor(Color.WHITE)
                            textSize = 14f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.parseColor("#99000000"))
                            }
                            setPadding(0, 0, 0, 0)
                            setOnClickListener { dialog.dismiss() }
                        }
                        val size = (32 * density).toInt()
                        val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                            topMargin = (8 * density).toInt()
                            marginEnd = (8 * density).toInt()
                        }
                        frame.addView(close, lp)
                        dialog.setContentView(frame)
                        dialog.show()
                    } catch (e: Exception) {
                        synchronized(WitAnime::class.java) { WitAnime.isWebViewOpen = false }
                        activeDialog = null
                        try {
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(megaUrl)))
                        } catch (ex: Exception) {
                        }
                    }
                } else {
                    synchronized(WitAnime::class.java) { WitAnime.isWebViewOpen = false }
                    activeDialog = null
                    try {
                        getAppContext()?.let {
                            it.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(megaUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    init {
        PlayerAccess.startMonitoring()
    }

    // ============================= Helpers =============================
    // اجعلها true فقط عند الحاجة لعرض سطور التشخيص دائماً
    private val DEBUG = false

    private val episodeUrlRegex = Regex("""/watch/([^/?#]+)/([^/?#]+)""")
    private val tokenRegex = Regex("^[A-Za-z0-9_-]{16,256}$")
    private val fileHosts = listOf(
        "mediafire.com", "gofile.io", "4shared.com", "mega.nz", "mega.io",
        "drive.google.com", "pixeldrain", "krakenfiles", "streamtape", "dood",
        "voe.sx", "mixdrop", "filemoon", "streamwish", "vidhide", "uqload",
        "ok.ru", "sendvid", "userdrive", "lulustream", "upstream", "vidmoly",
        "yourupload", "mp4upload", "dailymotion", "terabox", "send.cm", "workupload"
    )

    private fun hostOf(u: String): String? = try {
        java.net.URI(u).host?.lowercase()
    } catch (e: Exception) {
        null
    }

    private fun isFileHost(u: String): Boolean {
        val h = hostOf(u) ?: return false
        return fileHosts.any { h.contains(it) }
    }

    private fun normalizeUrl(raw: String, base: String = mainUrl): String? {
        val value = unescape(raw.trim()).trim('"', '\'')
        if (value.isBlank() || value.startsWith("#") || value.startsWith("javascript:", true)) return null
        return try {
            java.net.URI(base).resolve(value).toString()
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
        } catch (e: Exception) {
            null
        }
    }

    private fun unescape(s: String): String = s
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u002F", "/")
        .replace("&amp;", "&")

    private fun imgUrl(img: Element?): String? {
        if (img == null) return null
        val s = img.attr("src").ifBlank { img.attr("data-src") }.ifBlank { img.attr("data-lazy-src") }
        return s.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
    }

    private fun qualityValue(label: String?): Int {
        val l = label?.trim()?.uppercase().orEmpty()
        return when {
            l.isBlank() -> Qualities.Unknown.value
            l == "4K" || l == "UHD" -> 2160
            l == "FHD" -> 1080
            l == "HD" -> 720
            l == "SD" -> 480
            else -> Regex("""\d{3,4}""").find(l)?.value?.toIntOrNull()
                ?.takeIf { it in 144..4320 } ?: Qualities.Unknown.value
        }
    }

    private fun qualityLabel(q: Int): String = when {
        q >= 2160 -> "4K"
        q >= 1080 -> "FHD"
        q >= 720 -> "HD"
        q in 1..719 -> "SD"
        else -> "?"
    }

    // أولوية الترتيب: FHD ثم HD ثم 4K ثم SD ثم غير معروف
    private fun qualityRank(q: Int): Int = when {
        q == 1080 -> 0
        q == 720 -> 1
        q >= 1440 -> 2
        q in 1..719 -> 3
        else -> 4
    }

    private val keyedQualityRegex =
        Regex("""(?i)(?:label|size|res|resolution|quality|height|title|name)["']?\s*[:=]\s*["']?\s*(\d{3,4})""")
    private val keyedWordQualityRegex =
        Regex("""(?i)(?:label|quality|title|name)["']?\s*[:=]\s*["']?\s*(fhd|uhd|4k|hd|sd)(?![a-z])""")

    private fun qualityFromUrl(u: String): Int? {
        Regex("""(?<!\d)(2160|1440|1080|720|576|480|360|240)(?!\d)""").find(u)?.let { return it.value.toInt() }
        val up = u.uppercase()
        return when {
            Regex("""(?<![A-Z])(4K|UHD)(?![A-Z])""").containsMatchIn(up) -> 2160
            Regex("""(?<![A-Z])FHD(?![A-Z])""").containsMatchIn(up) -> 1080
            Regex("""(?<![A-Z])HD(?![A-Z])""").containsMatchIn(up) -> 720
            Regex("""(?<![A-Z])SD(?![A-Z])""").containsMatchIn(up) -> 480
            else -> null
        }
    }

    private fun qualityFromWindow(w: String): Int? {
        keyedQualityRegex.find(w)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 144..4320 }?.let { return it }
        keyedWordQualityRegex.find(w)?.groupValues?.get(1)?.let { return qualityValue(it) }
        return null
    }

    private fun animeUrlFromAny(href: String): String {
        val m = episodeUrlRegex.find(href)
        return if (m != null) "$mainUrl/anime/${m.groupValues[1]}" else fixUrl(href)
    }

    private fun cardToResponse(a: Element, withEpisode: Boolean): SearchResponse? {
        val href = a.attr("href")
        if (href.isBlank()) return null
        val img = a.select("img").firstOrNull { it.attr("alt").isNotBlank() } ?: a.selectFirst("img")
        val title = (a.selectFirst("h3")?.text()?.takeIf { it.isNotBlank() }
            ?: a.selectFirst("span[dir=ltr]")?.text()?.takeIf { it.isNotBlank() }
            ?: img?.attr("alt"))?.trim()
        if (title.isNullOrBlank()) return null
        val poster = imgUrl(img)
        val badge = if (withEpisode && href.contains("/watch/")) {
            a.selectFirst("div.truncate")?.text()?.trim()
        } else null
        val nm = if (!badge.isNullOrBlank()) "$title - $badge" else title
        val type = if (href.contains("/movie/")) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(nm, animeUrlFromAny(href), type) {
            this.posterUrl = poster
        }
    }

    private fun norm(s: String): String = s
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ة', 'ه').replace('ى', 'ي').lowercase()

    private fun cardsIn(container: Element): List<SearchResponse> =
        container.select("a[href*=/watch/], a[href*=/anime/], a[href*=/movie/]")
            .filter { a -> a.parents().none { it.tagName() == "nav" || it.tagName() == "footer" } }
            .mapNotNull { cardToResponse(it, withEpisode = true) }
            .distinctBy { it.url }

    private fun containerFor(h2: Element): Element? {
        var c: Element? = h2.parent()
        var i = 0
        while (c != null && i < 6) {
            if (c.selectFirst("a[href*=/watch/], a[href*=/anime/], a[href*=/movie/]") != null) return c
            c = c.parent()
            i++
        }
        return null
    }

    private fun listingCards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("a[href*=/anime/], a[href*=/movie/]")
            .filter { a -> a.parents().none { it.tagName() == "aside" || it.tagName() == "nav" || it.tagName() == "footer" } }
            .mapNotNull { cardToResponse(it, withEpisode = false) }
            .distinctBy { it.url }

    // ============================= Main page =============================
    override val mainPage = mainPageOf(
        "home" to "الرئيسية",
        "$mainUrl/movies" to "كل الأفلام",
        "$mainUrl/seasonal" to "كل أنميات الموسم",
        "$mainUrl/browse" to "قائمة الأنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "home") return getHome(page)
        val doc = app.get("${request.data}?page=$page", interceptor = cfKiller).document
        val items = listingCards(doc)
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    private suspend fun getHome(page: Int): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList<HomePageList>(), false)

        val document = app.get(mainUrl, interceptor = cfKiller).document
        var episodes: List<SearchResponse>? = null
        var movies: List<SearchResponse>? = null
        var season: List<SearchResponse>? = null
        val others = ArrayList<HomePageList>()

        // الشرائح المميزة في أعلى الصفحة (hero carousel)
        val hero = document.select("[data-hero-slide]").mapNotNull { sl ->
            val a = sl.selectFirst("h2 a") ?: return@mapNotNull null
            val href = a.attr("href")
            val t = a.text().trim()
            if (href.isBlank() || t.isBlank()) return@mapNotNull null
            newAnimeSearchResponse(t, fixUrl(href), TvType.Anime) {
                this.posterUrl = imgUrl(sl.selectFirst("img"))
            }
        }.distinctBy { it.url }

        for (h2 in document.select("h2")) {
            if (h2.parents().any { it.attr("data-test") == "hero-carousel" }) continue
            val title = h2.text().trim()
            if (title.isBlank()) continue
            val container = containerFor(h2) ?: continue
            val items = cardsIn(container)
            if (items.isEmpty()) continue
            val t = norm(title)
            when {
                t.contains("احدث") && t.contains("حلقات") && episodes == null -> episodes = items
                t.contains("احدث") && t.contains("افلام") && movies == null -> movies = items
                t.contains("اشهر") && t.contains("الموسم") && season == null -> season = items
                else -> others.add(HomePageList(title, items))
            }
        }
        if (movies == null) {
            movies = try {
                listingCards(app.get("$mainUrl/movies", interceptor = cfKiller).document).take(30)
            } catch (e: Exception) {
                null
            }
        }
        if (season == null) {
            season = try {
                listingCards(app.get("$mainUrl/seasonal", interceptor = cfKiller).document).take(30)
            } catch (e: Exception) {
                null
            }
        }

        val result = ArrayList<HomePageList>()
        if (hero.isNotEmpty()) result.add(HomePageList("المميز", hero))
        episodes?.takeIf { it.isNotEmpty() }?.let { result.add(HomePageList("أحدث الحلقات", it)) }
        movies?.takeIf { it.isNotEmpty() }?.let { result.add(HomePageList("أحدث الأفلام", it)) }
        season?.takeIf { it.isNotEmpty() }?.let { result.add(HomePageList("أشهر أنميات الموسم", it)) }
        result.addAll(others)
        return newHomePageResponse(result, false)
    }

    // ============================= Search =============================
    private fun collectSuggest(node: Any?, out: MutableList<SearchResponse>) {
        when (node) {
            is JSONObject -> {
                val u = node.optString("url")
                val t = node.optString("title")
                if (u.startsWith("http") && t.isNotBlank()) {
                    val type = if (u.contains("/movie/")) TvType.AnimeMovie else TvType.Anime
                    out.add(newAnimeSearchResponse(t, u, type) {
                        this.posterUrl = node.optString("poster").takeIf { it.isNotBlank() }
                    })
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val v = node.opt(keys.next())
                    if (v is JSONObject || v is JSONArray) collectSuggest(v, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collectSuggest(node.opt(i), out)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val fromPage = try {
            listingCards(
                app.get("$mainUrl/search?q=$q", headers = mapOf("User-Agent" to userAgent), interceptor = cfKiller).document
            )
        } catch (e: Exception) {
            emptyList()
        }
        if (fromPage.isNotEmpty()) return fromPage
        return try {
            val res = app.get(
                "$mainUrl/search/suggest?q=$q",
                headers = mapOf("Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest"),
                interceptor = cfKiller
            ).text
            val out = ArrayList<SearchResponse>()
            val root: Any = if (res.trimStart().startsWith("[")) JSONArray(res) else JSONObject(res)
            collectSuggest(root, out)
            out.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============================= Load =============================
    override suspend fun load(url: String): LoadResponse {
        var pageUrl = url
        var res = app.get(pageUrl, interceptor = cfKiller)
        if (res.code == 404 && pageUrl.contains("/anime/")) {
            val alt = pageUrl.replace("/anime/", "/movie/")
            val r2 = try { app.get(alt, interceptor = cfKiller) } catch (e: Exception) { null }
            if (r2 != null && r2.code in 200..299) {
                pageUrl = alt
                res = r2
            }
        }
        return loadPage(pageUrl, res.document)
    }

    private suspend fun loadPage(url: String, document: org.jsoup.nodes.Document): LoadResponse {
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val info = document.selectFirst("h1")?.parent()
        val poster = imgUrl(document.selectFirst("div.shrink-0 img"))
        val description = info?.selectFirst("p.leading-relaxed")?.text()?.trim()
        val genres = document.select("span.rounded-full")
            .filter { it.className().contains("bg-white/10") }
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val statusText = info?.selectFirst("span.capitalize")?.text().orEmpty()
        val typeText = info?.selectFirst("span.font-bold")?.text().orEmpty()
        val completed = statusText.contains("مكتمل") || statusText.contains("منتهي")
        val tvType = if (typeText.contains("movie", ignoreCase = true) || typeText.contains("فيلم")) {
            TvType.AnimeMovie
        } else TvType.Anime

        fun detail(label: String): String? =
            document.select("span.text-neutral-400")
                .firstOrNull { it.text().trim() == label }
                ?.parent()?.selectFirst("p")?.text()?.trim()

        val year = detail("السنة")?.toIntOrNull()
        val slug = url.trimEnd('/').substringAfterLast("/")
        val isMovieUrl = url.contains("/movie", ignoreCase = true)
        val isMovie = tvType == TvType.AnimeMovie || isMovieUrl

        fun parseEpisodes(links: List<Element>): List<Episode> = links.mapNotNull { a ->
            val href = a.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val num = href.trimEnd('/').substringAfterLast("/").toIntOrNull()
            val label = a.selectFirst("span.text-sm")?.text()?.trim()
            newEpisode(fixUrl(href)) {
                this.name = label?.takeIf { it.isNotBlank() } ?: num?.let { "الحلقة $it" } ?: "الحلقة"
                this.episode = num
                this.posterUrl = imgUrl(a.selectFirst("img"))
            }
        }

        var episodes = parseEpisodes(
            document.select("div[data-episode-list]").firstOrNull()?.select("a[href*=/watch/]")?.toList().orEmpty()
        )
        if (episodes.isEmpty()) {
            episodes = parseEpisodes(
                document.select("a[href*=/watch/]").filter { it.attr("href").contains("/$slug") }
            )
        }
        if (episodes.isEmpty() && isMovie) {
            val probes = listOf("$mainUrl/watch/$slug/1", "$mainUrl/watch/$slug", "$mainUrl/movie/$slug/watch")
            for (p in probes) {
                val ok = try {
                    val r = app.get(p, interceptor = cfKiller)
                    r.code in 200..299 && (r.text.contains("sourcesUrl") || r.text.contains("csrf-token"))
                } catch (e: Exception) {
                    false
                }
                if (ok) {
                    episodes = listOf(newEpisode(p) { this.name = title; this.episode = 1 })
                    break
                }
            }
        }
        if (episodes.isEmpty() && isMovie) {
            episodes = listOf(newEpisode(url) { this.name = title; this.episode = 1 })
        }
        episodes = episodes.distinctBy { it.data }.sortedBy { it.episode ?: Int.MAX_VALUE }

        if (isMovie && episodes.size <= 1 && episodes.isNotEmpty()) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, episodes.first().data) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                this.year = year
            }
        }

        return newAnimeLoadResponse(title, url, if (isMovie) TvType.AnimeMovie else tvType) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.year = year
            this.showStatus = if (completed) ShowStatus.Completed else ShowStatus.Ongoing
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ============================= Sources =============================
    private val iframeHeaders = mapOf(
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin"
    )

    private data class SrcEntry(
        val quality: String,
        val token: String?,
        val direct: String?,
        val label: String?,
        val version: String?,
        val download: Boolean = false
    )

    private fun isQualityKey(k: String): Boolean {
        val u = k.uppercase()
        return u in setOf("SD", "HD", "FHD", "UHD", "4K") || Regex("""\d{3,4}P?""").matches(u)
    }

    private fun collectEntries(node: Any?, qualityKey: String, out: MutableList<SrcEntry>) {
        when (node) {
            is JSONObject -> {
                val token = listOf("token", "sourceToken", "source_token", "key")
                    .map { node.optString(it) }
                    .firstOrNull { tokenRegex.matches(it) }

                val direct = listOf("url", "link", "href", "src", "file", "download")
                    .map { node.optString(it) }
                    .firstOrNull {
                        val u = it.trim()
                        u.startsWith("http", true) || u.startsWith("//") || u.startsWith("/")
                    }

                if (token != null || direct != null) {
                    out.add(
                        SrcEntry(
                            quality = node.optString("quality")
                                .ifBlank { node.optString("resolution") }
                                .ifBlank { qualityKey },
                            token = token,
                            direct = direct,
                            label = node.optString("label").takeIf { it.isNotBlank() }
                                ?: node.optString("name").takeIf { it.isNotBlank() }
                                ?: node.optString("server").takeIf { it.isNotBlank() },
                            version = node.optString("version").takeIf { it.isNotBlank() }
                        )
                    )
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (v is JSONObject || v is JSONArray) {
                        collectEntries(v, if (isQualityKey(k)) k else qualityKey, out)
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collectEntries(node.opt(i), qualityKey, out)
        }
    }

    // جلسة الصفحة: الـ CSRF + الكوكيز المتراكمة
    private class Session(
        val html: String,
        val document: org.jsoup.nodes.Document,
        val csrf: String,
        val cookies: MutableMap<String, String>
    ) {
        val xsrf: String?
            get() = cookies["XSRF-TOKEN"]?.let {
                try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
            }
    }

    // كاش للجلسات كي لا نُغرق الموقع بالطلبات (يتجنب خطأ 429)
    private val sessionCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Session>>()

    private fun retryAfterMs(header: String?, attempt: Int): Long =
        ((header?.trim()?.toLongOrNull()?.times(1000)) ?: (1500L * (attempt + 1))).coerceIn(800L, 8000L)

    private suspend fun openSession(url: String, force: Boolean = false): Session {
        val now = System.currentTimeMillis()
        if (!force) {
            sessionCache[url]?.let { (t, sess) -> if (now - t < 90_000L) return sess }
        }
        for (i in 0..2) {
            val page = app.get(url, interceptor = cfKiller)
            if (page.code == 429) {
                delay(retryAfterMs(page.headers["Retry-After"], i))
                continue
            }
            val html = page.text
            val doc = page.document
            val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: Regex("""(?i)csrf[-_]?token["']?\s*[:=,]\s*["']([A-Za-z0-9]{20,})["']""").find(html)?.groupValues?.get(1)
                ?: ""
            val sess = Session(html, doc, csrf, page.cookies.toMutableMap())
            sessionCache[url] = System.currentTimeMillis() to sess
            return sess
        }
        throw ErrorLoadingException("429")
    }

    // اكتشاف مسارات الموقع (بوابات التحميل ...) من ملفات الـ JS الخاصة به
    @Volatile private var cachedEndpoints: List<String>? = null

    private suspend fun discoverEndpoints(doc: org.jsoup.nodes.Document): List<String> {
        cachedEndpoints?.let { return it }
        val found = LinkedHashSet<String>()
        val rx = Regex("""["'`](/?(?:watch/)?[A-Za-z0-9_\-]*(?:gate|source|download|dl)[A-Za-z0-9_\-/${'$'}{}.]*)["'`]""", RegexOption.IGNORE_CASE)
        val scripts = doc.select("script[src]").map { it.attr("src") }
            .filter { it.contains("/build/assets/") && it.endsWith(".js") }.take(3)
        for (src in scripts) {
            val url = normalizeUrl(src) ?: continue
            val js = try { app.get(url, interceptor = cfKiller).text } catch (e: Exception) { "" }
            rx.findAll(js).forEach { found.add(it.groupValues[1]) }
        }
        val list = found.filter { it.contains("/") && it.length in 5..80 }.take(30)
        cachedEndpoints = list
        return list
    }

    private fun endpointBase(candidate: String?): String? {
        if (candidate == null) return null
        var b = candidate.substringBefore("$").substringBefore("{").trim()
        if (!b.startsWith("/")) b = "/$b"
        if (!b.endsWith("/")) b += "/"
        return b
    }

    private data class SourcesResult(val session: Session, val code: Int, val text: String)

    // يعيد المحاولة بتمهّل: 429 => ينتظر Retry-After، 419 => جلسة جديدة
    private suspend fun fetchSources(data: String, first: Session): SourcesResult {
        var session = first
        var code = -1
        var text = ""
        val sourcesPath = Regex("""sourcesUrl\s*[:=]\s*['"]([^'"]+)['"]""")
            .find(first.html)?.groupValues?.get(1)
            ?.replace("\\/","/")
            ?: (java.net.URI(data).path.trimEnd('/') + "/sources")
        val sourcesUrl = if (sourcesPath.startsWith("http")) sourcesPath else mainUrl + sourcesPath

        for (attempt in 0..3) {
            if (attempt > 0) {
                if (code == 429) {
                    delay(retryAfterMs(null, attempt))
                } else {
                    delay(700L * attempt)
                    session = try { openSession(data, force = true) } catch (e: Exception) { session }
                }
            }
            val token = session.csrf.ifBlank { session.xsrf.orEmpty() }
            val headers = mutableMapOf(
                "Accept" to "application/json",
                "X-CSRF-TOKEN" to token,
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl,
                "Referer" to data
            )
            session.xsrf?.let { headers["X-XSRF-TOKEN"] = it }

            try {
                val res = when (attempt) {
                    1 -> app.post(
                        sourcesUrl, headers = headers, cookies = session.cookies,
                        data = mapOf("_token" to token), interceptor = cfKiller
                    )
                    2 -> app.post(sourcesUrl, headers = headers, cookies = session.cookies)
                    else -> app.post(sourcesUrl, headers = headers, cookies = session.cookies, interceptor = cfKiller)
                }
                code = res.code
                text = res.text
                session.cookies.putAll(res.cookies)
                if (code in 200..299) break
            } catch (e: Exception) {
                logError(e)
            }
        }
        return SourcesResult(session, code, text)
    }

    // ============================= Extractors =============================
    private fun collectUrlCandidates(
        node: Any?,
        out: MutableSet<String>,
        base: String = mainUrl
    ) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    when (value) {
                        is JSONObject, is JSONArray -> collectUrlCandidates(value, out, base)
                        is String -> {
                            val k = key.lowercase()
                            if (k in setOf("url", "link", "href", "src", "file", "download", "stream", "video")) {
                                normalizeUrl(value, base)?.let(out::add)
                            }
                        }
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectUrlCandidates(node.opt(i), out, base)
            }
        }
    }

    private suspend fun extractMedia(
        body: String,
        referer: String,
        name: String,
        defaultQuality: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false
        val seen = HashSet<String>()
        val mediaRegex = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mkv|webm)(?:\?[^\s"'<>\\]*)?""")
        for (m in mediaRegex.findAll(body)) {
            val media = m.value
            if (!seen.add(media)) continue
            if (media.contains("a-ads.com")) continue

            val after = body.substring(m.range.last + 1, minOf(body.length, m.range.last + 1 + 90))
                .substringBefore("http")
            val quality = qualityFromUrl(media) ?: qualityFromWindow(after) ?: defaultQuality
            val isM3u8 = media.contains(".m3u8")

            if (isM3u8) {
                val variants = try {
                    M3u8Helper.generateM3u8(name, media, referer)
                } catch (e: Exception) {
                    emptyList()
                }
                if (variants.isNotEmpty()) {
                    variants.forEach(callback)
                    any = true
                    continue
                }
            }
            callback(
                newExtractorLink(source = name, name = name, url = media) {
                    this.referer = referer
                    this.quality = quality
                    this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
            any = true
        }
        return any
    }

    private suspend fun resolveMediaFire(
        link: String, name: String, quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = unescape(app.get(link, headers = mapOf("User-Agent" to userAgent)).text)
        val direct =
            Regex("""id=["']downloadButton["'][^>]*?href=["'](https?://[^"']+)""").find(html)?.groupValues?.get(1)
                ?: Regex("""href=["'](https?://download\d*\.mediafire\.com/[^"']+)""").find(html)?.groupValues?.get(1)
                ?: Regex("""data-scrambled-url=["']([^"']+)""").find(html)?.groupValues?.get(1)?.let {
                    try { String(Base64.decode(it, Base64.DEFAULT)) } catch (e: Exception) { null }
                }
                ?: Regex("""https?://download\d*\.mediafire\.com/[^"'\s<>]+""").find(html)?.value
            ?: return false
        callback(
            newExtractorLink(source = name, name = "$name • MediaFire", url = direct) {
                this.referer = "https://www.mediafire.com/"
                this.quality = qualityFromUrl(direct) ?: quality
                this.type = ExtractorLinkType.VIDEO
            }
        )
        return true
    }

    private suspend fun resolveGofile(
        link: String, name: String, quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""(?:https?://)?(?:www\.)?gofile\.io/(?:d|embed|contents)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE).find(link)?.groupValues?.get(1)
            ?: return false
        val token = JSONObject(app.post("https://api.gofile.io/accounts").text)
            .optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() } ?: return false
        val res = app.get(
            "https://api.gofile.io/contents/$id?wt=4fd6sg89d7s6&cache=true",
            headers = mapOf("Authorization" to "Bearer $token")
        ).text
        val root = try { JSONObject(res) } catch (e: Exception) { return false }
        val data = root.optJSONObject("data") ?: return false
        val children = data.optJSONObject("children") ?: return false
        var any = false
        val keys = children.keys()
        while (keys.hasNext()) {
            val f = children.optJSONObject(keys.next()) ?: continue
            if (f.optString("type") != "file") continue
            if (!f.optString("mimetype").startsWith("video")) continue
            val dl = f.optString("link").takeIf { it.startsWith("http") } ?: continue
            val fileName = f.optString("name")
            callback(
                newExtractorLink(source = name, name = "$name • Gofile", url = dl) {
                    this.referer = "https://gofile.io/"
                    this.quality = qualityFromUrl(fileName) ?: quality
                    this.type = ExtractorLinkType.VIDEO
                    this.headers = mapOf("Cookie" to "accountToken=$token")
                }
            )
            any = true
        }
        return any
    }

    private suspend fun resolveFourShared(
        link: String, name: String, quality: Int, prefetched: String?, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = "https://www.4shared.com/"
        val hdr = mapOf("User-Agent" to userAgent, "Referer" to ref)
        val b = prefetched ?: unescape(app.get(link, headers = hdr).text)

        var any = false
        val og = Regex("""property=["']og:video(?::url|:secure_url)?["']\s+content=["']([^"']+)""")
            .findAll(b).map { it.groupValues[1] }.toList()
        if (extractMedia(b, ref, "$name • 4shared", quality, callback)) any = true
        og.filter { it.startsWith("http") && !it.contains(".mp4") && !it.contains(".m3u8") }.forEach { u ->
            callback(
                newExtractorLink(source = name, name = "$name • 4shared", url = u) {
                    this.referer = ref
                    this.quality = quality
                    this.type = ExtractorLinkType.VIDEO
                }
            )
            any = true
        }
        if (any) return true

        val id = Regex("""4shared\.com/(?:web/embed/file|video|file|embed|s)/([A-Za-z0-9_\-]+)""")
            .find(link)?.groupValues?.get(1)
        if (id != null) {
            val embed = "https://www.4shared.com/web/embed/file/$id"
            if (embed != link) {
                val eb = try { unescape(app.get(embed, headers = hdr).text) } catch (e: Exception) { "" }
                if (extractMedia(eb, ref, "$name • 4shared", quality, callback)) return true
            }
        }
        return false
    }

    private suspend fun resolveWorkupload(
        link: String, name: String, quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""(?:https?://)?(?:www\.)?workupload\.com/(?:file|start)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE).find(link)?.groupValues?.get(1)
            ?: return false
        val first = app.get("https://workupload.com/file/$id", headers = mapOf("User-Agent" to userAgent))
        val api = app.get(
            "https://workupload.com/api/file/getDownloadServer/$id",
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://workupload.com/file/$id",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            cookies = first.cookies
        ).text
        val direct = try {
            val root = JSONObject(api)
            val data = root.optJSONObject("data")
            listOf(
                data?.optString("url"),
                data?.optString("downloadUrl"),
                root.optString("url"),
                root.optString("downloadUrl")
            ).firstOrNull { it?.startsWith("http", true) == true }
        } catch (e: Exception) {
            null
        }?.takeIf { it.startsWith("http", true) } ?: return false
        callback(
            newExtractorLink(source = name, name = "$name • Workupload", url = direct) {
                this.referer = "https://workupload.com/"
                this.quality = quality
                this.type = ExtractorLinkType.VIDEO
                this.headers = first.cookies.takeIf { it.isNotEmpty() }
                    ?.let { mapOf("Cookie" to it.entries.joinToString("; ") { c -> "${c.key}=${c.value}" }) }
                    ?: emptyMap()
            }
        )
        return true
    }

    private suspend fun scanGenericHost(
        link: String,
        prefetched: String?,
        referer: String,
        name: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ): Boolean {
        val raw = prefetched ?: unescape(
            app.get(link, referer = referer, headers = mapOf("User-Agent" to userAgent)).text
        )
        var body = raw
        if (raw.contains("eval(function(p,a,c,k,e,")) {
            try {
                JsUnpacker(raw).unpack()?.let { body += "\n" + unescape(it) }
            } catch (e: Exception) {
            }
        }
        if (extractMedia(body, link, name, quality, callback)) return true
        if (depth >= 2) return false

        val next = LinkedHashSet<String>()
        Regex("""go_to_player\(['"]([^'"]+)""").findAll(body).forEach { next.add(it.groupValues[1]) }
        Regex("""(?i)<iframe[^>]+(?:src|data-src)=["']([^"']+)""").findAll(body).forEach { next.add(it.groupValues[1]) }
        Regex("""(?:data-url|data-link|data-video|data-src)=["']([^"']+)""").findAll(body).forEach { next.add(it.groupValues[1]) }
        Regex("""atob\(['"]([A-Za-z0-9+/=]{16,})['"]\)""").findAll(body).forEach {
            try {
                next.add(String(Base64.decode(it.groupValues[1], Base64.DEFAULT)))
            } catch (e: Exception) {
            }
        }

        var any = false
        for (n in next) {
            val u = normalizeUrl(n.trim()) ?: continue
            if (u == link || u.contains("a-ads.com") || !u.startsWith("http")) continue
            if (resolveExternal(u, link, name, quality, subtitleCallback, callback, null, depth + 1)) any = true
        }
        return any
    }

    private suspend fun resolveExternal(
        link: String,
        referer: String,
        name: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback0: (ExtractorLink) -> Unit,
        prefetched: String? = null,
        depth: Int = 0
    ): Boolean {
        val host = hostOf(link) ?: return false
        val callback: (ExtractorLink) -> Unit = { l ->
            if (l.quality == Qualities.Unknown.value && quality != Qualities.Unknown.value) l.quality = quality
            callback0(l)
        }

        if (host.contains("mega.nz") || host.contains("mega.io")) {
            callback(
                newExtractorLink(source = name, name = name, url = "mega-webview://$link") {
                    this.quality = quality
                    this.type = ExtractorLinkType.VIDEO
                }
            )
            return true
        }

        val viaLib = try {
            loadExtractor(link, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            false
        }
        if (viaLib) return true

        return try {
            when {
                host.contains("mediafire.com") -> resolveMediaFire(link, name, quality, callback)
                host.contains("gofile.io") -> resolveGofile(link, name, quality, callback)
                host.contains("4shared.com") -> resolveFourShared(link, name, quality, prefetched, callback)
                host.contains("workupload.com") -> resolveWorkupload(link, name, quality, callback)
                else -> scanGenericHost(link, prefetched, referer, name, quality, subtitleCallback, callback, depth)
            }
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    private suspend fun scanUrl(
        url: String,
        referer: String,
        name: String,
        quality: Int,
        cookies: Map<String, String>,
        depth: Int,
        debug: MutableList<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(
            url,
            referer = referer,
            cookies = cookies,
            headers = iframeHeaders,
            interceptor = cfKiller
        )
        val body = unescape(res.text)
        val gateHost = hostOf(url)
        val finalHost = hostOf(res.url)
        var handled = false

        if (finalHost != null && finalHost != gateHost) {
            handled = resolveExternal(res.url, referer, name, quality, subtitleCallback, callback, body)
            synchronized(debug) {
                debug.add("host:$finalHost:${if (handled) "ok" else "fail"}")
                if (!handled && debug.none { it.startsWith("page:") }) {
                    debug.add("page:${res.url} len=${body.length} " + body.take(500).replace(Regex("\\s+"), " "))
                }
            }
            if (handled) return true
        } else {
            synchronized(debug) {
                debug.add("host:internal")
                if (debug.count { it.startsWith("ipage:") } < 3) {
                    debug.add("ipage:${res.code} ${res.url} len=${body.length} " + body.take(350).replace(Regex("\\s+"), " "))
                }
            }
        }

        if (extractMedia(body, url, name, quality, callback)) handled = true

        val found = LinkedHashSet<String>()
        res.document.select("iframe").forEach { f ->
            val s = f.attr("src").ifBlank { f.attr("data-src") }
            if (s.isNotBlank()) found.add(s)
        }
        res.document.select("a[href]").forEach { a ->
            normalizeUrl(a.attr("href"), res.url)?.let { if (isFileHost(it)) found.add(it) }
        }
        res.document.select("[data-url], [data-link]").forEach { e ->
            val s = e.attr("data-url").ifBlank { e.attr("data-link") }
            normalizeUrl(s, res.url)?.let { if (isFileHost(it)) found.add(it) }
        }
        Regex("""(?i)url\s*=\s*([^"'>\s;]+)""")
            .find(res.document.select("meta[http-equiv=refresh]").attr("content"))
            ?.groupValues?.get(1)?.let { found.add(it) }
        Regex("""location(?:\.href|\.replace|\.assign)?\s*(?:=|\()\s*['"]([^'"]+)['"]""")
            .findAll(body)
            .forEach { found.add(it.groupValues[1]) }

        for (raw in found) {
            val link = normalizeUrl(raw, res.url) ?: continue
            if (link.contains("a-ads.com") || link == url) continue
            if (hostOf(link) == gateHost) {
                if (depth < 2 && scanUrl(link, url, name, quality, cookies, depth + 1, debug, subtitleCallback, callback)) {
                    handled = true
                }
            } else if (resolveExternal(link, url, name, quality, subtitleCallback, callback)) {
                handled = true
            }
        }
        return handled
    }

    // ============================= loadLinks =============================
    private fun hintFor(code: Int): String? = when (code) {
        429 -> "⚠️ الموقع يحدّ الطلبات الآن (429). انتظر دقيقة ثم أعد المحاولة"
        419 -> "⚠️ الموقع يرفض الجلسة (419) — غالباً عطل مؤقت من الموقع، حاول لاحقاً"
        403 -> "⚠️ الموقع حجب الطلب (403) — افتح الموقع مرة من المتصفح ثم أعد المحاولة"
        in 500..599 -> "⚠️ خطأ في خادم الموقع ($code) — حاول لاحقاً"
        else -> null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val firstSession = try {
            openSession(data)
        } catch (e: Exception) {
            logError(e)
            logError(ErrorLoadingException(hintFor(429) ?: "تعذّر فتح الصفحة"))
            return false
        }
        val sr = fetchSources(data, firstSession)
        val session = sr.session
        val html = session.html
        val doc = session.document
        val csrf = session.csrf.ifBlank { session.xsrf.orEmpty() }
        val cookies: Map<String, String> = session.cookies.toMap()

        val ajaxHeaders = mutableMapOf(
            "Accept" to "application/json",
            "X-CSRF-TOKEN" to csrf,
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to mainUrl,
            "Referer" to data
        )
        session.xsrf?.let { ajaxHeaders["X-XSRF-TOKEN"] = it }

        val sourcesCode = sr.code
        val sourcesText = sr.text
        val entries = mutableListOf<SrcEntry>()
        try {
            val root = JSONObject(sourcesText)
            val dlList = mutableListOf<SrcEntry>()
            val players = root.optJSONObject("players")
            if (players != null) collectEntries(players, "", entries) else collectEntries(root, "", entries)
            root.optJSONObject("downloads")?.let { collectEntries(it, "", dlList) }
            dlList.forEach { entries.add(it.copy(download = true)) }
        } catch (e: Exception) {
            logError(e)
        }

        // مسارات بوابات التحميل (تُكتشف من ملفات JS الموقع)
        val endpoints = try { discoverEndpoints(doc) } catch (e: Exception) { emptyList() }
        fun pickDl(vararg keys: String): String? = endpoints.firstOrNull { e ->
            val l = e.lowercase()
            (l.contains("download") || l.contains("/dl")) && keys.any { l.contains(it) }
        }
        val dlSourceBase = endpointBase(pickDl("source")) ?: "/watch/download-source/"
        val dlGateBase = endpointBase(pickDl("gate")) ?: "/watch/download-gate/"

        val hostLabels = listOf(
            "gofile", "workupload", "mediafire", "mega", "4shared", "pixeldrain",
            "krakenfiles", "terabox", "send", "drive", "yonaplay", "hgcloud", "videa"
        )
        val pageLinks = LinkedHashMap<String, Pair<Int, String>>()
        doc.select("a[href]").forEach { a ->
            val link = normalizeUrl(a.attr("href"), data) ?: return@forEach
            val label = a.text().trim()
            val labelHit = label.length in 3..24 && hostLabels.any { label.lowercase().contains(it) }
            if (!isFileHost(link) && !labelHit) return@forEach
            val q = qualityFromAncestors(a) ?: qualityFromUrl(link) ?: Qualities.Unknown.value
            val shown = label.takeIf { labelHit } ?: hostOf(link)?.removePrefix("www.") ?: "Download"
            pageLinks.putIfAbsent(link, q to shown)
        }

        // روابط التحميل المخبأة داخل الـ HTML أو الـ JSON (gofile / mediafire / workupload ...)
        val dlHostRx = Regex("""https?://(?:www\.)?(?:gofile\.io|mediafire\.com|workupload\.com|4shared\.com|pixeldrain\.com|krakenfiles\.com|send\.cm|terabox\.com)/[^\s"'<>\)]+""")
        for (src in listOf(unescape(html), unescape(sourcesText))) {
            for (m in dlHostRx.findAll(src)) {
                val link = m.value.trimEnd(',', ';', '.')
                if (pageLinks.containsKey(link)) continue
                val ctx = src.substring(maxOf(0, m.range.first - 160), m.range.first)
                val q = qualityFromWindow(ctx) ?: qualityFromUrl(link) ?: Qualities.Unknown.value
                val shown = hostOf(link)?.removePrefix("www.")?.substringBefore('.') ?: "Download"
                pageLinks.putIfAbsent(link, q to shown)
            }
        }

        // FHD ثم HD أولاً
        val sortedEntries = entries.withIndex()
            .sortedWith(compareBy({ qualityRank(qualityValue(it.value.quality)) }, { it.index }))
        val sortedPageLinks = pageLinks.entries.sortedBy { qualityRank(it.value.first) }

        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val emitted = java.util.concurrent.atomic.AtomicInteger(0)
        val tracked: (ExtractorLink) -> Unit = { link ->
            if (seen.add(link.url)) {
                emitted.incrementAndGet()
                callback(link)
            }
        }
        val debug = mutableListOf<String>()
        val results = java.util.concurrent.ConcurrentLinkedQueue<String>()
        // تقليل التوازي لتفادي حد الطلبات (429)
        val semaphore = Semaphore(4)

        coroutineScope {
            val jobs = ArrayList<Deferred<Unit>>()

            sortedEntries.forEach { (index, entry) ->
                jobs.add(async(Dispatchers.IO) {
                    semaphore.withPermit {
                        var ok = false
                        val label = (entry.label ?: "Server ${index + 1}").replaceFirstChar { it.uppercase() }
                        val quality = qualityValue(entry.quality)
                        val qLabel = entry.quality.uppercase().ifBlank { qualityLabel(quality) }
                        withTimeoutOrNull(35_000L) {
                            try {
                                val versionLabel = if (entry.version == "dub") "مدبلج" else "مترجم"
                                val nm = if (entry.download) "تحميل • $label • $qLabel" else "$label • $qLabel • $versionLabel"
                                val srcBase = if (entry.download) dlSourceBase else "/watch/stream-source/"
                                val gateBase = if (entry.download) dlGateBase else "/watch/stream-gate/"

                                if (entry.token != null) {
                                    var sourceResolved = false
                                    try {
                                        val ssr = app.post(
                                            "$mainUrl$srcBase${entry.token}",
                                            headers = ajaxHeaders,
                                            cookies = cookies,
                                            interceptor = cfKiller
                                        )
                                        val sourceBody = unescape(ssr.text)

                                        // download-source قد يعيد رابط السيرفر مباشرة أو داخل JSON.
                                        // لا نهمل الرد: بعض السيرفرات (Gofile/Workupload/MediaFire)
                                        // لا تحتاج المرور عبر download-gate بعد استخراج الرابط.
                                        val sourceCandidates = LinkedHashSet<String>()
                                        if (extractMedia(sourceBody, data, nm, quality, tracked)) {
                                            sourceResolved = true
                                        }
                                        try {
                                            val trimmed = sourceBody.trimStart()
                                            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                                                val json: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
                                                collectUrlCandidates(json, sourceCandidates, "$mainUrl$srcBase")
                                            }
                                        } catch (_: Exception) {
                                        }
                                        val absRx = Regex("""https?://[^\s"'<>()\\]+""")
                                        absRx.findAll(sourceBody).forEach { m ->
                                            sourceCandidates.add(m.value.trimEnd(',', ';', '.', '\\'))
                                        }
                                        val keyedRx = Regex(
                                            """(?i)["'](?:url|link|href|src|file|download)["']\s*[:=]\s*["']([^"']+)["']"""
                                        )
                                        keyedRx.findAll(sourceBody).forEach { m ->
                                            normalizeUrl(m.groupValues[1], "$mainUrl$srcBase")?.let(sourceCandidates::add)
                                        }

                                        for (candidate in sourceCandidates) {
                                            val normalized = normalizeUrl(candidate, "$mainUrl$srcBase") ?: continue
                                            if (normalized.contains("/watch/download-source/") ||
                                                normalized.contains("/watch/download-gate/")) continue
                                            if (resolveExternal(
                                                    normalized, data, nm, quality,
                                                    subtitleCallback, tracked
                                                )
                                            ) {
                                                sourceResolved = true
                                                break
                                            }
                                        }

                                        if (DEBUG && label.lowercase() in setOf("gofile", "mediafire", "workupload")) {
                                            synchronized(debug) {
                                                if (debug.count { it.startsWith("src:") } < 6) {
                                                    debug.add(
                                                        "src:$label ${ssr.code} " +
                                                            sourceBody.replace(Regex("\\s+"), " ").take(220)
                                                    )
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        logError(e)
                                    }

                                    ok = sourceResolved || scanUrl(
                                        "$mainUrl$gateBase${entry.token}",
                                        data, nm, quality, cookies, 0, debug, subtitleCallback, tracked
                                    )
                                } else if (entry.direct != null) {
                                    val link = normalizeUrl(entry.direct, data)
                                    if (link != null) ok = resolveExternal(link, data, nm, quality, subtitleCallback, tracked)
                                }
                            } catch (e: Exception) {
                                logError(e)
                            }
                        }
                        results.add("$qLabel/$label ${if (ok) "✓" else "✗"}")
                        Unit
                    }
                })
            }

            sortedPageLinks.forEach { (link, qs) ->
                jobs.add(async(Dispatchers.IO) {
                    semaphore.withPermit {
                        var ok = false
                        val (q, shown) = qs
                        val qLabel = qualityLabel(q)
                        withTimeoutOrNull(35_000L) {
                            try {
                                val nm = "تحميل • $shown • $qLabel"
                                ok = if (hostOf(link) == hostOf(mainUrl)) {
                                    scanUrl(link, data, nm, q, cookies, 0, debug, subtitleCallback, tracked)
                                } else {
                                    resolveExternal(link, data, nm, q, subtitleCallback, tracked)
                                }
                            } catch (e: Exception) {
                                logError(e)
                            }
                        }
                        results.add("DL $qLabel/$shown ${if (ok) "✓" else "✗"}")
                        Unit
                    }
                })
            }

            jobs.awaitAll()
        }

        if (emitted.get() == 0) {
            // نسمح بإعادة فتح الجلسة في المرة القادمة
            sessionCache.remove(data)
            hintFor(sourcesCode)?.let { logError(ErrorLoadingException(it)) }
        }

        return emitted.get() > 0
    }

    private fun qualityFromAncestors(el: Element): Int? {
        val startRx = Regex("""^\s*(4K|UHD|FHD|HD|SD)(?![A-Za-z])""", RegexOption.IGNORE_CASE)
        val anyRx = Regex("""(?<![A-Za-z])(FHD|HD|SD)(?![A-Za-z])""", RegexOption.IGNORE_CASE)
        var cur: Element? = el.parent()
        var i = 0
        while (cur != null && i < 7) {
            val t = cur.text()
            val m = startRx.find(t)
            if (m != null && anyRx.findAll(t).map { it.value.uppercase() }.toSet().size <= 1) {
                return qualityValue(m.groupValues[1])
            }
            cur = cur.parent()
            i++
        }
        return null
    }
}

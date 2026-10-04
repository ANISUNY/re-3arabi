
package com.witanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import android.util.Base64
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.nio.charset.Charset
import org.json.JSONArray
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlin.text.toIntOrNull
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import kotlinx.coroutines.*
import kotlin.text.RegexOption
import android.util.Log
import android.widget.FrameLayout
import java.util.logging.Handler
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Looper
import android.view.Gravity
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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
        private const val DEBOUNCE_DELAY_MS = 10000L // 🌟 مهلة 10 ثوانٍ كاملة لمنع فتح نافذتين في نفس الوقت
    }
    object PlayerAccess {

        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var isMonitoring = false
        private var lastHookedPlayer: Any? = null
        private var activeDialog: Dialog? = null
        private var isLoopStarted = false
        @Volatile var isWebViewOpen = false

        private val monitorRunnable = object : Runnable {
            override fun run() {
                if (!isMonitoring) return
                hookPlayerListener()
                handler.postDelayed(this, 1000L) // فحص دوري كل ثانية واحدة لحقن المستمع
            }
        }

        fun startMonitoring() {
            if (isLoopStarted) {
                android.util.Log.d("WitAnimeScanner", "⏭️ حلقة المراقبة تعمل بالفعل في الذاكرة، تم تجاهل الطلب المكرر.")
                return
            }
            isLoopStarted = true
            isMonitoring = true
            android.util.Log.d("WitAnimeScanner", "🚀 تم بدء تشغيل دالة مراقبة مشغل الفيديو بنجاح لأول مرة كحلقة وحيدة!")
            handler.post(monitorRunnable)
        }
        private fun findFragmentRecursive(fragment: androidx.fragment.app.Fragment, packageName: String): androidx.fragment.app.Fragment? {
            if (fragment.javaClass.name.startsWith(packageName)) return fragment
            try {
                val childFragments = fragment.childFragmentManager.fragments
                for (child in childFragments) {
                    if (child != null) {
                        val found = findFragmentRecursive(child, packageName)
                        if (found != null) return found
                    }
                }
            } catch (e: Exception) {}
            return null
        }
        fun getActiveActivity(): Activity? {
            return try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
                val activitiesField = activityThreadClass.getDeclaredField("mActivities")
                activitiesField.isAccessible = true
                val activities = activitiesField.get(activityThread) as Map<*, *>
                var activeActivity: Activity? = null
                for (activityRecord in activities.values) {
                    if (activityRecord == null) continue
                    val activityRecordClass = activityRecord.javaClass
                    val activityField = activityRecordClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    val act = activityField.get(activityRecord) as? Activity

                    if (act != null && !act.isFinishing && !act.isDestroyed) {
                        activeActivity = act
                        break
                    }
                }
                activeActivity
            } catch (e: Exception) {
                android.util.Log.e("WitAnimeScanner", "❌ فشل استخراج الـ Activity ريفلكتيفلي", e)
                null
            }
        }
        fun getPlayerFragment(): Any? {
            val activity = getActiveActivity()
            if (activity == null) {
                return null
            }
            return try {
                val fragments = (activity as? androidx.fragment.app.FragmentActivity)
                    ?.supportFragmentManager
                    ?.fragments
                if (fragments == null) {
                    return null
                }
                for (f in fragments) {
                    if (f != null) {
                        val found = findFragmentRecursive(f, "com.lagradost.cloudstream3.ui.player")
                        if (found != null) {
                            return found
                        }
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
                val playerField = fragment.javaClass.getDeclaredField("player")
                playerField.isAccessible = true
                playerField.get(fragment)
            } catch (e: Exception) {
                try {
                    val getPlayerMethod = fragment.javaClass.getMethod("getPlayer")
                    getPlayerMethod.invoke(fragment)
                } catch (ex: Exception) {
                    null
                }
            }
        }
        fun getAppContext(): Context? {
            return try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
                currentApplicationMethod.invoke(null) as? Context
            } catch (e: Exception) {
                null
            }
        }
        fun pausePlayer() {
            val player = currentPlayer() ?: return
            try {
                val methods = player.javaClass.methods
                val handleEventMethod = methods.firstOrNull { it.name == "handleEvent" }
                if (handleEventMethod != null) {
                    val parameterTypes = handleEventMethod.parameterTypes
                    if (parameterTypes.isNotEmpty()) {
                        val eventEnumClass = parameterTypes[0]
                        val pauseEnumConstant = eventEnumClass.enumConstants?.firstOrNull {
                            it.toString().contains("Pause", ignoreCase = true)
                        }

                        if (parameterTypes.size == 2) {
                            val sourceEnumClass = parameterTypes[1]
                            val syncEnumConstant = sourceEnumClass.enumConstants?.firstOrNull {
                                it.toString().contains("Sync", ignoreCase = true)
                            } ?: sourceEnumClass.enumConstants?.firstOrNull {
                                it.toString().contains("UI", ignoreCase = true)
                            }
                            handleEventMethod.invoke(player, pauseEnumConstant, syncEnumConstant)
                        } else {
                            handleEventMethod.invoke(player, pauseEnumConstant)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        fun getRealExoPlayer(player: Any): Any? {
            val rootClassName = player.javaClass.name
            if (rootClassName.contains("Player", ignoreCase = true) && !rootClassName.contains("Cache", ignoreCase = true)) {
                try {
                    val methods = player.javaClass.methods
                    if (methods.any { it.name == "addListener" }) {
                        return player
                    }
                } catch (e: Exception) {}
            }

            try {
                val fields = player.javaClass.declaredFields
                for (f in fields) {
                    f.isAccessible = true
                    val value = f.get(player) ?: continue
                    val className = value.javaClass.name

                    if (className.contains("Player", ignoreCase = true) && !className.contains("Cache", ignoreCase = true)) {
                        try {
                            val methods = value.javaClass.methods
                            if (methods.any { it.name == "addListener" }) {
                                return value
                            }
                        } catch (ex: Exception) {}
                    }
                }
            } catch (e: Exception) {}
            return null
        }
        private fun hookPlayerListener() {
            val rawPlayer = currentPlayer() ?: return
            val player = getRealExoPlayer(rawPlayer) ?: return // جلب المشغل الحقيقي من داخل كلاس الحماية
            if (player === lastHookedPlayer) return
            lastHookedPlayer = player

            android.util.Log.d("WitAnimeScanner", "🚀 [مشغل نشط مكتشف!] جاري حقن مستمع الـ ExoPlayer الحقيقي في الذاكرة...")
            try {
                val listenerClass = try {
                    Class.forName("androidx.media3.common.Player\$Listener")
                } catch (e: Exception) {
                    Class.forName("com.google.android.exoplayer2.Player\$Listener")
                }

                val addListenerMethod = player.javaClass.getMethod("addListener", listenerClass)

                val proxyListener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass),
                    object : java.lang.reflect.InvocationHandler {
                        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                            val methodName = method.name
                            if (methodName == "equals") {
                                return proxy === (args?.get(0))
                            }
                            if (methodName == "hashCode") {
                                return System.identityHashCode(proxy)
                            }
                            if (methodName == "toString") {
                                return "ExoPlayerProxyListener"
                            }
                            handler.post {
                                checkMegaPlayback(player)
                            }

                            val returnType = method.returnType
                            if (returnType == Boolean::class.javaPrimitiveType || returnType == Boolean::class.java) {
                                return false
                            }
                            if (returnType?.isPrimitive == true) {
                                return 0
                            }
                            return null
                        }
                    }
                )

                addListenerMethod.invoke(player, proxyListener)
                android.util.Log.d("WitAnimeScanner", "✅ [تم الاختراق بنجاح!] تم حقن مستمع الـ ExoPlayer بنجاح وتفعيل المراقبة المباشرة!")
            } catch (e: Exception) {
                android.util.Log.e("WitAnimeScanner", "❌ فشل حقن مستمع الـ ExoPlayer", e)
            }
        }
        private fun findUrlInObject(obj: Any, depth: Int = 0): String? {
            if (depth > 3) return null
            try {
                val fields = obj.javaClass.declaredFields
                for (f in fields) {
                    f.isAccessible = true
                    val value = f.get(obj) ?: continue
                    if (value is String && value.contains("mega-webview://")) {
                        return value
                    }
                    if (value is Uri && value.toString().contains("mega-webview://")) {
                        return value.toString()
                    }
                    val pkg = value.javaClass.`package`?.name ?: ""
                    if (pkg.contains("lagradost") || pkg.contains("media3") || pkg.contains("exoplayer") || pkg.contains("google")) {
                        val found = findUrlInObject(value, depth + 1)
                        if (found != null) return found
                    }
                }
            } catch (e: Exception) {}
            return null
        }

        private fun getPlayingUrl(player: Any): String? {
            return try {
                val getMediaItemMethod = player.javaClass.getMethod("getCurrentMediaItem")
                val mediaItem = getMediaItemMethod.invoke(player) ?: return null
                val localConfigField = mediaItem.javaClass.getDeclaredField("localConfiguration")
                localConfigField.isAccessible = true
                val localConfig = localConfigField.get(mediaItem) ?: return null
                val uriField = localConfig.javaClass.getDeclaredField("uri")
                uriField.isAccessible = true
                val uri = uriField.get(localConfig) as? Uri
                uri?.toString() ?: findUrlInObject(player)
            } catch (e: Exception) {
                findUrlInObject(player)
            }
        }
        private fun checkMegaPlayback(player: Any) {
            val currentTime = System.currentTimeMillis()
            synchronized(WitAnime::class.java) {
                if (WitAnime.isWebViewOpen || (currentTime - WitAnime.lastWebViewOpenTime) < 10000L || activeDialog?.isShowing == true) {
                    return
                }

                val playingUrl = getPlayingUrl(player) ?: return
                if (playingUrl.contains("mega-webview://")) {
                    WitAnime.isWebViewOpen = true
                    WitAnime.lastWebViewOpenTime = currentTime

                    android.util.Log.d("WitAnimeScanner", "🎯 [هدف مكتشف!] تم اعتراض تشغيل سيرفر Mega المخصص بنجاح: $playingUrl")
                    pausePlayer()

                    val realMegaUrl = playingUrl.substringAfter("mega-webview://")

                    val dispatcher = Dispatchers.Main
                    CoroutineScope(dispatcher).launch {
                        android.util.Log.d("WitAnimeScanner", "🌐 فتح واجهة الـ WebView المخصصة لـ Mega مع زر الخروج: $realMegaUrl")
                        openMegaPlayer(realMegaUrl)
                    }
                }
            }
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

                        val frameLayout = FrameLayout(activity)
                        frameLayout.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        val webView = WebView(activity)
                        webView.layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        webView.settings.javaScriptEnabled = true
                        webView.settings.domStorageEnabled = true
                        webView.settings.mediaPlaybackRequiresUserGesture = false
                        webView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                        webView.webViewClient = WebViewClient()
                        webView.webChromeClient = WebChromeClient()
                        webView.loadUrl(megaUrl)

                        frameLayout.addView(webView)
                        val closeButton = android.widget.Button(activity).apply {
                            text = "✕"
                            setTextColor(Color.WHITE)
                            textSize = 14f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.parseColor("#99000000")) // أسود شفاف بنسبة 60%
                            }
                            setPadding(0, 0, 0, 0)
                            setOnClickListener {
                                dialog.dismiss()
                            }
                        }

                        val btnSize = (32 * activity.resources.displayMetrics.density).toInt() // حجم الزر 32dp فقط
                        val btnParams = FrameLayout.LayoutParams(
                            btnSize,
                            btnSize,
                            Gravity.TOP or Gravity.END // أعلى اليمين
                        ).apply {
                            topMargin = (8 * activity.resources.displayMetrics.density).toInt() // يبعد 8dp فقط من الأعلى
                            marginEnd = (8 * activity.resources.displayMetrics.density).toInt() // يبعد 8dp فقط من اليمين
                        }

                        frameLayout.addView(closeButton, btnParams)
                        dialog.setContentView(frameLayout)
                        dialog.show()

                    } catch (e: Exception) {
                        synchronized(WitAnime::class.java) {
                            WitAnime.isWebViewOpen = false
                        }
                        activeDialog = null
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(megaUrl))
                            activity.startActivity(intent)
                        } catch (ex: Exception) {
                            android.util.Log.e("WitAnime", "Error starting activity", ex)
                        }
                    }
                } else {
                    synchronized(WitAnime::class.java) {
                        WitAnime.isWebViewOpen = false
                    }
                    activeDialog = null
                    try {
                        val context = getAppContext()
                        if (context != null) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(megaUrl))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("WitAnime", "Error starting context", e)
                    }
                }
            }
        }
    }
    init {
        PlayerAccess.startMonitoring()
    }

    // ---------------------------------------------------------------------
    // الموقع الجديد (Laravel + Alpine.js): البطاقات تأتي جاهزة من الخادم
    // ---------------------------------------------------------------------

    private val episodeUrlRegex = Regex("""/watch/([^/?#]+)/([^/?#]+)""")

    private fun hostOf(u: String): String? = try {
        java.net.URI(u).host
    } catch (e: Exception) {
        null
    }

    // رابط الحلقة /watch/slug/N يتحول إلى رابط الأنمي /anime/slug
    private fun animeUrlFromAny(href: String): String {
        val m = episodeUrlRegex.find(href)
        return if (m != null) "$mainUrl/anime/${m.groupValues[1]}" else fixUrl(href)
    }

    private fun cardToResponse(a: Element, withEpisode: Boolean): SearchResponse? {
        val href = a.attr("href")
        if (href.isBlank()) return null
        val img = a.selectFirst("img")
        val title = (a.selectFirst("h3")?.text() ?: img?.attr("alt"))?.trim()
        if (title.isNullOrBlank()) return null
        val poster = img?.attr("src")?.takeIf { it.isNotBlank() }
        val badge = if (withEpisode && href.contains("/watch/")) {
            a.selectFirst("div.truncate")?.text()?.trim()
        } else null
        val name = if (!badge.isNullOrBlank()) "$title - $badge" else title
        return newAnimeSearchResponse(name, animeUrlFromAny(href), TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, interceptor = cfKiller).document
        val homePageList = ArrayList<HomePageList>()

        document.select("div[x-ref=track]").forEach { track ->
            var holder: Element? = track.parent()
            while (holder != null && holder.selectFirst("h2") == null) holder = holder.parent()
            val title = holder?.selectFirst("h2")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@forEach

            val items = track.children()
                .filter { it.tagName() == "a" }
                .mapNotNull { cardToResponse(it, withEpisode = true) }
                .distinctBy { it.url }
            if (items.isNotEmpty()) homePageList.add(HomePageList(title, items))
        }

        if (homePageList.isEmpty()) {
            val items = document.select("a[href*=/anime/], a[href*=/watch/]")
                .mapNotNull { cardToResponse(it, withEpisode = true) }
                .distinctBy { it.url }
            if (items.isNotEmpty()) homePageList.add(HomePageList("WitAnime", items))
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        // عنوان البحث الحقيقي لم يتأكد بعد، فنجرب الاحتمالات بالترتيب
        val candidates = listOf(
            "$mainUrl/search?q=$q",
            "$mainUrl/search?query=$q",
            "$mainUrl/search?keyword=$q",
            "$mainUrl/browse?search=$q",
            "$mainUrl/browse?q=$q"
        )
        for (u in candidates) {
            val results = try {
                app.get(u, headers = mapOf("User-Agent" to userAgent), interceptor = cfKiller)
                    .document
                    .select("a[href*=/anime/]")
                    .mapNotNull { cardToResponse(it, withEpisode = false) }
                    .distinctBy { it.url }
            } catch (e: Exception) {
                emptyList()
            }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = cfKiller).document

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val info = document.selectFirst("h1")?.parent()
        val poster = document.selectFirst("div.shrink-0 img")?.attr("src")?.takeIf { it.isNotBlank() }
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
        } else {
            TvType.Anime
        }

        fun detail(label: String): String? =
            document.select("span.text-neutral-400")
                .firstOrNull { it.text().trim() == label }
                ?.parent()?.selectFirst("p")?.text()?.trim()

        val year = detail("السنة")?.toIntOrNull()

        val episodes = document.select("div[data-episode-list]").firstOrNull()
            ?.select("a[href*=/watch/]")
            ?.mapNotNull { a ->
                val href = a.attr("href")
                if (href.isBlank()) return@mapNotNull null
                val num = href.trimEnd('/').substringAfterLast("/").toIntOrNull()
                val label = a.selectFirst("span.text-sm")?.text()?.trim()
                newEpisode(fixUrl(href)) {
                    this.name = label?.takeIf { it.isNotBlank() } ?: num?.let { "الحلقة $it" } ?: "الحلقة"
                    this.episode = num
                    this.posterUrl = a.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                }
            }.orEmpty()

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.year = year
            this.showStatus = if (completed) ShowStatus.Completed else ShowStatus.Ongoing
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun qualityValue(label: String): Int = when (label.uppercase()) {
        "4K" -> 2160
        "FHD" -> 1080
        "HD" -> 720
        "SD" -> 480
        else -> Qualities.Unknown.value
    }

    private val iframeHeaders = mapOf(
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin"
    )

    // يفحص صفحة البوابة: تحويل إلى سيرفر خارجي، أو iframe، أو رابط ميديا مباشر
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
        val body = res.text.replace("\\/", "/")
        synchronized(debug) {
            if (debug.isEmpty()) {
                debug.add(
                    "gate ${res.code} ${hostOf(res.url)} len=${body.length} " +
                        body.take(160).replace(Regex("\\s+"), " ")
                )
            }
        }

        val gateHost = hostOf(url)
        val finalHost = hostOf(res.url)
        if (finalHost != null && finalHost != gateHost) {
            return loadExtractor(res.url, referer, subtitleCallback, callback)
        }

        var handled = false

        // روابط ميديا مباشرة
        Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""")
            .findAll(body)
            .map { it.value }
            .distinct()
            .forEach { media ->
                val isM3u8 = media.contains(".m3u8")
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = media,
                    ) {
                        this.referer = url
                        this.quality = quality
                        this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                )
                handled = true
            }

        // روابط مضمنة: iframe أو تحويل
        val found = LinkedHashSet<String>()
        res.document.select("iframe").forEach { f ->
            val s = f.attr("src").ifBlank { f.attr("data-src") }
            if (s.isNotBlank()) found.add(s)
        }
        Regex("""(?i)url\s*=\s*([^"'>\s;]+)""")
            .find(res.document.select("meta[http-equiv=refresh]").attr("content"))
            ?.groupValues?.get(1)?.let { found.add(it) }
        Regex("""location(?:\.href|\.replace|\.assign)?\s*(?:=|\()\s*['"]([^'"]+)['"]""")
            .findAll(body)
            .forEach { found.add(it.groupValues[1]) }

        for (raw in found) {
            val link = when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("/") -> mainUrl + raw
                raw.startsWith("http") -> raw
                else -> continue
            }
            if (link.contains("a-ads.com") || link == url) continue
            if (hostOf(link) == gateHost) {
                if (depth < 2) {
                    if (scanUrl(link, url, name, quality, cookies, depth + 1, debug, subtitleCallback, callback)) {
                        handled = true
                    }
                }
            } else if (loadExtractor(link, url, subtitleCallback, callback)) {
                handled = true
            }
        }
        return handled
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = app.get(data, interceptor = cfKiller)
        val html = page.text
        val csrf = page.document.selectFirst("meta[name=csrf-token]")?.attr("content").orEmpty()
        val cookies = page.cookies

        val sourcesPath = Regex("""sourcesUrl:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?: (java.net.URI(data).path.trimEnd('/') + "/sources")
        val sourcesUrl = if (sourcesPath.startsWith("http")) sourcesPath else mainUrl + sourcesPath

        val ajaxHeaders = mapOf(
            "Accept" to "application/json",
            "X-CSRF-TOKEN" to csrf,
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to mainUrl,
            "Referer" to data
        )

        val sourcesRes = app.post(
            sourcesUrl,
            headers = ajaxHeaders,
            cookies = cookies,
            interceptor = cfKiller
        )
        val parsed = tryParseJson<WpResponse>(sourcesRes.text)

        val entries = parsed?.players.orEmpty().flatMap { (quality, list) ->
            list.mapNotNull { e ->
                val token = e.token
                if (token != null && Regex("^[a-f0-9]{64}$").matches(token)) Triple(quality, e, token) else null
            }
        }

        val emitted = java.util.concurrent.atomic.AtomicInteger(0)
        val tracked: (ExtractorLink) -> Unit = {
            emitted.incrementAndGet()
            callback(it)
        }
        val debug = mutableListOf<String>()
        val semaphore = Semaphore(4)

        coroutineScope {
            entries.mapIndexed { index, (quality, entry, token) ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val label = entry.label?.takeIf { it.isNotBlank() } ?: "Server ${index + 1}"
                            val versionLabel = if (entry.version == "dub") "مدبلج" else "مترجم"
                            val name = "$label • $versionLabel"

                            // 1) الموقع يطلب هذه الخطوة قبل فتح البوابة
                            app.post(
                                "$mainUrl/watch/stream-source/$token",
                                headers = ajaxHeaders,
                                cookies = cookies,
                                interceptor = cfKiller
                            )
                            // 2) البوابة هي ما يُحمَّل داخل iframe في الموقع
                            scanUrl(
                                "$mainUrl/watch/stream-gate/$token",
                                data,
                                name,
                                qualityValue(quality),
                                cookies,
                                0,
                                debug,
                                subtitleCallback,
                                tracked
                            )
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }
                }
            }.awaitAll()
        }

        if (emitted.get() == 0) {
            // سطر تشخيصي مؤقت يظهر في قائمة السيرفرات إذا لم يُستخرج أي رابط
            val note = "sources=${sourcesRes.code} n=${entries.size} " + debug.firstOrNull().orEmpty()
            callback(
                newExtractorLink(
                    source = "WitAnime",
                    name = "[تشخيص] $note".take(300),
                    url = "https://example.invalid/",
                ) {
                    this.quality = Qualities.Unknown.value
                    this.type = ExtractorLinkType.VIDEO
                }
            )
        }

        return emitted.get() > 0
    }
}

internal data class WpEntry(
    val token: String? = null,
    val label: String? = null,
    val version: String? = null,
    val lang: String? = null
)

internal data class WpResponse(
    val players: Map<String, List<WpEntry>>? = null
)

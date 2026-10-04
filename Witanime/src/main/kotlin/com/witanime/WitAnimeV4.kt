
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

    // =====================================================================
    //  أدوات مساعدة عامة
    // =====================================================================

    private val DEBUG = false // true = يعرض سطور تشخيص داخل قائمة السيرفرات

    private val episodeUrlRegex = Regex("""/watch/([^/?#]+)/([^/?#]+)""")
    private val tokenRegex = Regex("^[a-f0-9]{64}$")

    // مواقع الملفات/الاستضافة التي نلتقط روابطها من صفحة الحلقة (قسم التحميل)
    private val fileHosts = listOf(
        "mediafire.com", "gofile.io", "4shared.com", "mega.nz", "mega.io",
        "drive.google.com", "pixeldrain", "krakenfiles", "streamtape", "dood",
        "voe.sx", "mixdrop", "filemoon", "streamwish", "vidhide", "uqload",
        "ok.ru", "sendvid", "userdrive", "lulustream", "upstream", "vidmoly",
        "yourupload", "mp4upload", "dailymotion", "krakenfiles", "terabox", "send.cm"
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

    private fun normalizeUrl(raw: String, base: String = mainUrl): String? = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("http") -> raw
        raw.startsWith("/") -> mainUrl + raw
        else -> null
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

    // -------- الجودة --------
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

    // =====================================================================
    //  الصفحة الرئيسية
    // =====================================================================

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
        val poster = imgUrl(img)
        val badge = if (withEpisode && href.contains("/watch/")) {
            a.selectFirst("div.truncate")?.text()?.trim()
        } else null
        val name = if (!badge.isNullOrBlank()) "$title - $badge" else title
        return newAnimeSearchResponse(name, animeUrlFromAny(href), TvType.Anime) {
            this.posterUrl = poster
        }
    }

    private fun norm(s: String): String = s
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ة', 'ه').replace('ى', 'ي').lowercase()

    // 0 = أحدث الحلقات، 1 = أحدث الأفلام، 2 = أشهر انميات الموسم، 3 = غير ذلك
    private fun classify(title: String): Int {
        val t = norm(title)
        return when {
            t.contains("فيلم") || t.contains("افلام") || t.contains("movie") -> 1
            t.contains("حلقات") || t.contains("حلقه") || t.contains("episode") -> 0
            t.contains("اشهر") || t.contains("الاكثر") || t.contains("شعبي") ||
                t.contains("popular") || t.contains("trending") || t.contains("top") ||
                t.contains("الموسم") || t.contains("season") -> 2
            else -> 3
        }
    }

    // يجلب قائمة أنميات من أول رابط يعمل (للصفوف الناقصة في الرئيسية)
    private suspend fun fetchListing(urls: List<String>): List<SearchResponse> {
        for (u in urls) {
            val items = try {
                val r = app.get(u, interceptor = cfKiller)
                if (r.code !in 200..299 || r.url.trimEnd('/') == mainUrl.trimEnd('/')) emptyList()
                else r.document.select("a[href*=/anime/]")
                    .mapNotNull { cardToResponse(it, false) }
                    .distinctBy { it.url }
                    .take(30)
            } catch (e: Exception) {
                emptyList()
            }
            if (items.size >= 3) return items
        }
        return emptyList()
    }

    private val rowTitles = mapOf(
        0 to "أحدث الحلقات",
        1 to "أحدث الأفلام",
        2 to "أشهر انميات الموسم"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList<HomePageList>(), false)

        val document = app.get(mainUrl, interceptor = cfKiller).document
        val classified = LinkedHashMap<Int, List<SearchResponse>>()
        val others = ArrayList<HomePageList>()

        document.select("div[x-ref=track]").forEach { track ->
            var holder: Element? = track.parent()
            while (holder != null && holder.selectFirst("h2") == null) holder = holder.parent()
            val title = holder?.selectFirst("h2")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@forEach

            val items = track.children()
                .filter { it.tagName() == "a" }
                .mapNotNull { cardToResponse(it, withEpisode = true) }
                .distinctBy { it.url }
            if (items.isEmpty()) return@forEach

            val kind = classify(title)
            if (kind in 0..2 && !classified.containsKey(kind)) classified[kind] = items
            else others.add(HomePageList(title, items))
        }

        // احتياط: لا توجد صفوف بهذا الشكل
        if (!classified.containsKey(0)) {
            val eps = document.select("a[href*=/watch/]")
                .mapNotNull { cardToResponse(it, withEpisode = true) }
                .distinctBy { it.url }
            if (eps.isNotEmpty()) classified[0] = eps
        }

        // الأفلام والأشهر: إن لم نجدها في الرئيسية نجلبها من صفحات الموقع (بالتوازي)
        coroutineScope {
            val moviesJob = if (!classified.containsKey(1)) async {
                fetchListing(
                    listOf(
                        "$mainUrl/movies", "$mainUrl/anime?type=movie", "$mainUrl/browse?type=movie",
                        "$mainUrl/anime?format=movie", "$mainUrl/type/movie", "$mainUrl/anime-type/movie"
                    )
                )
            } else null
            val popularJob = if (!classified.containsKey(2)) async {
                fetchListing(
                    listOf(
                        "$mainUrl/popular", "$mainUrl/anime?sort=popular", "$mainUrl/trending",
                        "$mainUrl/season", "$mainUrl/anime?sort=views", "$mainUrl/browse?sort=popular"
                    )
                )
            } else null
            moviesJob?.await()?.takeIf { it.isNotEmpty() }?.let { classified[1] = it }
            popularJob?.await()?.takeIf { it.isNotEmpty() }?.let { classified[2] = it }
        }

        // آخر حل للأشهر: أول صف متبقٍ في الرئيسية
        if (!classified.containsKey(2) && others.isNotEmpty()) {
            classified[2] = others.removeAt(0).list
        }

        val result = ArrayList<HomePageList>()
        for (k in 0..2) classified[k]?.let { result.add(HomePageList(rowTitles.getValue(k), it)) }
        result.addAll(others)
        return newHomePageResponse(result, false)
    }

    // =====================================================================
    //  البحث
    // =====================================================================

    private var workingSearch: Int = -1

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val templates = listOf(
            "$mainUrl/search?q=$q",
            "$mainUrl/search?query=$q",
            "$mainUrl/search?keyword=$q",
            "$mainUrl/browse?search=$q",
            "$mainUrl/browse?q=$q"
        )
        val order = if (workingSearch in templates.indices)
            listOf(workingSearch) + templates.indices.filter { it != workingSearch }
        else templates.indices.toList()

        for (i in order) {
            val results = try {
                app.get(templates[i], headers = mapOf("User-Agent" to userAgent), interceptor = cfKiller)
                    .document
                    .select("a[href*=/anime/]")
                    .mapNotNull { cardToResponse(it, withEpisode = false) }
                    .distinctBy { it.url }
            } catch (e: Exception) {
                emptyList()
            }
            if (results.isNotEmpty()) {
                workingSearch = i
                return results
            }
        }
        return emptyList()
    }

    // =====================================================================
    //  صفحة الأنمي
    // =====================================================================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = cfKiller).document

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
        } else {
            TvType.Anime
        }

        fun detail(label: String): String? =
            document.select("span.text-neutral-400")
                .firstOrNull { it.text().trim() == label }
                ?.parent()?.selectFirst("p")?.text()?.trim()

        val year = detail("السنة")?.toIntOrNull()

        var episodes = document.select("div[data-episode-list]").firstOrNull()
            ?.select("a[href*=/watch/]")
            ?.mapNotNull { a ->
                val href = a.attr("href")
                if (href.isBlank()) return@mapNotNull null
                val num = href.trimEnd('/').substringAfterLast("/").toIntOrNull()
                val label = a.selectFirst("span.text-sm")?.text()?.trim()
                newEpisode(fixUrl(href)) {
                    this.name = label?.takeIf { it.isNotBlank() } ?: num?.let { "الحلقة $it" } ?: "الحلقة"
                    this.episode = num
                    this.posterUrl = imgUrl(a.selectFirst("img"))
                }
            }.orEmpty()
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        // فيلم بدون قائمة حلقات: نفترض أن مشاهدته على /watch/slug/1
        if (episodes.isEmpty() && tvType == TvType.AnimeMovie) {
            val slug = url.substringAfter("/anime/", "").trim('/')
            if (slug.isNotBlank()) {
                episodes = listOf(newEpisode("$mainUrl/watch/$slug/1") {
                    this.name = title
                    this.episode = 1
                })
            }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.year = year
            this.showStatus = if (completed) ShowStatus.Completed else ShowStatus.Ongoing
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // =====================================================================
    //  استخراج الروابط (كل السيرفرات + كل سيرفرات التحميل)
    // =====================================================================

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
        val version: String?
    )

    private fun isQualityKey(k: String): Boolean {
        val u = k.uppercase()
        return u in setOf("SD", "HD", "FHD", "UHD", "4K") || Regex("""\d{3,4}P?""").matches(u)
    }

    // يمشي على JSON بالكامل فيلتقط أي كائن فيه token أو رابط مباشر (players / downloads / ...)
    private fun collectEntries(node: Any?, qualityKey: String, out: MutableList<SrcEntry>) {
        when (node) {
            is JSONObject -> {
                val token = node.optString("token").takeIf { tokenRegex.matches(it) }
                val direct = listOf("url", "link", "href", "src", "file")
                    .map { node.optString(it) }
                    .firstOrNull { it.startsWith("http") || it.startsWith("//") }
                if (token != null || direct != null) {
                    out.add(
                        SrcEntry(
                            quality = node.optString("quality").ifBlank { qualityKey },
                            token = token,
                            direct = direct,
                            label = node.optString("label").takeIf { it.isNotBlank() }
                                ?: node.optString("name").takeIf { it.isNotBlank() },
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

    // ------- ميديا مباشرة داخل نص صفحة -------
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

    // ------- MediaFire -------
    private suspend fun resolveMediaFire(
        link: String, name: String, quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = unescape(app.get(link, headers = mapOf("User-Agent" to userAgent)).text)
        val direct =
            Regex("""id=["']downloadButton["'][^>]*?href=["'](https?://[^"']+)""").find(html)?.groupValues?.get(1)
                ?: Regex("""href=["'](https?://download\d*\.mediafire\.com/[^"']+)""").find(html)?.groupValues?.get(1)
                ?: Regex("""data-scrambled-url=["']([^"']+)""").find(html)?.groupValues?.get(1)?.let {
                    try {
                        String(Base64.decode(it, Base64.DEFAULT))
                    } catch (e: Exception) {
                        null
                    }
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

    // ------- Gofile -------
    private suspend fun resolveGofile(
        link: String, name: String, quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""gofile\.io/(?:d|embed)/([A-Za-z0-9]+)""").find(link)?.groupValues?.get(1)
            ?: return false
        val token = JSONObject(app.post("https://api.gofile.io/accounts").text)
            .optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() } ?: return false
        val res = app.get(
            "https://api.gofile.io/contents/$id?wt=4fd6sg89d7s6&cache=true",
            headers = mapOf("Authorization" to "Bearer $token")
        ).text
        val children = JSONObject(res).optJSONObject("data")?.optJSONObject("children") ?: return false
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

    // ------- 4shared (يدعم SD / HD / FHD إن وُجدت داخل الصفحة) -------
    private suspend fun resolveFourShared(
        link: String, name: String, quality: Int, prefetched: String?, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = "https://www.4shared.com/"
        val hdr = mapOf("User-Agent" to userAgent, "Referer" to ref)
        val bodies = mutableListOf<String>()
        bodies.add(prefetched ?: unescape(app.get(link, headers = hdr).text))

        var any = false
        for (b in bodies) {
            // og:video / source / data-* إضافة إلى أي mp4/m3u8
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
        }
        if (any) return true

        // محاولة صفحة التضمين
        val id = Regex("""4shared\.com/(?:web/embed/file|video|file|embed|s)/([A-Za-z0-9_\-]+)""")
            .find(link)?.groupValues?.get(1)
        if (id != null) {
            val embed = "https://www.4shared.com/web/embed/file/$id"
            if (embed != link) {
                val b = try {
                    unescape(app.get(embed, headers = hdr).text)
                } catch (e: Exception) {
                    ""
                }
                if (extractMedia(b, ref, "$name • 4shared", quality, callback)) return true
            }
        }
        return false
    }

    // يحوّل أي رابط خارجي إلى روابط تشغيل: المستخرجات الجاهزة أولاً ثم معالجاتنا الخاصة
    private suspend fun resolveExternal(
        link: String,
        referer: String,
        name: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        prefetched: String? = null
    ): Boolean {
        val host = hostOf(link) ?: return false

        if (host.contains("mega.nz") || host.contains("mega.io")) {
            callback(
                newExtractorLink(source = name, name = "$name • Mega", url = "mega-webview://$link") {
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
                else -> {
                    val body = prefetched ?: unescape(
                        app.get(link, referer = referer, headers = mapOf("User-Agent" to userAgent)).text
                    )
                    extractMedia(body, link, name, quality, callback)
                }
            }
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    // يفحص بوابة السيرفر: تحويل خارجي، iframe، روابط ميديا، أو روابط تحميل
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
            synchronized(debug) { debug.add("host:internal") }
        }

        if (extractMedia(body, url, name, quality, callback)) handled = true

        val found = LinkedHashSet<String>()
        res.document.select("iframe").forEach { f ->
            val s = f.attr("src").ifBlank { f.attr("data-src") }
            if (s.isNotBlank()) found.add(s)
        }
        res.document.select("a[href]").forEach { a ->
            val h = a.attr("href")
            normalizeUrl(h)?.let { if (isFileHost(it)) found.add(it) }
        }
        res.document.select("[data-url], [data-link]").forEach { e ->
            val s = e.attr("data-url").ifBlank { e.attr("data-link") }
            normalizeUrl(s)?.let { if (isFileHost(it)) found.add(it) }
        }
        Regex("""(?i)url\s*=\s*([^"'>\s;]+)""")
            .find(res.document.select("meta[http-equiv=refresh]").attr("content"))
            ?.groupValues?.get(1)?.let { found.add(it) }
        Regex("""location(?:\.href|\.replace|\.assign)?\s*(?:=|\()\s*['"]([^'"]+)['"]""")
            .findAll(body)
            .forEach { found.add(it.groupValues[1]) }

        for (raw in found) {
            val link = normalizeUrl(raw) ?: continue
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

        var sourcesCode = -1
        val entries = mutableListOf<SrcEntry>()
        try {
            val sourcesRes = app.post(sourcesUrl, headers = ajaxHeaders, cookies = cookies, interceptor = cfKiller)
            sourcesCode = sourcesRes.code
            collectEntries(JSONObject(sourcesRes.text), "", entries)
        } catch (e: Exception) {
            logError(e)
        }

        // روابط التحميل/السيرفرات الظاهرة في HTML الصفحة نفسها (ميديافاير، جوفايل، 4shared ...)
        val pageLinks = LinkedHashMap<String, Int>()
        page.document.select("a[href]").forEach { a ->
            val link = normalizeUrl(a.attr("href")) ?: return@forEach
            if (!isFileHost(link)) return@forEach
            val ctx = a.text() + " " + (a.parent()?.text().orEmpty().take(80))
            pageLinks.putIfAbsent(link, qualityFromUrl(link) ?: qualityFromUrl(ctx) ?: Qualities.Unknown.value)
        }

        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val emitted = java.util.concurrent.atomic.AtomicInteger(0)
        val tracked: (ExtractorLink) -> Unit = { link ->
            if (seen.add(link.url)) {
                emitted.incrementAndGet()
                callback(link)
            }
        }
        val debug = mutableListOf<String>()
        val semaphore = Semaphore(6)

        coroutineScope {
            val jobs = ArrayList<Deferred<Unit>>()

            entries.forEachIndexed { index, entry ->
                jobs.add(async(Dispatchers.IO) {
                    semaphore.withPermit {
                        withTimeoutOrNull(35_000L) {
                            try {
                                val label = entry.label ?: "Server ${index + 1}"
                                val versionLabel = if (entry.version == "dub") "مدبلج" else "مترجم"
                                val name = "$label • $versionLabel"
                                val quality = qualityValue(entry.quality)

                                if (entry.token != null) {
                                    // الموقع يطلب هذه الخطوة قبل فتح البوابة
                                    app.post(
                                        "$mainUrl/watch/stream-source/${entry.token}",
                                        headers = ajaxHeaders,
                                        cookies = cookies,
                                        interceptor = cfKiller
                                    )
                                    scanUrl(
                                        "$mainUrl/watch/stream-gate/${entry.token}",
                                        data, name, quality, cookies, 0, debug, subtitleCallback, tracked
                                    )
                                } else if (entry.direct != null) {
                                    val link = normalizeUrl(entry.direct)
                                    if (link != null) resolveExternal(link, data, name, quality, subtitleCallback, tracked)
                                }
                            } catch (e: Exception) {
                                logError(e)
                            }
                        }
                        Unit
                    }
                })
            }

            pageLinks.entries.forEachIndexed { i, (link, q) ->
                jobs.add(async(Dispatchers.IO) {
                    semaphore.withPermit {
                        withTimeoutOrNull(35_000L) {
                            try {
                                val host = hostOf(link)?.removePrefix("www.") ?: "Download"
                                resolveExternal(link, data, "تحميل ${i + 1} • $host", q, subtitleCallback, tracked)
                            } catch (e: Exception) {
                                logError(e)
                            }
                        }
                        Unit
                    }
                })
            }

            jobs.awaitAll()
        }

        // سطور التشخيص تظهر فقط عند عدم استخراج أي رابط
        if (emitted.get() == 0 && (DEBUG || debug.isNotEmpty())) {
            val hosts = debug.filter { it.startsWith("host:") }
                .groupingBy { it.removePrefix("host:") }.eachCount()
                .entries.joinToString(" ") { "${it.key} x${it.value}" }
            val pg = debug.firstOrNull { it.startsWith("page:") }?.removePrefix("page:").orEmpty()
            listOfNotNull(
                "[تشخيص] sources=$sourcesCode n=${entries.size} dl=${pageLinks.size} hosts: $hosts",
                if (pg.isNotBlank()) "[تشخيص] $pg" else null
            ).forEachIndexed { i, note ->
                callback(
                    newExtractorLink(source = "WitAnime", name = note.take(800), url = "https://example.invalid/$i") {
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.VIDEO
                    }
                )
            }
        }

        return emitted.get() > 0
    }
}

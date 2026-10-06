package com.witanime

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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
    private val DEBUG = false

    private val episodeUrlRegex = Regex("""/watch/([^/?#]+)/([^/?#]+)""")
    private val tokenRegex = Regex("^[a-f0-9]{64}$")
    private val fileHosts = listOf(
        "mediafire.com", "gofile.io", "4shared.com", "mega.nz", "mega.io",
        "drive.google.com", "pixeldrain.com", "krakenfiles.com", "streamtape.com", "dood.pm", "doodstream.com",
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
        return fileHosts.any { h == it || h.endsWith(".$it") }
    }

    private fun normalizeUrl(raw: String, base: String = mainUrl): String? {
        val v = unescape(raw.trim()).trim('"', '\'')
        if (v.isBlank() || v.startsWith("#") || v.startsWith("javascript:", true) || v.startsWith("data:", true)) return null
        return try {
            when {
                v.startsWith("//") -> {
                    val scheme = java.net.URI(base).scheme ?: "https"
                    "$scheme:$v"
                }
                v.startsWith("http://", true) || v.startsWith("https://", true) -> v
                else -> java.net.URI(base).resolve(v).toString()
            }
        } catch (_: Exception) {
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
    private fun qualityRank(q: Int): Int = when {
        q >= 2160 -> 0
        q >= 1440 -> 1
        q >= 1080 -> 2
        q >= 720 -> 3
        q in 1..719 -> 4
        else -> 5
    }

    private val keyedQualityRegex =
        Regex("""(?i)(?:label|size|res|resolution|quality|height|title|name)["']?\s*[:=]\s*["']?\s*(\d{3,4})""")
    private val keyedWordQualityRegex =
        Regex("""(?i)(?:label|quality|title|name)["']?\s*[:=]\s*["']?\s*(fhd|uhd|4k|hd|sd)(?![a-z])""")

    private fun qualityFromUrl(u: String): Int? {
        Regex("""(?<![0-9A-Za-z])(2160|1440|1080|720|576|480|360|240)p?(?![0-9A-Za-z])""").find(u)?.let { return it.groupValues[1].toInt() }
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
    private val siteLock = Mutex()
    @Volatile private var nextSlotAt = 0L
    @Volatile private var cooldownUntil = 0L
    private val throttleHits = java.util.concurrent.atomic.AtomicInteger(0)
    private val rateLimited = java.util.concurrent.atomic.AtomicBoolean(false)
    private val SITE_GAP_MS = 1500L

    private suspend fun siteWait(): Boolean {
        if (rateLimited.get()) return false
        siteLock.withLock {
            if (rateLimited.get()) return false
            val now = System.currentTimeMillis()
            val start = maxOf(now, nextSlotAt, cooldownUntil)
            nextSlotAt = start + SITE_GAP_MS
            if (start > now) delay(start - now)
            return !rateLimited.get()
        }
    }

    private fun siteBackoff(ms: Long) {
        throttleHits.incrementAndGet()
        rateLimited.set(true)
        cooldownUntil = maxOf(cooldownUntil, System.currentTimeMillis() + maxOf(ms, 10_000L))
    }

    private fun isThrottled(code: Int, text: String): Boolean =
        code == 429 || (text.length < 8000 &&
            (text.contains("Too Many Requests", ignoreCase = true) || text.contains("طلبات كثيرة")))
    private val linkCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<ExtractorLink>>>()
    private val sessionCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Session>>()

    private fun retryAfterMs(header: String?, attempt: Int): Long =
        ((header?.trim()?.toLongOrNull()?.times(1000)) ?: (1500L * (attempt + 1))).coerceIn(800L, 8000L)

    private suspend fun openSession(url: String, force: Boolean = false): Session {
        val now = System.currentTimeMillis()
        if (!force) {
            sessionCache[url]?.let { (t, sess) ->
                if (now - t < 90_000L) return sess
            }
        }
        if (!siteWait()) throw ErrorLoadingException("429")

        val page = app.get(url, interceptor = cfKiller)
        if (page.code == 429) {
            siteBackoff(retryAfterMs(page.headers["Retry-After"], 0))
            throw ErrorLoadingException("429")
        }
        if (page.code == 403) throw ErrorLoadingException("403")
        if (page.code in 500..599) throw ErrorLoadingException(page.code.toString())

        val html = page.text
        val doc = page.document
        val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: Regex("""(?i)csrf[-_]?token["']?\s*[:=,]\s*["']([A-Za-z0-9_\-]{20,})["']""").find(html)?.groupValues?.get(1)
            ?: ""
        val sess = Session(html, doc, csrf, page.cookies.toMutableMap())
        sessionCache[url] = System.currentTimeMillis() to sess
        return sess
    }
    @Volatile private var cachedEndpoints: Pair<Long, List<String>>? = null

    private suspend fun discoverEndpoints(doc: org.jsoup.nodes.Document): List<String> {
        cachedEndpoints?.let { (t0, v0) -> if (System.currentTimeMillis() - t0 < 10 * 60_000L) return v0 }
        val found = LinkedHashSet<String>()
        val rx = Regex("""["'`](/?(?:watch/)?[A-Za-z0-9_\-]*(?:gate|source|download|dl)[A-Za-z0-9_\-/${'$'}{}.]*)["'`]""", RegexOption.IGNORE_CASE)
        val scripts = doc.select("script[src]").map { it.attr("src") }
            .filter { it.contains("/build/assets/") && it.endsWith(".js") }.take(3)
        for (src in scripts) {
            val url = normalizeUrl(src) ?: continue
            if (!siteWait()) break
            val js = try { app.get(url, interceptor = cfKiller).text } catch (e: Exception) { "" }
            rx.findAll(js).forEach { found.add(it.groupValues[1]) }
        }
        val list = found.filter { it.contains("/") && it.length in 5..80 }.take(30)
        cachedEndpoints = System.currentTimeMillis() to list
        return list
    }

    private fun endpointBase(candidate: String?): String? {
        if (candidate == null) return null
        var b = candidate.substringBefore("$").substringBefore("{").trim()
        if (b.isBlank()) return null
        if (b.startsWith("http://", true) || b.startsWith("https://", true)) {
            return b.trimEnd('/') + "/"
        }
        if (!b.startsWith("/")) b = "/$b"
        return b.trimEnd('/') + "/"
    }

    private fun endpointUrl(base: String, token: String): String =
        if (base.startsWith("http://", true) || base.startsWith("https://", true))
            base.trimEnd('/') + "/" + token
        else mainUrl.trimEnd('/') + "/" + base.trimStart('/') + token

    private data class SourcesResult(val session: Session, val code: Int, val text: String)
    private suspend fun fetchSources(data: String, first: Session): SourcesResult {
        val token = first.csrf.ifBlank { first.xsrf.orEmpty() }
        val sourcesPath = Regex("""(?i)\bsourcesUrl\s*[:=]\s*["']([^"']+)["']""")
            .find(first.html)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: (java.net.URI(data).path.trimEnd('/') + "/sources")
        val sourcesUrl = if (sourcesPath.startsWith("http", true)) sourcesPath
        else mainUrl.trimEnd('/') + "/" + sourcesPath.trimStart('/')

        if (!siteWait()) return SourcesResult(first, 429, "")
        val headers = mutableMapOf(
            "Accept" to "application/json",
            "X-CSRF-TOKEN" to token,
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to mainUrl,
            "Referer" to data
        )
        first.xsrf?.let { headers["X-XSRF-TOKEN"] = it }

        return try {
            val res = app.post(
                sourcesUrl,
                headers = headers,
                cookies = first.cookies,
                data = mapOf("_token" to token),
                interceptor = cfKiller
            )
            first.cookies.putAll(res.cookies)
            if (res.code == 429) siteBackoff(retryAfterMs(res.headers["Retry-After"], 0))
            SourcesResult(first, res.code, res.text)
        } catch (e: Exception) {
            logError(e)
            SourcesResult(first, -1, "")
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

            val before = body.substring(maxOf(0, m.range.first - 140), m.range.first)
            val after = body.substring(m.range.last + 1, minOf(body.length, m.range.last + 1 + 90))
            val quality = qualityFromUrl(media)
                ?: qualityFromWindow(before)
                ?: qualityFromWindow(after)
                ?: defaultQuality
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
            newExtractorLink(source = name, name = name, url = direct) {
                this.referer = "https://www.mediafire.com/"
                this.quality = qualityFromUrl(direct) ?: quality
                this.type = ExtractorLinkType.VIDEO
            }
        )
        return true
    }

    @Volatile private var gofileNote = ""
    private val FAIL_REPORT = false
    private fun serverRank(n: String): Int {
        val l = n.lowercase()
        return when {
            l.contains("videa") -> 0
            l.contains("hgcloud") -> 1
            l.contains("4shared") -> 2
            l.contains("yonaplay") -> 3
            Regex("""(^|[^a-z])ok([^a-z]|$)""").containsMatchIn(l) -> 4
            l.contains("mega") -> 9
            else -> 5
        }
    }

    private fun zipName(n: String): String = if (n.contains("ZIP")) n else "$n • ZIP"

    private suspend fun resolveGofile(
        link: String, name: String, quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""gofile\.io/(?:d|embed|download)/([A-Za-z0-9]+)""").find(link)?.groupValues?.get(1)
        if (id == null) {
            gofileNote = "no-id: " + link.take(80)
            return false
        }
        val acc = app.post(
            "https://api.gofile.io/accounts",
            json = emptyMap<String, String>(),
            headers = mapOf("User-Agent" to userAgent)
        )
        val token = try {
            JSONObject(acc.text).optJSONObject("data")?.optString("token")
        } catch (e: Exception) {
            null
        }?.takeIf { it.isNotBlank() }
        if (token == null) {
            gofileNote = "accounts ${acc.code} " + acc.text.replace(Regex("\\s+"), " ").take(120)
            return false
        }

        fun sha256(x: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(x.toByteArray())
                .joinToString("") { "%02x".format(it) }
        val bucket = System.currentTimeMillis() / 1000 / 14400
        val wtDynamic = sha256("$userAgent::en-US::$token::$bucket::5d4f7g8sd45fsd")

        var lastNote = ""
        for (wt in listOf(wtDynamic, "4fd6sg89d7s6")) {
            val res = app.get(
                "https://api.gofile.io/contents/$id?contentFilter=&page=1&pageSize=1000&sortField=name&sortDirection=1",
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "X-Website-Token" to wt,
                    "X-BL" to "en-US",
                    "User-Agent" to userAgent
                )
            )
            val children = try {
                JSONObject(res.text).optJSONObject("data")?.optJSONObject("children")
            } catch (e: Exception) {
                null
            }
            if (children == null) {
                lastNote = "contents ${res.code} " + res.text.replace(Regex("\\s+"), " ").take(160)
                continue
            }
            var any = false
            val keys = children.keys()
            while (keys.hasNext()) {
                val f = children.optJSONObject(keys.next()) ?: continue
                if (f.optString("type") != "file") continue
                val fileName = f.optString("name")
                val isArchive = Regex("""(?i)\.(zip|rar|7z)$""").containsMatchIn(fileName)
                if (!f.optString("mimetype").startsWith("video") && !isArchive) continue
                val dl = f.optString("link").takeIf { it.startsWith("http") } ?: continue
                callback(
                    newExtractorLink(source = name, name = if (isArchive) "$name • ZIP" else name, url = dl) {
                        this.referer = "https://gofile.io/"
                        this.quality = qualityFromUrl(fileName) ?: quality
                        this.type = ExtractorLinkType.VIDEO
                        this.headers = mapOf("Cookie" to "accountToken=$token")
                    }
                )
                any = true
            }
            if (any) {
                gofileNote = ""
                return true
            }
            lastNote = "no-files: " + res.text.replace(Regex("\\s+"), " ").take(160)
        }
        gofileNote = lastNote
        return false
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
        val id = Regex("""workupload\.com/(?:file|start)/([A-Za-z0-9]+)""").find(link)?.groupValues?.get(1)
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
            val o = JSONObject(api)
            listOf(
                o.optJSONObject("data")?.optString("url"),
                o.optJSONObject("data")?.optString("downloadUrl"),
                o.optString("url")
            ).firstOrNull { it?.startsWith("http", true) == true }
        } catch (e: Exception) {
            null
        } ?: return false
        val wuHeaders: Map<String, String> = first.cookies.takeIf { it.isNotEmpty() }
            ?.let { mapOf("Cookie" to it.entries.joinToString("; ") { c -> "${c.key}=${c.value}" }) }
            ?: emptyMap()
        val wuZip = Regex("""(?i)\.(zip|rar|7z)\b""").containsMatchIn(first.text.take(30000)) ||
            looksLikeZip(direct, wuHeaders, "https://workupload.com/")
        callback(
            newExtractorLink(source = name, name = if (wuZip) zipName(name) else name, url = direct) {
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

    private suspend fun resolveOkRu(
        link: String, name: String, quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""(?:videoembed|video)/(\d+)""").find(link)?.groupValues?.get(1) ?: return false
        val raw = app.get(
            "https://ok.ru/videoembed/$id",
            headers = mapOf("User-Agent" to userAgent, "Referer" to "https://ok.ru/")
        ).text
        val t = raw.replace("&quot;", "\"").replace("\\\"", "\"")
            .replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        val qmap = mapOf("mobile" to 144, "lowest" to 240, "low" to 360, "sd" to 480, "hd" to 720, "full" to 1080, "quad" to 1440, "ultra" to 2160)
        var any = false
        for (m in Regex("""name":"([a-z]+)","url":"(https?://[^"]+)""").findAll(t)) {
            val q = qmap[m.groupValues[1]] ?: continue
            callback(
                newExtractorLink(source = name, name = name, url = m.groupValues[2]) {
                    this.referer = "https://ok.ru/"
                    this.quality = q
                    this.type = ExtractorLinkType.VIDEO
                }
            )
            any = true
        }
        if (!any) {
            Regex("""(?:hlsManifestUrl|ondemandHls)":"(https?://[^"]+)""").find(t)?.let { hm ->
                for (v in M3u8Helper.generateM3u8(name, hm.groupValues[1], "https://ok.ru/")) {
                    callback(v)
                    any = true
                }
            }
        }
        return any
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
            val u = normalizeUrl(n.trim(), referer) ?: continue
            if (u == link || u.contains("a-ads.com") || !u.startsWith("http")) continue
            if (resolveExternal(u, link, name, quality, subtitleCallback, callback, null, depth + 1)) any = true
        }
        return any
    }
    private suspend fun renamedLink(l: ExtractorLink, nm: String): ExtractorLink = try {
        newExtractorLink(source = l.source, name = nm, url = l.url, type = l.type) {
            this.referer = l.referer
            this.quality = l.quality
            this.headers = l.headers
        }
    } catch (e: Exception) {
        l
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
        val specific = try {
            when {
                host.contains("mediafire.com") -> resolveMediaFire(link, zipName(name), quality, callback)
                host.contains("gofile.io") -> resolveGofile(link, name, quality, callback)
                host.contains("workupload.com") -> resolveWorkupload(link, name, quality, callback)
                host.contains("4shared.com") -> resolveFourShared(link, name, quality, prefetched, callback)
                host.contains("ok.ru") || host.contains("odnoklassniki") -> resolveOkRu(link, name, quality, callback)
                else -> false
            }
        } catch (e: Exception) {
            logError(e)
            false
        }
        if (specific) return true
        val collected = java.util.concurrent.ConcurrentLinkedQueue<ExtractorLink>()
        val viaLib = try {
            loadExtractor(link, referer, subtitleCallback) { collected.add(it) }
        } catch (e: Exception) {
            false
        }
        if (collected.isNotEmpty()) {
            val shownName = if (host.contains("mediafire.com")) zipName(name) else name
            for (l in collected) {
                if (l.quality == Qualities.Unknown.value && quality != Qualities.Unknown.value) l.quality = quality
                callback0(renamedLink(l, shownName))
            }
            return true
        }

        return try {
            scanGenericHost(link, prefetched, referer, name, quality, subtitleCallback, callback, depth)
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
        val siteHost = hostOf(mainUrl)
        suspend fun fetchPage() = app.get(
            url,
            referer = referer,
            cookies = cookies,
            headers = iframeHeaders,
            interceptor = cfKiller
        )
        if (hostOf(url) == siteHost && !siteWait()) return false
        val res = fetchPage()
        if (hostOf(url) == siteHost && res.code == 429) {
            siteBackoff(retryAfterMs(res.headers["Retry-After"], 0))
            return false
        }
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

        Regex("""(?i)["']?(?:file|src|source|url|link|embed(?:_?url)?|stream(?:_?url)?|iframe)["']?\s*[:=]\s*["']((?:https?:)?//[^"'\s<>]+)["']""")
            .findAll(body).forEach { found.add(it.groupValues[1]) }
        Regex("""(?i)<(?:video|source)[^>]+src=["']([^"']+)""").findAll(body).forEach { found.add(it.groupValues[1]) }

        val staticRx = Regex("""(?i)\.(js|css|png|jpe?g|gif|svg|ico|woff2?|webp)(\?|$)""")
        for (raw in found) {
            val link = normalizeUrl(raw, res.url) ?: continue
            if (link.contains("a-ads.com") || link == url) continue
            if (staticRx.containsMatchIn(link) || link.contains("googletagmanager") || link.contains("cloudflare")) continue
            if (hostOf(link) == gateHost) {
                if (depth < 2 && scanUrl(link, url, name, quality, cookies, depth + 1, debug, subtitleCallback, callback)) {
                    handled = true
                }
            } else if (resolveExternal(link, url, name, quality, subtitleCallback, callback)) {
                handled = true
            }
        }
        if (!handled && depth == 0) {
            synchronized(debug) {
                debug.add("fail:$name | ${res.url.take(110)} | len=${body.length} | " + body.take(if (body.length < 1500) 900 else 220).replace(Regex("\\s+"), " "))
            }
        }
        return handled
    }
    private suspend fun looksLikeZip(url: String, headers: Map<String, String>, referer: String?): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 12000
                c.readTimeout = 12000
                c.setRequestProperty("Range", "bytes=0-3")
                c.setRequestProperty("User-Agent", userAgent)
                referer?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Referer", it) }
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                val b = ByteArray(4)
                var n = 0
                c.inputStream.use { st ->
                    while (n < 4) {
                        val r = st.read(b, n, 4 - n)
                        if (r < 0) break
                        n += r
                    }
                }
                c.disconnect()
                n >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte()
            } catch (e: Exception) {
                false
            }
        }

    private fun notice(text: String, idx: Int, callback: (ExtractorLink) -> Unit) {
        android.util.Log.w("WitAnime", text.take(800))
    }

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
        linkCache[data]?.let { (t, list) ->
            if (System.currentTimeMillis() - t < 5 * 60_000L && list.isNotEmpty()) {
                list.forEach { callback(it) }
                return true
            }
        }
        throttleHits.set(0)
        rateLimited.set(false)
        val firstSession = try {
            openSession(data)
        } catch (e: Exception) {
            logError(e)
            val c = e.message?.trim()?.toIntOrNull() ?: 429
            notice(hintFor(c) ?: "⚠️ تعذّر فتح الصفحة", 0, callback)
            return false
        }
        val sr = fetchSources(data, firstSession)
        val session = sr.session
        val html = session.html
        val doc = session.document
        val csrf = session.csrf.ifBlank { session.xsrf.orEmpty() }
        val cookies: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap(session.cookies)

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
        if (sourcesCode == 429 || rateLimited.get()) {
            sessionCache.remove(data)
            notice(hintFor(429) ?: "⚠️ الموقع يحدّ الطلبات الآن (429)", 0, callback)
            return false
        }
        val entries = mutableListOf<SrcEntry>()
        try {
            val root = JSONObject(sourcesText)
            val dlList = mutableListOf<SrcEntry>()
            val players = root.opt("players")
            if (players is JSONObject || players is JSONArray) {
                collectEntries(players, "", entries)
            } else {
                collectEntries(root, "", entries)
            }
            val downloads = root.opt("downloads")
            if (downloads is JSONObject || downloads is JSONArray) {
                collectEntries(downloads, "", dlList)
            }
            dlList.forEach { entries.add(it.copy(download = true)) }
        } catch (e: Exception) {
            logError(e)
        }
        var discoveredEndpoints: List<String>? = null
        suspend fun discovered(kind: String, download: Boolean): String? {
            val eps = discoveredEndpoints ?: discoverEndpoints(doc).also { discoveredEndpoints = it }
            return eps.firstOrNull { e ->
                val l = e.lowercase()
                (if (download) l.contains("download") || l.contains("/dl") else !l.contains("download")) && l.contains(kind)
            }?.let { endpointBase(it) }
        }
        var dlSourceBase = "/watch/download-source/"
        var dlGateBase = "/watch/download-gate/"
        var streamSourceBase = "/watch/stream-source/"
        var streamGateBase = "/watch/stream-gate/"

        val hostLabels = listOf(
            "gofile", "workupload", "mediafire", "mega", "4shared", "pixeldrain",
            "krakenfiles", "terabox", "send", "drive", "yonaplay", "hgcloud", "videa"
        )
        val pageLinks = LinkedHashMap<String, Pair<Int, String>>()
        doc.select("a[href]").forEach { a ->
            val link = normalizeUrl(a.attr("href")) ?: return@forEach
            val label = a.text().trim()
            val labelHit = label.length in 3..24 && hostLabels.any { label.lowercase().contains(it) }
            if (!isFileHost(link) && !labelHit) return@forEach
            val q = qualityFromAncestors(a) ?: qualityFromUrl(link) ?: Qualities.Unknown.value
            val shown = label.takeIf { labelHit } ?: hostOf(link)?.removePrefix("www.") ?: "Download"
            pageLinks.putIfAbsent(link, q to shown)
        }
        val dlHostRx = Regex("""https?://(?:www\.)?(?:gofile\.io|mediafire\.com|workupload\.com|4shared\.com|pixeldrain\.com|krakenfiles\.com|send\.cm|terabox\.com|mega\.nz|mega\.io)/[^\s"'<>\)]+""")
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

        val knownTokens = entries.mapNotNull { it.token }.toSet()
        Regex("""[a-f0-9]{64}""").findAll(html).map { it.value }.distinct()
            .filter { it !in knownTokens }.take(10)
            .forEach { entries.add(SrcEntry("", it, null, null, null)) }
        val sortedEntries = entries.withIndex()
            .sortedWith(compareBy({ qualityRank(qualityValue(it.value.quality)) }, { it.value.download }, { it.index }))
        val sortedPageLinks = pageLinks.entries.sortedBy { qualityRank(it.value.first) }

        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val emitted = java.util.concurrent.atomic.AtomicInteger(0)
        val gofileSeen = java.util.concurrent.atomic.AtomicBoolean(false)
        gofileNote = ""
        val buffer = java.util.concurrent.ConcurrentLinkedQueue<ExtractorLink>()
        val seenKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val tracked: (ExtractorLink) -> Unit = { link ->
            if (seen.add(link.url) && seenKeys.add("${link.name}|${link.quality}")) {
                emitted.incrementAndGet()
                if (link.name.contains("Gofile", ignoreCase = true)) gofileSeen.set(true)
                buffer.add(link)
            }
        }
        val debug = mutableListOf<String>()
        val results = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val semaphore = Semaphore(1)

        coroutineScope {
            val jobs = ArrayList<Deferred<Unit>>()

            sortedEntries.forEach { (index, entry) ->
                jobs.add(async(Dispatchers.IO) {
                    semaphore.withPermit {
                        var ok = false
                        val label = (entry.label ?: "Server ${index + 1}").replaceFirstChar { it.uppercase() }
                        val quality = qualityValue(entry.quality)
                        val qLabel = entry.quality.uppercase().ifBlank { qualityLabel(quality) }
                        val done = withTimeoutOrNull(35_000L) {
                            try {
                                val versionLabel = if (entry.version == "dub") "مدبلج" else "مترجم"
                                val nm = if (entry.download) "تحميل • $label" else "$label • $versionLabel"
                                val srcBase = if (entry.download) dlSourceBase else streamSourceBase
                                val gateBase = if (entry.download) dlGateBase else streamGateBase

                                if (entry.token != null) {
                                    if (rateLimited.get()) return@withPermit
                                    suspend fun postSrc(base: String) = if (siteWait()) {
                                        app.post(
                                            endpointUrl(base, entry.token!!),
                                            headers = ajaxHeaders,
                                            cookies = cookies,
                                            interceptor = cfKiller
                                        )
                                    } else null

                                    var ssr = postSrc(srcBase)
                                    if (ssr != null && ssr.code == 429) {
                                        siteBackoff(retryAfterMs(ssr.headers["Retry-After"], 0))
                                        return@withPermit
                                    }
                                    if (ssr != null && ssr.code in setOf(404, 405)) {
                                        val candidate = discovered("source", entry.download)
                                        if (candidate != null) {
                                            val retry = postSrc(candidate)
                                            if (retry != null && retry.code == 429) {
                                                siteBackoff(retryAfterMs(retry.headers["Retry-After"], 0))
                                                return@withPermit
                                            }
                                            ssr = retry
                                            if (entry.download) dlSourceBase = candidate else streamSourceBase = candidate
                                        }
                                    }

                                    if (ssr != null) {
                                        if (ssr.cookies.isNotEmpty()) cookies.putAll(ssr.cookies)
                                        ok = extractMedia(ssr.text, data, nm, quality, tracked)
                                        if (!ok) {
                                            val directUrls = Regex("""https?://[^\s\"'<>]+""").findAll(unescape(ssr.text))
                                                .map { it.value.trimEnd(',', ';', '.') }.distinct().take(8).toList()
                                            for (u in directUrls) {
                                                if (resolveExternal(u, data, nm, quality, subtitleCallback, tracked)) { ok = true; break }
                                            }
                                        }
                                        if (!ok && ssr.code in 200..299 && !rateLimited.get()) {
                                            val gate = gateBase
                                            ok = scanUrl(
                                                endpointUrl(gate, entry.token),
                                                data, nm, quality, cookies, 0, debug, subtitleCallback, tracked
                                            )
                                            if (!ok && !rateLimited.get()) {
                                                val candidate = discovered("gate", entry.download)
                                                if (candidate != null) {
                                                    if (entry.download) dlGateBase = candidate else streamGateBase = candidate
                                                    ok = scanUrl(
                                                        endpointUrl(candidate, entry.token),
                                                        data, nm, quality, cookies, 0, debug, subtitleCallback, tracked
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else if (entry.direct != null) {
                                    val link = normalizeUrl(entry.direct)
                                    if (link != null) ok = resolveExternal(link, data, nm, quality, subtitleCallback, tracked)
                                }
                            } catch (e: Exception) {
                                logError(e)
                                synchronized(debug) { debug.add("fail:$label | ${e.javaClass.simpleName}: ${e.message?.take(80)}") }
                            }
                            true
                        }
                        if (done == null) synchronized(debug) { debug.add("fail:$label | timeout") }
                        results.add("$qLabel/$label ${if (ok) "✓" else "✗"}")
                        Unit
                    }
                })
            }

            sortedPageLinks.forEach { (link, qs) ->
                jobs.add(async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (rateLimited.get()) return@withPermit
                        var ok = false
                        val (q, shown) = qs
                        val qLabel = qualityLabel(q)
                        withTimeoutOrNull(35_000L) {
                            try {
                                val nm = "تحميل • $shown"
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
        val sortedLinks = buffer.toList().sortedWith(
            compareBy<ExtractorLink>(
                { if (it.name.startsWith("تحميل")) 1 else 0 },
                { -it.quality },
                { serverRank(it.name) },
                { it.name }
            )
        )
        sortedLinks.forEach { callback(it) }
        if (sortedLinks.isNotEmpty() && throttleHits.get() == 0) {
            linkCache[data] = System.currentTimeMillis() to sortedLinks
        }

        if (gofileNote.isNotBlank() && results.none { it.contains("Gofile ✓") }) {
            notice("[Gofile] $gofileNote", 60, callback)
        }

        if (FAIL_REPORT) {
            debug.filter { it.startsWith("fail:") }
                .distinctBy { it.removePrefix("fail:").substringBefore(" |").trim() }
                .take(6)
                .forEachIndexed { i, f -> notice("[فشل] " + f.removePrefix("fail:"), 70 + i, callback) }
        }

        if (emitted.get() == 0) {
            sessionCache.remove(data)
            hintFor(sourcesCode)?.let { notice(it, 0, callback) }
        }

        if (DEBUG || emitted.get() == 0) {
            val extraKeys: List<String> = try {
                val o = JSONObject(sourcesText)
                o.keys().asSequence().filter { it != "players" }.take(3)
                    .map { k -> "[تشخيص] key $k: " + o.opt(k).toString().take(300) }.toList()
            } catch (e: Exception) {
                emptyList()
            }
            val summary = results.sorted().joinToString(" | ")
            val hosts = debug.filter { it.startsWith("host:") }
                .groupingBy { it.removePrefix("host:") }.eachCount()
                .entries.joinToString(" ") { "${it.key} x${it.value}" }
            listOfNotNull(
                "[تشخيص] sources=$sourcesCode n=${entries.size} dl=${pageLinks.size} csrf=${if (csrf.isBlank()) "empty" else "ok"}",
                "[تشخيص] url=$data title=${doc.title().take(60)} len=${html.length} hasSourcesUrl=${html.contains("sourcesUrl")} watchLinks=${doc.select("a[href*=/watch/]").size}",
                html.indexOf("sources").takeIf { it >= 0 }?.let {
                    "[تشخيص] ctx: " + html.substring(maxOf(0, it - 120), minOf(html.length, it + 220)).replace(Regex("\\s+"), " ")
                },
                if (summary.isNotBlank()) "[تشخيص] $summary" else null,
                if (hosts.isNotBlank()) "[تشخيص] hosts: $hosts" else null,
                *sourcesText.replace(Regex("\\s+"), " ").chunked(230).take(1)
                    .mapIndexed { i, c -> "[تشخيص] json${i + 1}: $c" }.toTypedArray(),
                "[تشخيص] eps: " + endpoints.joinToString(" , ").take(450) + " | dl=" + dlSourceBase + " " + dlGateBase,
                "[تشخيص] wp: " + html.substringAfter("watchPlayer({", "").replace(Regex("\\s+"), " ").take(450),
                *extraKeys.toTypedArray(),
                *debug.filter { it.startsWith("ipage:") || it.startsWith("src:") }.take(4)
                    .map { "[تشخيص] $it" }.toTypedArray()
            ).forEachIndexed { i, note -> notice(note, i + 1, callback) }
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

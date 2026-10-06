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

    // ============================= Helpers =============================
    // اجعلها true فقط عند الحاجة لعرض سطور التشخيص دائماً
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

    // أولوية الترتيب: FHD ثم HD ثم 4K ثم SD ثم غير معروف
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

    // ============================= Current watch-page decoder =============================
    // The current public Witanime provider uses one watch-page request and decodes
    // the server registry locally. This avoids the old sources/gate request fan-out.
    private val frameworkHash = "1c0f3441-e3c2-4023-9e8b-bee77ff59adf"

    private fun cleanB64(s: String): String = s.replace(Regex("[^A-Za-z0-9+/=]"), "")

    private fun decodeB64String(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return try {
            String(Base64.decode(cleanB64(s), Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    private fun lookupRegistry(reg: Any?, sid: String): Any? = try {
        when (reg) {
            is JSONObject -> if (reg.has(sid)) reg.get(sid) else sid.toIntOrNull()?.let { i ->
                if (reg.has(i.toString())) reg.get(i.toString()) else null
            }
            is JSONArray -> sid.toIntOrNull()?.let { i ->
                if (i in 0 until reg.length()) reg.get(i) else null
            }
            is Map<*, *> -> reg[sid] ?: sid.toIntOrNull()?.let { reg[it] }
            else -> null
        }
    } catch (_: Exception) { null }

    private fun paramOffset(config: Any?): Int = try {
        when (config) {
            is JSONObject -> {
                val k = config.optString("k").takeIf { it.isNotBlank() } ?: return 0
                val idx = decodeB64String(k).toIntOrNull() ?: return 0
                config.optJSONArray("d")?.optInt(idx, 0) ?: 0
            }
            is Map<*, *> -> {
                val k = config["k"]?.toString().orEmpty()
                val idx = decodeB64String(k).toIntOrNull() ?: return 0
                (config["d"] as? List<*>)?.getOrNull(idx)?.toString()?.toIntOrNull() ?: 0
            }
            else -> 0
        }
    } catch (_: Exception) { 0 }

    private fun decodeServerResource(raw: Any?, offset: Int): String {
        val value = when (raw) {
            is String -> raw
            is JSONObject -> raw.optString("r").ifBlank {
                raw.optString("resource").ifBlank { raw.optString("data") }
            }
            is Map<*, *> -> (raw["r"] ?: raw["resource"] ?: raw["data"])?.toString().orEmpty()
            else -> ""
        }
        if (value.isBlank()) return ""
        return try {
            val bytes = Base64.decode(cleanB64(value.reversed()), Base64.DEFAULT)
            val sliced = if (offset > 0 && offset <= bytes.size) bytes.copyOf(bytes.size - offset) else bytes
            String(sliced, Charsets.UTF_8).trim().replace("\\/", "/")
        } catch (_: Exception) { "" }
    }

    private fun registryFrom(value: String): Any? {
        if (value.isBlank()) return null
        return try {
            JSONObject(value)
        } catch (_: Exception) {
            try { JSONArray(value) } catch (_: Exception) { null }
        }
    }

    private fun serverEntries(html: String): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        val rx = Regex("""<a[^>]+class=[\"'][^\"']*server-link[^\"']*[\"'][^>]*>.*?</a>""", RegexOption.DOT_MATCHES_ALL)
        for (m in rx.findAll(html)) {
            val tag = m.value
            val sid = Regex("""data-server-id\s*=\s*[\"']([^\"']+)[\"']""").find(tag)?.groupValues?.get(1) ?: continue
            val label = Regex("""<span[^>]+class=[\"'][^\"']*ser[^\"']*[\"'][^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
                .find(tag)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()
                ?: "Server-$sid"
            out.add(sid to label)
        }
        return out.toList()
    }

    private fun pxParse(js: String): Triple<String?, List<String>, Map<String, List<String>>> {
        val m = Regex("""var\s+_m\s*=\s*\{\s*\"r\"\s*:\s*\"([^\"]+)\"""").find(js)?.groupValues?.get(1)
        val s = Regex("""var\s+_s\s*=\s*\[(.*?)\]\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(js)?.groupValues?.get(1)?.let { body -> Regex("\\\"([^\\\"]*)\\\"").findAll(body).map { it.groupValues[1] }.toList() }.orEmpty()
        val p = mutableMapOf<String, List<String>>()
        Regex("""var\s+(_p\d+)\s*=\s*\[\s*(.*?)\s*\]\s*;""", RegexOption.DOT_MATCHES_ALL)
            .findAll(js).forEach { mm ->
                p[mm.groupValues[1]] = Regex("\\\"([^\\\"]*)\\\"").findAll(mm.groupValues[2]).map { it.groupValues[1] }.toList()
            }
        return Triple(m, s, p)
    }

    private fun decryptDownloadChunks(mr: String?, slist: List<String>, pmap: Map<String, List<String>>): List<String> {
        if (mr.isNullOrBlank()) return emptyList()
        val secret = try { Base64.decode(cleanB64(mr), Base64.DEFAULT) } catch (_: Exception) { return emptyList() }
        fun chunk(hex: String): String = try {
            val clean = hex.replace(Regex("[^0-9a-fA-F]"), "")
            if (clean.length % 2 != 0) return ""
            val bytes = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            String(ByteArray(bytes.size) { i -> (bytes[i].toInt() xor secret[i % secret.size].toInt()).toByte() }, Charsets.UTF_8).trim()
        } catch (_: Exception) { "" }
        val count = maxOf(slist.size, pmap.keys.count { it.startsWith("_p") })
        val out = ArrayList<String>()
        for (i in 0 until count) {
            val pieces = pmap["_p$i"] ?: continue
            val decoded = pieces.map(::chunk)
            val order = slist.getOrNull(i)?.let { runCatching { JSONArray(chunk(it)) }.getOrNull() }
            val joined = if (order != null && order.length() == decoded.size) {
                val arr = Array(decoded.size) { "" }
                for (j in decoded.indices) {
                    val pos = order.optInt(j, -1)
                    if (pos in arr.indices) arr[pos] = decoded[j]
                }
                arr.joinToString("")
            } else decoded.joinToString("")
            if (joined.isNotBlank()) out.add(joined.trim())
        }
        return out
    }

    private suspend fun loadWitExternal(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val final = url.trim().replace("\\/", "/")
        if (!final.startsWith("http")) return
        try {
            loadExtractor(final, referer, subtitleCallback, callback)
        } catch (_: Exception) {}
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cache = linkCache[data]
        if (cache != null && System.currentTimeMillis() - cache.first < 5 * 60_000L) {
            cache.second.forEach(callback)
            return cache.second.isNotEmpty()
        }

        // One request to the watch page. No /sources, no per-server source/gate fan-out.
        val page = try {
            app.get(data, interceptor = cfKiller)
        } catch (e: Exception) {
            logError(e)
            return false
        }
        if (page.code == 429 || isThrottled(page.code, page.text)) {
            return false
        }
        if (page.code !in 200..299) return false

        val html = page.text
        val servers = serverEntries(html)
        if (servers.isEmpty()) return false

        var zG = Regex("""var\\s+_zG\\s*=\\s*\\\"([^\\\"]+)\\\"""").find(html)?.groupValues?.get(1)
        var zH = Regex("""var\\s+_zH\\s*=\\s*\\\"([^\\\"]+)\\\"""").find(html)?.groupValues?.get(1)

        // Inline scripts first. We deliberately do not scan arbitrary JS unless necessary.
        if (zG.isNullOrBlank() || zH.isNullOrBlank()) {
            Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { m ->
                val js = m.groupValues[1]
                if (zG.isNullOrBlank()) zG = Regex("""var\\s+_zG\\s*=\\s*\\\"([^\\\"]+)\\\"""").find(js)?.groupValues?.get(1)
                if (zH.isNullOrBlank()) zH = Regex("""var\\s+_zH\\s*=\\s*\\\"([^\\\"]+)\\\"""").find(js)?.groupValues?.get(1)
            }
        }

        val resources = registryFrom(decodeB64String(zG))
        val configs = registryFrom(decodeB64String(zH))
        val emitted = java.util.concurrent.ConcurrentLinkedQueue<ExtractorLink>()
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        for ((sid, label) in servers) {
            val raw = decodeServerResource(lookupRegistry(resources, sid), paramOffset(lookupRegistry(configs, sid)))
            if (raw.isBlank()) continue
            var link = raw
            if (link.startsWith("//")) link = "https:$link"
            if (!link.startsWith("http")) continue
            if (link.contains("yonaplay.net", true) && link.matches(Regex("https://yonaplay\\.net/embed\\.php\\?id=\\d+"))) {
                link += "&apiKey=$frameworkHash"
            }
            val before = emitted.size
            loadWitExternal(link, data, subtitleCallback) { l ->
                if (seen.add(l.url)) emitted.add(l)
            }
            if (emitted.size == before) {
                // The generic extractor may not support the host. Do not hammer it with retries.
                continue
            }
        }

        // Downloads are embedded in the page's _m/_s/_pN payload and can be decoded locally.
        var pxM: String? = null
        var pxS = emptyList<String>()
        val pxP = mutableMapOf<String, List<String>>()
        Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { m ->
            val triple = pxParse(m.groupValues[1])
            if (pxM == null) pxM = triple.first
            if (pxS.isEmpty()) pxS = triple.second
            pxP.putAll(triple.third)
        }
        for (dl in decryptDownloadChunks(pxM, pxS, pxP)) {
            val idx = dl.indexOf("http")
            val final = if (idx >= 0) dl.substring(idx).trim() else dl.trim()
            if (final.startsWith("http")) loadWitExternal(final, data, subtitleCallback) { l ->
                if (seen.add(l.url)) emitted.add(l)
            }
        }

        val result = emitted.toList().sortedWith(compareBy<ExtractorLink>({ -it.quality }, { it.name }))
        result.forEach(callback)
        if (result.isNotEmpty()) linkCache[data] = System.currentTimeMillis() to result
        return result.isNotEmpty()
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

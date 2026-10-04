package com.phoenix

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * anime-phoenix.com  (التصميم الجديد: Streamit + Elementor)
 *  - الصفحة الرئيسية: HTML ثابت
 *  - البحث: عبر صفحة /index (فهرس كامل ثابت) لأن نتائج البحث الأصلية تُحمَّل بـ JS
 *  - الحلقات: /episodes/{slug}-episode-{n}
 *  - الأفلام: /movies/{slug}  ->  /movies/{slug}/watch
 */
class AnimePhoenixProvider : MainAPI() {
    override var mainUrl = "https://anime-phoenix.com"
    override var name = "anime-phoenix"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val mainPage = mainPageOf("$mainUrl/" to "الرئيسية")

    private val customHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    private fun dbg(msg: String) {
        println("AnimePhoenixDebug | $msg")
    }

    private fun absUrl(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val h = href.trim()
        if (h.startsWith("#") || h.startsWith("javascript", true)) return null
        return when {
            h.startsWith("http") -> h
            h.startsWith("//") -> "https:$h"
            h.startsWith("/") -> mainUrl.trimEnd('/') + h
            else -> mainUrl.trimEnd('/') + "/" + h
        }
    }

    private fun slugOf(url: String): String =
        url.substringBefore("?").substringBefore("#").trimEnd('/').substringAfterLast('/')

    private fun humanize(slug: String): String =
        slug.replace('-', ' ').split(' ').filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun normKey(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9\u0600-\u06FF]+"), " ").trim()

    private fun posterOf(root: Element): String? {
        val img = root.selectFirst("img") ?: return null
        val raw = img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
        if (raw.isBlank() || raw.startsWith("data:")) return null
        return absUrl(raw)
    }

    private val itemRegex = Regex("""/(animes|movies|episodes)/([^/?#]+)""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return newHomePageResponse(emptyList<HomePageList>(), hasNext = false)

        val document = app.get(mainUrl, headers = customHeaders, timeout = 45).document
        val rows = mutableListOf<HomePageList>()

        var heading = "مميز"
        var bucket = LinkedHashMap<String, SearchResponse>()

        fun flush() {
            if (bucket.size >= 3) rows.add(HomePageList(heading, bucket.values.toList()))
            bucket = LinkedHashMap()
        }

        val skipTags = setOf("nav", "header", "footer")

        for (el in document.select("h1, h2, h3, a[href]")) {
            if (el.parents().any { it.tagName() in skipTags }) continue

            if (el.tagName().startsWith("h")) {
                if (el.parents().any { it.tagName() == "a" }) continue
                val t = el.text().trim()
                if (t.isNotBlank()) {
                    flush()
                    heading = t
                }
                continue
            }

            val href = absUrl(el.attr("href")) ?: continue
            if (!href.contains("anime-phoenix")) continue
            val m = itemRegex.find(href) ?: continue
            val kind = m.groupValues[1]
            var slug = m.groupValues[2]

            val isMovie = kind == "movies"
            var url: String
            if (kind == "episodes") {
                slug = slug.replace(Regex("-episode-\\d+.*$"), "")
                url = "$mainUrl/animes/$slug"
            } else {
                url = "$mainUrl/$kind/$slug"
            }

            val title = el.attr("title").ifBlank { "" }
                .ifBlank { el.selectFirst("img")?.attr("alt") ?: "" }
                .ifBlank { el.selectFirst("[class*=title],[class*=name],h3,h4")?.text() ?: "" }
                .trim()
                .ifBlank { humanize(slug) }
            val poster = posterOf(el)
            val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

            if (!bucket.containsKey(url)) {
                bucket[url] = newAnimeSearchResponse(title, url, type).apply {
                    this.posterUrl = poster
                }
            }
        }
        flush()

        dbg("home rows = ${rows.size}")
        return newHomePageResponse(rows, hasNext = false)
    }

    private data class IndexItem(val title: String, val url: String, val isMovie: Boolean, val key: String)

    private var indexCache: List<IndexItem>? = null

    private suspend fun getIndex(): List<IndexItem> {
        indexCache?.let { if (it.isNotEmpty()) return it }
        val doc = app.get("$mainUrl/index", headers = customHeaders, timeout = 90).document
        val items = doc.select("a[href*='/animes/'], a[href*='/movies/']").mapNotNull { a ->
            val url = absUrl(a.attr("href")) ?: return@mapNotNull null
            if (!url.contains("anime-phoenix")) return@mapNotNull null
            val m = itemRegex.find(url) ?: return@mapNotNull null
            val kind = m.groupValues[1]
            if (kind == "episodes") return@mapNotNull null
            val slug = m.groupValues[2]
            var title = a.text().trim()
            if (title.isBlank() || title.startsWith("http")) title = humanize(slug)
            IndexItem(
                title = title,
                url = "$mainUrl/$kind/$slug",
                isMovie = kind == "movies",
                key = normKey("$title $slug")
            )
        }.distinctBy { it.url }
        dbg("index items = ${items.size}")
        indexCache = items
        return items
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val tokens = normKey(query).split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        val index = try {
            getIndex()
        } catch (e: Exception) {
            dbg("index failed: ${e.message}")
            return emptyList()
        }

        return index
            .filter { item -> tokens.all { item.key.contains(it) } }
            .sortedBy { if (it.key.startsWith(tokens[0])) 0 else 1 }
            .take(50)
            .map { item ->
                newAnimeSearchResponse(
                    item.title,
                    item.url,
                    if (item.isMovie) TvType.AnimeMovie else TvType.Anime
                )
            }
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = try {
            app.get(url, headers = customHeaders, timeout = 45)
        } catch (e: Exception) {
            dbg("load failed: ${e.message}")
            return null
        }
        val finalUrl = response.url.ifBlank { url }
        val document = response.document

        val title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.replace(Regex("\\s*\\|.*$"), "")
                ?.replace("أنمي ", "")?.replace("فيلم ", "")
                ?.replace(" مترجم كامل", "")?.trim()
            ?: humanize(slugOf(finalUrl))

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() && !it.contains("cropped-Icon") }
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.replace(Regex("\\s*-\\s*(شاهد|مشاهدة).*$"), "")?.trim()

        val isMovie = finalUrl.contains("/movies/")
        dbg("load: title=$title isMovie=$isMovie")

        if (isMovie) {
            val watchUrl = finalUrl.trimEnd('/') + "/watch"
            return newMovieLoadResponse(title, finalUrl, TvType.AnimeMovie, watchUrl).apply {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val slug = slugOf(finalUrl)
        val ownRegex = Regex("^" + Regex.escape(slug) + "-episode-(\\d+)")
        val anyNumRegex = Regex("episode-(\\d+)")
        val found = LinkedHashMap<Int, String>()

        fun collect(d: Document) {
            val anchors = d.select("a[href*='/episodes/']").mapNotNull { a ->
                val u = absUrl(a.attr("href")) ?: return@mapNotNull null
                Triple(u, slugOf(u), a.text())
            }
            val own = anchors.filter { ownRegex.containsMatchIn(it.second) }
            val use = if (own.isNotEmpty()) own else anchors
            for ((u, s, txt) in use) {
                val n = (if (own.isNotEmpty()) ownRegex.find(s)?.groupValues?.get(1) else anyNumRegex.find(s)?.groupValues?.get(1))
                    ?.toIntOrNull()
                    ?: Regex("(\\d+)").find(txt)?.groupValues?.get(1)?.toIntOrNull()
                    ?: continue
                if (!found.containsKey(n)) found[n] = u.trimEnd('/')
            }
        }

        collect(document)
        try {
            val allEps = app.get(finalUrl.trimEnd('/') + "/episodes", headers = customHeaders, timeout = 45).document
            collect(allEps)
        } catch (_: Exception) {
        }
        val total = Regex("عدد الحلقات\\s*(\\d+)").find(document.text())?.groupValues?.get(1)?.toIntOrNull()
        if (total != null && total in 1..3000 && (found.isEmpty() || found.keys.min() == 1)) {
            for (n in 1..total) {
                if (!found.containsKey(n)) found[n] = "$mainUrl/episodes/$slug-episode-$n"
            }
        }

        val episodes = found.toSortedMap().map { (n, epUrl) ->
            newEpisode(epUrl) {
                this.name = "الحلقة $n"
                this.episode = n
                this.season = 1
                this.posterUrl = poster
            }
        }
        dbg("episodes = ${episodes.size}")

        return newTvSeriesLoadResponse(title, finalUrl, TvType.Anime, episodes).apply {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private data class Cand(val url: String, val name: String, val direct: Boolean)

    private val junkHosts = listOf(
        "facebook.com", "twitter.com", "x.com", "t.me", "telegram.me", "instagram.com",
        "youtube.com", "youtu.be", "whatsapp.com", "linkedin.com", "discord.com", "discord.gg",
        "google.com", "gstatic.com", "googleapis.com", "schema.org", "w.org", "wp.com",
        "aisaasedu.com", "ouo.io", "ouo.press", "cloudflare.com", "jsdelivr.net", "cdnjs.cloudflare.com"
    )
    private val badExt = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".ico",
        ".css", ".js", ".woff", ".woff2", ".ttf", ".json", ".xml"
    )

    private fun hostOf(u: String): String =
        u.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()

    private fun usable(u: String): Boolean {
        val host = hostOf(u)
        if (host.contains("anime-phoenix")) return false
        if (junkHosts.any { host == it || host.endsWith(".$it") }) return false
        val path = u.substringBefore("?").lowercase()
        if (badExt.any { path.endsWith(it) }) return false
        return true
    }

    private fun addLink(link: String, name: String, direct: Boolean, out: MutableList<Cand>) {
        val l = link.trim().replace("\\/", "/")
        if (l.contains("<iframe", true)) {
            Jsoup.parse(l).select("iframe[src]").forEach { f ->
                absUrl(f.attr("src"))?.let { if (usable(it)) out += Cand(it, name, false) }
            }
            return
        }
        absUrl(l)?.let { if (usable(it)) out += Cand(it, name, direct) }
    }

    private fun collectStrings(node: Any?, name: String, out: MutableList<Cand>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) collectStrings(node.opt(keys.next()), name, out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectStrings(node.opt(i), name, out)
            is String -> if (node.startsWith("http") || node.startsWith("//") || node.contains("<iframe", true)) {
                addLink(node, name, false, out)
            }
        }
    }

    private fun parseDecoded(text: String, name: String, out: MutableList<Cand>) {
        val t = text.trim()
        try {
            when {
                t.startsWith("{") -> {
                    val o = JSONObject(t)
                    val nm = o.optString("name", name).ifBlank { name }
                    val type = o.optString("type")
                    val link = o.optString("link").ifBlank { o.optString("url") }
                        .ifBlank { o.optString("src") }.ifBlank { o.optString("embed") }
                    if (link.isNotBlank()) addLink(link, nm, type == "direct", out)
                    else collectStrings(o, nm, out)
                }
                t.startsWith("[") -> collectStrings(JSONArray(t), name, out)
                t.startsWith("http") || t.startsWith("//") || t.contains("<iframe", true) ->
                    addLink(t, name, false, out)
            }
        } catch (_: Exception) {
        }
    }

    private fun tryDecode(v: String): String? {
        for (flags in listOf(Base64.DEFAULT, Base64.URL_SAFE)) {
            try {
                var s = String(Base64.decode(v, flags), Charsets.UTF_8)
                if (s.contains('%')) {
                    s = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
                }
                val ts = s.trimStart()
                if (ts.startsWith("{") || ts.startsWith("[") || ts.startsWith("http") || ts.contains("<iframe", true)) {
                    return s
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun candidatesFromValue(raw: String, name: String): List<Cand> {
        val out = mutableListOf<Cand>()
        val v = raw.trim()
        if (v.isEmpty()) return out
        when {
            v.startsWith("http") || v.startsWith("//") || v.contains("<iframe", true) -> addLink(v, name, false, out)
            v.startsWith("{") || v.startsWith("[") -> parseDecoded(v, name, out)
            v.startsWith("%7B", true) || v.startsWith("%5B", true) -> {
                try { parseDecoded(URLDecoder.decode(v, "UTF-8"), name, out) } catch (_: Exception) {}
            }
            v.length >= 16 && Regex("^[A-Za-z0-9+/=_-]+$").matches(v) -> {
                tryDecode(v)?.let { parseDecoded(it, name, out) }
            }
        }
        return out
    }

    private val mediaRegex = Regex(
        """https?:[^"'\s\\<>()]+?\.(?:m3u8|mp4)(?:\?[^"'\s\\<>()]*)?""",
        RegexOption.IGNORE_CASE
    )

    private suspend fun genericExtract(
        url: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var n = 0
        try {
            val text = app.get(url, headers = customHeaders + mapOf("Referer" to referer), timeout = 30).text
            var scan = text.replace("\\/", "/")
            try {
                val unpacked = getAndUnpack(text)
                if (!unpacked.isNullOrBlank()) scan += "\n" + unpacked.replace("\\/", "/")
            } catch (_: Throwable) {
            }
            val seen = mutableSetOf<String>()
            for (m in mediaRegex.findAll(scan)) {
                val link = m.value
                if (!seen.add(link)) continue
                val isM3u8 = link.lowercase().contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$serverName (Auto)",
                        url = link,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                    }
                )
                n++
            }
        } catch (e: Exception) {
            dbg("generic failed $url : ${e.message}")
        }
        return n
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try {
            app.get(data, headers = customHeaders, timeout = 45).document
        } catch (e: Exception) {
            dbg("loadLinks get failed: ${e.message}")
            return false
        }

        val cands = LinkedHashMap<String, Cand>()
        fun add(list: List<Cand>) {
            for (c in list) if (!cands.containsKey(c.url)) cands[c.url] = c
        }
        for (el in document.allElements) {
            val label = el.ownText().trim().ifBlank { el.text().trim() }
                .replace(Regex("\\s*Watch$", RegexOption.IGNORE_CASE), "")
                .take(40).ifBlank { "Phoenix" }
            for (attr in el.attributes()) {
                val key = attr.key.lowercase()
                if (key.startsWith("data-") || key == "src" || key == "value") {
                    if (attr.value.isNotBlank()) {
                        add(candidatesFromValue(attr.value, label))
                    }
                }
            }
        }
        val scriptLink = Regex("""\"(?:link|url|embed|src|file)\"\s*:\s*\"(https?:[^\"]+)\"""")
        for (s in document.select("script:not([src])")) {
            for (m in scriptLink.findAll(s.data())) {
                val l = mutableListOf<Cand>()
                addLink(m.groupValues[1], "Phoenix", false, l)
                add(l)
            }
        }
        for (f in document.select("iframe[src], iframe[data-src]")) {
            val l = mutableListOf<Cand>()
            addLink(f.attr("src").ifBlank { f.attr("data-src") }, "Phoenix", false, l)
            add(l)
        }

        dbg("loadLinks candidates = ${cands.size} for $data")
        if (cands.isEmpty()) {
            document.select("a, button, li").filter { it.text().contains("Watch", true) }.take(5).forEach {
                dbg("watch element: ${it.outerHtml().take(300)}")
            }
        }

        val found = AtomicInteger(0)

        cands.values.toList().take(40).amap { c ->
            try {
                val local = AtomicInteger(0)
                val cb: (ExtractorLink) -> Unit = {
                    local.incrementAndGet()
                    found.incrementAndGet()
                    callback(it)
                }

                if (c.url.contains("drive.google.com", ignoreCase = true)) {
                    val fileId = Regex("/file/d/([0-9A-Za-z_-]{10,})").find(c.url)?.groupValues?.get(1)
                        ?: Regex("[?&]id=([0-9A-Za-z_-]{10,})").find(c.url)?.groupValues?.get(1)
                    if (!fileId.isNullOrBlank()) {
                        cb(
                            newExtractorLink(
                                source = this.name,
                                name = "${c.name} (GDrive Direct)",
                                url = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t",
                            ) {
                                referer = "https://drive.google.com/"
                                quality = Qualities.Unknown.value
                            }
                        )
                    } else {
                        loadExtractor(c.url, data, subtitleCallback, cb)
                    }
                } else if (c.direct) {
                    val isM3u8 = c.url.lowercase().contains(".m3u8")
                    cb(
                        newExtractorLink(
                            source = this.name,
                            name = c.name,
                            url = c.url,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            referer = mainUrl
                            quality = Qualities.Unknown.value
                        }
                    )
                } else {
                    loadExtractor(c.url, data, subtitleCallback, cb)
                    if (local.get() == 0) {
                        val n = genericExtract(c.url, data, c.name, callback)
                        if (n > 0) found.addAndGet(n)
                    }
                }
            } catch (e: Exception) {
                dbg("candidate failed ${c.url}: ${e.message}")
            }
        }

        dbg("loadLinks total = ${found.get()}")
        return found.get() > 0
    }
}

package com.cimaclub

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CimaClub : MainAPI() {
    override var mainUrl = "https://cimacub.com"
    override var name = "CimaClub"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val mainPage = mainPageOf(
        "$mainUrl/category/افلام-اجنبي/" to "أفلام أجنبي",
        "$mainUrl/category/افلام-عربي/" to "أفلام عربي",
        "$mainUrl/category/افلام-هندي/" to "أفلام هندي",
        "$mainUrl/category/افلام-اسيوية/" to "أفلام اسيوية",
        "$mainUrl/category/افلام-انمي/" to "أفلام انمي",
        "$mainUrl/category/مسلسلات-رمضان-2025/" to "مسلسلات رمضان 2025",
        "$mainUrl/category/مسلسلات-اجنبي/" to "مسلسلات أجنبي",
        "$mainUrl/category/مسلسلات-تركية/" to "مسلسلات تركية",
        "$mainUrl/category/مسلسلات-عربي/" to "مسلسلات عربي",
        "$mainUrl/category/مسلسلات-اسيوية/" to "مسلسلات اسيوية",
        "$mainUrl/category/مسلسلات-هندية/" to "مسلسلات هندي",
        "$mainUrl/category/مسلسلات-انمي/" to "مسلسلات انمي",
        "$mainUrl/category/مسلسلات-مدبلجة/" to "مسلسلات مدبلجة",
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("inner--title > h2")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.ifBlank { null }

        val isTv = href.contains("/series/") || href.contains("/مسلسل-") || this.selectFirst(".number") != null

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url).document
        return document.select("div.BlocksHolder > div.Small--Box").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            "${request.data}page/$page/"
        } else {
            request.data
        }

        val document = app.get(url).document
        val items = document.select("div.BlocksHolder > div.Small--Box").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val isTvSeries = url.contains("/series/") || url.contains("/مسلسل-") ||
                document.select("section.allepcont .row a").size > 1

        val title = document.selectFirst("h1.PostTitle")?.text()?.trim()
            ?: throw RuntimeException("Title not found on page: $url")
        val poster =
            document.selectFirst(".MainSingle .left .image img")?.attr("src")?.ifBlank { null }
        val plot = document.selectFirst(".StoryArea p")?.text()?.replace("قصة العرض", "")?.trim()
        val tags = document.select(".TaxContent a[href*='/genre/']").mapNotNull { it.text() }
        val year =
            document.selectFirst(".TaxContent a[href*='/release-year/']")?.text()?.toIntOrNull()
        val contentRating =
            document.select(".half-tags li span:contains(التصنيف العمرى)").firstOrNull()
                ?.parent()?.selectFirst("a")?.text()?.trim()

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            val seasons = document.select("section.allseasonss .Small--Box a")

            if (seasons.isNotEmpty()) {
                seasons.amap { seasonLink ->
                    val seasonUrl = seasonLink.attr("href")
                    val seasonDoc = if (seasonUrl == url) document else app.get(seasonUrl).document
                    val seasonNumText =
                        seasonLink.selectFirst(".epnum span")?.nextSibling()?.toString()?.trim()
                    val seasonNum = seasonNumText?.toIntOrNull()

                    seasonDoc.select("section.allepcont .row a").map { ep ->
                        newEpisode(ep.attr("href")) {
                            this.name = ep.selectFirst(".ep-info h2")?.text()
                            episode =
                                ep.selectFirst(".epnum")?.ownText()?.trim()?.toIntOrNull()
                            season = seasonNum
                            posterUrl = poster
                        }
                    }
                }.flatten().toCollection(episodes)
            } else {
                document.select("section.allepcont .row a").mapTo(episodes) { ep ->
                    newEpisode(ep.attr("href")) {
                        this.name = ep.selectFirst(".ep-info h2")?.text()
                        this.episode = ep.selectFirst(".epnum")?.ownText()?.trim()?.toIntOrNull()
                        this.posterUrl = poster
                    }
                }
            }

            return newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes.distinctBy { it.data }
                    .sortedWith(compareBy({ it.season }, { it.episode }))
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.contentRating = contentRating
            }
        } else {
            return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = url.trimEnd('/') + "/watch/"
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.contentRating = contentRating
            }
        }
    }

    // ---------------------------------------------------------------------
    //  استخراج الروابط (يدعم الموقع الجديد المبني على Inertia + الطريقة القديمة)
    // ---------------------------------------------------------------------

    private class Ctx(
        val subtitleCallback: (SubtitleFile) -> Unit,
        val callback: (ExtractorLink) -> Unit
    ) {
        val visited = mutableSetOf<String>()
        var found = 0
    }

    private val badExt = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".ico",
        ".css", ".js", ".woff", ".woff2", ".ttf", ".json", ".xml"
    )
    private val badHosts = listOf(
        "facebook.com", "twitter.com", "x.com", "instagram.com", "telegram.me", "t.me",
        "whatsapp.com", "schema.org", "w3.org", "google-analytics", "googletagmanager",
        "gstatic.com", "googleapis.com", "fonts.", "cloudflare.com", "wp.com"
    )

    private fun isSameSite(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore("/").lowercase()
        return host.contains("cimacub") || host.contains("cimaclub")
    }

    private fun normalize(raw: String): String? {
        var u = raw.trim().replace("\\/", "/")
        if (u.startsWith("//")) u = "https:$u"
        if (!u.startsWith("http")) return null
        val lower = u.substringBefore("?").lowercase()
        if (badExt.any { lower.endsWith(it) }) return null
        if (badHosts.any { u.lowercase().substringAfter("://").substringBefore("/").contains(it) }) return null
        return u
    }

    /** يمر على كل قيم الـ JSON ويجمع أي رابط أو iframe */
    private fun collectUrls(node: Any?, out: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) collectUrls(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectUrls(node.opt(i), out)
            is String -> {
                val s = node.trim()
                if (s.contains("<iframe", true) || s.contains("<a ", true)) {
                    val d = Jsoup.parse(s)
                    d.select("iframe[src], iframe[data-src]").forEach {
                        normalize(it.attr("src").ifBlank { it.attr("data-src") })?.let(out::add)
                    }
                } else if (s.startsWith("http") || s.startsWith("//")) {
                    normalize(s)?.let(out::add)
                }
            }
        }
    }

    private suspend fun getPage(url: String, referer: String): Pair<Document?, JSONObject?> {
        val res = app.get(url, headers = mapOf("Referer" to referer))
        val doc = res.document
        val raw = doc.selectFirst("[data-page]")?.attr("data-page")
        if (!raw.isNullOrBlank()) {
            try {
                return doc to JSONObject(raw)
            } catch (_: Exception) {
            }
        }
        // محاولة طلب Inertia مباشرة (يرجع JSON)
        return try {
            val xhr = app.get(
                url,
                headers = mapOf(
                    "Referer" to referer,
                    "X-Inertia" to "true",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "text/html, application/xhtml+xml"
                )
            )
            doc to JSONObject(xhr.text)
        } catch (_: Exception) {
            doc to null
        }
    }

    private suspend fun handleLink(link: String, referer: String, depth: Int, ctx: Ctx) {
        if (isSameSite(link)) {
            val l = link.lowercase()
            if (depth < 2 && (l.contains("embed") || l.contains("player") || l.contains("video") || l.contains("watch"))) {
                processPage(link, referer, depth + 1, ctx)
            }
            return
        }
        if (!ctx.visited.add(link)) return
        Log.d("CimaClub", "Found link: $link")
        loadExtractor(link, referer, ctx.subtitleCallback) {
            ctx.found++
            ctx.callback(it)
        }
    }

    private suspend fun processPage(url: String, referer: String, depth: Int, ctx: Ctx) {
        if (!ctx.visited.add(url)) return
        val (doc, json) = try {
            getPage(url, referer)
        } catch (e: Exception) {
            Log.d("CimaClub", "Failed page $url : ${e.message}")
            return
        }

        val links = linkedSetOf<String>()

        // الطريقة الجديدة: بيانات Inertia
        if (json != null) collectUrls(json, links)

        // الطريقة القديمة وعناصر HTML العادية
        doc?.select("ul#watch li[data-watch]")?.forEach { normalize(it.attr("data-watch"))?.let(links::add) }
        doc?.select("iframe[src], iframe[data-src]")?.forEach {
            normalize(it.attr("src").ifBlank { it.attr("data-src") })?.let(links::add)
        }
        doc?.select(".ServersList.Download a[href]")?.forEach { normalize(it.attr("href"))?.let(links::add) }

        links.forEach { handleLink(it, url, depth, ctx) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ctx = Ctx(subtitleCallback, callback)

        val base = data.trimEnd('/').removeSuffix("/watch")
        val candidates = listOf(data, "$base/watch/", "$base/").distinct()

        for (c in candidates) {
            processPage(c, data, 0, ctx)
            if (ctx.found > 0) break
        }

        // احتياطي: الطريقة القديمة (POST)
        if (ctx.found == 0) {
            try {
                val doc = app.post(
                    data,
                    data = mapOf("watch" to "1"),
                    headers = mapOf("Referer" to data)
                ).document
                doc.select("ul#watch li").forEach {
                    val embedUrl = it.attr("data-watch")
                    if (embedUrl.isNotBlank()) {
                        loadExtractor(embedUrl, data, subtitleCallback) {
                            ctx.found++
                            callback(it)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        return ctx.found > 0
    }
}

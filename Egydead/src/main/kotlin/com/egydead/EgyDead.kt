package com.egydead

import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.Episode as CS3Episode
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EgyDead : MainAPI() {
    override var mainUrl = "https://tv10.egydead.live/"
    override var name = "ايجي ديد"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    private var savedCookies: String? = null
    private val cfMutex = Mutex()

    private fun log(tag: String, msg: String) {
        println("EgyDeadDebug | [$tag] -> $msg")
    }

    private val imageHeaders: Map<String, String>
        get() {
            val headers = mutableMapOf(
                "User-Agent" to userAgent,
                "Referer" to "$mainUrl/"
            )
            savedCookies?.let { headers["Cookie"] = it }
            return headers
        }
    private fun buildHeaders(referer: String?): MutableMap<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "Referer" to (referer ?: mainUrl),
            "Accept-Language" to "ar,en-US;q=0.9",
            "Upgrade-Insecure-Requests" to "1"
        )
        savedCookies?.let { headers["Cookie"] = it }
        return headers
    }


    private suspend fun httpGet(url: String, referer: String? = null): Document {
        var currentRequestUrl = url
        log("GET-REQUEST", "Fetching: $currentRequestUrl")
        var headers = buildHeaders(referer)

        var res = app.get(currentRequestUrl, headers = headers, timeout = 30)
        if (res.code in listOf(403, 503, 429)) {
            cfMutex.withLock {
                val currentCookies = android.webkit.CookieManager.getInstance().getCookie(currentRequestUrl)
                if (currentCookies != null && currentCookies != savedCookies && currentCookies.contains("cf_clearance")) {
                    log("GET-REQUEST", "Cloudflare already solved by another thread.")
                    savedCookies = currentCookies
                } else {
                    log("GET-REQUEST", "Cloudflare detected (Code: ${res.code}). Running Cookie Hunter...")
                    val activity = CommonActivity.activity ?: com.lagradost.cloudstream3.CommonActivity.activity

                    if (activity != null) {
                        val solverResult = CloudflareSolver.solve(activity, currentRequestUrl, userAgent)

                        if (solverResult != null) {
                            if (!solverResult.cookies.isNullOrEmpty()) {
                                savedCookies = solverResult.cookies
                                log("GET-REQUEST", "تم حفظ الكوكيز في الإضافة بنجاح.")
                            }
                            if (solverResult.finalUrl != currentRequestUrl) {
                                log("DOMAIN-UPDATE", "تم اكتشاف توجيه من $currentRequestUrl إلى ${solverResult.finalUrl}")
                                try {
                                    val newHost = java.net.URL(solverResult.finalUrl).host
                                    mainUrl = "https://$newHost"
                                    log("DOMAIN-UPDATE", "تم تحديث mainUrl ليصبح: $mainUrl")
                                } catch (e: Exception) {}
                                currentRequestUrl = solverResult.finalUrl
                            }
                        }
                    }
                }
            }
            log("GET-REQUEST", "إعادة الطلب (Retry) بالكوكيز الجديدة للرابط: $currentRequestUrl")
            headers = buildHeaders(referer)
            res = app.get(currentRequestUrl, headers = headers, timeout = 30)
        }

        return res.document
    }
    private suspend fun httpPost(url: String, data: Map<String, String>, referer: String? = null): Document {
        var currentRequestUrl = url
        log("POST-REQUEST", "Sending to: $currentRequestUrl")
        var headers = buildHeaders(referer).apply {
            put("X-Requested-With", "XMLHttpRequest")
            put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }

        var res = app.post(currentRequestUrl, data = data, headers = headers, timeout = 30)

        if (res.code in listOf(403, 503, 429)) {
            cfMutex.withLock {
                val currentCookies = android.webkit.CookieManager.getInstance().getCookie(currentRequestUrl)
                if (currentCookies != null && currentCookies != savedCookies && currentCookies.contains("cf_clearance")) {
                    savedCookies = currentCookies
                } else {
                    log("POST-REQUEST", "Cloudflare detected (Code: ${res.code}). Running Cookie Hunter...")
                    val activity = CommonActivity.activity ?: com.lagradost.cloudstream3.CommonActivity.activity
                    if (activity != null) {
                        val solverResult = CloudflareSolver.solve(activity, currentRequestUrl, userAgent)
                        if (solverResult != null) {
                            if (!solverResult.cookies.isNullOrEmpty()) {
                                savedCookies = solverResult.cookies
                            }
                            if (solverResult.finalUrl != currentRequestUrl) {
                                try {
                                    val newHost = java.net.URL(solverResult.finalUrl).host
                                    mainUrl = "https://$newHost"
                                } catch (e: Exception) {}
                                currentRequestUrl = solverResult.finalUrl
                            }
                        }
                    }
                }
            }

            headers = buildHeaders(referer).apply {
                put("X-Requested-With", "XMLHttpRequest")
                put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
            res = app.post(currentRequestUrl, data = data, headers = headers, timeout = 30)
        }

        return res.document
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val logTag = "MAIN-PAGE"
        log(logTag, "Starting getMainPage | Page: $page | Request: ${request.name}")

        val document = try {
            log(logTag, "Attempting to fetch HTML from: $mainUrl")
            httpGet(mainUrl)
        } catch (e: Exception) {
            log(logTag, "CRITICAL ERROR: Failed to fetch main page -> ${e.message}")
            return newHomePageResponse(emptyList())
        }

        val homePageList = ArrayList<HomePageList>()

        log(logTag, "Parsing Pinned Section (div.pin-posts-list)...")
        val pinnedSection = document.selectFirst("div.pin-posts-list")
        if (pinnedSection != null) {
            val sectionTitle = pinnedSection.selectFirst("h1.TitleMaster em")?.text()?.trim() ?: "المميز"
            log(logTag, "Pinned Section found! Title: '$sectionTitle'")

            val items = pinnedSection.select("li.movieItem").mapNotNull {
                it.toSearchResponse("PINNED")
            }

            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, items, isHorizontalImages = true))
            }
        }

        log(logTag, "Parsing Main Sections (section.main-section)...")
        val mainSections = document.select("section.main-section")

        mainSections.forEachIndexed { index, section ->
            val sectionTitle = section.selectFirst("h1.TitleMaster em")?.text()?.trim() ?: "قسم ${index + 1}"
            val items = section.select("li.movieItem").mapNotNull {
                it.toSearchResponse("SECTION-$index")
            }

            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, items))
            }
        }

        return newHomePageResponse(homePageList.filter { it.list.isNotEmpty() })
    }
    private fun Element.toSearchResponse(parentTag: String): SearchResponse? {
        try {
            val linkEl = this.selectFirst("a") ?: return null
            val href = linkEl.attr("href")
            val fullUrl = fixUrlNull(href) ?: return null
            val title = this.selectFirst("h1.BottomTitle")?.text()?.trim() ?: return null
            val posterUrl = this.selectFirst("img")?.attr("src")

            return newMovieSearchResponse(title, fullUrl) {
                this.posterUrl = posterUrl
                this.posterHeaders = imageHeaders
            }
        } catch (e: Exception) {
            log("PARSER-$parentTag", "CRITICAL Item Error: ${e.message}")
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = httpGet(url)
        return document.select("ul.posts-list li.movieItem").mapNotNull {
            it.toSearchResponse("SEARCH")
        }
    }

    private val seasonNumRegex = Regex(
        """(?ix)(?:الموسم[\s:\-_.]*0*(\d+))|(?:S(?:eason)?[\s:\-_.]*0*(\d+))"""
    )

    private val episodeNumRegex = Regex(
        """(?ix)(?:حلقة[\s:\-_.]*0*(\d+))|(?:Episode[\s:\-_.]*0*(\d+))|(?:EP[\s:\-_.]*0*(\d+))|(?:\d+[xX]0*(\d+))|(?:S(?:eason)?[\s:\-_.]*\d+[\s\-_.,]*E(?:p(?:isode)?)?[\s:\-_.]*0*(\d+))"""
    )

    private fun getSeasonNum(title: String?): Int {
        if (title == null) return 9999
        val match = seasonNumRegex.find(title) ?: return 9999
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 9999
    }

    private fun getEpisodeNum(title: String?): Int {
        if (title == null) return 9999
        val match = episodeNumRegex.find(title) ?: return 9999
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 9999
    }

    private fun normalizeUrl(link: String?, base: String): String? {
        if (link.isNullOrBlank()) return null
        val t = link.trim()
        if (t.startsWith("#") || t.lowercase().startsWith("javascript:")) return null
        return try {
            val resolved = if (t.startsWith("http")) t else URL(URL(base), t).toString()
            fixUrl(resolved)
        } catch (e: Exception) { null }
    }
    private suspend fun batchFetch(
        urls: List<String>,
        concurrency: Int = 8
    ): Map<String, Document?> {
        val sem = Semaphore(concurrency)
        val out = mutableMapOf<String, Document?>()
        coroutineScope {
            val jobs = urls.map { u ->
                async {
                    sem.withPermit {
                        try {
                            val res = httpGet(u)
                            out[u] = res
                        } catch (e: Exception) {
                            out[u] = null
                        }
                    }
                }
            }
            jobs.awaitAll()
        }
        return out
    }
    private suspend fun discoverSeasonsPreserveOrder(
        startUrl: String,
        concurrency: Int = 8
    ): List<Triple<Int, String, String>> {
        val discovered = mutableListOf<Triple<Int, String, String>>()
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(startUrl); seen.add(startUrl)
        var nextIndex = 0

        while (queue.isNotEmpty()) {
            val batch = mutableListOf<String>()
            repeat(minOf(queue.size, concurrency)) { batch.add(queue.poll()) }
            if (batch.isEmpty()) break

            val docs = batchFetch(batch, concurrency)
            for (u in batch) {
                val doc = docs[u] ?: continue
                val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: "موسم غير معروف"
                discovered.add(Triple(nextIndex++, title, u))

                val seasonsCont = doc.selectFirst("div.seasons-list") ?: doc.selectFirst("div.seasons")
                seasonsCont?.select("li.movieItem a, a")?.forEach { a ->
                    val href = normalizeUrl(a.attr("href"), u) ?: return@forEach
                    if (href !in seen && href.contains("/season/")) {
                        seen.add(href)
                        queue.add(href)
                    }
                }
            }
        }
        return discovered.distinctBy { it.third }
    }
    private fun extractEpisodesFromSeasonDoc(seasonUrl: String, doc: Document): List<CS3Episode> {
        val episodes = mutableListOf<CS3Episode>()
        val epsContainer = doc.selectFirst("div.EpsList") ?: doc.selectFirst("div.episodes-list") ?: doc.selectFirst("ul") ?: return emptyList()

        val items = epsContainer.select("li, a")
        for (el in items) {
            val a: Element = if (el.tagName() == "a") el else el.selectFirst("a") ?: continue
            val rawTitle = (a.attr("title").takeIf { it.isNotBlank() } ?: a.text()).trim()
            val href = normalizeUrl(a.attr("href"), seasonUrl) ?: continue

            if (href.contains("/season/")) continue
            if (href.contains("/film/")) continue

            val epNum = getEpisodeNum(rawTitle)
            val ep: CS3Episode = newEpisode(href) {
                this.name = rawTitle
                this.episode = if (epNum != 9999) epNum else null
                this.data = href
            }
            episodes.add(ep)
        }
        return episodes.sortedBy { it.episode ?: 9999 }
    }
    private fun parseRecommendations(doc: Document, base: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val nodes = doc.select(".related-posts li.movieItem, .related-posts a, .related-posts li")
        for (li in nodes) {
            val a = li.selectFirst("a") ?: continue
            val href = normalizeUrl(a.attr("href"), base) ?: continue
            val title = a.selectFirst("h1, span, .title")?.text() ?: a.attr("title").takeIf { it.isNotBlank() } ?: a.text()
            val poster = a.selectFirst("img")?.attr("src")
            val sr = when {
                href.contains("/film/") -> newMovieSearchResponse(title, href) {
                    this.posterUrl = poster
                    this.posterHeaders = imageHeaders
                }
                href.contains("/season/") || href.contains("/series/") || href.contains("/show/") || href.contains("/serie/") || href.contains("/assembly/") -> newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = poster
                    this.posterHeaders = imageHeaders
                }
                else -> null
            }
            sr?.let { out.add(it) }
        }
        return out
    }
    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            httpGet(url)
        } catch (e: Exception) {
            return null
        }

        val movieCollectionList = document.selectFirst("div.salery-list ul")
        if (movieCollectionList != null) {
            val seriesTitle = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: "Movie Collection"
            val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            val plot = document.selectFirst("div.singleStory")?.text()?.trim()

            val moviesAsEpisodes = movieCollectionList.select("li.movieItem").mapIndexedNotNull { index, item ->
                val a = item.selectFirst("a") ?: return@mapIndexedNotNull null
                val href = normalizeUrl(a.attr("href"), url) ?: return@mapIndexedNotNull null
                if (!href.contains("/film/")) return@mapIndexedNotNull null

                val movieTitle = item.selectFirst("h1.BottomTitle")?.text() ?: "Movie ${index + 1}"
                val moviePoster = item.selectFirst("img")?.attr("src")

                newEpisode(href) {
                    this.name = movieTitle
                    this.posterUrl = moviePoster
                    this.season = 1
                    this.episode = index + 1
                    this.data = href
                }
            }

            if (moviesAsEpisodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, moviesAsEpisodes) {
                    this.posterUrl = poster
                    this.posterHeaders = imageHeaders
                    this.plot = plot
                    this.recommendations = parseRecommendations(document, url)
                }
            }
        }

        if (url.contains("/film/")) {
            val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: return null
            val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            val plot = document.selectFirst("div.singleStory")?.text()?.trim()
            val year = document.select("div.LeftBox li:has(span:contains(السنه)) a").text().toIntOrNull()
            val tags = document.select("div.LeftBox li:has(span:contains(النوع)) a").map { it.text() }
            val recommendations = parseRecommendations(document, url)

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = imageHeaders
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        }

        val isEpisode = url.contains("/episode/")
        val isSeason = url.contains("/season/")
        val isSeriesPage = url.contains("/serie/")
        val hasSeasonsList = document.selectFirst("div.seasons-list") != null

        var startSeasonUrl: String? = null

        if (isEpisode) {
            val bc = document.selectFirst("div.breadcrumbs-single, div.breadcrumbs")
            bc?.select("a")?.forEach { a ->
                val href = a.attr("href")
                if (href.contains("/season/") || href.contains("/serie/")) {
                    startSeasonUrl = normalizeUrl(href, url)
                }
            }
            if (startSeasonUrl == null) {
                val linkInPage = document.selectFirst("div.seasons-list li.movieItem a, div.seasons-list a")?.attr("href")
                startSeasonUrl = normalizeUrl(linkInPage, url)
            }
        } else if (isSeason) {
            startSeasonUrl = url
        } else {
            val maybeEps = document.selectFirst("div.EpsList, div.episodes-list, ul.episodes")
            if (isSeriesPage || hasSeasonsList) {
                startSeasonUrl = url
            } else if (maybeEps != null) {
                val eps = extractEpisodesFromSeasonDoc(url, document)
                val pageImage = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { normalizeUrl(it, url) }
                val epsWithImage = eps.map { ep ->
                    if (pageImage != null) { try { ep.posterUrl = pageImage } catch (_: Exception) {} }
                    ep
                }
                val seriesTitle = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: "TV Series"
                val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
                val plot = document.selectFirst("div.singleStory")?.text()?.trim()

                return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, epsWithImage) {
                    this.posterUrl = poster
                    this.posterHeaders = imageHeaders
                    this.plot = plot
                }
            } else {
                val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: return null
                val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
                val plot = document.selectFirst("div.singleStory")?.text()?.trim()
                val recommendations = parseRecommendations(document, url)
                return newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.posterHeaders = imageHeaders
                    this.plot = plot
                    this.recommendations = recommendations
                }
            }
        }

        if (startSeasonUrl == null) return null

        val startDoc = try { httpGet(startSeasonUrl!!) } catch (e: Exception) { return null }

        val seasonAnchors = startDoc.select("div.seasons-list li.movieItem a, div.seasons-list a, div.seasons-list ul li a")
        val candidateSeasonUrls = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (a in seasonAnchors) {
            val raw = a.attr("href")
            val href = normalizeUrl(raw, startSeasonUrl!!)
            if (href != null && href !in seen) {
                seen.add(href)
                candidateSeasonUrls.add(href)
            }
        }

        if (candidateSeasonUrls.isNotEmpty()) {
            candidateSeasonUrls.reverse()
            val fetched = batchFetch(candidateSeasonUrls, concurrency = 8)
            val seasonsResults = mutableListOf<Pair<Int, List<CS3Episode>>>()

            for ((idx, sUrl) in candidateSeasonUrls.withIndex()) {
                val doc = fetched[sUrl]
                val seasonTitle = doc?.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                val titleForSeason = seasonTitle ?: "Season ${idx + 1}"
                val rawImg = doc?.selectFirst("meta[property=og:image]")?.attr("content")
                val seasonImage = rawImg?.let { normalizeUrl(it, sUrl) }

                val eps = if (doc != null) extractEpisodesFromSeasonDoc(sUrl, doc) else emptyList()
                val seasonNumber = getSeasonNum(titleForSeason).takeIf { it != 9999 } ?: (idx + 1)

                val epsWithSeason = eps.mapIndexed { epIdx, ep ->
                    ep.season = seasonNumber
                    ep.episode = ep.episode ?: (epIdx + 1)
                    if (seasonImage != null) { try { ep.posterUrl = seasonImage } catch (_: Exception) {} }
                    ep
                }
                seasonsResults.add(Pair(idx, epsWithSeason))
            }

            val allEpisodes = seasonsResults.flatMap { it.second }
            val seriesTitle = startDoc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.replace(Regex("""\s*(الموسم|الحلقة)\s+.*"""), "")?.trim() ?: "TV Series"
            val poster = startDoc.selectFirst("meta[property=og:image]")?.attr("content")
            val plot = startDoc.selectFirst("div.singleStory")?.text()?.trim()

            return newTvSeriesLoadResponse(seriesTitle, startSeasonUrl!!, TvType.TvSeries, allEpisodes) {
                this.posterUrl = poster
                this.posterHeaders = imageHeaders
                this.plot = plot
            }
        }

        val fallbackEpisodes = extractEpisodesFromSeasonDoc(startSeasonUrl!!, startDoc)
        val seriesTitleFallback = startDoc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: "TV Series"
        val posterFallback = startDoc.selectFirst("meta[property=og:image]")?.attr("content")?.let { normalizeUrl(it, startSeasonUrl!!) }
        val plotFallback = startDoc.selectFirst("div.singleStory")?.text()?.trim()

        val epsFixed = fallbackEpisodes.mapIndexed { idx, ep ->
            ep.season = ep.season ?: 1
            ep.episode = ep.episode ?: (idx + 1)
            if (posterFallback != null) { try { ep.posterUrl = posterFallback } catch (_: Exception) {} }
            ep
        }

        return newTvSeriesLoadResponse(seriesTitleFallback, startSeasonUrl!!, TvType.TvSeries, epsFixed) {
            this.posterUrl = posterFallback
            this.posterHeaders = imageHeaders
            this.plot = plotFallback
        }
    }

    // =====================================================================
    //  loadLinks (مُعاد كتابتها): محاولات متعددة لجلب صفحة السيرفرات
    //  + استخراج عام للروابط عند فشل الـ extractors المعروفة
    // =====================================================================

    private val junkHostsExact = listOf(
        "facebook.com", "twitter.com", "x.com", "t.me", "instagram.com",
        "youtube.com", "youtu.be", "whatsapp.com", "api.whatsapp.com",
        "google.com", "gstatic.com", "schema.org", "w.org", "wp.com", "c4u1r.sbs"
    )

    private fun hostOf(u: String): String =
        u.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()

    private fun isJunkLink(u: String): Boolean {
        val h = hostOf(u)
        if (h.contains("egydead")) return true
        return junkHostsExact.any { h == it || h.endsWith(".$it") }
    }

    private val mediaRegex = Regex(
        """https?:[^"'\s\\<>()]+?\.(?:m3u8|mp4)(?:\?[^"'\s\\<>()]*)?""",
        RegexOption.IGNORE_CASE
    )

    /** يفتح صفحة المشغّل ويبحث عن m3u8/mp4 (مع فك تشفير p.a.c.k.e.d) */
    private suspend fun genericExtract(
        url: String,
        referer: String,
        serverName: String?,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var n = 0
        try {
            val text = app.get(
                url,
                headers = mapOf("User-Agent" to userAgent, "Referer" to referer),
                timeout = 30
            ).text
            var scan = text.replace("\\/", "/")
            try {
                val unpacked = getAndUnpack(text)
                if (!unpacked.isNullOrBlank()) scan += "\n" + unpacked.replace("\\/", "/")
            } catch (_: Throwable) {}

            val seen = mutableSetOf<String>()
            for (m in mediaRegex.findAll(scan)) {
                val link = m.value
                if (!seen.add(link)) continue
                val isM3u8 = link.lowercase().contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        source = this@EgyDead.name,
                        name = "${serverName ?: hostOf(url)} (Auto)",
                        url = link,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                    }
                )
                n++
            }
        } catch (e: Exception) {
            log("GENERIC", "failed for $url : ${e.message}")
        }
        return n
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tag = "LOAD-LINKS"
        try {
            val originalUrl = data
            val baseUrl = data.substringBefore("?")
            val watchPageUrl = "$baseUrl?view=watch"

            val found = AtomicInteger(0)
            val allSeenLinks = java.util.Collections.synchronizedSet(mutableSetOf<String>())

            data class Cand(val url: String, val name: String?, val isServer: Boolean)

            fun collect(document: Document, base: String): List<Cand> {
                val out = mutableListOf<Cand>()
                fun add(raw: String?, name: String?, server: Boolean) {
                    val n = normalizeUrl(raw, base) ?: return
                    if (!n.startsWith("http")) return
                    if (isJunkLink(n)) return
                    out += Cand(n, name?.takeIf { it.isNotBlank() }, server)
                }

                // قوائم السيرفرات (الأسماء القديمة + احتمالات جديدة)
                val listSelectors = listOf(
                    "ul.donwload-servers-list li", "ul.download-servers-list li",
                    "div.donwload-servers-list li", "ul.serversList li", "ul.servers-list li",
                    "div.serversList li", "div.servers-list li", "ul[class*=server] li",
                    "div[class*=server] li", "ul[class*=donwload] li", "ul[class*=download] li"
                )
                for (sel in listSelectors) {
                    for (li in document.select(sel)) {
                        val name = li.selectFirst("span.ser-name")?.text()
                            ?: li.selectFirst(".ser-name")?.text()
                            ?: li.selectFirst("p")?.text()
                            ?: li.attr("data-name")
                        val raw = li.attr("data-link").ifBlank {
                            li.selectFirst("[data-link]")?.attr("data-link")
                                ?: li.selectFirst("a.ser-link")?.attr("href")
                                ?: li.selectFirst("a")?.attr("href")
                                ?: ""
                        }
                        add(raw, name?.trim(), true)
                    }
                }

                // أي عنصر يحمل رابط سيرفر
                for (attr in listOf("data-link", "data-url", "data-src", "data-embed", "data-watch", "data-href")) {
                    for (el in document.select("[$attr]")) {
                        val name = el.attr("data-name").ifBlank { el.attr("data-provider") }
                        add(el.attr(attr), name, true)
                    }
                }

                for (f in document.select("iframe[src], iframe[data-src]")) {
                    add(f.attr("src").ifBlank { f.attr("data-src") }, null, true)
                }

                // روابط خارجية عادية (غالباً تحميل)
                for (a in document.select("a[href]")) {
                    val href = a.attr("href")
                    if (href.startsWith("http") && !hostOf(href).contains("egydead")) {
                        add(href, a.attr("title").ifBlank { a.text() }, false)
                    }
                }
                return out.distinctBy { it.url }
            }

            val semaphore = Semaphore(8)

            suspend fun processCandidates(cands: List<Cand>) {
                coroutineScope {
                    cands.map { c ->
                        async {
                            semaphore.withPermit {
                                if (!allSeenLinks.add(c.url)) return@withPermit
                                val local = AtomicInteger(0)
                                try {
                                    loadExtractor(c.url, data, subtitleCallback) {
                                        local.incrementAndGet()
                                        found.incrementAndGet()
                                        callback(it)
                                    }
                                } catch (_: Exception) {}

                                val nm = c.name
                                if (nm != null && (nm.equals("EarnVids", true) || nm.equals("StreamHG", true))) {
                                    try {
                                        val customLink: String? = withContext(Dispatchers.IO) {
                                            ExternalEarnVidsExtractor.extract(c.url, this@EgyDead.mainUrl)
                                        }
                                        if (!customLink.isNullOrBlank()) {
                                            callback.invoke(
                                                newExtractorLink(
                                                    source = this@EgyDead.name,
                                                    name = "$nm (Custom)",
                                                    url = customLink.toString(),
                                                    type = ExtractorLinkType.M3U8
                                                ) {
                                                    this.referer = this@EgyDead.mainUrl
                                                }
                                            )
                                            local.incrementAndGet()
                                            found.incrementAndGet()
                                        }
                                    } catch (_: Exception) {}
                                }

                                // لم تتعرف أي extractor على الرابط -> محاولة عامة
                                if (local.get() == 0 && c.isServer) {
                                    val n = genericExtract(c.url, data, c.name, callback)
                                    if (n > 0) found.addAndGet(n)
                                }
                            }
                        }
                    }.awaitAll()
                }
            }

            // الصفحة الأصلية (تفيدنا لقراءة الفورم + كاحتياط)
            val pageDoc = try { httpGet(originalUrl, referer = originalUrl) } catch (e: Exception) { null }

            // محاولات جلب صفحة السيرفرات بالترتيب
            val attempts: List<suspend () -> Pair<Document?, String>> = listOf(
                { Pair(try { httpPost(watchPageUrl, mapOf("View" to "1"), originalUrl) } catch (e: Exception) { null }, watchPageUrl) },
                { Pair(try { httpPost(baseUrl, mapOf("View" to "1"), originalUrl) } catch (e: Exception) { null }, baseUrl) },
                {
                    // استخدم الفورم الحقيقي الموجود في الصفحة (اسم الحقل/الـ action)
                    val form = pageDoc?.selectFirst("form:has(input[name~=(?i)view]), form:has(button[name~=(?i)view])")
                    if (form != null) {
                        val action = normalizeUrl(form.attr("action").ifBlank { baseUrl }, baseUrl) ?: baseUrl
                        val fields = mutableMapOf<String, String>()
                        form.select("input[name]").forEach { fields[it.attr("name")] = it.attr("value") }
                        form.select("button[name]").forEach { fields[it.attr("name")] = it.attr("value").ifBlank { "1" } }
                        if (fields.isEmpty()) fields["View"] = "1"
                        Pair(try { httpPost(action, fields, originalUrl) } catch (e: Exception) { null }, action)
                    } else Pair(null, baseUrl)
                },
                { Pair(try { httpGet(watchPageUrl, referer = originalUrl) } catch (e: Exception) { null }, watchPageUrl) },
                { Pair(pageDoc, originalUrl) }
            )

            for ((i, attempt) in attempts.withIndex()) {
                val (doc, base) = attempt()
                if (doc == null) {
                    log(tag, "attempt #$i: no document")
                    continue
                }
                val cands = collect(doc, base)
                log(tag, "attempt #$i: ${cands.size} candidates (html=${doc.html().length})")
                if (cands.isEmpty()) continue
                processCandidates(cands)
                if (found.get() > 0) break
            }

            log(tag, "total links loaded: ${found.get()}")
            return found.get() > 0
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}

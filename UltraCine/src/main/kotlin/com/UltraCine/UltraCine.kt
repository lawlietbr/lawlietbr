package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/lancamentos/" to "Lançamentos",
        "$mainUrl/category/acao/" to "Ação",
        "$mainUrl/category/animacao/" to "Animação",
        "$mainUrl/category/comedia/" to "Comédia",
        "$mainUrl/category/crime/" to "Crime",
        "$mainUrl/category/documentario/" to "Documentário",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/familia/" to "Família",
        "$mainUrl/category/fantasia/" to "Fantasia",
        "$mainUrl/category/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/category/guerra/" to "Guerra",
        "$mainUrl/category/kids/" to "Kids",
        "$mainUrl/category/misterio/" to "Misterio",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "page/$page/" else ""
        val document = app.get(url).document
        val home = document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("header.entry-header h2.entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        
        val posterUrl = selectFirst("div.post-thumbnail img")?.attr("src")
            ?: selectFirst("img[data-src]")?.attr("data-src")
            ?: selectFirst("img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { 
                var url = fixUrl(it)
                url = url.replace("/w500/", "/original/")
                url = url.replace("/w300/", "/original/")
                url
            }

        val year = selectFirst("span.year")?.text()?.toIntOrNull()
        val isSerie = href.contains("/serie/")
        val type = if (isSerie) TvType.TvSeries else TvType.Movie

        return if (isSerie) {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = getQualityFromString(selectFirst("span.post-ql")?.text())
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("aside.fg1 header.entry-header h1.entry-title")?.text() ?: return null
        
        val poster = document.selectFirst("div.bghd img.TPostBg")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it).replace("/w1280/", "/original/") }
            ?: document.selectFirst("div.bghd img.TPostBg")?.attr("data-src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it).replace("/w1280/", "/original/") }

        val yearText = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.year")?.ownText()
        val year = yearText?.toIntOrNull()
        val durationText = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.duration")?.ownText()
        val plot = document.selectFirst("aside.fg1 div.description p")?.text()
        val tags = document.select("aside.fg1 header.entry-header div.entry-meta span.genres a").map { it.text() }
        val actors = document.select("aside.fg1 ul.cast-lst p a").map {
            Actor(it.text(), it.attr("href"))
        }
        val trailer = document.selectFirst("div.mdl-cn div.video iframe")?.attr("src")
            ?.takeIf { it.isNotBlank() } ?: document.selectFirst("div.mdl-cn div.video iframe")?.attr("data-src")

        val iframeUrl = document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("src")
            ?.takeIf { it.isNotBlank() } ?: document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("data-src")

        val isSerie = url.contains("/serie/")

        return if (isSerie) {
            val episodes = parseSeriesEpisodesFromPage(document, url)
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = null
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl ?: "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = parseDuration(durationText)
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun parseSeriesEpisodesFromPage(doc: org.jsoup.nodes.Document, seriesUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        doc.select("div.season, section.season, .season-cn, .temporada").forEachIndexed { seasonIndex, seasonDiv ->
            val seasonNum = seasonIndex + 1
            
            seasonDiv.select("a[href*='assistirseriesonline']").forEachIndexed { epIndex, episodeEl ->
                val href = episodeEl.attr("href")
                if (href.isNotBlank()) {
                    val ids = extractIdsFromEpisodeUrl(href)
                    if (ids.isNotEmpty()) {
                        val epId = ids.first()
                        val title = episodeEl.text().takeIf { it.isNotBlank() } ?: "Episódio ${epIndex + 1}"
                        val epNum = extractEpisodeNumber(title) ?: (epIndex + 1)
                        
                        episodes.add(newEpisode(epId) {
                            this.name = cleanEpisodeTitle(title)
                            this.season = seasonNum
                            this.episode = epNum
                        })
                    }
                }
            }
        }
        
        if (episodes.isEmpty()) {
            var episodeCount = 1
            doc.select("a[href*='assistirseriesonline']").forEach { link ->
                val href = link.attr("href")
                val ids = extractIdsFromEpisodeUrl(href)
                
                if (ids.isNotEmpty()) {
                    val epId = ids.first()
                    val title = link.text().takeIf { it.isNotBlank() } ?: "Episódio $episodeCount"
                    val epNum = extractEpisodeNumber(title) ?: episodeCount
                    
                    episodes.add(newEpisode(epId) {
                        this.name = cleanEpisodeTitle(title)
                        this.season = 1
                        this.episode = epNum
                    })
                    
                    episodeCount++
                }
            }
        }
        
        return episodes.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private fun extractIdsFromEpisodeUrl(url: String): List<String> {
        val ids = mutableListOf<String>()
        
        val pattern1 = Regex("""/embed/(\d+)(?:#(\d+[_\d]*))?""")
        val match1 = pattern1.find(url)
        if (match1 != null) {
            ids.add(match1.groupValues[1])
            if (match1.groupValues[2].isNotBlank()) {
                ids.add(match1.groupValues[2])
            }
        }
        
        val pattern2 = Regex("""/episodio/(\d+)""")
        val match2 = pattern2.find(url)
        if (match2 != null) {
            ids.add(match2.groupValues[1])
        }
        
        return ids.distinct()
    }

    private fun extractEpisodeNumber(title: String): Int? {
        val patterns = listOf(
            Regex("""Epis[oó]dio\s+(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Ep\.?\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""E(\d+)""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(title)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        
        return null
    }

    private fun cleanEpisodeTitle(title: String): String {
        var cleaned = title.trim()
        val prefixes = listOf("Assistir", "Ver", "Watch", "Episódio", "Ep.", "E")
        
        for (prefix in prefixes) {
            if (cleaned.startsWith(prefix, ignoreCase = true)) {
                cleaned = cleaned.substring(prefix.length).trim()
            }
        }
        
        cleaned = cleaned.replaceFirst(Regex("""^\d+[\s\.\-]+"""), "").trim()
        return if (cleaned.isBlank()) "Episódio" else cleaned
    }

    private fun parseDuration(duration: String?): Int? {
        if (duration.isNullOrBlank()) return null
        val regex = Regex("""(\d+)h.*?(\d+)m""")
        val match = regex.find(duration)
        return if (match != null) {
            val h = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 0
            h * 60 + m
        } else {
            Regex("""(\d+)m""").find(duration)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        return try {
            val finalUrl = when {
                data.contains("#") && data.matches(Regex("""\d+#[\d_]+""")) -> {
                    val parts = data.split("#")
                    "https://assistirseriesonline.icu/embed/${parts[0]}#${parts[1]}"
                }
                data.matches(Regex("^\\d+$")) -> {
                    "https://assistirseriesonline.icu/episodio/$data"
                }
                data.contains("ultracine.org/") && data.matches(Regex(".*/\\d+$")) -> {
                    val id = data.substringAfterLast("/")
                    "https://assistirseriesonline.icu/episodio/$id"
                }
                else -> data
            }

            val res = app.get(finalUrl, referer = mainUrl, timeout = 30)
            val html = res.text
            
            val jwPlayerPattern = Regex("""<video[^>]+class=["'][^"']*jw[^"']*["'][^>]+src=["'](https?://[^"']+)["']""")
            val jwMatches = jwPlayerPattern.findAll(html).toList()
            
            if (jwMatches.isNotEmpty()) {
                for (match in jwMatches) {
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.isNotBlank() && 
                        (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) &&
                        !videoUrl.contains("banner") && 
                        !videoUrl.contains("ads")) {
                        
                        val quality = extractQualityFromUrl(videoUrl)
                        val isM3u8 = videoUrl.contains(".m3u8")
                        
                        val linkName = if (quality != Qualities.Unknown.value) {
                            "${this.name} (${quality}p)"
                        } else {
                            "${this.name} (Série)"
                        }
                        
                        val link = newExtractorLink(
                            source = this.name,
                            name = linkName,
                            url = videoUrl
                        )
                        callback.invoke(link)
                        return true
                    }
                }
            }
            
            val googlePattern = Regex("""https?://storage\.googleapis\.com/[^"'\s<>]+\.mp4[^"'\s<>]*""")
            val googleMatches = googlePattern.findAll(html).toList()
            
            if (googleMatches.isNotEmpty()) {
                for (match in googleMatches) {
                    val videoUrl = match.value
                    if (videoUrl.isNotBlank() && !videoUrl.contains("banner")) {
                        val link = newExtractorLink(
                            source = this.name,
                            name = "${this.name} (Google Storage)",
                            url = videoUrl
                        )
                        callback.invoke(link)
                        return true
                    }
                }
            }
            
            val mp4Pattern = Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)""")
            val mp4Matches = mp4Pattern.findAll(html).toList()
            
            if (mp4Matches.isNotEmpty()) {
                for (match in mp4Matches) {
                    val videoUrl = match.value
                    if (videoUrl.isNotBlank() && 
                        !videoUrl.contains("banner") && 
                        !videoUrl.contains("ads") &&
                        videoUrl.length > 30) {
                        
                        val link = newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = videoUrl
                        )
                        callback.invoke(link)
                        return true
                    }
                }
            }
            
            val doc = res.document
            
            val iframes = doc.select("iframe[src]")
            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    if (src.contains("embedplay") || src.contains("player") || 
                        src.contains("stream") || src.contains("video")) {
                        
                        if (loadExtractor(src, finalUrl, subtitleCallback, callback)) {
                            return true
                        }
                    }
                }
            }
            
            val buttons = doc.select("button[data-source], a[data-source]")
            for (button in buttons) {
                val source = button.attr("data-source")
                if (source.isNotBlank()) {
                    if (loadExtractor(source, finalUrl, subtitleCallback, callback)) {
                        return true
                    }
                }
            }
            
            if (finalUrl.contains("assistirseriesonline") || 
                finalUrl.contains("/episodio/") || 
                finalUrl.contains("/embed/") ||
                data.matches(Regex("^\\d+$")) ||
                data.contains("#")) {
                return true
            }
            
            false
            
        } catch (e: Exception) {
            e.printStackTrace()
            if (data.matches(Regex("^\\d+$")) || 
                data.contains("assistirseriesonline") || 
                data.contains("#") ||
                data.contains("/episodio/") ||
                data.contains("/embed/")) {
                return true
            }
            false
        }
    }

    private fun extractQualityFromUrl(url: String): Int {
        val qualityPattern = Regex("""/(\d+)p?/""")
        val match = qualityPattern.find(url)
        
        if (match != null) {
            val qualityNum = match.groupValues[1].toIntOrNull()
            return when (qualityNum) {
                360 -> 360
                480 -> 480
                720 -> 720
                1080 -> 1080
                2160 -> 2160
                else -> Qualities.Unknown.value
            }
        }
        
        return when {
            url.contains("360p", ignoreCase = true) -> 360
            url.contains("480p", ignoreCase = true) -> 480
            url.contains("720p", ignoreCase = true) -> 720
            url.contains("1080p", ignoreCase = true) -> 1080
            url.contains("2160p", ignoreCase = true) -> 2160
            else -> Qualities.Unknown.value
        }
    }
}
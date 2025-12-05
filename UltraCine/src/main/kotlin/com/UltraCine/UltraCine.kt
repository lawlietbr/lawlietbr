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
        "$mainUrl/category/misterio/" to "Mistério",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + if (page > 1) "page/$page/" else "").document
        val home = document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("header.entry-header h2.entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = selectFirst("div.post-thumbnail figure img")?.let { img ->
            val src = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src")
            src?.let { if (it.startsWith("//")) "https:$it" else it }?.replace("/w500/", "/original/")
        }
        val year = selectFirst("span.year")?.text()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("\( mainUrl/?s= \){query.urlEncode()}").document
        return document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("aside.fg1 header.entry-header h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("div.bghd img.TPostBg")?.let { img ->
            val src = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src")
            src?.let { if (it.startsWith("//")) "https:$it" else it }?.replace("/w1280/", "/original/")
        }
        val year = document.selectFirst("span.year")?.text()?.toIntOrNull()
        val duration = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.duration")?.text()
        val plot = document.selectFirst("aside.fg1 div.description p")?.text()
        val tags = document.select("aside.fg1 span.genres a").map { it.text() }

        val actors = document.select("aside.fg1 ul.cast-lst a").map {
            Actor(it.text(), it.attr("href"))
        }

        val trailerUrl = document.selectFirst("div.mdl-cn div.video iframe")?.attr("src")

        val iframeUrl = document.selectFirst("iframe[src*='assistirseriesonline'], iframe[data-src*='assistirseriesonline']")
            ?.let { it.attr("src").takeIf { s -> s.isNotBlank() } ?: it.attr("data-src") }

        val isSerie = url.contains("/serie/")

        return if (isSerie) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, iframeUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                if (actors.isNotEmpty()) addActors(actors)
                addTrailer(trailerUrl)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl ?: "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = parseDuration(duration)
                if (actors.isNotEmpty()) addActors(actors)
                addTrailer(trailerUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val url = when {
            data.matches(Regex("^\\d+$")) -> "https://assistirseriesonline.icu/episodio/$data"
            data.startsWith("http") -> data
            else -> return false
        }

        try {
            val res = app.get(url, referer = mainUrl)
            val html = res.text

            val match = Regex("""["'](?:file|src|source)["']?\s*:\s*["'](https?://[^"']+embedplay[^"']+)""")
                .find(html)

            if (match == null) return false

            var videoUrl = match.groupValues[1]

            if (videoUrl.contains("embedplay.upns.pro") || videoUrl.contains("embedplay.upn.one")) {
                val id = videoUrl.substringAfterLast("/").substringBefore("?")
                videoUrl = "https://player.ultracine.org/watch/$id"
            }

            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name • 4K Tela Cheia",
                    url = videoUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )

            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun parseDuration(duration: String?): Int? {
        if (duration == null) return null
        val hours = Regex("(\\d+)h").find(duration)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)m").find(duration)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return hours * 60 + minutes
    }
}
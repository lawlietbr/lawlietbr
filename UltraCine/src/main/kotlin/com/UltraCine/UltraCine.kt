// Código corrigido do UltraCine para Cloudstream 4+
// Ajustado: urlEncode removido, Score.of usado, ExtractorLink atualizado, isM3u8 removido, referer corrigido.

package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import java.net.URLEncoder

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

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
        val title = this.selectFirst("header.entry-header h2.entry-title")?.text() ?: return null
        val href = this.selectFirst("a.lnk-blk")?.attr("href") ?: return null

        val posterUrl = this.selectFirst("div.post-thumbnail figure img")?.let { img ->
            val src = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src")
            src?.let { url ->
                val fullUrl = if (url.startsWith("//")) "https:$url" else url
                fullUrl.replace("/w500/", "/original/")
            }
        }

        val year = this.selectFirst("span.year")?.text()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchResponse = app.get("$mainUrl/?s=$encoded").document
        return searchResponse.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("aside.fg1 header.entry-header h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("div.bghd img.TPostBg")?.let { img ->
            val src = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src")
            src?.let { url ->
                val fullUrl = if (url.startsWith("//")) "https:$url" else url
                fullUrl.replace("/w1280/", "/original/")
            }
        }

        val year = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.year")?.text()?.substringAfter("far">")?.toIntOrNull()
        val duration = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.duration")?.text()?.substringAfter("far">")
        val rating = document.selectFirst("div.vote-cn span.vote span.num")?.text()?.toDoubleOrNull()
        val plot = document.selectFirst("aside.fg1 div.description p")?.text()
        val genres = document.select("aside.fg1 header.entry-header div.entry-meta span.genres a").map { it.text() }

        val actors = document.selectFirst("aside.fg1 ul.cast-lst p")?.select("a")?.map {
            Actor(it.text(), it.attr("href"))
        }

        val trailerUrl = document.selectFirst("div.mdl-cn div.video iframe")?.attr("src")

        val iframeElement = document.selectFirst("iframe[src*='assistirseriesonline']")
        val iframeUrl = iframeElement?.attr("src")

        val isSerie = url.contains("/serie/")

        return if (isSerie) {
            val episodes = iframeUrl?.let {
                val iframeDocument = app.get(it).document
                parseSeriesEpisodes(iframeDocument, it)
            } ?: emptyList()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = Score.of(rating?.times(1000)?.toInt() ?: 0)
                this.tags = genres
                actors?.let { addActors(it) }
                addTrailer(trailerUrl)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl ?: "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = Score.of(rating?.times(1000)?.toInt() ?: 0)
                this.tags = genres
                this.duration = parseDuration(duration)
                actors?.let { addActors(it) }
                addTrailer(trailerUrl)
            }
        }
    }

    private suspend fun parseSeriesEpisodes(iframeDocument: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seasons = iframeDocument.select("header.header ul.header-navigation li")

        for (seasonElement in seasons) {
            val seasonNumber = seasonElement.attr("data-season-number").toIntOrNull() ?: continue
            val seasonId = seasonElement.attr("data-season-id")

            val seasonEpisodes = iframeDocument.select("li[data-season-id='$seasonId']")
                .mapNotNull { episodeElement ->
                    val episodeId = episodeElement.attr("data-episode-id")
                    val episodeTitle = episodeElement.selectFirst("a")?.text() ?: return@mapNotNull null

                    val episodeNumber = episodeTitle.substringBefore(" - ").toIntOrNull() ?: 1
                    val cleanTitle = if (episodeTitle.contains(" - ")) {
                        episodeTitle.substringAfter(" - ")
                    } else episodeTitle

                    newEpisode(episodeId) {
                        this.name = cleanTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                }

            episodes.addAll(seasonEpisodes)
        }

        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        suspend fun sendLink(url: String): Boolean {
            callback(
                ExtractorLink(
                    source = "UltraCine",
                    name = "UltraCine 4K • Tela Cheia",
                    url = url,
                    referer = mainUrl,
                    quality = Qualities.Unknown,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }

        suspend fun handleDocument(document: org.jsoup.nodes.Document): Boolean {
            val embedPlay = document.selectFirst("button[data-source*='embedplay']")?.attr("data-source")
            if (!embedPlay.isNullOrBlank()) return sendLink(embedPlay)

            val iframe = document.selectFirst("div.play-overlay div#player iframe")?.attr("src")
            if (!iframe.isNullOrBlank()) return sendLink(iframe)

            return false
        }

        return try {
            if (data.matches(Regex("\\d+"))) {
                val episodeUrl = "https://assistirseriesonline.icu/episodio/$data"
                val doc = app.get(episodeUrl).document
                handleDocument(doc)
            } else {
                val doc = app.get(data).document
                handleDocument(doc)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun parseDuration(duration: String?): Int? {
        if (duration == null) return null
        val regex = Regex("(\\d+)h\\s*(\\d+)m")
        val match = regex.find(duration)
        return if (match != null) {
            val h = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 0
            h * 60 + m
        } else Regex("(\\d+)m").find(duration)?.groupValues?.get(1)?.toIntOrNull()
    }
}

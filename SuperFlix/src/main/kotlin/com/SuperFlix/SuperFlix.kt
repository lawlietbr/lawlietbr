package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries",
        "$mainUrl/lancamentos" to "Lançamentos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, .movie-card, article, .item").forEach { element ->
            element.toSearchResult()?.let { home.add(it) }
        }

        if (home.isEmpty()) {
            document.select("a[href*='/filme/'], a[href*='/serie/']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && !href.contains("#")) {
                    val title = link.selectFirst("img")?.attr("alt")
                        ?: link.selectFirst(".rec-title, .title, h2, h3")?.text()
                        ?: href.substringAfterLast("/").replace("-", " ").replace(Regex("\\d{4}$"), "").trim()

                    if (title.isNotBlank()) {
                        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                        val poster = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                        val isSerie = href.contains("/serie/")

                        val searchResponse = if (isSerie) {
                            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        } else {
                            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        }

                        home.add(searchResponse)
                    }
                }
            }
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".rec-title, .movie-title, h2, h3, .title")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: return null

        val href = attr("href") ?: selectFirst("a")?.attr("href") ?: return null

        val poster = selectFirst("img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
            ?: selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: selectFirst(".rec-meta, .movie-year, .year")?.text()?.let {
                Regex("\\b(\\d{4})\\b").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }

        val isSerie = href.contains("/serie/")
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery").document

        val results = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, a[href*='/filme/'], a[href*='/serie/']").forEach { element ->
            element.toSearchResult()?.let { results.add(it) }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val html = document.html()

        val jsonLd = extractJsonLd(html)

        val title = jsonLd.title ?: document.selectFirst("h1, .title")?.text() ?: return null
        val year = jsonLd.year ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val poster = jsonLd.posterUrl?.replace("/w500/", "/original/")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?.replace("/w500/", "/original/")

        val plot = jsonLd.description ?: document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst(".syn, .description")?.text()

        val tags = jsonLd.genres ?: document.select("a.chip, .chip").map { it.text() }

        val actors = jsonLd.actors?.map { Actor(it, "") } ?: emptyList()

        val director = jsonLd.director?.firstOrNull()

        val isSerie = url.contains("/serie/") || jsonLd.type == "TVSeries"

        return if (isSerie) {
            val episodes = extractEpisodesFromButtons(document, url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        } else {
            // Para filmes, armazenamos a URL do iframe/button para uso posterior
            val videoData = findFembedUrl(document) ?: ""

            newMovieLoadResponse(title, url, TvType.Movie, videoData) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        }
    }

    private fun extractEpisodesFromButtons(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        document.select("button.bd-play[data-url]").forEach { button ->
            val fembedUrl = button.attr("data-url")
            val season = button.attr("data-season").toIntOrNull() ?: 1
            val episodeNum = button.attr("data-ep").toIntOrNull() ?: 1

            var episodeTitle = "Episódio $episodeNum"

            val parent = button.parents().find { it.hasClass("episode-item") || it.hasClass("episode") }
            parent?.let {
                val titleElement = it.selectFirst(".ep-title, .title, .name, h3, h4")
                if (titleElement != null) {
                    episodeTitle = titleElement.text().trim()
                }
            }

            episodes.add(
                newEpisode(fembedUrl) {
                    this.name = episodeTitle
                    this.season = season
                    this.episode = episodeNum
                }
            )
        }

        return episodes
    }

    private fun findFembedUrl(document: org.jsoup.nodes.Document): String? {
        // Primeiro, procurar por iframe do Fembed
        val iframe = document.selectFirst("iframe[src*='fembed']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        // Procurar por botão de play com data-url
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }

        // Procurar por qualquer botão com URL do Fembed
        val anyButton = document.selectFirst("button[data-url*='fembed']")
        if (anyButton != null) {
            return anyButton.attr("data-url")
        }

        // Procurar no HTML por padrões
        val html = document.html()
        val patterns = listOf(
            Regex("""https?://[^"'\s]*fembed[^"'\s]*/e/\w+"""),
            Regex("""data-url=["'](https?://[^"']*fembed[^"']+)["']"""),
            Regex("""src\s*[:=]\s*["'](https?://[^"']*fembed[^"']+)["']""")
        )

        patterns.forEach { pattern ->
            pattern.find(html)?.let { match ->
                val url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                if (url.isNotBlank()) return url
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix DEBUG: loadLinks chamado com data = '$data'")

        if (data.isBlank()) {
            println("SuperFlix DEBUG: Data está vazia")
            return false
        }

        return try {
            // Verificar se é uma URL do Fembed
            if (data.contains("fembed")) {
                val embedUrl = extractFembedEmbedUrl(data)
                println("SuperFlix DEBUG: URL do embed extraída: '$embedUrl'")
                
                // Usar o extrator padrão do CloudStream
                if (loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)) {
                    println("SuperFlix DEBUG: ✅ Extrator padrão funcionou!")
                    return true
                }
                
                // Se o extrator padrão não funcionar, tentar resolver manualmente
                return tryManualFembedExtraction(embedUrl, subtitleCallback, callback)
            }
            
            false
        } catch (e: Exception) {
            println("SuperFlix DEBUG: Erro em loadLinks: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun extractFembedEmbedUrl(data: String): String {
        // Extrair ID do Fembed de várias formas possíveis
        val idPatterns = listOf(
            Regex("""/e/([a-zA-Z0-9]+)"""),
            Regex("""/v/([a-zA-Z0-9]+)"""),
            Regex("""/f/([a-zA-Z0-9]+)"""),
            Regex("""/([a-zA-Z0-9]+)$""")
        )
        
        for (pattern in idPatterns) {
            val match = pattern.find(data)
            if (match != null && match.groupValues.size > 1) {
                val videoId = match.groupValues[1]
                return "https://www.fembed.com/v/$videoId"
            }
        }
        
        // Se não conseguir extrair ID, retornar a URL original
        return data.replace("fembed.sx", "www.fembed.com")
    }
    
    private suspend fun tryManualFembedExtraction(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix DEBUG: Tentando extração manual do Fembed: $embedUrl")
        
        try {
            // Fazer request para obter o HTML do player
            val response = app.get(embedUrl)
            val html = response.text
            
            // Procurar por URLs de vídeo no HTML
            val videoPatterns = listOf(
                Regex("""https?://[^"'\s]+\.(mp4|m3u8)[^"'\s]*"""),
                Regex("""file["']?\s*:\s*["']([^"']+\.(mp4|m3u8)[^"']+)["']"""),
                Regex("""sources\s*:\s*\[[^\]]*"file"\s*:\s*"([^"]+\.(mp4|m3u8)[^"]+)""")
            )
            
            for (pattern in videoPatterns) {
                val matches = pattern.findAll(html)
                matches.forEach { match ->
                    val url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                    if (url.isNotBlank() && (url.contains(".mp4") || url.contains(".m3u8"))) {
                        println("SuperFlix DEBUG: Encontrado vídeo: $url")
                        
                        // Criar ExtractorLink manualmente
                        val quality = if (url.contains("720")) "720p" else if (url.contains("1080")) "1080p" else "480p"
                        
                        callback.invoke(
                            ExtractorLink(
                                name = "SuperFlix",
                                source = name,
                                url = url,
                                referer = embedUrl,
                                quality = Qualities.fromString(quality),
                                isM3u8 = url.contains(".m3u8")
                            )
                        )
                        
                        return true
                    }
                }
            }
            
            // Procurar por API do Fembed
            val apiPattern = Regex("""/api/source/([a-zA-Z0-9]+)""")
            apiPattern.find(html)?.let { match ->
                if (match.groupValues.size > 1) {
                    val apiId = match.groupValues[1]
                    val apiUrl = "https://www.fembed.com/api/source/$apiId"
                    
                    println("SuperFlix DEBUG: Tentando API do Fembed: $apiUrl")
                    
                    // Tentar fazer POST para a API do Fembed
                    try {
                        val apiResponse = app.post(apiUrl, headers = mapOf(
                            "Referer" to embedUrl,
                            "X-Requested-With" to "XMLHttpRequest"
                        ))
                        
                        val json = apiResponse.parsedSafe<FembedApiResponse>()
                        if (json?.success == true && json.data != null) {
                            json.data.forEach { video ->
                                val videoUrl = video.file ?: return@forEach
                                val quality = video.label ?: "Unknown"
                                
                                callback.invoke(
                                    ExtractorLink(
                                        name = "SuperFlix",
                                        source = name,
                                        url = videoUrl,
                                        referer = embedUrl,
                                        quality = Qualities.fromString(quality),
                                        isM3u8 = videoUrl.contains(".m3u8")
                                    )
                                )
                            }
                            return true
                        }
                    } catch (e: Exception) {
                        println("SuperFlix DEBUG: Erro na API do Fembed: ${e.message}")
                    }
                }
            }
            
        } catch (e: Exception) {
            println("SuperFlix DEBUG: Erro na extração manual: ${e.message}")
        }
        
        return false
    }

    private data class JsonLdInfo(
        val title: String? = null,
        val year: Int? = null,
        val posterUrl: String? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val director: List<String>? = null,
        val actors: List<String>? = null,
        val tmdbId: String? = null,
        val type: String? = null
    )

    private fun extractJsonLd(html: String): JsonLdInfo {
        val pattern = Regex("<script type=\"application/ld\\+json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        val matches = pattern.findAll(html)

        matches.forEach { match ->
            try {
                val json = match.groupValues[1].trim()
                if (json.contains("\"@type\":\"Movie\"") || json.contains("\"@type\":\"TVSeries\"")) {

                    val title = Regex("\"name\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val image = Regex("\"image\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val description = Regex("\"description\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)

                    val genresMatch = Regex("\"genre\":\\s*\\[([^\\]]+)\\]").find(json)
                    val genres = genresMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.map { it.trim().trim('"', '\'') }
                        ?.filter { it.isNotBlank() }

                    val actorsMatch = Regex("\"actor\":\\s*\\[([^\\]]+)\\]").find(json)
                    val actors = actorsMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { actor ->
                            Regex("\"name\":\"([^\"]+)\"").find(actor)?.groupValues?.get(1)
                        }

                    val directorMatch = Regex("\"director\":\\s*\\[([^\\]]+)\\]").find(json)
                    val director = directorMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { dir ->
                            Regex("\"name\":\"([^\"]+)\"").find(dir)?.groupValues?.get(1)
                        }

                    val sameAsMatch = Regex("\"sameAs\":\\s*\\[([^\\]]+)\\]").find(json)
                    val tmdbId = sameAsMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.find { it.contains("themoviedb.org") }
                        ?.substringAfterLast("/")
                        ?.trim(' ', '"', '\'')

                    val type = if (json.contains("\"@type\":\"Movie\"")) "Movie" else "TVSeries"

                    return JsonLdInfo(
                        title = title,
                        year = null,
                        posterUrl = image,
                        description = description,
                        genres = genres,
                        director = director,
                        actors = actors,
                        tmdbId = tmdbId,
                        type = type
                    )
                }
            } catch (e: Exception) {
                // Continua
            }
        }

        return JsonLdInfo()
    }
    
    // Classe para parse da resposta da API do Fembed
    private data class FembedApiResponse(
        val success: Boolean? = null,
        val data: List<FembedVideo>? = null
    )
    
    private data class FembedVideo(
        val file: String? = null,
        val label: String? = null,
        val type: String? = null
    )
}
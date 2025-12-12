package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat
import org.json.JSONObject
import java.net.URLEncoder

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    // ============ CONFIGURAÇÃO DO PROXY TMDB ============
    private val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
    private val TMDB_IMAGE_URL = "https://image.tmdb.org/t/p"

    // ============ FUNÇÃO PRINCIPAL DE BUSCA NO TMDB ============
    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        val type = if (isTv) "tv" else "movie"
        
        return try {
            // PASSO 1: BUSCA BÁSICA
            val searchUrl = "$TMDB_PROXY_URL/search/$type?" +
                           "query=${URLEncoder.encode(query, "UTF-8")}&" +
                           "language=pt-BR"
            
            val searchResponse = app.get(searchUrl, timeout = 10_000)
            
            // CORREÇÃO: CloudStream usa .code não .status
            if (searchResponse.code != 200) return null
            
            val searchJson = JSONObject(searchResponse.text)
            val results = searchJson.getJSONArray("results")
            if (results.length() == 0) return null
            
            val firstItem = results.getJSONObject(0)
            val itemId = firstItem.getInt("id")
            
            // PASSO 2: DETALHES COMPLETOS
            val detailsUrl = "$TMDB_PROXY_URL/$type/$itemId?" +
                            "language=pt-BR&" +
                            "append_to_response=credits,videos" +
                            (if (isTv) ",recommendations" else "")
            
            val detailsResponse = app.get(detailsUrl, timeout = 10_000)
            
            // CORREÇÃO: CloudStream usa .code não .status
            if (detailsResponse.code != 200) {
                // Fallback: usa dados básicos se detalhes falharem
                return createBasicTMDBInfo(firstItem, isTv)
            }
            
            val detailsJson = JSONObject(detailsResponse.text)
            return parseFullTMDBInfo(detailsJson, isTv, itemId)
            
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // ============ FUNÇÃO PARA INFORMAÇÕES BÁSICAS (FALLBACK) ============
    private fun createBasicTMDBInfo(item: JSONObject, isTv: Boolean): TMDBInfo {
        return TMDBInfo(
            id = item.getInt("id"),
            title = if (isTv) item.optString("name", "") 
                    else item.optString("title", ""),
            year = if (isTv) item.optString("first_air_date", "").take(4).toIntOrNull()
                    else item.optString("release_date", "").take(4).toIntOrNull(),
            posterUrl = item.optString("poster_path", "").takeIf { it.isNotEmpty() }
                        ?.let { "$TMDB_IMAGE_URL/w500$it" },
            backdropUrl = item.optString("backdrop_path", "").takeIf { it.isNotEmpty() }
                          ?.let { "$TMDB_IMAGE_URL/original$it" },
            overview = item.optString("overview", ""),
            genres = null,
            actors = null,
            youtubeTrailer = null,
            duration = null,
            seasonsEpisodes = emptyMap()
        )
    }

    // ============ FUNÇÃO PARA PROCESSAR DETALHES COMPLETOS ============
    private suspend fun parseFullTMDBInfo(json: JSONObject, isTv: Boolean, itemId: Int): TMDBInfo {
        // Extrai atores dos créditos
        val actors = mutableListOf<Actor>()
        try {
            val credits = json.getJSONObject("credits")
            val cast = credits.getJSONArray("cast")
            
            for (i in 0 until cast.length().coerceAtMost(15)) {
                val actor = cast.getJSONObject(i)
                val name = actor.getString("name")
                val profilePath = actor.optString("profile_path", "")
                
                if (name.isNotBlank()) {
                    actors.add(
                        Actor(
                            name = name,
                            image = if (profilePath.isNotBlank()) 
                                    "$TMDB_IMAGE_URL/w185$profilePath" 
                                  else null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Se não conseguir créditos, continua sem atores
        }
        
        // Extrai trailer do YouTube
        var youtubeTrailer: String? = null
        try {
            // CORREÇÃO: A estrutura correta é videos.results
            val videos = json.getJSONObject("videos")
            val results = videos.getJSONArray("results")
            for (i in 0 until results.length()) {
                val video = results.getJSONObject(i)
                if (video.getString("site") == "YouTube" && 
                    video.getString("type") == "Trailer") {
                    youtubeTrailer = "https://www.youtube.com/watch?v=${video.getString("key")}"
                    break
                }
            }
        } catch (e: Exception) {
            // Se não conseguir vídeos, sem trailer
        }
        
        // Extrai gêneros
        val genres = mutableListOf<String>()
        try {
            val genresArray = json.getJSONArray("genres")
            for (i in 0 until genresArray.length()) {
                val genre = genresArray.getJSONObject(i)
                genres.add(genre.getString("name"))
            }
        } catch (e: Exception) {
            // Se não conseguir gêneros, lista vazia
        }
        
        // Para séries, busca temporadas/episódios
        val seasonsEpisodes = if (isTv) {
            getSeasonsWithEpisodes(itemId)
        } else {
            emptyMap()
        }
        
        return TMDBInfo(
            id = json.getInt("id"),
            title = if (isTv) json.optString("name", "") 
                    else json.optString("title", ""),
            year = if (isTv) json.optString("first_air_date", "").take(4).toIntOrNull()
                    else json.optString("release_date", "").take(4).toIntOrNull(),
            posterUrl = json.optString("poster_path", "").takeIf { it.isNotEmpty() }
                        ?.let { "$TMDB_IMAGE_URL/w500$it" },
            backdropUrl = json.optString("backdrop_path", "").takeIf { it.isNotEmpty() }
                          ?.let { "$TMDB_IMAGE_URL/original$it" },
            overview = json.optString("overview", ""),
            genres = if (genres.isNotEmpty()) genres else null,
            actors = if (actors.isNotEmpty()) actors else null,
            youtubeTrailer = youtubeTrailer,
            duration = if (!isTv) json.optInt("runtime", 0) else null,
            seasonsEpisodes = seasonsEpisodes
        )
    }

    // ============ FUNÇÃO PARA BUSCAR TEMPORADAS E EPISÓDIOS ============
    private suspend fun getSeasonsWithEpisodes(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()
        
        try {
            // Primeiro, pega detalhes da série para saber as temporadas
            val seriesUrl = "$TMDB_PROXY_URL/tv/$seriesId?language=pt-BR"
            val seriesResponse = app.get(seriesUrl, timeout = 10_000)
            
            // CORREÇÃO: CloudStream usa .code não .status
            if (seriesResponse.code != 200) return emptyMap()
            
            val seriesJson = JSONObject(seriesResponse.text)
            val seasonsArray = seriesJson.getJSONArray("seasons")
            
            // Para cada temporada (exceto temporada 0)
            for (i in 0 until seasonsArray.length()) {
                val seasonJson = seasonsArray.getJSONObject(i)
                val seasonNumber = seasonJson.getInt("season_number")
                
                if (seasonNumber > 0 && seasonNumber <= 10) {
                    // Busca episódios desta temporada
                    val episodes = getEpisodesForSeason(seriesId, seasonNumber)
                    if (episodes.isNotEmpty()) {
                        seasonsEpisodes[seasonNumber] = episodes
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return seasonsEpisodes
    }

    // ============ FUNÇÃO PARA BUSCAR EPISÓDIOS DE UMA TEMPORADA ============
    private suspend fun getEpisodesForSeason(seriesId: Int, seasonNumber: Int): List<TMDBEpisode> {
        return try {
            val url = "$TMDB_PROXY_URL/tv/$seriesId/season/$seasonNumber?language=pt-BR"
            val response = app.get(url, timeout = 10_000)
            
            // CORREÇÃO: CloudStream usa .code não .status
            if (response.code != 200) return emptyList()
            
            val json = JSONObject(response.text)
            val episodesArray = json.getJSONArray("episodes")
            val episodes = mutableListOf<TMDBEpisode>()
            
            for (i in 0 until episodesArray.length()) {
                val episodeJson = episodesArray.getJSONObject(i)
                
                episodes.add(
                    TMDBEpisode(
                        episode_number = episodeJson.getInt("episode_number"),
                        name = episodeJson.getString("name"),
                        overview = episodeJson.optString("overview", ""),
                        still_path = episodeJson.optString("still_path", null),
                        runtime = episodeJson.optInt("runtime", 0),
                        air_date = episodeJson.optString("air_date", null)
                    )
                )
            }
            
            episodes
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============ FUNÇÃO PRINCIPAL PARA CARREGAR DETALHES ============
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1, .title")
        val title = titleElement?.text() ?: return null

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/tv/") ||
                     (!isAnime && document.selectFirst(".episode-list, .season-list, .seasons") != null)

        // Busca informações no TMDB
        val tmdbInfo = searchOnTMDB(cleanTitle, year, isSerie || isAnime)

        // Recomendações do site
        val siteRecommendations = extractRecommendationsFromSite(document)

        // Extrai episódios do site
        val episodes = extractEpisodesFromSite(document, url, tmdbInfo, isAnime, isSerie)

        return if (tmdbInfo != null) {
            createLoadResponseWithTMDB(tmdbInfo, url, episodes, isAnime, isSerie, siteRecommendations)
        } else {
            createLoadResponseFromSite(document, url, cleanTitle, year, isAnime, isSerie, episodes, siteRecommendations)
        }
    }

    // ============ FUNÇÃO PARA CRIAR RESPOSTA COM DADOS DO TMDB ============
    private suspend fun createLoadResponseWithTMDB(
        tmdbInfo: TMDBInfo,
        url: String,
        episodes: List<Episode>,
        isAnime: Boolean,
        isSerie: Boolean,
        siteRecommendations: List<SearchResponse>
    ): LoadResponse {
        return if (isSerie || isAnime) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            
            // CORREÇÃO: Esta função deve ser suspensa
            newTvSeriesLoadResponse(
                name = tmdbInfo.title ?: "",
                url = url,
                type = type,
                episodes = episodes
            ) {
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres

                tmdbInfo.actors?.let { actors ->
                    addActors(actors)
                }

                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }

                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            // CORREÇÃO: Precisa do documento para encontrar playerUrl
            val document = app.get(url).document
            val playerUrl = findPlayerUrl(document)
            
            // CORREÇÃO: Esta função deve ser suspensa
            newMovieLoadResponse(
                name = tmdbInfo.title ?: "",
                url = url,
                type = TvType.Movie,
                dataUrl = playerUrl ?: url
            ) {
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres
                this.duration = tmdbInfo.duration

                tmdbInfo.actors?.let { actors ->
                    addActors(actors)
                }

                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }

                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        }
    }

    // ============ FUNÇÃO PARA EXTRAIR EPISÓDIOS DO SITE ============
    private fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        tmdbInfo: TMDBInfo?,
        isAnime: Boolean,
        isSerie: Boolean
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Tenta vários seletores
        val selectors = listOf(
            "button.bd-play[data-url]",
            "a.episode-card",
            ".episode-item",
            ".episode-link",
            "[class*='episode']",
            "[class*='episodio']"
        )
        
        var episodeCounter = 1
        
        selectors.forEach { selector ->
            document.select(selector).forEach { element ->
                try {
                    val dataUrl = element.attr("data-url") ?: element.attr("href")
                    if (dataUrl.isNullOrBlank()) return@forEach
                    
                    val epNumber = extractEpisodeNumber(element, episodeCounter)
                    val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1
                    
                    // Busca informações deste episódio no TMDB
                    val tmdbEpisode = findTMDBEpisode(tmdbInfo, seasonNumber, epNumber)
                    
                    episodes.add(createEpisode(
                        dataUrl = dataUrl,
                        seasonNumber = seasonNumber,
                        episodeNumber = epNumber,
                        element = element,
                        tmdbEpisode = tmdbEpisode,
                        isAnime = isAnime
                    ))
                    
                    episodeCounter++
                } catch (e: Exception) {
                    // Ignora erro e continua
                }
            }
        }
        
        return episodes
    }

    // ============ FUNÇÃO PARA CRIAR EPISÓDIO ============
    private fun createEpisode(
        dataUrl: String,
        seasonNumber: Int,
        episodeNumber: Int,
        element: Element,
        tmdbEpisode: TMDBEpisode?,
        isAnime: Boolean
    ): Episode {
        // Nome do episódio
        val name = tmdbEpisode?.name ?:
                  element.selectFirst(".ep-title, .title, .episode-title, h3, h4")?.text()?.trim() ?:
                  "Episódio $episodeNumber"
        
        // Thumbnail
        val posterUrl = tmdbEpisode?.still_path?.let { "$TMDB_IMAGE_URL/w300$it" } ?:
                       element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        // Descrição
        val descriptionBuilder = StringBuilder()
        
        // Primeiro usa sinopse do TMDB
        tmdbEpisode?.overview?.takeIf { it.isNotEmpty() }?.let { overview ->
            descriptionBuilder.append(overview)
        }
        
        // Se não tem do TMDB, tenta do site
        if (descriptionBuilder.isEmpty()) {
            element.selectFirst(".ep-desc, .description, .synopsis")?.text()?.trim()?.let { siteDesc ->
                if (siteDesc.isNotEmpty()) {
                    descriptionBuilder.append(siteDesc)
                }
            }
        }
        
        // Duração
        val duration = when {
            isAnime -> tmdbEpisode?.runtime ?: 24
            else -> tmdbEpisode?.runtime ?: 0
        }
        
        if (duration > 0) {
            if (descriptionBuilder.isNotEmpty()) {
                descriptionBuilder.append("\n\n⏱️ Duração: ${duration}min")
            } else {
                descriptionBuilder.append("⏱️ Duração: ${duration}min")
            }
        }
        
        // Data de lançamento
        var episodeDate: Long? = null
        tmdbEpisode?.air_date?.takeIf { it.isNotEmpty() }?.let { airDate ->
            try {
                val dateFormatter = SimpleDateFormat("yyyy-MM-dd")
                episodeDate = dateFormatter.parse(airDate)?.time
            } catch (e: Exception) {
                // Ignora erro de parse
            }
        }
        
        return newEpisode(fixUrl(dataUrl)) {
            this.name = name
            this.season = seasonNumber
            this.episode = episodeNumber
            this.posterUrl = posterUrl
            this.description = descriptionBuilder.toString().takeIf { it.isNotEmpty() }
            this.date = episodeDate
        }
    }

    // ============ FUNÇÕES AUXILIARES ============
    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        return element.attr("data-ep").toIntOrNull() ?:
               element.selectFirst(".ep-number, .number, .episode-number")?.text()?.toIntOrNull() ?:
               Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("Epis[oó]dio\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
    }

    private fun findTMDBEpisode(tmdbInfo: TMDBInfo?, season: Int, episode: Int): TMDBEpisode? {
        return tmdbInfo?.seasonsEpisodes?.get(season)?.find { it.episode_number == episode }
    }

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".recs-grid .rec-card, .recs-grid a").mapNotNull { element ->
            try {
                val href = element.attr("href") ?: return@mapNotNull null
                if (href.isBlank() || href == "#") return@mapNotNull null

                val imgElement = element.selectFirst("img")
                val title = imgElement?.attr("alt") ?:
                           element.selectFirst(".rec-title")?.text() ?:
                           element.attr("title") ?:
                           return@mapNotNull null

                val poster = imgElement?.attr("src")?.let { fixUrl(it) }
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

                val isAnime = href.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
                val isSerie = href.contains("/serie/") || href.contains("/tv/")
                val isMovie = !isSerie && !isAnime

                return@mapNotNull when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun createLoadResponseFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        title: String,
        year: Int?,
        isAnime: Boolean,
        isSerie: Boolean,
        episodes: List<Episode>,
        recommendations: List<SearchResponse>
    ): LoadResponse {
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = ogImage?.let { fixUrl(it) }

        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val synopsis = document.selectFirst(".syn, .description, .sinopse, .plot")?.text()
        val plot = description ?: synopsis

        val tags = document.select("a.chip, .chip, .genre, .tags").map { it.text() }
            .takeIf { it.isNotEmpty() }?.toList()

        return if (isAnime || isSerie) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            // CORREÇÃO: Esta função deve ser suspensa
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            val playerUrl = findPlayerUrl(document)
            // CORREÇÃO: Esta função deve ser suspensa
            newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        }
    }

    // ============ FUNÇÕES OBRIGATÓRIAS DO CLOUDSTREAM ============
    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Últimos Filmes",
        "$mainUrl/series" to "Últimas Séries",
        "$mainUrl/animes" to "Últimas Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = document.select("a.card, div.recs-grid a.rec-card").mapNotNull { element ->
            element.toSearchResult()
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = attr("title") ?: selectFirst("img")?.attr("alt") ?: return null
        val href = attr("href") ?: return null

        val localPoster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        val badge = selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
        val isAnime = badge.contains("anime") || href.contains("/anime/") ||
                      title.contains("(Anime)", ignoreCase = true)
        val isSerie = badge.contains("série") || badge.contains("serie") ||
                     href.contains("/serie/") ||
                     (!isAnime && (badge.contains("tv") || href.contains("/tv/")))
        val isMovie = !isSerie && !isAnime

        return when {
            isAnime -> {
                newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
            isSerie -> {
                newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
            else -> {
                newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/buscar?q=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select(".grid .card, a.card").mapNotNull { card ->
            try {
                val title = card.attr("title") ?: card.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val href = card.attr("href") ?: return@mapNotNull null

                val poster = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

                val badge = card.selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
                val isAnime = badge.contains("anime") || href.contains("/anime/") ||
                             title.contains("(Anime)", ignoreCase = true)
                val isSerie = badge.contains("série") || badge.contains("serie") ||
                             href.contains("/serie/") ||
                             (!isAnime && (badge.contains("tv") || href.contains("/tv/")))

                when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TODO: Implementar extração de links de vídeo
        return false
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) return playButton.attr("data-url")
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) return iframe.attr("src")
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        return videoLink?.attr("href")
    }

    // ============ CLASSES DE DADOS DO TMDB ============
    private data class TMDBInfo(
        val id: Int,
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val genres: List<String>?,
        val actors: List<Actor>?,
        val youtubeTrailer: String?,
        val duration: Int?,
        val seasonsEpisodes: Map<Int, List<TMDBEpisode>> = emptyMap()
    )

    private data class TMDBEpisode(
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("air_date") val air_date: String?
    )
}
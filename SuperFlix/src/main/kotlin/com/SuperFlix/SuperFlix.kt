package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class SuperFlix : MainAPI() {
    // URL principal (verifique se ainda é válida, se não for, altere aqui)
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    // Corrigido para 'var'
    override var lang = "pt"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Headers agressivos para simular um navegador e evitar bloqueios (USADO EM load E search)
    private val defaultHeaders = mapOf(
        "Referer" to mainUrl,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    )

    // Helper para converter Elemento Jsoup em SearchResponse
    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.attr("title")
        val url = fixUrl(this.attr("href"))
        // Usando let para garantir que posterUrl não seja null e a URL seja corrigida
        val posterUrl = this.selectFirst("img.card-img")?.attr("src")?.let { fixUrl(it) }

        if (title.isNullOrEmpty() || url.isNullOrEmpty()) return null

        val year = title.substringAfterLast("(").substringBeforeLast(")").toIntOrNull()
        val cleanTitle = title.substringBeforeLast("(").trim()

        val type = if (url.contains("/filme/")) TvType.Movie else TvType.TvSeries

        return newMovieSearchResponse(cleanTitle, url, type) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    // Função para extrair a URL de embed do Fembed
    private fun getFembedUrl(element: Element): String? {
        val iframeSrc = element.selectFirst("iframe#player")?.attr("src")
        if (!iframeSrc.isNullOrEmpty() && iframeSrc.contains("fembed")) {
            return iframeSrc
        }

        val dataUrl = element.selectFirst("button[data-url]")?.attr("data-url")
        if (!dataUrl.isNullOrEmpty() && dataUrl.contains("fembed")) {
            return dataUrl
        }

        return null
    }

    // 2. mainPage (categorias) - Sintaxe corrigida
    override val mainPage = listOf(
        MainPageData("Lançamentos", "$mainUrl/lancamentos"),
        MainPageData("Últimos Filmes", "$mainUrl/filmes"),
        MainPageData("Últimas Séries", "$mainUrl/series"),
        MainPageData("Últimos Animes", "$mainUrl/animes")
    )

    // 3. getMainPage() - Adicionado Headers para evitar bloqueio
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            val type = request.data.substringAfterLast("/")
            if (type.contains("genero")) {
                val genre = request.data.substringAfterLast("genero/").substringBefore("/")
                "$mainUrl/genero/$genre/page/$page"
            } else {
                "$mainUrl/$type/page/$page"
            }
        }
        
        // Usando headers robustos
        val response = app.get(url, headers = defaultHeaders)
        val document = response.document

        val list = document.select("a.card").mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    // 5. search() - Adicionado Headers para evitar bloqueio
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        // Usando headers robustos para evitar "did not return any search responses"
        val response = app.get(url, headers = defaultHeaders)
        val document = response.document

        return document.select("a.card").mapNotNull { it.toSearchResponse() }
    }

    // 6. load() - Adicionado Headers e Fallback no Título
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = defaultHeaders) 
        val document = response.document

        val isMovie = url.contains("/filme/")

        // Fallback no Título (tenta o seletor específico, senão tenta h1 genérico)
        val title = document.selectFirst("h1.text-3xl")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim() 
            ?: throw ErrorLoadingException("Título não encontrado")
            
        val posterUrl = document.selectFirst("div.poster img")?.attr("src")?.let { fixUrl(it) }
        val plot = document.selectFirst("p.text-gray-400")?.text()?.trim()
        val tags = document.select("a[href*=/genero/]").map { it.text().trim() }
        val year = title.substringAfterLast("(").substringBeforeLast(")").toIntOrNull()

        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        return if (isMovie) {
            val embedUrl = getFembedUrl(document)
            newMovieLoadResponse(title, url, type, embedUrl) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            val seasons = document.select("div#season-tabs button").mapIndexed { index, element ->
                val seasonName = element.text().trim()
                newEpisode(url) {
                    name = seasonName
                    season = index + 1
                    episode = 1 
                    data = url 
                }
            }

            // Tipo de retorno corrigido (List<Episode> exigido pelo seu compilador)
            newTvSeriesLoadResponse(title, url, type, seasons) { 
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    // 7. loadLinks() - Adicionado Headers
    override suspend fun loadLinks(
        data: String,
        isMovie: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isMovie) {
            // Para filmes, 'data' é a URL do Fembed (LoadExtractor já lida com redirecionamentos)
            return loadExtractor(data, data, subtitleCallback, callback)
        } else {
            // Para séries, 'data' é a URL da série
            val response = app.get(data, headers = defaultHeaders) 
            val document = response.document

            // Seleciona todos os botões de play que contêm a URL do Fembed
            val episodeButtons = document.select("button[data-url*=\"fembed\"]")

            for (button in episodeButtons) {
                val embedUrl = button.attr("data-url")
                if (!embedUrl.isNullOrEmpty()) {
                    loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                }
            }
            return true
        }
    }
}

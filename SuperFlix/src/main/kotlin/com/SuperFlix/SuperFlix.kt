package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
// Removida a importação de java.util.concurrent.TimeUnit

// Necessário para executar funções suspend (como app.get) de forma síncrona
import kotlinx.coroutines.runBlocking 

class SuperFlix : MainAPI() {
    
    override lateinit var mainUrl: String 
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    init {
        // Inicializa a URL chamando a função de busca dentro de um bloco síncrono
        mainUrl = runBlocking {
            getWorkingDomain()
        }
    }
    
    // --- FUNÇÃO DE BUSCA DE DOMÍNIO (AGORA É SUSPEND) ---
    // A função precisa ser 'suspend' pois chama app.get()
    private suspend fun getWorkingDomain(): String {
        // Mantenha o último domínio conhecido como fallback seguro (ajuste para .hub se for mais recente)
        val fallbackDomain = "https://superflix.hub" 
        
        try {
            val searchQuery = "SuperFlix assistir filmes"
            val searchUrl = "https://www.google.com/search?q=$searchQuery"
            
            // Faz a requisição à página de resultados do Google.
            // Timeout ajustado para 5000 milissegundos (5 segundos) e 'timeUnit' removido.
            val searchPage = app.get(searchUrl, timeout = 5000)
            
            // Procura o primeiro link que contenha "superflix" no link (href) e seja uma URL completa
            val linkElement = searchPage.document.select("a").firstOrNull { 
                it.attr("href").contains("superflix") && it.attr("href").startsWith("http")
            }
            
            val fullUrl = linkElement?.attr("href")

            if (fullUrl != null) {
                // Limpa a URL para pegar apenas o domínio base
                val domainBase = fullUrl
                    .substringAfter("://") 
                    .substringBefore("/")
                    .substringBefore("?")

                // Retorna a URL limpa com o protocolo HTTPS.
                return "https://$domainBase"
            }
            
        } catch (e: Exception) {
            println("Erro na busca dinâmica de domínio para SuperFlix: ${e.message}")
        }
        
        // Se a busca falhar, retorna o domínio de fallback.
        return fallbackDomain 
    }
    
    // As páginas principais usam a mainUrl resolvida acima
    override val mainPage = mainPageOf(
        "$mainUrl/filmes/page/" to "Filmes",
        "$mainUrl/series/page/" to "Séries"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val items = doc.select("article.post").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    private fun org.jsoup.nodes.Element.toSearchResponse(): SearchResponse? {
        val title = selectFirst("h2")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.attr("src")
        return if (href.contains("/series/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article.post").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text() ?: ""
        val poster = doc.selectFirst(".poster img")?.attr("src")
        val plot = doc.selectFirst(".sinopse")?.text()
        val year = doc.selectFirst(".year")?.text()?.toIntOrNull()

        return if (url.contains("/series/")) {
            val episodes = doc.select(".episodios .episodio").mapNotNull { ep ->
                newEpisode(ep.attr("href")) {
                    name = ep.selectFirst(".titulo")?.text() ?: "Episódio"
                    season = ep.attr("data-season")?.toIntOrNull()
                    episode = ep.attr("data-episode")?.toIntOrNull()
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        doc.select("iframe").mapNotNull { it.attr("src") }.forEach {
            loadExtractor(it, data, subtitleCallback, callback)
        }

        doc.select("source[src]").forEach {
            val src = it.attr("src")
            callback(ExtractorLink(
                source = name,
                name = name,
                url = src,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
            ))
        }

        doc.select("track[kind=subtitles]").forEach {
            val lang = it.attr("label").ifBlank { "Português" }
            val url = it.attr("src")
            if (url.isNotBlank()) {
                // Use newSubtitleFile para evitar warnings de depreciação (Boa Prática!)
                subtitleCallback(SubtitleFile(lang, url))
            }
        }

        return true
    }
}
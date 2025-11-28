package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.LoadResponse.Companion.addMovie
import com.lagradost.cloudstream3.LoadResponse.Companion.addTvSeries

class SuperFlix : MainAPI() {
    // 1. Informações Básicas do Provedor
    override var mainUrl = "https://superflix20.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // 2. Mapeamento de Elementos HTML para SearchResponse (Resultados de Busca)
    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href"))

        // Pega o título do atributo 'alt' da imagem ou do texto <h3>
        val title = link.selectFirst("img")?.attr("alt")?.trim()
            ?: link.selectFirst("h3")?.text()?.trim()
            ?: return null

        val posterUrl = link.selectFirst("img")?.attr("src")

        // Determina o tipo (Filme ou Série) com base na URL
        val type = when {
            href.contains("/filme/") -> TvType.Movie
            href.contains("/serie/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        // CORREÇÃO CRÍTICA: Uso do construtor SearchResponse correto
        return SearchResponse(
            name = title,
            url = href,
            type = type,
            posterUrl = posterUrl,
        )
    }

    // 3. Função Auxiliar para Obter Itens de Lista de uma URL
    private suspend fun getListItems(url: String): List<SearchResponse> {
        val document = app.get(url).document
        // Seleciona todos os itens da lista principal
        return document.select("div.lista-de-filmes-e-series > a").mapNotNull { it.toSearchResult() }
    }

    // 4. Definição das Páginas Principais (Home Page)
    override val mainPage = mainPageOf(
        "/lancamentos?page=1&f=filmes" to "Lançamentos (Filmes)",
        "/lancamentos?page=1&f=series" to "Lançamentos (Séries)",
        "/filmes?page=1" to "Filmes Populares",
        "/series?page=1" to "Séries Populares"
    )

    // 5. Implementação da Busca da Página Principal
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            // Lógica para paginação, mantendo os filtros (f=filmes, f=series)
            val baseUrl = "$mainUrl${request.data.substringBeforeLast("page=")}page=$page"
            baseUrl
        }

        val home = getListItems(url)

        // CORREÇÃO: Fechando corretamente a estrutura da função
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            )
        )
    }

    // As funções 'search', 'load' e 'get and extract links' (que são o restante do provedor)
    // ainda não estão implementadas, mas o código já é compilável neste ponto.
}

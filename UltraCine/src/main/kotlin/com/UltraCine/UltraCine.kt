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

        val posterUrl = selectFirst("div.post-thumbnail figure img")?.attr("src")
            ?.takeIf { it.isNotBlank() } 
            ?.let { fixUrl(it).replace("/w500/", "/original/") }
            ?: selectFirst("div.post-thumbnail figure img")?.attr("data-src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it).replace("/w500/", "/original/") }

        val year = selectFirst("span.year")?.text()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(selectFirst("span.post-ql")?.text())
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
            val episodes = if (iframeUrl != null) {
                try {
                    val iframeDoc = app.get(iframeUrl).document
                    parseSeriesEpisodes(iframeDoc)
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()

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

    // FUNÇÃO MELHORADA PARA EXTRAIR EPISÓDIOS
    private fun parseSeriesEpisodes(doc: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        println("=== ANALISANDO EPISÓDIOS ===")
        
        // 1. PRIMEIRO: Encontra o padrão base da série
        // Procura por qualquer link que tenha o padrão /embed/NUMERO#NUMERO_NUMERO
        val allLinks = doc.select("a[href*='/embed/']")
        
        var seriesId = "" // Ex: 615 (ID fixo da série)
        var seasonId = "" // Ex: 13014 (ID da temporada)
        var baseEpisodeId = 0L // Ex: 250521 (ID base do episódio 1)
        
        // Tenta encontrar o padrão nos links
        for (link in allLinks) {
            val href = link.attr("href")
            println("🔗 Link encontrado: $href")
            
            // Procura padrão: /embed/615#13014_250521
            val pattern = Regex("""/embed/(\d+)#(\d+)_(\d+)""")
            val match = pattern.find(href)
            
            if (match != null) {
                seriesId = match.groupValues[1] // 615
                seasonId = match.groupValues[2] // 13014
                baseEpisodeId = match.groupValues[3].toLong() // 250521
                println("🎯 Padrão encontrado!")
                println("   Série ID: $seriesId")
                println("   Temporada ID: $seasonId") 
                println("   Episódio base ID: $baseEpisodeId")
                break
            }
        }
        
        if (seriesId.isBlank()) {
            println("❌ Não encontrou padrão de IDs")
            return emptyList()
        }
        
        // 2. AGORA: Extrai todos os episódios da interface
        doc.select("header.header ul.header-navigation li").forEach { seasonEl ->
            val seasonNum = seasonEl.attr("data-season-number").toIntOrNull() ?: return@forEach
            val seasonElId = seasonEl.attr("data-season-id")
            
            println("\n📺 TEMPORADA $seasonNum (ID: $seasonElId)")
            
            // Para cada temporada, precisa descobrir o seasonId correto
            var currentSeasonId = seasonId
            
            // Se tem mais de uma temporada, ajusta o ID
            // Temporada 1: 13014, Temporada 2: 13015, etc.
            if (seasonNum > 1) {
                // Incrementa o seasonId baseado no número da temporada
                currentSeasonId = (seasonId.toInt() + (seasonNum - 1)).toString()
                println("   🆔 Season ID ajustado: $currentSeasonId")
            }
            
            doc.select("li[data-season-id='$seasonElId']").forEachIndexed { index, epEl ->
                val epNum = index + 1
                
                // Calcula o episodeId: base + ((epNum - 1) * 2)
                // Ep 1: 250521, Ep 2: 250523, Ep 3: 250525, etc.
                val episodeId = (baseEpisodeId + ((epNum - 1) * 2)).toString()
                
                val title = epEl.selectFirst("a")?.text() ?: "Episódio $epNum"
                val cleanTitle = title.substringAfter(" - ").takeIf { it.isNotEmpty() } ?: title
                
                // Cria o link do player: /embed/SERIES_ID#SEASON_ID_EPISODE_ID
                val playerUrl = "https://assistirseriesonline.icu/embed/$seriesId#$currentSeasonId"_$episodeId"
                
                println("   ▶️ Ep $epNum: $cleanTitle")
                println("      URL: $playerUrl")
                
                episodes.add(newEpisode(playerUrl) {
                    this.name = cleanTitle
                    this.season = seasonNum
                    this.episode = epNum
                })
            }
        }
        
        println("\n✅ Total de episódios extraídos: ${episodes.size}")
        return episodes
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

    // VERSÃO SIMPLIFICADA DO loadLinks
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 ULTRA CINE loadLinks CHAMADO")
        println("📦 Data recebido: $data")
        
        if (data.isBlank()) return false

        return try {
            // Se a data é uma URL do player (vem do parseSeriesEpisodes)
            if (data.startsWith("https://assistirseriesonline.icu/embed/")) {
                println("🎯 URL DO PLAYER DETECTADA: $data")
                
                // Simplesmente passa a URL para o extractor padrão
                if (loadExtractor(data, data, subtitleCallback, callback)) {
                    println("✅ Extractors carregados com sucesso!")
                    return true
                } else {
                    println("❌ Nenhum extractor funcionou")
                    
                    // Fallback: tenta extrair diretamente da página
                    return extractFromPlayerPage(data, callback)
                }
            }
            
            // PARA FILMES (mantém original)
            val finalUrl = when {
                data.contains("ultracine.org/") && data.matches(Regex(".*/\\d+$")) -> {
                    val id = data.substringAfterLast("/")
                    "https://assistirseriesonline.icu/episodio/$id"
                }
                else -> data
            }

            val res = app.get(finalUrl, referer = mainUrl, timeout = 30)
            val doc = res.document
            
            // Tenta iframes
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && loadExtractor(src, finalUrl, subtitleCallback, callback)) {
                    return true
                }
            }
            
            false
        } catch (e: Exception) {
            println("💥 ERRO: ${e.message}")
            false
        }
    }
    
    // FUNÇÃO AUXILIAR PARA EXTRAIR DE PÁGINAS DE PLAYER
    private suspend fun extractFromPlayerPage(playerUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            println("🔍 Extraindo vídeo da página do player: $playerUrl")
            
            val res = app.get(playerUrl, timeout = 30)
            val html = res.text
            
            // Procura por iframes dentro do player
            val iframePattern = Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""")
            val iframeMatch = iframePattern.find(html)
            
            if (iframeMatch != null) {
                val iframeSrc = iframeMatch.groupValues[1]
                println("🎯 Iframe encontrado no player: $iframeSrc")
                
                // Cria um ExtractorLink simples
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        "${this.name} (Player)",
                        iframeSrc,
                        playerUrl,
                        Qualities.Unknown.value,
                        false
                    )
                )
                return true
            }
            
            // Procura por URLs de vídeo direto
            val videoPatterns = listOf(
                Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
                Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)"""),
                Regex("""<video[^>]+src=["'](https?://[^"']+)["']""")
            )
            
            for (pattern in videoPatterns) {
                val matches = pattern.findAll(html).toList()
                for (match in matches) {
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.isNotBlank() && 
                        (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4"))) {
                        println("🎬 Vídeo direto encontrado: $videoUrl")
                        
                        callback.invoke(
                            ExtractorLink(
                                this.name,
                                "${this.name} (Direct)",
                                videoUrl,
                                playerUrl,
                                extractQualityFromUrl(videoUrl),
                                videoUrl.contains(".m3u8")
                            )
                        )
                        return true
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            println("❌ Erro ao extrair do player: ${e.message}")
            false
        }
    }
    
    private fun extractQualityFromUrl(url: String): Int {
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
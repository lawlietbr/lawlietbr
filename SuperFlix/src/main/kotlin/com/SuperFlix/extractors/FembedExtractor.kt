package com.SuperFlix.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

class FembedExtractor : ExtractorApi() {
    override val name = "Fembed"
    override val mainUrl = "https://fembed.sx"
    override val requiresReferer = true
    
    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        
        try {
            // Extrair ID do vídeo
            val videoId = extractVideoId(url) ?: return links
            
            // Tentar domínios conhecidos
            val domains = listOf("www.fembed.com", "fembed.sx", "fembed.to", "feurl.com")
            
            for (domain in domains) {
                try {
                    val apiUrl = "https://$domain/api/source/$videoId"
                    
                    val response = app.post(
                        apiUrl,
                        headers = mapOf(
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "Referer" to "https://$domain/",
                            "X-Requested-With" to "XMLHttpRequest"
                        ),
                        data = mapOf("r" to "", "d" to domain)
                    )
                    
                    val json = response.parsedSafe<FembedResponse>()
                    if (json?.success == true) {
                        json.data?.forEach { stream ->
                            val file = stream.file ?: return@forEach
                            val label = stream.label ?: "Unknown"
                            val isM3u8 = file.contains(".m3u8")
                            
                            // Usando newExtractorLink com parâmetros corretos
                            // A ordem correta é: url, source, name, quality, referer, isM3u8
                            newExtractorLink(
                                url = file,
                                source = name,
                                name = label,
                                quality = getQuality(label),
                                referer = "https://$domain/",
                                isM3u8 = isM3u8
                            )?.let { link ->
                                links.add(link)
                            }
                        }
                        
                        if (links.isNotEmpty()) return links
                    }
                } catch (e: Exception) {
                    // Tenta próximo domínio
                    continue
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return links
    }
    
    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""/(?:e|v|f)/([a-zA-Z0-9]+(?:/[a-zA-Z0-9\-]+)?)"""),
            Regex("""(\d+(?:/\d+-\d+)?)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues.getOrNull(1)
            }
        }
        
        return null
    }
    
    private fun getQuality(label: String): Int {
        return when {
            label.contains("1080") -> Qualities.P1080.value
            label.contains("720") -> Qualities.P720.value
            label.contains("480") -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
    
    data class FembedResponse(
        @JsonProperty("success") val success: Boolean = false,
        @JsonProperty("data") val data: List<FembedStream>? = null
    )
    
    data class FembedStream(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("type") val type: String? = null
    )
}
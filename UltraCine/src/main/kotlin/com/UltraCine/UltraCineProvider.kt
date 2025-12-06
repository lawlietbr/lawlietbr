package com.UltraCine

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

// 1. IMPORTA DO SUBPACOTE 'extractors'
import com.UltraCine.extractors.PlayEmbedApiSite

// 2. IMPORTA DO PACOTE PRINCIPAL 'com.UltraCine' (onde estão os outros extratores)
import com.UltraCine.EmbedPlayUpnsInk // Este é o que lida com .ink

@CloudstreamPlugin
class UltraCineProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(UltraCine()) 
        
        // REGISTROS:
        registerExtractorAPI(PlayEmbedApiSite()) 
        registerExtractorAPI(EmbedPlayUpnsInk()) // Deve resolver o link .ink
    }
}

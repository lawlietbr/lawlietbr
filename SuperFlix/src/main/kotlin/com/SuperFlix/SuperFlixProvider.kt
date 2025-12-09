package com.SuperFlix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SuperFlixProviderPlugin: Plugin() {
    override fun load(context: Context) {
        println("SuperFlixProviderPlugin: load - INICIANDO PLUGIN")

        // Registrar o provider principal
        registerMainAPI(SuperFlix())
        println("SuperFlixProviderPlugin: load - SuperFlix provider registrado")

        // Não é necessário registrar extractors separadamente para providers customizados
        println("SuperFlixProviderPlugin: load - Plugin carregado com sucesso")
    }
}
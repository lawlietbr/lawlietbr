package com.SuperFlix

import android.content.Context // Import essencial
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin // <-- CLASSE PAI CORRIGIDA

@CloudstreamPlugin
// Mude o nome da classe pai de BasePlugin para Plugin
class SuperFlixProviderPlugin: Plugin() {
    
    // Assinatura correta da função 'load'
    override fun load(context: Context) {
        // Registra a API principal
        registerMainAPI(SuperFlix())
    }
}

package com.UltraCine

import com.lagradost.cloudstream3.extractors.VidStack

// Extratores para EmbedPlay (UpnsPro e UpnOne)
class EmbedPlayUpnsPro : VidStack() {
    override var name = "EmbedPlay UpnsPro"
    override var mainUrl = "https://embedplay.upns.pro"
}

class EmbedPlayUpnOne : VidStack() {
    override var name = "EmbedPlay UpnOne"
    override var mainUrl = "https://embedplay.upn.one"
}
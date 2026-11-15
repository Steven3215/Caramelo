package com.caramelo.vidbox

import android.content.Context
import com.caramelo.webcatalog.WebSettings
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VidBoxPlugin : Plugin() {
    override fun load(context: Context) {
        val settings = WebSettings(context, "VidBox", VidBoxProvider.rows, VidBoxProvider.servers)
        registerMainAPI(VidBoxProvider(settings))
        openSettings = { settings.show(it) }
    }
}

package com.caramelo.asiaflix

import android.content.Context
import com.caramelo.webcatalog.WebSettings
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AsiaFlixPlugin : Plugin() {
    override fun load(context: Context) {
        val settings = WebSettings(context, "AsiaFlix", AsiaFlixProvider.rows, AsiaFlixProvider.servers)
        registerMainAPI(AsiaFlixProvider(settings))
        openSettings = { settings.show(it) }
    }
}

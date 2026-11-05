package com.caramelo.corsflix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CorsflixPlugin : Plugin() {
    override fun load(context: Context) {
        val settings = CorsflixSettings(context)
        registerMainAPI(CorsflixProvider(settings))
        openSettings = { settings.show(it) }
    }
}

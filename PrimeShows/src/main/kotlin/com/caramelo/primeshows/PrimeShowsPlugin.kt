package com.caramelo.primeshows

import android.content.Context
import com.caramelo.webcatalog.WebSettings
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PrimeShowsPlugin : Plugin() {
    override fun load(context: Context) {
        val settings = WebSettings(context, "PrimeShows", PrimeShowsProvider.rows, PrimeShowsProvider.servers)
        registerMainAPI(PrimeShowsProvider(settings))
        openSettings = { settings.show(it) }
    }
}

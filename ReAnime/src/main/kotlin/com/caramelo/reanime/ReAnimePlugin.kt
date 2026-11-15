package com.caramelo.reanime

import android.content.Context
import com.caramelo.webcatalog.WebSettings
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ReAnimePlugin : Plugin() {
    override fun load(context: Context) {
        val settings = WebSettings(context, "ReAnime", ReAnimeProvider.rows, ReAnimeProvider.servers)
        registerMainAPI(ReAnimeProvider(settings))
        openSettings = { settings.show(it) }
    }
}

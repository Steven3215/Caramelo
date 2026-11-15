package com.caramelo.anipm

import android.content.Context
import com.caramelo.webcatalog.WebSettings
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniPmPlugin : Plugin() {
    override fun load(context: Context) {
        val settings = WebSettings(context, "AniPm", AniPmProvider.rows, AniPmProvider.servers)
        registerMainAPI(AniPmProvider(settings))
        openSettings = { settings.show(it) }
    }
}

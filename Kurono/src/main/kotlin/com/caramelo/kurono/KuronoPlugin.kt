package com.caramelo.kurono

import android.content.Context
import com.caramelo.webcatalog.WebSettings
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KuronoPlugin : Plugin() {
    override fun load(context: Context) {
        val settings = WebSettings(context, "Kurono", KuronoProvider.rows, KuronoProvider.servers)
        registerMainAPI(KuronoProvider(settings))
        openSettings = { settings.show(it) }
    }
}

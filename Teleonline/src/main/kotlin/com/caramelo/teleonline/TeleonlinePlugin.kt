package com.caramelo.teleonline

import android.content.Context
import com.caramelo.settings.ChannelRows
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TeleonlinePlugin : Plugin() {
    override fun load(context: Context) {
        val settings = ChannelRows(context, "caramelo_teleonline_v1", "Teleonline", TeleonlineDirectory.rows)
        registerMainAPI(TeleonlineProvider(settings))
        openSettings = { settings.show(it) }
    }
}

package com.caramelo.telelibre

import android.content.Context
import com.caramelo.settings.ChannelRow
import com.caramelo.settings.ChannelRows
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TeleLibrePlugin : Plugin() {
    override fun load(context: Context) {
        val rows = listOf(
            ChannelRow("all", "All live channels"), ChannelRow("general", "General"),
            ChannelRow("noticias", "News"), ChannelRow("deportes", "Sports"),
            ChannelRow("cine", "Movies and series"), ChannelRow("infantil", "Kids"),
            ChannelRow("documentales", "Documentaries"), ChannelRow("musica", "Music")
        )
        val settings = ChannelRows(context, "caramelo_telelibre_v1", "Tele Libre", rows)
        registerMainAPI(TeleLibreProvider(settings))
        openSettings = { settings.show(it) }
    }
}

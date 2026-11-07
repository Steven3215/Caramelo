package com.caramelo.justanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JustAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JustAnimeProvider())
    }
}

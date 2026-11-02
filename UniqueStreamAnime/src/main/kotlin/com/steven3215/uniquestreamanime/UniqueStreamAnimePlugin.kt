package com.steven3215.uniquestreamanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class UniqueStreamAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(UniqueStreamAnimeProvider())
    }
}

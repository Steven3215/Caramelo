package com.caramelo.primeshows

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PrimeShowsPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PrimeShowsProvider())
    }
}

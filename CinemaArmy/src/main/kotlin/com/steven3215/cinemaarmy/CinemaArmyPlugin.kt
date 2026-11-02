package com.steven3215.cinemaarmy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinemaArmyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CinemaArmyProvider())
    }
}

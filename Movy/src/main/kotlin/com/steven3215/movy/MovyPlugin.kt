package com.steven3215.movy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovyPlugin : Plugin() {
    override fun load(context: Context) {
        val settings = MovySettings(context)
        registerMainAPI(MovyProvider(settings))
        openSettings = { settings.show(it) }
    }
}

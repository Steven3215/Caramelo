package com.steven3215.globalfreetv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class GlobalFreeTvPlugin : Plugin() {
    override fun load(context: Context) {
        val settings = GlobalFreeTvSettings(context)
        registerMainAPI(GlobalFreeTvProvider(settings))
        openSettings = { settings.show(it) }
    }
}

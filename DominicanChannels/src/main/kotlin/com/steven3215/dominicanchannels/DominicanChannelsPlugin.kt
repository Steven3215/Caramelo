package com.steven3215.dominicanchannels

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DominicanChannelsPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DominicanChannelsProvider())
    }
}

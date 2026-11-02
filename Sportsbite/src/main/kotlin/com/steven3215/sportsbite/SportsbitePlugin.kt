package com.steven3215.sportsbite
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SportsbitePlugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(SportsbiteProvider()) }
}

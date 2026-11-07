package com.caramelo.thugtv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ThugTvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ThugTvProvider())
    }
}

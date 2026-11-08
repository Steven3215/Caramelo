package com.caramelo.vidbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VidBoxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VidBoxProvider())
    }
}

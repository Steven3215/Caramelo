package com.steven3215.cinemaarmy

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

/**
 * Skeleton only. The site's data API and player behavior must be verified
 * before implementing search, load, and loadLinks or enabling this provider.
 */
class CinemaArmyProvider : MainAPI() {
    override var mainUrl = "https://cinema.army"
    override var name = "Cinema Army (not implemented)"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie)
    override val hasMainPage = false
}

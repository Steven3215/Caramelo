package com.caramelo.anipm

import com.caramelo.webcatalog.WebCatalog
import com.caramelo.webcatalog.WebSettings
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI

internal class AniPmProvider(private val settings: WebSettings) : WebCatalog() {
    override var name = "Ani.pm"
    override var mainUrl = "https://ani.pm"
    override val moviePath = "anime"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    companion object {
        val rows = linkedMapOf(
            "browse?sort=trending" to "Trending",
            "catalog?sort=newest&status=RELEASING&avail=sourced" to "Currently Airing",
            "catalog?genre=Romance&sort=trending" to "Romance",
            "catalog?format=MOVIE&sort=trending" to "Anime Movies",
        )
        val servers = listOf("Ani.pm")
    }
    override val mainPage: List<MainPageData>
        get() = rows.filterKeys(settings::rowEnabled).map { (key, label) -> mainPage(key, label) }

    private fun artwork(path: String?): String? = path?.let { URI("$mainUrl/").resolve(it).toString() }
        ?.takeIf { webUrl(it) != null }

    private fun card(item: JsonNode): SearchResponse? {
        val title = item.text("title") ?: return null
        val route = item.text("routeId")?.takeIf { it.matches(Regex("[a-z0-9-]+")) } ?: return null
        val category = if (item.text("source") == "anilist") "ani" else "anime"
        val type = if (item.text("type").equals("MOVIE", true)) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, "$mainUrl/$category/$route", type) {
            posterUrl = artwork(item.text("poster"))
            year = item.path("year").asInt().takeIf { it > 0 }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data !in rows || !settings.rowEnabled(request.data)) return newHomePageResponse(request, emptyList(), false)
        val body = json("$mainUrl/api/anime/${request.data}&page=$page")
        return newHomePageResponse(request, body.items("items").mapNotNull(::card), body.path("hasNextPage").asBoolean())
    }

    override suspend fun search(query: String): List<SearchResponse> =
        if (query.isBlank()) emptyList() else json("$mainUrl/api/anime/search?q=${encode(query.trim())}")
            .items("items").mapNotNull(::card).distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse {
        val match = Regex("""/(anime|ani)/[a-z0-9-]+-(\d+)-[a-z0-9]+""").matchEntire(url.removePrefix(mainUrl))
            ?: throw ErrorLoadingException("Invalid Ani.pm title.")
        val (category, id) = match.destructured
        val api = if (category == "ani") "ani" else "series"
        val item = json("$mainUrl/api/anime/$api/$id?routes=e4", url)
        val title = item.text("title") ?: throw ErrorLoadingException("Ani.pm title unavailable.")
        val type = if (item.text("type").equals("MOVIE", true)) TvType.AnimeMovie else TvType.Anime
        return newAnimeLoadResponse(title, url, type) {
            posterUrl = artwork(item.text("poster"))
            backgroundPosterUrl = artwork(item.text("banner"))
            plot = item.text("synopsis")
            year = item.path("year").asInt().takeIf { it > 0 }
            tags = item.items("genres").map { it.asText() }
            for ((dub, channel) in listOf(DubStatus.Subbed to "sub", DubStatus.Dubbed to "dub")) {
                addEpisodes(dub, item.items("episodes").mapNotNull { ep ->
                    val number = ep.path("number").asInt().takeIf { it > 0 && ep.path(channel).asBoolean() } ?: return@mapNotNull null
                    newEpisode("$url?ep=$number&lang=$channel") {
                        episode = number
                        name = ep.text("title")
                        posterUrl = artwork(ep.text("thumbnail"))
                        description = ep.text("description")
                    }
                })
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (!settings.serverEnabled("Ani.pm")) return false
        val match = Regex("""/(anime|ani)/[a-z0-9-]+-(\d+)-[a-z0-9]+\?ep=(\d+)&lang=(sub|dub)""").matchEntire(data.removePrefix(mainUrl)) ?: return false
        val (category, id, episode, language) = match.destructured
        val page = data.substringBefore("&lang=")
        val source = if (category == "ani") "anilist" else "settlar"
        val bootstrap = json("$mainUrl/api/anime/playback-bootstrap/$source/$id?ep=$episode&lang=$language&core=0", page)
        if (bootstrap.text("effectiveLanguage") != language) return false
        val selection = bootstrap.text("settlarSelection") ?: return false
        val session = json("$mainUrl/api/anime/settlar/session?selection=${encode(selection)}&provider=anipm&ep=$episode&channel=$language&telemetry=0", page)
        val embed = session.text("embedUrl")?.takeIf { webUrl(it)?.host == "embed.settlar.io" } ?: return false
        val resolver = WebViewResolver(interceptUrl = Regex("\\.m3u8(?:[?#]|$)"), useOkhttp = false, timeout = 25000L)
        val req = resolver.resolveUsingWebView(embed, referer = page, headers = emptyMap()).first ?: return false
        val headers = listOf("Origin", "User-Agent", "Cookie").mapNotNull { key -> req.header(key)?.let { key to it } }.toMap()
        val link = hls("Ani.pm · $language", req.url.toString(), req.header("Referer") ?: embed, headers) ?: return false
        callback(link)
        return true
    }
}

package com.caramelo.asiaflix

import com.caramelo.webcatalog.WebCatalog
import com.caramelo.webcatalog.WebSettings
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI

internal class AsiaFlixProvider(private val preferences: WebSettings) : WebCatalog() {
    override var name = "AsiaFlix"
    override var mainUrl = "https://asiaflix.net"
    override val moviePath = "drama"
    companion object {
        val rows = linkedMapOf("all" to "All Titles")
        val servers = listOf("Vidmoly", "Streamtape", "Mixdrop")

        init {
            for (country in listOf("South Korea", "China", "Japan", "Thailand", "Hong Kong", "Taiwan", "Philippines")) {
                rows[country] = country
            }
        }
    }

    override val mainPage: List<MainPageData>
        get() = rows.filterKeys(preferences::rowEnabled).map { (key, label) -> mainPage(key, label) }

    private val titlePath = Regex("""/drama/([a-z0-9-]+)(?:/episode-([0-9]+))?/?""")
    private val playlistPattern = Regex("""\bfile\s*:\s*['"](https://[^'"\s]+\.m3u8(?:\?[^'"\s]+)?)['"]""")
    private val trackPattern = Regex("""\{[^{}]*\bkind\s*:\s*['"]captions['"][^{}]*\}""")
    private val trackFile = Regex("""\bfile\s*:\s*['"]([^'"]+)['"]""")
    private val trackLabel = Regex("""\blabel\s*:\s*['"]([^'"]+)['"]""")

    private suspend fun state(path: String, apiPath: String): JsonNode {
        val response = app.get("$mainUrl$path", headers = browser, referer = "$mainUrl/", timeout = 12)
        if (response.code !in 200..299) throw ErrorLoadingException("AsiaFlix returned HTTP ${response.code}.")
        val data = response.document.selectFirst("script#ng-state")?.data()
            ?: throw ErrorLoadingException("AsiaFlix data unavailable.")
        return mapper.readTree(data).firstOrNull { it.text("u")?.substringBefore('?')?.endsWith(apiPath) == true }?.path("b")
            ?: throw ErrorLoadingException("AsiaFlix data unavailable.")
    }

    private fun listing(node: JsonNode): SearchResponse? {
        val slug = node.text("slug")?.takeIf { it.matches(Regex("[a-z0-9-]+")) } ?: return null
        val title = node.text("name") ?: return null
        val url = "$mainUrl/drama/$slug"
        val poster = node.text("image")
        val released = node.text("releaseYear")?.toIntOrNull()
        return if (node.text("type").equals("Movie", true)) {
            newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster; year = released }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster; year = released }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data !in rows || !preferences.rowEnabled(request.data)) return newHomePageResponse(request, emptyList(), false)
        val country = if (request.data == "all") "" else "&country=${encode(request.data)}"
        val data = state("/shows?page=$page$country", "/v1/drama/list")
        return newHomePageResponse(request, data.items("body").mapNotNull(::listing), data.path("hasNext").asBoolean())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return state("/search?q=${encode(query.trim())}", "/v1/drama/search").items("body")
            .mapNotNull(::listing).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val match = titlePath.matchEntire(url.removePrefix(mainUrl)) ?: throw ErrorLoadingException("Invalid AsiaFlix title.")
        val path = "/drama/${match.groupValues[1]}"
        val item = state(path, "/v1/drama/detail")
        val title = item.text("name") ?: throw ErrorLoadingException("AsiaFlix title unavailable.")
        val episodes = item.items("episodes").filter { it.path("number").asInt() > 0 }
        val response = if (item.text("type").equals("Movie", true)) {
            val number = episodes.firstOrNull()?.path("number")?.asInt() ?: 1
            newMovieLoadResponse(title, "$mainUrl$path", TvType.Movie, "$mainUrl$path/episode-$number")
        } else {
            newTvSeriesLoadResponse(title, "$mainUrl$path", TvType.TvSeries, episodes.distinctBy { it.path("number").asInt() }.map { ep ->
                val number = ep.path("number").asInt()
                newEpisode("$mainUrl$path/episode-$number") {
                    episode = number
                    name = ep.text("title")
                }
            })
        }
        response.posterUrl = item.text("image")
        response.backgroundPosterUrl = item.text("coverImage")
        response.plot = item.text("description")?.let { Jsoup.parse(it).text() }
        response.year = item.text("releaseYear")?.toIntOrNull()
        response.tags = item.items("genres").mapNotNull { if (it.isTextual) it.asText() else it.text("name") }
        return response
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val match = titlePath.matchEntire(data.removePrefix(mainUrl)) ?: return false
        val (slug, number) = match.destructured
        val episode = number.toIntOrNull() ?: return false
        if (servers.none(preferences::serverEnabled)) throw ErrorLoadingException("Enable an AsiaFlix playback server in extension settings.")
        val item = state("/drama/$slug/episode-$number", "/v1/drama/detail")
        val sources = item.items("episodes").filter { it.path("number").asInt() == episode }.flatMap { it.items("streamUrls") }
        val seen = mutableSetOf<String>()
        for (source in sources) {
            val server = servers.firstOrNull { it.equals(source.text("source"), true) } ?: continue
            if (!preferences.serverEnabled(server)) continue
            val link = source.text("url") ?: continue
            val host = webUrl(link)?.host ?: continue
            attempt {
                when {
                    server == "Vidmoly" && host in setOf("vidmoly.biz", "vidmoly.to", "vidmoly.me", "vidmoly.net") -> {
                        val page = app.get(link, headers = browser, referer = "$mainUrl/", timeout = 10)
                        val playlist = playlistPattern.find(page.text)?.groupValues?.get(1)
                            ?.let { Parser.unescapeEntities(it, false) } ?: return@attempt
                        val origin = "https://$host"
                        val stream = hls("AsiaFlix · Vidmoly", playlist, "$origin/", browser + ("Origin" to origin)) ?: return@attempt
                        if (seen.add(stream.url)) callback(stream)
                        trackPattern.findAll(page.text).forEach { track ->
                            val file = trackFile.find(track.value)?.groupValues?.get(1) ?: return@forEach
                            val url = URI(link).resolve(Parser.unescapeEntities(file, false)).toString()
                            val label = trackLabel.find(track.value)?.groupValues?.get(1) ?: "Unknown"
                            if (webUrl(url) != null && seen.add(url)) subtitleCallback(newSubtitleFile(label, url))
                        }
                    }
                    server == "Streamtape" && host in setOf("streamtape.com", "streamtape.to") ||
                        server == "Mixdrop" && host in setOf("mixdrop.top", "mixdrop.ps", "mixdrop.co") -> {
                        loadExtractor(link, "$mainUrl/", subtitleCallback) { stream ->
                            if (seen.add(stream.url)) callback(stream)
                        }
                    }
                }
            }
        }
        return seen.isNotEmpty()
    }
}

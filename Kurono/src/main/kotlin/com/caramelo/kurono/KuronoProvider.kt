package com.caramelo.kurono

import com.caramelo.webcatalog.WebCatalog
import com.caramelo.webcatalog.WebSettings
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import java.net.URI

internal class KuronoProvider(private val preferences: WebSettings) : WebCatalog() {
    override var name = "Kurono"
    override var mainUrl = "https://kurono.nl"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val moviePath = "movies"

    companion object {
        val rows = linkedMapOf("sort=rating" to "Top Rated", "sort=newest" to "Recently Added", "type=TV" to "Series", "type=Movie" to "Movies")
        val servers = listOf("Subtitled", "Dubbed")
    }

    override val mainPage: List<MainPageData>
        get() = rows.filterKeys(preferences::rowEnabled).map { (key, label) -> mainPage(key, label) }

    private val titlePath = Regex("""/(series|movies)/([a-z0-9-]+)/?""")
    private val episodePath = Regex("""/watch/([a-z0-9-]+)/(\d+)\?season=(\d+)&dub=([01])""")

    private fun absolute(value: String?): String? = value?.let {
        runCatching { URI("$mainUrl/").resolve(it).toString() }.getOrNull()
    }?.takeIf { webUrl(it) != null }

    private suspend fun document(url: String): Document {
        val response = app.get(url, headers = browser, referer = "$mainUrl/", timeout = 15)
        if (response.code !in 200..299) throw ErrorLoadingException("Kurono returned HTTP ${response.code}.")
        return response.document
    }

    private fun card(item: JsonNode): SearchResponse? {
        val slug = item.text("slug")?.takeIf { it.matches(Regex("[a-z0-9-]+")) } ?: return null
        val title = item.text("title") ?: return null
        val movie = item.text("type").equals("Movie", true)
        val category = if (movie) "movies" else "series"
        return newAnimeSearchResponse(title, "$mainUrl/$category/$slug", if (movie) TvType.AnimeMovie else TvType.Anime) {
            posterUrl = absolute(item.text("imgSeed"))
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page < 1 || request.data !in rows || !preferences.rowEnabled(request.data)) return newHomePageResponse(request, emptyList(), false)
        val body = json("$mainUrl/api/catalog-browse?${request.data}&page=$page")
        val items = body.items("items")
        return newHomePageResponse(request, items.mapNotNull(::card), items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return json("$mainUrl/api/catalog-search?q=${encode(query.trim())}&limit=30").items("items")
            .mapNotNull(::card).distinctBy { it.url }
    }

    private fun findDetails(node: JsonNode, slug: String): JsonNode? {
        if (node.isObject && node.text("slug") == slug && node.has("description")) return node
        return if (node.isContainerNode) node.firstNotNullOfOrNull { findDetails(it, slug) } else null
    }

    private fun details(doc: Document, slug: String): JsonNode? {
        val payload = doc.select("script").mapNotNull { script ->
            val call = script.data().trim()
            if (!call.startsWith("self.__next_f.push([1,")) return@mapNotNull null
            val json = call.substringAfter("self.__next_f.push(").removeSuffix(";").removeSuffix(")")
            runCatching { mapper.readTree(json).path(1).asText() }.getOrNull()
        }.joinToString("")
        return payload.lineSequence().firstNotNullOfOrNull { line ->
            val value = line.substringAfter(':', "")
            if (value.firstOrNull() != '[' && value.firstOrNull() != '{') return@firstNotNullOfOrNull null
            runCatching { findDetails(mapper.readTree(value), slug) }.getOrNull()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val route = titlePath.matchEntire(url.removePrefix(mainUrl)) ?: throw ErrorLoadingException("Invalid Kurono title.")
        val (category, slug) = route.destructured
        val item = details(document(url), slug) ?: throw ErrorLoadingException("Kurono details unavailable.")
        val title = item.text("title") ?: throw ErrorLoadingException("Kurono title unavailable.")
        val movie = category == "movies"
        val episodes = item.items("episodes")
        return newAnimeLoadResponse(title, url, if (movie) TvType.AnimeMovie else TvType.Anime) {
            posterUrl = absolute(item.text("imgSeed"))
            backgroundPosterUrl = absolute(item.text("bannerSeed"))
            plot = item.text("description")
            year = item.path("year").asInt().takeIf { it > 0 }
            tags = item.items("genres").map { it.asText() }
            for ((index, dub) in listOf(DubStatus.Subbed, DubStatus.Dubbed).withIndex()) {
                if (!preferences.serverEnabled(servers[index])) continue
                addEpisodes(dub, episodes.mapNotNull { ep ->
                    val number = ep.path("number").asInt().takeIf { it > 0 } ?: return@mapNotNull null
                    val seasonNumber = ep.path("season").asInt(1).coerceAtLeast(1)
                    newEpisode("$mainUrl/watch/$slug/$number?season=$seasonNumber&dub=$index") {
                        episode = number
                        season = seasonNumber
                        name = ep.text("title")
                        description = ep.text("description")
                    }
                }.ifEmpty {
                    if (movie) listOf(newEpisode("$mainUrl/watch/$slug/1?season=1&dub=$index") { episode = 1 })
                    else emptyList()
                })
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val route = episodePath.matchEntire(data.removePrefix(mainUrl)) ?: return false
        val (slug, episode, season, dubbed) = route.destructured
        val label = servers[dubbed.toInt()]
        if (!preferences.serverEnabled(label)) return false
        val query = "slug=$slug&ep=$episode&season=$season" + if (dubbed == "1") "&dub=1" else ""
        val watchPage = "$mainUrl/watch/$slug/$episode"
        val body = json("$mainUrl/api/stream-resolve?$query", watchPage)
        val playlist = absolute(body.text("m3u8")) ?: return false
        val stream = hls("Kurono · $label", playlist, watchPage, browser) ?: return false
        for (track in body.items("subtitles")) {
            val file = absolute(track.text("url") ?: track.text("file") ?: track.text("src")) ?: continue
            subtitleCallback(newSubtitleFile(track.text("label") ?: track.text("lang") ?: "Unknown", file))
        }
        callback(stream)
        return true
    }
}

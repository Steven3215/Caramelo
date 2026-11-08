package com.caramelo.vidbox

import com.fasterxml.jackson.databind.JsonNode
import com.caramelo.webcatalog.WebCatalog
import com.caramelo.webcatalog.WebTitle
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import java.net.URI

internal class VidBoxProvider : WebCatalog() {
    override var name = "VidBox"
    override var mainUrl = "https://vidbox.wtf"
    override val moviePath = "movie"
    override val mainPage = mainPageOf(
        "movie&trending=true" to "Trending Movies",
        "tv&trending=true" to "Trending TV",
        "movie" to "Popular Movies",
        "tv" to "Popular TV",
        "movie&genre=28" to "Action Movies",
        "movie&genre=27" to "Horror Movies",
    )

    private val player = "https://vixsrc.to"
    private val playerHeaders = browser + ("Origin" to player)
    private val playlistObject = Regex("""window\.masterPlaylist\s*=\s*\{\s*params:\s*\{([^}]*)}\s*,\s*url:\s*['"]([^'"]+)['"]""")
    private val playlistParameter = Regex("""['"](token|expires|asn)['"]\s*:\s*['"]([^'"]+)['"]""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val body = json("$mainUrl/api/search/discover?type=${request.data}&page=$page")
        val type = request.data.substringBefore('&')
        return newHomePageResponse(request, body.items("results").mapNotNull { card(it, type) }, page < body.path("total_pages").asInt(page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val body = json("$mainUrl/api/search/suggest?q=${encode(query.trim())}")
        return body.items("data").mapNotNull { card(it) }.distinctBy { it.url }
    }

    private fun findSeries(node: JsonNode, id: Int): JsonNode? {
        if (node.isObject && node.path("id").asInt() == id && node.path("seasons").isArray) return node
        return if (node.isContainerNode) node.firstNotNullOfOrNull { findSeries(it, id) } else null
    }

    private fun series(document: Document, id: Int): JsonNode? {
        // Next.js emits the server-rendered series details across ordered Flight chunks.
        val payload = document.select("script").mapNotNull { script ->
            val call = script.data().trim()
            if (!call.startsWith("self.__next_f.push([1,")) return@mapNotNull null
            val json = call.substringAfter("self.__next_f.push(").removeSuffix(";").removeSuffix(")")
            runCatching { mapper.readTree(json).path(1).asText() }.getOrNull()
        }.joinToString("")
        return payload.lineSequence().firstNotNullOfOrNull { line ->
            val value = line.substringAfter(':', "")
            if (value.firstOrNull() != '[' && value.firstOrNull() != '{') return@firstNotNullOfOrNull null
            runCatching { findSeries(mapper.readTree(value), id) }.getOrNull()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val item = parseTitle(url) ?: throw ErrorLoadingException("Invalid VidBox title.")
        val canonical = url(item.copy(season = null, episode = null))
        val page = app.get(canonical, headers = browser, referer = "$mainUrl/home", timeout = 12)
        if (page.code !in 200..299) throw ErrorLoadingException("VidBox title unavailable (HTTP ${page.code}).")
        val document = page.document
        if (item.type == "movie") {
            val title = document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
                ?: throw ErrorLoadingException("VidBox title details unavailable.")
            val poster = document.select("img[alt]").firstOrNull { it.attr("alt") == title }
            val metadata = mapper.createObjectNode().apply {
                put("title", title)
                put("overview", document.selectFirst("meta[property=og:description]")?.attr("content"))
                put("poster_url", poster?.absUrl("src"))
                put("backdrop_url", document.selectFirst("meta[property=og:image]")?.absUrl("content"))
            }
            return details(item, metadata)
        }
        val metadata = series(document, item.id) ?: throw ErrorLoadingException("VidBox seasons unavailable.")
        val episodes = metadata.items("seasons").flatMap { entry ->
            val season = entry.path("season_number").asInt(-1)
            if (season < 0) return@flatMap emptyList()
            (1..entry.path("episode_count").asInt()).map { number ->
                newEpisode(url(WebTitle("tv", item.id, season, number))) {
                    this.season = season
                    episode = number
                }
            }
        }
        return details(item, metadata, episodes)
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val item = parseTitle(data) ?: return false
        if (item.type == "tv" && (item.season == null || item.episode == null)) return false
        val route = item.playbackPath
        val src = json("$player/api/$route", "$player/$route", playerHeaders).text("src") ?: return false
        val embed = URI("$player/").resolve(src)
        if (embed.host != URI(player).host || embed.scheme != "https" || embed.port != -1 || embed.userInfo != null ||
            !embed.path.startsWith("/embed/")) return false
        val page = app.get(embed.toString(), headers = playerHeaders, referer = "$player/$route", timeout = 12)
        if (page.code !in 200..299) return false
        val settings = playlistObject.find(page.text) ?: return false
        val endpoint = webUrl(settings.groupValues[2]) ?: return false
        if (endpoint.host != URI(player).host || !endpoint.path.startsWith("/playlist/")) return false
        val parameters = playlistParameter.findAll(settings.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
        if ("token" !in parameters || "expires" !in parameters) return false
        val query = parameters.entries.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        val hd = if ("window.canPlayFHD = true" in page.text) "&h=1" else ""
        val link = hls("VidBox · Vidpro", "$endpoint?$query$hd", "$player/", playerHeaders) ?: return false
        callback(link)
        return true
    }
}

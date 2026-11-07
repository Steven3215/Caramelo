package com.caramelo.corsflix

import com.caramelo.tracks.MediaTracks
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal class CorsflixProvider(private val settings: CorsflixSettings) : MainAPI() {
    override var name = "Corsflix"
    override var mainUrl = CorsflixProtocol.BASE
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override var sequentialMainPage = true
    override val mainPage: List<MainPageData>
        get() = CorsflixProtocol.rows.filter { settings.rowEnabled(it.key) }.map { (key, label) -> mainPage(key, label) }
    private val mapper = ObjectMapper()
    private val gate = Semaphore(4)
    private val headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Origin" to mainUrl, "Accept" to "application/json")
    private val cache = ConcurrentHashMap<String, Pair<Long, JsonNode>>()
    private suspend fun catalog(path: String): JsonNode {
        cache[path]?.takeIf { System.currentTimeMillis() - it.first < 300000 }?.let { return it.second }
        val response = gate.withPermit { app.get(CorsflixProtocol.CATALOG + path, headers = headers, referer = "$mainUrl/", timeout = 20) }
        if (response.code !in 200..299) throw ErrorLoadingException("Corsflix catalog unavailable (HTTP ${response.code})")
        val body = mapper.readTree(response.text)
        if (cache.size > 200) cache.clear()
        cache[path] = System.currentTimeMillis() to body
        return body
    }
    private fun image(path: String?): String? = path?.takeIf { it.startsWith('/') && !it.startsWith("//") }
        ?.let { "https://image.tmdb.org/t/p/w780$it" }
    private fun year(item: JsonNode): Int? = (item.text("release_date") ?: item.text("first_air_date"))?.take(4)?.toIntOrNull()
    private fun title(item: JsonNode) = item.text("title") ?: item.text("name") ?: "Unknown"
    private fun searchItem(item: JsonNode, fallback: String?): SearchResponse? {
        val type = item.text("media_type") ?: fallback ?: return null
        val id = item.get("id")?.asInt()?.takeIf { it > 0 } ?: return null
        if (type !in setOf("movie", "tv")) return null
        val url = CorsflixProtocol.url(CorsflixItem(type, id))
        return if (type == "movie") newMovieSearchResponse(title(item), url, TvType.Movie) {
            posterUrl = image(item.text("poster_path")); year = year(item)
        } else newTvSeriesSearchResponse(title(item), url, TvType.TvSeries) {
            posterUrl = image(item.text("poster_path")); year = year(item)
        }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page < 1 || !settings.rowEnabled(request.data)) return newHomePageResponse(request, emptyList(), false)
        val path = when (request.data) {
            "trending" -> "/trending/all/week"
            "movies" -> "/movie/popular"
            "tv" -> "/tv/popular"
            "top-movies" -> "/movie/top_rated"
            "top-tv" -> "/tv/top_rated"
            "new-movies" -> "/movie/now_playing"
            "airing-tv" -> "/tv/airing_today"
            else -> mapOf("action" to 28, "comedy" to 35, "drama" to 18, "horror" to 27, "animation" to 16, "documentary" to 99)[request.data]
                ?.let { "/discover/movie?with_genres=$it&sort_by=popularity.desc&include_adult=false" }
                ?: return newHomePageResponse(request, emptyList(), false)
        }
        val body = catalog(path + (if ('?' in path) "&" else "?") + "page=$page&language=en-US")
        val fallback = if (path.startsWith("/tv/")) "tv" else if (request.data != "trending") "movie" else null
        return newHomePageResponse(request, body.items("results").mapNotNull { searchItem(it, fallback) }, page < (body.get("total_pages")?.asInt() ?: page))
    }
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val result = catalog("/search/query?query=${CorsflixProtocol.encode(query.trim())}&page=1&language=en-US&include_adult=false")
        return result.items("results").mapNotNull { searchItem(it, null) }
    }
    override suspend fun load(url: String): LoadResponse {
        val item = CorsflixProtocol.item(url)
        val data = catalog("/${item.type}/${item.id}?append_to_response=external_ids&language=en-US")
        val canonical = CorsflixProtocol.url(item.copy(season = null, episode = null))
        val genres = data.items("genres").mapNotNull { it.text("name") }
        if (item.type == "movie") return newMovieLoadResponse(title(data), canonical, TvType.Movie, canonical) {
            posterUrl = image(data.text("poster_path")); backgroundPosterUrl = image(data.text("backdrop_path"))
            year = year(data); plot = data.text("overview"); tags = genres; duration = data.get("runtime")?.asInt()
            addScore(data.text("vote_average"))
        }
        val seasons = data.items("seasons").filter { (it.get("episode_count")?.asInt() ?: 0) > 0 }.mapNotNull { it.get("season_number")?.asInt() }.distinct().sorted()
        val episodes = coroutineScope {
            seasons.map { season -> async {
                catalog("/tv/${item.id}/season/$season?language=en-US").items("episodes").mapNotNull { ep ->
                    val number = ep.get("episode_number")?.asInt()?.takeIf { it > 0 } ?: return@mapNotNull null
                    newEpisode(CorsflixProtocol.url(item.copy(season = season, episode = number))) {
                        this.season = season; episode = number; name = ep.text("name")
                        posterUrl = image(ep.text("still_path")); description = ep.text("overview")
                    }
                }
            } }.awaitAll().flatten()
        }
        return newTvSeriesLoadResponse(title(data), canonical, TvType.TvSeries, episodes) {
            posterUrl = image(data.text("poster_path")); backgroundPosterUrl = image(data.text("backdrop_path"))
            year = year(data); plot = data.text("overview"); tags = genres
            addScore(data.text("vote_average"))
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        val mediaTracks = MediaTracks(subtitleCallback)
        val item = CorsflixProtocol.item(data)
        if (item.type == "tv" && item.episode == null) throw ErrorLoadingException("Choose an episode first")
        val found = linkedSetOf<String>()
        suspend fun emit(link: String, server: String, referer: String?, requestHeaders: Map<String, String>) {
            if (!CorsflixProtocol.webUrl(link) || link in found) return
            val response = app.get(link, headers = requestHeaders, referer = referer, timeout = 12)
            if (response.code !in 200..299 || !response.text.trimStart().startsWith("#EXTM3U")) return
            found.add(link)
            mediaTracks.publish(newExtractorLink(name, "$name – $server", link, ExtractorLinkType.M3U8) {
                this.referer = referer.orEmpty()
                this.headers = requestHeaders
            }, callback, response.text, response.url)
        }
        if ("twoembed" in settings.servers()) try {
            val embed = if (item.type == "movie") "https://www.2embed.cc/embed/${item.id}"
                else "https://www.2embed.cc/embedtv/${item.id}&s=${item.season}&e=${item.episode}"
            val page = app.get(embed, referer = "$mainUrl/", timeout = 15).document
            val inner = page.selectFirst("iframe#iframesrc")?.attr("data-src")
                ?.takeIf { URI(it).host == "streamsrcs.2embed.cc" }
            if (inner != null) {
                val frame = app.get(inner, referer = embed, timeout = 15).document
                val code = frame.selectFirst("iframe#framesrc")?.attr("src")
                    ?.takeIf { Regex("[A-Za-z0-9]{6,40}").matches(it) }
                if (code != null) {
                    val player = "https://2vcdn.skin/e/$code"
                    val html = app.get(player, referer = inner, timeout = 15).text
                    val unpacked = getAndUnpack(html)
                    for (link in CorsflixProtocol.playlists(unpacked)) {
                        emit(link, "2Embed", player, mapOf("Origin" to "https://2vcdn.skin"))
                    }
                }
            }
        } catch (error: Exception) { if (error is CancellationException) throw error }
        if (found.isEmpty() && "rive" in settings.servers() && !isCasting) try {
            val embed = "https://rivestream.ru/embed?type=${item.type}&id=${item.id}" +
                (if (item.type == "tv") "&season=${item.season}&episode=${item.episode}" else "")
            val resolver = WebViewResolver(interceptUrl = Regex("\\.m3u8(?:[?#]|$)"), useOkhttp = false, timeout = 25000L)
            val request = resolver.resolveUsingWebView(embed, referer = "$mainUrl/", headers = emptyMap()).first
            if (request != null && request.method == "GET") {
                val mediaHeaders = listOf("Origin", "User-Agent").mapNotNull { key ->
                    request.header(key)?.let { key to it }
                }.toMap()
                emit(request.url.toString(), "Rive", request.header("Referer"), mediaHeaders)
            }
        } catch (error: Exception) { if (error is CancellationException) throw error }
        if (found.isEmpty()) throw ErrorLoadingException("Corsflix's third-party players are unavailable. The Rive fallback requires Android WebView; try again later or another source.")
        return true
    }
}

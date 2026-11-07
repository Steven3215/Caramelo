package com.steven3215.movy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal class MovyProvider(private val settings: MovySettings) : MainAPI() {
    override var name = "Movy"
    override var mainUrl = MovyProtocol.BASE
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override var sequentialMainPage = true
    override val mainPage: List<MainPageData>
        get() = MovyProtocol.rows.filter { settings.rowEnabled(it.key) }.map { (key, label) -> mainPage(key, label) }
    private val mapper = ObjectMapper()
    private val gate = Semaphore(4)
    private val headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Origin" to mainUrl, "Accept" to "application/json")
    private val cache = ConcurrentHashMap<String, Pair<Long, JsonNode>>()
    private suspend fun catalog(path: String): JsonNode {
        cache[path]?.takeIf { System.currentTimeMillis() - it.first < 300000 }?.let { return it.second }
        val response = gate.withPermit { app.get(MovyProtocol.CATALOG + path, headers = headers, referer = "$mainUrl/", timeout = 20) }
        if (response.code !in 200..299) throw ErrorLoadingException("Movy catalog unavailable (HTTP ${response.code})")
        val body = mapper.readTree(response.text)
        if (cache.size > 200) cache.clear()
        cache[path] = System.currentTimeMillis() to body
        return body
    }
    private fun image(path: String?): String? = path?.takeIf { it.startsWith('/') && !it.startsWith("//") }
        ?.let { "$mainUrl/img/w780$it/q70.webp" }
    private fun year(item: JsonNode): Int? = (item.text("release_date") ?: item.text("first_air_date"))?.take(4)?.toIntOrNull()
    private fun title(item: JsonNode) = item.text("title") ?: item.text("name") ?: "Unknown"
    private fun searchItem(item: JsonNode, fallback: String?): SearchResponse? {
        val type = item.text("media_type") ?: fallback ?: return null
        val id = item.get("id")?.asInt()?.takeIf { it > 0 } ?: return null
        if (type !in setOf("movie", "tv")) return null
        val url = MovyProtocol.url(MovyItem(type, id))
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
        val result = catalog("/search/multi?query=${MovyProtocol.encode(query.trim())}&page=1&language=en-US&include_adult=false")
        return result.items("results").mapNotNull { searchItem(it, null) }
    }
    override suspend fun load(url: String): LoadResponse {
        val item = MovyProtocol.item(url)
        val data = catalog("/${item.type}/${item.id}?append_to_response=external_ids&language=en-US")
        val canonical = MovyProtocol.url(item.copy(season = null, episode = null))
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
                    newEpisode(MovyProtocol.url(item.copy(season = season, episode = number))) {
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
    private suspend fun seed(id: Int): String {
        val response = gate.withPermit { app.get("${MovyProtocol.STREAMS}/seed?mediaId=$id", headers = headers, referer = "$mainUrl/", timeout = 15) }
        if (response.code !in 200..299) throw ErrorLoadingException("Movy stream seed unavailable (HTTP ${response.code})")
        return mapper.readTree(response.text).text("seed") ?: throw ErrorLoadingException("Invalid Movy stream seed")
    }
    private suspend fun sources(server: String, item: MovyItem, details: JsonNode, initialSeed: String): JsonNode? {
        val params = linkedMapOf(
            "title" to MovyProtocol.encode(title(details)), "mediaType" to item.type,
            "year" to year(details)?.toString().orEmpty(), "tmdbId" to item.id.toString(),
            "imdbId" to (details.text("imdb_id") ?: details.get("external_ids")?.text("imdb_id")).orEmpty(),
            "totalSeasons" to (details.get("number_of_seasons")?.asInt() ?: 0).toString(),
            "seasonId" to (item.season ?: 1).toString(), "episodeId" to (item.episode ?: 1).toString(), "enc" to "2"
        )
        if (server == "munich") params["language"] = "german"
        var nonce = initialSeed
        repeat(2) { attempt ->
            val query = (params + ("seed" to nonce)).entries.joinToString("&") { "${it.key}=${MovyProtocol.encode(it.value)}" }
            val response = gate.withPermit { app.get("${MovyProtocol.STREAMS}/$server/sources?$query", headers = headers, referer = "$mainUrl/", timeout = 25) }
            if (response.code == 401 && attempt == 0) nonce = seed(item.id)
            else {
                if (response.code !in 200..299) return null
                val text = response.text.trim()
                val encoded = if (text.startsWith('"')) mapper.readTree(text).asText() else text
                return mapper.readTree(MovyProtocol.decode(encoded, nonce, item.id))
            }
        }
        return null
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        val item = MovyProtocol.item(data)
        if (item.type == "tv" && (item.season == null || item.episode == null)) throw ErrorLoadingException("Choose an episode first")
        val selected = settings.servers()
        if (selected.isEmpty()) throw ErrorLoadingException("Enable at least one Movy playback server in extension settings")
        val details = catalog("/${item.type}/${item.id}?append_to_response=external_ids&language=en-US")
        val nonce = seed(item.id)
        val resolved = coroutineScope { selected.map { server -> async {
            try { sources(server, item, details, nonce)?.let { server to it } }
            catch (error: Exception) { if (error is CancellationException) throw error; null }
        } }.awaitAll().filterNotNull() }
        val subtitles = resolved.flatMap { it.second.items("subtitles") }.distinctBy { it.text("url") }
        for (sub in subtitles) {
            val url = sub.text("url")?.takeIf { MovyProtocol.webUrl(it) } ?: continue
            subtitleCallback(SubtitleFile(sub.text("language") ?: sub.text("lang") ?: sub.text("label") ?: "Unknown", url))
        }
        val streams = resolved.flatMap { (server, body) -> body.items("sources").filter {
            when (server) { "austin" -> it.text("quality") == "English"; "delhi" -> it.text("quality") == "Hindi"; else -> true }
        }.map { server to it } }.filter { MovyProtocol.webUrl(it.second.text("url")) }.distinctBy { it.second.text("url") }.take(48)
        val verified = coroutineScope { streams.map { (server, source) -> async {
            val url = source.text("url").orEmpty()
            try {
                val video = URI(url).path.endsWith(".mp4", true)
                if (!video) {
                    val response = gate.withPermit { app.get(url, headers = headers - "Accept", referer = "$mainUrl/", timeout = 12) }
                    if (response.code !in 200..299 || !response.text.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("#EXTM3U")) return@async null
                }
                Triple(server, source, if (video) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8)
            } catch (error: Exception) { if (error is CancellationException) throw error; null }
        } }.awaitAll().filterNotNull() }
        for ((server, source, type) in verified) {
            callback(newExtractorLink(name, "$name – ${MovyProtocol.servers[server]} · ${source.text("quality") ?: "Auto"}", source.text("url").orEmpty(), type) {
                referer = "$mainUrl/"; quality = getQualityFromName(source.text("quality")); this.headers = this@MovyProvider.headers - "Accept"
            })
        }
        if (verified.isEmpty()) throw ErrorLoadingException("No working Movy stream was found. Try another server in extension settings.")
        return true
    }
}

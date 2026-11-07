package com.caramelo.thugtv

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CancellationException

class ThugTvProvider : MainAPI() {
    override var name = "ThugTV"
    override var mainUrl = "https://thugtv.online"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val mainPage = mainPageOf("trending" to "Trending Movies", "popular" to "Popular Movies",
        "trending-tv" to "Trending TV", "popular-tv" to "Popular TV", "top-rated" to "Top Rated Movies")
    private val apiBase = "https://api.thugtv.online/api"
    private val headers get() = mapOf("Origin" to mainUrl)
    private val json = ObjectMapper()
    private val gate = Semaphore(4)
    private class HomeRow {
        val lock = Mutex()
        val pages = mutableListOf<List<SearchResponse>>()
        val seen = mutableSetOf<String>()
        var updatedAt = 0L
    }
    private val homeRows = mainPage.associate { it.data to HomeRow() }
    private fun JsonNode.string(key: String) = get(key)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
    private fun image(path: String?) = path?.takeIf { it.startsWith('/') && !it.startsWith("//") }?.let { "https://image.tmdb.org/t/p/w780$it" }
    private fun title(item: JsonNode) = item.string("title") ?: item.string("name")
    private fun year(item: JsonNode) = (item.string("release_date") ?: item.string("first_air_date"))?.take(4)?.toIntOrNull()
    private suspend fun api(path: String): JsonNode {
        val response = gate.withPermit { app.get(apiBase + path, headers = headers, referer = "$mainUrl/", timeout = 22) }
        if (response.code !in 200..299) throw ErrorLoadingException("ThugTV API unavailable (HTTP ${response.code})")
        return json.readTree(response.text)
    }
    private fun card(item: JsonNode, fallback: String?): SearchResponse? {
        if (item.path("adult").asBoolean() || item.path("softcore").asBoolean()) return null
        val id = item.path("id").asInt().takeIf { it > 0 } ?: return null
        val name = title(item) ?: return null
        return when (item.string("media_type") ?: fallback) {
            "movie" -> newMovieSearchResponse(name, "$mainUrl/movie/$id") { posterUrl = image(item.string("poster_path")); year = year(item) }
            "tv" -> newTvSeriesSearchResponse(name, "$mainUrl/tv/$id") { posterUrl = image(item.string("poster_path")); year = year(item) }
            else -> null
        }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val row = homeRows[request.data]
        if (page < 1 || row == null) return newHomePageResponse(request, emptyList(), false)
        return row.lock.withLock {
            if (page == 1 && System.currentTimeMillis() - row.updatedAt >= 60_000L) {
                row.pages.clear()
                row.seen.clear()
            }
            val fallback = if (request.data.endsWith("-tv")) "tv" else "movie"
            while (row.pages.size < page && row.pages.lastOrNull()?.isEmpty() != true) {
                val next = row.pages.size + 1
                val items = api("/tmdb/${request.data}?page=$next").mapNotNull { card(it, fallback) }
                    .filter { row.seen.add(it.url) }
                row.pages.add(items)
                if (next == 1) row.updatedAt = System.currentTimeMillis()
            }
            val items = row.pages.getOrNull(page - 1).orEmpty()
            val hasNext = items.isNotEmpty() && row.pages.getOrNull(page)?.isEmpty() != true
            newHomePageResponse(request, items, hasNext)
        }
    }
    override suspend fun search(query: String): List<SearchResponse> = if (query.isBlank()) emptyList() else
        api("/tmdb/search?q=" + URLEncoder.encode(query.trim(), "UTF-8")).mapNotNull { card(it, null) }.distinctBy { it.url }
    override suspend fun load(url: String): LoadResponse {
        val uri = URI(url)
        val item = Regex("^/(movie|tv)/([1-9][0-9]{0,8})/?$").matchEntire(uri.path)
            ?.takeIf { uri.scheme == "https" && uri.host == "thugtv.online" && uri.userInfo == null }
            ?: throw ErrorLoadingException("Invalid ThugTV title")
        val (type, id) = item.destructured
        val info = api("/tmdb/$type/$id")
        if (info.path("id").asText() != id || info.path("adult").asBoolean() || info.path("softcore").asBoolean()) {
            throw ErrorLoadingException("Title unavailable")
        }
        val title = title(info) ?: throw ErrorLoadingException("ThugTV title is missing")
        val genres = info.path("genres").mapNotNull { it.string("name") }
        val score = info.path("vote_average").asDouble().toString()
        if (type == "movie") return newMovieLoadResponse(title, url, TvType.Movie, "movie/$id") {
            posterUrl = image(info.string("poster_path"))
            backgroundPosterUrl = image(info.string("backdrop_path"))
            plot = info.string("overview")
            tags = genres
            year = year(info)
            duration = info.path("runtime").asInt().takeIf { it > 0 }
            addScore(score)
        }
        val seasons = info.path("seasons").map { it.path("season_number").asInt(-1) }.filter { it >= 0 }.distinct().sorted()
        val episodes = coroutineScope {
            seasons.map { season -> async {
                try {
                    api("/tmdb/tv/$id/season/$season").path("episodes").mapNotNull { ep ->
                        val number = ep.path("episode_number").asInt().takeIf { it > 0 } ?: return@mapNotNull null
                        newEpisode("tv/$id/$season/$number") {
                            this.season = season
                            episode = number
                            name = ep.string("name")
                            description = ep.string("overview")
                            posterUrl = image(ep.string("still_path"))
                        }
                    }
                } catch (error: Exception) { if (error is CancellationException) throw error; emptyList() }
            } }.awaitAll().flatten()
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = image(info.string("poster_path"))
            backgroundPosterUrl = image(info.string("backdrop_path"))
            plot = info.string("overview")
            tags = genres
            year = year(info)
            addScore(score)
        }
    }
    private fun web(value: String) = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host != null && uri.userInfo == null
    }.getOrDefault(false)
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (!data.matches(Regex("(?:movie/[1-9][0-9]{0,8}|tv/[1-9][0-9]{0,8}/[0-9]{1,4}/[1-9][0-9]{0,4})"))) return false
        val sources = api("/stream/$data").path("sources")
        val emitted = mutableSetOf<String>()
        for (source in sources) try {
            val resolve = source.string("resolvePath") ?: continue
            if (resolve !in listOf("/stream/direct/moviebox/$data", "/stream/direct/vaplayer/$data")) continue
            val result = api(resolve).path("source")
            val url = result.string("url")?.takeIf { web(it) && it !in emitted } ?: continue
            if (result.string("type") != "hls" && result.string("format") != "hls") continue
            val response = app.get(url, headers = headers, referer = "$mainUrl/", timeout = 15)
            if (response.code !in 200..299 || !response.text.trimStart().startsWith("#EXTM3U")) continue
            emitted.add(url)
            val server = result.string("label") ?: source.string("name") ?: name
            callback(newExtractorLink(name, "$name – $server", url, ExtractorLinkType.M3U8) {
                referer = "$mainUrl/"
                this.headers = this@ThugTvProvider.headers
            })
            for (track in result.path("subtitles")) {
                val file = (track.string("url") ?: track.string("file"))?.takeIf { web(it) } ?: continue
                subtitleCallback(SubtitleFile(track.string("label") ?: track.string("lang") ?: "Subtitle", file))
            }
        } catch (error: Exception) { if (error is CancellationException) throw error }
        if (emitted.isEmpty()) for (source in sources.filter { it.string("type") == "embed" }.take(4)) {
            val url = source.string("url")?.takeIf { web(it) } ?: continue
            try {
                loadExtractor(url, "$mainUrl/", subtitleCallback) { if (emitted.add(it.url)) callback(it) }
            } catch (error: Exception) { if (error is CancellationException) throw error }
            if (emitted.isNotEmpty()) break
        }
        if (emitted.isEmpty()) throw ErrorLoadingException("No supported ThugTV stream is available for this title")
        return true
    }
}

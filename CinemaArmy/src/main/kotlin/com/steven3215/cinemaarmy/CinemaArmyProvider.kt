package com.steven3215.cinemaarmy

import com.caramelo.tracks.MediaTracks
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class CinemaArmyProvider : MainAPI() {
    override var mainUrl = "https://cinema.army"
    override var name = "Cinema Army"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    private val json = ObjectMapper()
    private val api get() = "$mainUrl/api"
    private val headers get() = mapOf("Accept" to "application/json", "Referer" to "$mainUrl/")
    override val mainPage = mainPageOf(
        "movies/trending" to "Trending Movies",
        "movies/popular" to "Popular Movies",
        "movies/top_rated" to "Top Rated Movies",
        "tv/popular" to "Popular TV Shows"
    )

    private fun JsonNode.text(key: String): String? = get(key)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }
    private fun JsonNode.number(key: String): Int? = get(key)?.takeIf { it.isNumber }?.asInt()
    private fun image(path: String?, size: String = "w500"): String? =
        path?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/$size$it" }
    private suspend fun get(path: String): JsonNode {
        val response = app.get("$api/$path", headers = headers, timeout = 20)
        if (response.code !in 200..299) throw ErrorLoadingException("Cinema Army request failed (HTTP ${response.code})")
        return json.readTree(response.text)
    }
    private suspend fun optionalGet(path: String): JsonNode? = try {
        get(path)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }
    private fun searchItem(item: JsonNode, fallback: String? = null): SearchResponse? {
        if (item.get("adult")?.asBoolean() == true) return null
        val type = item.text("media_type") ?: fallback ?: if (item.hasNonNull("title")) "movie" else "tv"
        if (type !in setOf("movie", "tv")) return null
        val id = item.number("id")?.takeIf { it > 0 } ?: return null
        val title = item.text(if (type == "movie") "title" else "name") ?: return null
        val url = CinemaItem(id, type).watchUrl(mainUrl)
        val poster = image(item.text("poster_path"))
        val year = item.text(if (type == "movie") "release_date" else "first_air_date")?.take(4)?.toIntOrNull()
        return if (type == "movie") newMovieSearchResponse(title, url) {
            posterUrl = poster
            this.year = year
        } else newTvSeriesSearchResponse(title, url) {
            posterUrl = poster
            this.year = year
        }
    }
    private fun results(body: JsonNode, fallback: String? = null): List<SearchResponse> {
        val items = body.get("results")?.takeIf { it.isArray }
            ?: throw ErrorLoadingException("Cinema Army returned an invalid catalog")
        return items.mapNotNull { searchItem(it, fallback) }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val paginated = request.data.endsWith("/popular")
        if (page > 1 && !paginated) return newHomePageResponse(request, emptyList(), false)
        val body = get("${request.data}?language=en-US&page=$page")
        return newHomePageResponse(request, results(body, if (request.data.startsWith("movies/")) "movie" else "tv"),
            paginated && page < (body.number("total_pages") ?: page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        // The site's search UI exposes a single result page.
        return results(get("search?query=${URLEncoder.encode(query.trim(), "UTF-8")}&language=en-US"))
    }

    private fun LoadResponse.metadata(body: JsonNode, type: String) {
        posterUrl = image(body.text("poster_path"))
        backgroundPosterUrl = image(body.text("backdrop_path"), "original")
        plot = body.text("overview")
        year = body.text(if (type == "movie") "release_date" else "first_air_date")?.take(4)?.toIntOrNull()
        tags = body.get("genres")?.mapNotNull { it.text("name") }
        duration = body.number("runtime")?.takeIf { it > 0 }
        addScore(body.text("vote_average"))
    }

    override suspend fun load(url: String): LoadResponse {
        val item = CinemaProtocol.item(url, mainUrl)
        val body = get("${if (item.type == "movie") "movies" else "tv"}/${item.id}?language=en-US")
        val title = body.text(if (item.type == "movie") "title" else "name")
            ?: throw ErrorLoadingException("Cinema Army returned no title")
        if (item.type == "movie") return newMovieLoadResponse(title, url, TvType.Movie, item.watchUrl(mainUrl)) {
            metadata(body, "movie")
        }
        val episodes = mutableListOf<Episode>()
        for (season in body.get("seasons")?.toList().orEmpty()) {
            val seasonNumber = season.number("season_number")?.takeIf { it > 0 } ?: continue
            val details = optionalGet("tv/${item.id}/season/$seasonNumber?language=en-US") ?: continue
            for (episode in details.get("episodes")?.toList().orEmpty()) {
                val episodeNumber = episode.number("episode_number")?.takeIf { it > 0 } ?: continue
                episodes += newEpisode(CinemaItem(item.id, "tv", seasonNumber, episodeNumber).watchUrl(mainUrl)) {
                    this.season = seasonNumber
                    this.episode = episodeNumber
                    this.name = episode.text("name")
                    this.posterUrl = image(episode.text("still_path"))
                    this.description = episode.text("overview")
                    addDate(episode.text("air_date"))
                }
            }
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("Cinema Army returned no episodes")
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { metadata(body, "tv") }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mediaTracks = MediaTracks(subtitleCallback)
        val item = CinemaProtocol.item(data, mainUrl)
        val path = item.streamPath()
        val streams = coroutineScope {
            listOf("cookiebakers", "tcloud", "ipcloud", "dcloud").mapIndexed { index, server ->
                async {
                    try {
                        val requestHeaders = headers + ("X-Stream-Token" to CinemaProtocol.token(item.id))
                        val response = if (server == "cookiebakers") {
                            app.post("$api/$server/$path", headers = requestHeaders, timeout = 20)
                        } else app.get("$api/$server/$path", headers = requestHeaders, timeout = 20)
                        if (response.code !in 200..299) return@async null
                        val value = json.readTree(response.text).get("stream")?.text("url") ?: return@async null
                        index to CinemaProtocol.streamUrl(value)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val seen = mutableSetOf<String>()
        for ((index, stream) in streams) {
            if (!seen.add(stream)) continue
            mediaTracks.publish(newExtractorLink(name, "$name Server ${index + 1}", stream, ExtractorLinkType.M3U8) {
                referer = "$mainUrl/"
                this.headers = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl)
            }, callback, inspect = true)
        }
        if (seen.isNotEmpty()) loadSubtitles(item, mediaTracks)
        return seen.isNotEmpty()
    }

    private suspend fun loadSubtitles(item: CinemaItem, mediaTracks: MediaTracks) {
        try {
            val response = app.get("https://rezesubs.com/data/subs/${item.streamPath()}", headers = headers, timeout = 8)
            if (response.code !in 200..299) return
            val body = json.readTree(response.text)
            val list = when {
                body.isArray -> body
                body.get("subtitles")?.isArray == true -> body.get("subtitles")
                body.get("data")?.isArray == true -> body.get("data")
                else -> return
            }
            mediaTracks.sidecars(list, "https://rezesubs.com/", mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Subtitles are optional and must not fail video playback.
        }
    }
}

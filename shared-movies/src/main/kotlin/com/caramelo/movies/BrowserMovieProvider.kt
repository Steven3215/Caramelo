package com.caramelo.movies

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CancellationException

internal data class MovieId(val type: String, val id: Int, val season: Int? = null, val episode: Int? = null) {
    fun playbackData() = if (episode == null) "$type/$id" else "$type/$id/$season/$episode"
}

internal abstract class BrowserMovieProvider : MainAPI() {
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    protected val json = ObjectMapper()
    private val gate = Semaphore(4)
    protected abstract suspend fun metadata(path: String): JsonNode
    protected open fun player(item: MovieId): String? = null
    protected open fun titleUrl(item: MovieId) = "$mainUrl/${item.type}/${item.id}"
    protected open fun parseTitle(url: String): MovieId {
        val uri = URI(url)
        val match = Regex("^/(?:en/)?(movie|tv)/([1-9][0-9]{0,8})/?$").matchEntire(uri.path)
            ?.takeIf { uri.scheme == "https" && uri.host == URI(mainUrl).host && uri.userInfo == null }
            ?: throw ErrorLoadingException("Invalid $name title URL")
        return MovieId(match.groupValues[1], match.groupValues[2].toInt())
    }
    protected fun JsonNode.string(key: String) = get(key)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
    protected fun image(value: String?) = value?.let {
        when {
            it.startsWith("https://image.tmdb.org/") -> it
            it.startsWith('/') && !it.startsWith("//") -> "https://image.tmdb.org/t/p/w780$it"
            else -> null
        }
    }
    private fun year(item: JsonNode) = (item.string("release_date") ?: item.string("first_air_date"))?.take(4)?.toIntOrNull()
    private fun title(item: JsonNode) = item.string("title") ?: item.string("name")
    protected fun result(item: MovieId, title: String, poster: String?, releaseYear: Int?): SearchResponse =
        if (item.type == "movie") newMovieSearchResponse(title, titleUrl(item)) {
            posterUrl = poster
            year = releaseYear
        } else newTvSeriesSearchResponse(title, titleUrl(item)) {
            posterUrl = poster
            year = releaseYear
        }
    protected fun card(item: JsonNode, fallback: String?): SearchResponse? {
        if (item.path("adult").asBoolean() || item.path("softcore").asBoolean()) return null
        val type = (item.string("media_type") ?: fallback)?.takeIf { it in setOf("movie", "tv") } ?: return null
        val id = item.path("id").asInt().takeIf { it > 0 } ?: return null
        return result(MovieId(type, id), title(item) ?: return null, image(item.string("poster_path")), year(item))
    }
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return metadata("/search/multi?include_adult=false&query=" + URLEncoder.encode(query.trim(), "UTF-8"))
            .path("results").mapNotNull { card(it, null) }
    }
    override suspend fun load(url: String): LoadResponse {
        val item = parseTitle(url)
        val (type, id) = item
        val info = metadata("/$type/$id")
        if (info.path("id").asInt() != id || info.path("adult").asBoolean() || info.path("softcore").asBoolean()) {
            throw ErrorLoadingException("Title unavailable")
        }
        val title = title(info) ?: throw ErrorLoadingException("$name title is missing")
        val genres = info.path("genres").mapNotNull { it.string("name") }
        val score = info.string("vote_average") ?: info.path("vote_average").asDouble().toString()
        if (type == "movie") return newMovieLoadResponse(title, url, TvType.Movie, item.playbackData()) {
            posterUrl = image(info.string("poster_path"))
            backgroundPosterUrl = image(info.string("backdrop_path"))
            plot = info.string("overview")
            tags = genres
            year = year(info)
            duration = info.path("runtime").asInt().takeIf { it > 0 }
            addScore(score)
        }
        val seasons = info.path("seasons").filter { it.path("episode_count").asInt() > 0 }
            .map { it.path("season_number").asInt(-1) }.filter { it >= 0 }.distinct().sorted()
        val episodes = coroutineScope {
            seasons.map { season -> async {
                try {
                    gate.withPermit { metadata("/tv/$id/season/$season") }.path("episodes").mapNotNull { episodeInfo ->
                        val number = episodeInfo.path("episode_number").asInt().takeIf { it > 0 } ?: return@mapNotNull null
                        newEpisode(item.copy(season = season, episode = number).playbackData()) {
                            this.season = season
                            episode = number
                            name = episodeInfo.string("name")
                            description = episodeInfo.string("overview")
                            posterUrl = image(episodeInfo.string("still_path"))
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
    protected fun parsePlayback(data: String): MovieId? {
        if (!data.matches(Regex("(?:movie/[1-9][0-9]{0,8}|tv/[1-9][0-9]{0,8}/[0-9]{1,4}/[1-9][0-9]{0,4})"))) return null
        val pieces = data.split('/')
        return MovieId(pieces[0], pieces[1].toInt(), pieces.getOrNull(2)?.toInt(), pieces.getOrNull(3)?.toInt())
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (isCasting) return false
        val item = parsePlayback(data) ?: return false
        val page = player(item) ?: return false
        val resolver = WebViewResolver(interceptUrl = Regex("\\.m3u8(?:[?#]|$)", RegexOption.IGNORE_CASE),
            useOkhttp = false, timeout = 25_000L)
        try {
            val link = app.get(page, referer = "$mainUrl/", interceptor = resolver).url
            val uri = URI(link)
            if (uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.path.endsWith(".m3u8", true)) {
                val referrer = URI(page).let { "${it.scheme}://${it.host}/" }
                val manifest = app.get(link, referer = referrer, timeout = 12)
                if (manifest.code in 200..299 && manifest.text.trimStart().startsWith("#EXTM3U")) {
                    callback(newExtractorLink(name, "$name – Browser player", link, ExtractorLinkType.M3U8) { referer = referrer })
                    return true
                }
            }
        } catch (error: Exception) { if (error is CancellationException) throw error }
        throw ErrorLoadingException("$name's Android browser player did not return a playable HLS stream")
    }
}

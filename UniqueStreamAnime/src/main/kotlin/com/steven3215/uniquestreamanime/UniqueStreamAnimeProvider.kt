package com.steven3215.uniquestreamanime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CancellationException

class UniqueStreamAnimeProvider : MainAPI() {
    override var mainUrl = "https://anime.uniquestream.net"
    override var name = "UniqueStream Anime"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    private val json = ObjectMapper()
    private val api get() = "$mainUrl/api/v1"
    private val headers get() = mapOf("Accept" to "application/json", "Referer" to "$mainUrl/")
    override val mainPage = mainPageOf(
        "new" to "Latest Anime",
        "popular" to "Popular Anime",
        "highest-rated" to "Highest Rated Anime",
        "movies" to "Anime Movies"
    )
    private fun JsonNode.text(key: String): String? =
        get(key)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }
    private fun JsonNode.items(key: String): List<JsonNode> =
        get(key)?.takeIf { it.isArray }?.toList().orEmpty()
    private suspend fun get(path: String): JsonNode {
        val response = app.get("$api/$path", headers = headers, timeout = 25)
        if (response.code !in 200..299) throw ErrorLoadingException("UniqueStream request failed (HTTP ${response.code})")
        return json.readTree(response.text)
    }
    private fun image(body: JsonNode, type: String): String? =
        body.items("images").firstOrNull { it.text("type") == type }?.text("url")
            ?.takeIf { UniqueStreamProtocol.isWebUrl(it) }
    private fun pageItems(body: JsonNode): List<JsonNode> {
        if (body.isArray) return body.toList()
        return body.get("data")?.takeIf { it.isArray }?.toList()
            ?: throw ErrorLoadingException("Invalid UniqueStream catalog response")
    }
    private fun searchItem(body: JsonNode, movie: Boolean = body.text("type") == "movie"): SearchResponse? {
        val id = body.text("content_id")?.takeIf { UniqueStreamProtocol.validId(it) } ?: return null
        val title = body.text("title") ?: return null
        return newAnimeSearchResponse(title, "$mainUrl/${if (movie) "movies" else "series"}/$id/details",
            if (movie) TvType.AnimeMovie else TvType.Anime) {
            posterUrl = body.text("image")?.takeIf { UniqueStreamProtocol.isWebUrl(it) }
            year = body.get("year")?.takeIf { it.isNumber }?.asInt()
            val count = body.get("episodes_total")?.takeIf { it.isNumber }?.asInt()
            if (body.get("subbed")?.asBoolean() == true) addDubStatus(DubStatus.Subbed, count)
            if (body.get("dubbed")?.asBoolean() == true) addDubStatus(DubStatus.Dubbed, count)
        }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        require(page > 0 && request.data in setOf("new", "popular", "highest-rated", "movies"))
        val body = pageItems(get("videos/${request.data}?page=$page&limit=${UniqueStreamProtocol.PAGE_SIZE}"))
        return newHomePageResponse(request, body.mapNotNull { searchItem(it) }, body.size == UniqueStreamProtocol.PAGE_SIZE)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val body = get("search?query=${URLEncoder.encode(query.trim(), "UTF-8")}&t=all&limit=20")
        if (!body.has("series") || !body.has("movies")) throw ErrorLoadingException("Invalid UniqueStream search response")
        return (body.items("series").mapNotNull { searchItem(it, false) } +
            body.items("movies").mapNotNull { searchItem(it, true) }).distinctBy { it.url }
    }
    private fun LoadResponse.metadata(body: JsonNode) {
        posterUrl = image(body, "poster_tall") ?: body.text("image")
        backgroundPosterUrl = image(body, "poster_wide")
        plot = body.text("description")
        tags = body.items("genre").mapNotNull { it.text("title") }
        year = body.get("year")?.takeIf { it.isNumber }?.asInt()
        duration = body.get("duration_ms")?.takeIf { it.isNumber && it.asLong() > 0 }?.let { (it.asLong() / 60000).toInt() }
        // Site user ratings use a five-star scale.
        body.text("rating_avg")?.let { addScore(it, 5) }
    }
    override suspend fun load(url: String): LoadResponse {
        val item = UniqueStreamProtocol.item(url, mainUrl)
        if (item.kind == "movies") {
            val body = get("movie/${item.id}")
            val title = body.text("title") ?: throw ErrorLoadingException("Movie not found")
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, "$mainUrl/watch/${item.id}/details") {
                metadata(body)
            }
        }
        if (item.kind == "watch") {
            val body = get("content/${item.id}")
            val series = body.text("series_id")
            if (series != null && UniqueStreamProtocol.validId(series)) return load("$mainUrl/series/$series/details")
            if (body.text("content_type") == "movie") return load("$mainUrl/movies/${item.id}/details")
            throw ErrorLoadingException("Unsupported content")
        }
        val body = get("series/${item.id}")
        val title = body.text("title") ?: throw ErrorLoadingException("Series not found")
        val subbed = mutableListOf<Episode>()
        val dubbed = mutableListOf<Episode>()
        for (season in body.items("seasons")) {
            val seasonId = season.text("content_id")?.takeIf { UniqueStreamProtocol.validId(it) } ?: continue
            val seasonNumber = season.get("season_seq_number")?.asInt() ?: season.get("season_number")?.asInt()
            val seen = mutableSetOf<String>()
            var page = 1
            while (true) {
                val episodes = pageItems(get("season/$seasonId/episodes?page=$page&limit=${UniqueStreamProtocol.PAGE_SIZE}&order_by=asc"))
                var added = 0
                for (entry in episodes) {
                    val id = entry.text("content_id")?.takeIf { UniqueStreamProtocol.validId(it) } ?: continue
                    if (!seen.add(id)) continue
                    added++
                    if (entry.get("is_clip")?.asBoolean() == true) continue
                    val locales = entry.items("audio_locales").map { it.asText() }
                    fun episode(locale: String): Episode = newEpisode("$mainUrl/watch/$id/details?audio=$locale") {
                        this.name = entry.text("title")
                        this.season = seasonNumber
                        this.episode = entry.get("episode_number")?.takeIf { it.isNumber }?.asInt()
                        this.posterUrl = entry.text("image")
                        this.description = entry.text("description")
                    }
                    val original = if ("ja-JP" in locales || locales.isEmpty()) "ja-JP" else locales.firstOrNull { !it.startsWith("en") }
                    if (original != null) subbed += episode(original)
                    if (locales.any { it == "en-US" }) dubbed += episode("en-US")
                }
                if (episodes.size < UniqueStreamProtocol.PAGE_SIZE || added == 0) break
                page++
            }
        }
        if (subbed.isEmpty() && dubbed.isEmpty()) throw ErrorLoadingException("No episodes are available")
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            metadata(body)
            addEpisodes(DubStatus.Subbed, subbed)
            addEpisodes(DubStatus.Dubbed, dubbed)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        // Cast receivers cannot run this provider's HLS key-response interceptor.
        if (isCasting) return false
        val item = UniqueStreamProtocol.item(data, mainUrl)
        require(item.kind == "watch")
        val details = get("content/${item.id}")
        val type = details.text("content_type")?.takeIf { it in setOf("episode", "movie") }
            ?: throw ErrorLoadingException("Unsupported playback content")
        val actualId = details.text("content_id")?.takeIf { UniqueStreamProtocol.validId(it) } ?: item.id
        val requested = item.audio.takeUnless { it == "all" } ?: "ja-JP"
        val media = get("$type/$actualId/media/hls/$requested")
        if (media.get("coming_soon")?.asBoolean() == true || media.get("maintenance_mode")?.asBoolean() == true)
            return false
        val primary = media.get("hls")?.takeUnless { it.isNull }
        val versions = listOfNotNull(primary) + media.get("versions")?.items("hls").orEmpty()
        val selection = if (item.audio == "all") versions else {
            versions.filter { it.text("locale") == requested }
        }
        val seen = mutableSetOf<String>()
        val subtitles = mutableSetOf<String>()
        for (version in selection) {
            val playlist = version.text("playlist")?.takeIf { UniqueStreamProtocol.isWebUrl(it) } ?: continue
            val mediaId = UniqueStreamProtocol.mediaId(playlist, media.text("media_id")) ?: continue
            if (!seen.add(playlist)) continue
            val locale = version.text("locale") ?: requested
            callback(newExtractorLink(name, "$name [$locale]", playlist, ExtractorLinkType.M3U8) {
                referer = "$mainUrl/"
                this.headers = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl, "x-am-media-id" to mediaId)
                extractorData = mediaId
            })
            for (subtitle in version.items("subtitles")) {
                val url = subtitle.text("url")?.takeIf { UniqueStreamProtocol.isWebUrl(it) } ?: continue
                if (subtitles.add(url)) subtitleCallback(SubtitleFile(subtitle.text("language") ?: "en", url))
            }
        }
        return seen.isNotEmpty()
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val mediaId = extractorLink.extractorData?.takeIf { Regex("[a-fA-F0-9]{32}").matches(it) } ?: return null
        val streamHost = URI(extractorLink.url).host
        return Interceptor { chain ->
            val request = chain.request()
            val isKey = request.url.host == streamHost && request.url.encodedPath.endsWith("/key.bin")
            val response = chain.proceed(if (isKey) request.newBuilder().header("x-am-media-id", mediaId).build() else request)
            if (!isKey || !response.isSuccessful || response.body == null) return@Interceptor response
            val body = response.body!!
            val bytes = body.bytes()
            try {
                val key = UniqueStreamProtocol.unwrapHlsKey(bytes, mediaId)
                response.newBuilder().removeHeader("Content-Length").removeHeader("Content-Encoding")
                    .body(key.toResponseBody(body.contentType())).build()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                throw IOException("Invalid UniqueStream HLS key response", error)
            }
        }
    }
}

package com.caramelo.webcatalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.net.URLEncoder

internal data class WebTitle(val type: String, val id: Int, val season: Int? = null, val episode: Int? = null) {
    val playbackPath: String
        get() = if (type == "movie") "movie/$id" else "tv/$id/$season/$episode"
}

internal abstract class WebCatalog : MainAPI() {
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    protected abstract val moviePath: String
    protected val mapper = ObjectMapper()
    protected val browser = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
    protected val jsonHeaders = browser + ("Accept" to "application/json")

    protected fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")
    protected fun JsonNode.text(key: String): String? = get(key)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }
    protected fun JsonNode.items(key: String): List<JsonNode> = path(key).takeIf { it.isArray }?.toList().orEmpty()
    protected fun image(path: String?): String? = path?.takeIf { it.startsWith("/") && !it.startsWith("//") }?.let { "https://image.tmdb.org/t/p/w780$it" }
    protected fun title(item: JsonNode): String? = item.text("title") ?: item.text("name")
    protected fun year(item: JsonNode): Int? = (item.text("release_date") ?: item.text("first_air_date"))?.take(4)?.toIntOrNull()
    protected fun webUrl(value: String): URI? = runCatching { URI(value) }.getOrNull()
        ?.takeIf { it.scheme == "https" && it.host != null && it.userInfo == null }
    protected fun url(item: WebTitle): String {
        val path = if (item.type == "movie") moviePath else "tv"
        val episode = item.episode?.let { "?season=${item.season}&episode=$it" }.orEmpty()
        return "$mainUrl/$path/${item.id}$episode"
    }

    protected fun parseTitle(url: String): WebTitle? {
        val uri = webUrl(url) ?: return null
        if (uri.host != URI(mainUrl).host || uri.port != -1) return null
        val parts = uri.path.trim('/').split('/')
        if (parts.size != 2) return null
        val type = when (parts[0]) {
            moviePath -> "movie"
            "tv" -> "tv"
            else -> return null
        }
        val id = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        if (type == "movie" || uri.query == null) return WebTitle(type, id)
        val query = uri.query.split('&').mapNotNull { field ->
            val pair = field.split('=', limit = 2)
            if (pair.size == 2) pair[0] to pair[1] else null
        }.toMap()
        return WebTitle(type, id, query["season"]?.toIntOrNull()?.takeIf { it >= 0 }, query["episode"]?.toIntOrNull()?.takeIf { it > 0 })
    }

    protected fun card(item: JsonNode, fallback: String? = null): SearchResponse? {
        if (item.path("adult").asBoolean(false)) return null
        val type = item.text("media_type") ?: fallback ?: if (item.has("title")) "movie" else "tv"
        val id = item.path("id").asInt().takeIf { it > 0 } ?: return null
        val label = title(item) ?: return null
        val link = url(WebTitle(type, id))
        return when (type) {
            "movie" -> newMovieSearchResponse(label, link, TvType.Movie) {
                posterUrl = image(item.text("poster_path"))
                year = year(item)
            }
            "tv" -> newTvSeriesSearchResponse(label, link, TvType.TvSeries) {
                posterUrl = image(item.text("poster_path"))
                year = year(item)
            }
            else -> null
        }
    }

    protected fun details(item: WebTitle, data: JsonNode, episodes: List<Episode> = emptyList()): LoadResponse {
        val label = title(data) ?: throw ErrorLoadingException("Title details unavailable.")
        val canonical = url(item.copy(season = null, episode = null))
        val tags = data.items("genres").mapNotNull { it.text("name") }
        val response = if (item.type == "movie") {
            newMovieLoadResponse(label, canonical, TvType.Movie, canonical) {
                duration = data.path("runtime").asInt().takeIf { it > 0 }
            }
        } else newTvSeriesLoadResponse(label, canonical, TvType.TvSeries, episodes)
        response.posterUrl = image(data.text("poster_path")) ?: data.text("poster_url")
        response.backgroundPosterUrl = image(data.text("backdrop_path")) ?: data.text("backdrop_url")
        response.year = year(data)
        response.plot = data.text("overview")
        response.tags = tags
        return response
    }

    protected suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    protected suspend fun json(url: String, referer: String = "$mainUrl/", headers: Map<String, String> = jsonHeaders): JsonNode {
        val response = app.get(url, headers = headers, referer = referer, timeout = 12)
        if (response.code !in 200..299) throw ErrorLoadingException("$name request failed (HTTP ${response.code}).")
        return mapper.readTree(response.text)
    }

    protected suspend fun hls(label: String, playlist: String, page: String, requestHeaders: Map<String, String>): ExtractorLink? {
        webUrl(playlist) ?: return null
        val result = app.get(playlist, headers = requestHeaders, referer = page, timeout = 10)
        if (result.code !in 200..299 || !result.text.trimStart('\uFEFF', '\r', '\n', ' ').startsWith("#EXTM3U")) return null
        return newExtractorLink(name, label, playlist, ExtractorLinkType.M3U8) {
            referer = page
            headers = requestHeaders
        }
    }
}

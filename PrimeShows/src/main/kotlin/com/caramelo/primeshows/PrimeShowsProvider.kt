package com.caramelo.primeshows

import android.util.Base64
import com.caramelo.webcatalog.WebCatalog
import com.caramelo.webcatalog.WebTitle
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class PrimeShowsProvider : WebCatalog() {
    override var name = "PrimeShows"
    override var mainUrl = "https://primeshows.org"
    override val moviePath = "movies"
    override val mainPage = mainPageOf(
        "/trending/movie/day" to "Trending Movies",
        "/trending/tv/day" to "Trending TV",
        "/movie/now_playing" to "Now Playing",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/popular" to "Popular TV",
    )

    private val catalogGate = Semaphore(5)
    private val player = "https://vidrock.net"
    private val playerHeaders = browser + ("Origin" to player)

    private suspend fun catalog(endpoint: String, query: String = "") = catalogGate.withPermit {
        json("$mainUrl/api/proxy/tmdb?endpoint=${encode(endpoint)}&language=en-US$query")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val body = catalog(request.data, "&page=$page")
        val type = if (request.data.startsWith("/tv/") || "/tv/" in request.data) "tv" else "movie"
        return newHomePageResponse(request, body.items("results").mapNotNull { card(it, type) }, page < body.path("total_pages").asInt(page))
    }

    override suspend fun search(query: String): List<SearchResponse> =
        if (query.isBlank()) emptyList() else catalog("/search/multi", "&query=${encode(query.trim())}&include_adult=false")
            .items("results").mapNotNull { card(it) }

    override suspend fun load(url: String): LoadResponse {
        val item = parseTitle(url) ?: throw ErrorLoadingException("Invalid PrimeShows title.")
        val body = catalog("/${item.type}/${item.id}")
        if (item.type == "movie") return details(item, body)
        val seasons = body.items("seasons").filter { it.path("episode_count").asInt() > 0 }
        val episodes = coroutineScope {
            seasons.map { entry -> async {
                val season = entry.path("season_number").asInt(-1)
                if (season < 0) return@async emptyList()
                val rows = attempt { catalog("/tv/${item.id}/season/$season").items("episodes") }
                if (rows == null) {
                    (1..entry.path("episode_count").asInt()).map { number ->
                        newEpisode(url(WebTitle("tv", item.id, season, number))) { this.season = season; episode = number }
                    }
                } else rows.mapNotNull { ep ->
                    val number = ep.path("episode_number").asInt().takeIf { it > 0 } ?: return@mapNotNull null
                    newEpisode(url(WebTitle("tv", item.id, season, number))) {
                        this.season = season
                        episode = number
                        name = ep.text("name")
                        posterUrl = image(ep.text("still_path"))
                        description = ep.text("overview")
                    }
                }
            } }.awaitAll().flatten()
        }
        return details(item, body, episodes)
    }

    // VidRock's public browser client wraps stream URLs in AES-GCM with a fixed format key.
    private fun decodeStream(payload: String): String {
        val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)
        require(bytes.size >= 28)
        val key = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val item = parseTitle(data) ?: return false
        if (item.type == "tv" && (item.season == null || item.episode == null)) return false
        val path = item.playbackPath
        return coroutineScope {
            val subtitles = async { attempt { json("https://sub.vdrk.site/v2/$path", "$player/", playerHeaders) } }
            val body = json("$player/api/$path", "$player/$path", playerHeaders)
            val candidates = body.fields().asSequence().mapNotNull { (server, entry) ->
                if (entry.text("type") != "hls") return@mapNotNull null
                val encoded = entry.text("url") ?: return@mapNotNull null
                val playlist = runCatching { decodeStream(encoded) }.getOrNull() ?: return@mapNotNull null
                val language = entry.text("language")
                Triple(server, language, playlist)
            }.distinctBy { it.third }.take(8).toList()
            val links = candidates.map { (server, language, playlist) -> async {
                val label = listOfNotNull("PrimeShows · $server", language).joinToString(" · ")
                attempt { hls(label, playlist, "$player/", playerHeaders) }
            } }.awaitAll().filterNotNull()
            subtitles.await()?.takeIf { it.isArray }?.distinctBy { it.text("file") }?.forEach { subtitle ->
                val file = subtitle.text("file")?.takeIf { webUrl(it) != null } ?: return@forEach
                subtitleCallback(newSubtitleFile(subtitle.text("label") ?: "Unknown", file))
            }
            links.forEach(callback)
            links.isNotEmpty()
        }
    }
}

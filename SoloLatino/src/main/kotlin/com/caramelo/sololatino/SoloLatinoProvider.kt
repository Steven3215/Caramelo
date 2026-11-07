package com.caramelo.sololatino

import com.caramelo.tracks.MediaTracks
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.caramelo.tracks.browserMedia
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CancellationException

class SoloLatinoProvider : MainAPI() {
    override var name = "SoloLatino"
    override var mainUrl = "https://sololatino.net"
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val mainPage = mainPageOf("/peliculas" to "Películas", "/series" to "Series", "/animes" to "Animes", "/doramas" to "Doramas")

    private val json = ObjectMapper()
    private val titlePattern = Regex("^/(pelicula|serie)/[a-zA-Z0-9_-]{1,240}/?$")
    private val episodePattern = Regex("^/serie/[a-zA-Z0-9_-]{1,240}/temporada-([0-9]{1,4})/episodio-([0-9]{1,5})/?$")
    private val playerHosts = setOf("player.pelisserieshoy.com", "embed69.org")
    private val siteCookies = setOf("XSRF-TOKEN", "sololatinonet-session")
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private fun canonical(url: String, allowEpisode: Boolean = false): String? = runCatching {
        val uri = URI(mainUrl).resolve(url)
        if (uri.scheme != "https" || uri.host != "sololatino.net" || uri.port != -1 || uri.userInfo != null) return null
        if (!titlePattern.matches(uri.path) && !(allowEpisode && episodePattern.matches(uri.path))) return null
        mainUrl + uri.rawPath.trimEnd('/')
    }.getOrNull()
    private suspend fun page(url: String): Document {
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent), timeout = 18)
        if (response.code !in 200..299) throw ErrorLoadingException("SoloLatino catalog unavailable (HTTP ${response.code})")
        return Jsoup.parse(response.text, response.url)
    }
    private fun cards(doc: Document): List<SearchResponse> = doc.select("a[href]:has(img.card__poster)").mapNotNull { link ->
        val url = canonical(link.absUrl("href")) ?: return@mapNotNull null
        val img = link.selectFirst("img.card__poster") ?: return@mapNotNull null
        val title = img.attr("alt").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val poster = img.absUrl("src").takeIf { it.startsWith("https://") }
        if (URI(url).path.startsWith("/pelicula/")) newMovieSearchResponse(title, url) { posterUrl = poster }
        else newTvSeriesSearchResponse(title, url) { posterUrl = poster }
    }.distinctBy { it.url }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page < 1) return newHomePageResponse(request, emptyList(), false)
        val document = page("$mainUrl${request.data}?page=$page")
        val results = cards(document)
        return newHomePageResponse(request, results, results.isNotEmpty() && document.selectFirst("link[rel=next], a[rel=next]") != null)
    }
    override suspend fun search(query: String): List<SearchResponse> =
        if (query.isBlank()) emptyList() else cards(page("$mainUrl/buscar?q=" + URLEncoder.encode(query.trim(), "UTF-8")))
    override suspend fun load(url: String): LoadResponse {
        val canonical = canonical(url) ?: throw ErrorLoadingException("Invalid SoloLatino title")
        val doc = page(canonical)
        val title = doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() } ?: throw ErrorLoadingException("SoloLatino title unavailable")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val releaseYear = Regex("""\(((?:19|20)\d{2})\)""").find(doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        if (URI(canonical).path.startsWith("/pelicula/")) {
            return newMovieLoadResponse(title, canonical, TvType.Movie, canonical) {
                posterUrl = poster
                plot = description
                year = releaseYear
            }
        }
        val episodes = doc.select("a.ep-item[href]").mapNotNull { link ->
            val episodeUrl = canonical(link.absUrl("href"), allowEpisode = true) ?: return@mapNotNull null
            val match = episodePattern.matchEntire(URI(episodeUrl).path) ?: return@mapNotNull null
            newEpisode(episodeUrl) {
                season = match.groupValues[1].toInt()
                episode = match.groupValues[2].toInt()
                name = link.selectFirst("p.font-semibold")?.text()
                posterUrl = link.selectFirst("img.ep-thumb")?.absUrl("src")
                this.description = link.selectFirst("p.line-clamp-2")?.text()
            }
        }.distinctBy { it.data }
        return newTvSeriesLoadResponse(title, canonical, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = description
            year = releaseYear
        }
    }
    private suspend fun embedOptions(url: String): List<String> {
        val headers = mapOf("User-Agent" to userAgent, "Origin" to mainUrl, "Accept" to "application/json",
            "Cache-Control" to "no-cache", "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Dest" to "empty")
        val bootstrap = app.get("$mainUrl/sanctum/csrf-cookie", headers = headers, referer = url, allowRedirects = false, timeout = 12)
        val session = bootstrap.cookies.filterKeys { it in siteCookies }
        val watch = app.get(url, headers = mapOf("User-Agent" to userAgent, "Cache-Control" to "no-cache", "Accept" to "text/html"),
            params = mapOf("_" to (System.currentTimeMillis() / 1000L).toString()), cookies = session, timeout = 18)
        if (watch.code !in 200..299) throw ErrorLoadingException("SoloLatino watch page unavailable")
        val cookies = session + watch.cookies.filterKeys { it in siteCookies }
        val document = Jsoup.parse(watch.text, watch.url)
        val csrf = cookies["XSRF-TOKEN"]?.let { "X-XSRF-TOKEN" to URLDecoder.decode(it, "UTF-8") }
            ?: ("X-CSRF-TOKEN" to document.selectFirst("meta[name=csrf-token]")?.attr("content").orEmpty())
        val options = document.select("[data-player-token]").map { it.attr("data-player-token") to "player-url" } +
            document.select("[data-lazy-embed]").map { it.attr("data-lazy-embed") to "player-embed" }
        return options.filter { it.first.isNotBlank() }.distinct().take(4).mapNotNull { (token, route) ->
            try {
                val response = app.post("$mainUrl/api/$route", headers = headers + csrf, referer = url, cookies = cookies,
                    json = mapOf("t" to token), allowRedirects = false, timeout = 15)
                if (response.code !in 200..299) return@mapNotNull null
                val link = json.readTree(response.text).path("url").asText()
                val uri = URI(link)
                link.takeIf { uri.scheme == "https" && uri.userInfo == null &&
                    (uri.host in playerHosts || uri.path.endsWith(".m3u8", true) || uri.path.endsWith(".mp4", true)) }
            } catch (error: Exception) { if (error is CancellationException) throw error; null }
        }.distinct()
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val mediaTracks = MediaTracks(subtitleCallback)
        if (isCasting) return false
        val url = canonical(data, allowEpisode = true) ?: return false
        val options = embedOptions(url)
        if (options.isEmpty()) throw ErrorLoadingException("SoloLatino returned no anonymous playback options")
        for (option in options) try {
            val media = browserMedia(option, url, allowMp4 = true) ?: continue
            mediaTracks.publish(newExtractorLink(name, "$name – ${URI(option).host}", media.url, media.type) {
                referer = media.referer
                headers = media.headers
            }, callback, media.manifest, media.manifestUrl)
            return true
        } catch (error: Exception) { if (error is CancellationException) throw error }
        throw ErrorLoadingException("SoloLatino returned player pages, but none exposed a playable HLS or MP4 stream")
    }
}

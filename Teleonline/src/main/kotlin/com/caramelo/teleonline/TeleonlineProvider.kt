package com.caramelo.teleonline

import com.caramelo.tracks.MediaTracks
import com.caramelo.tracks.checkHlsSignal
import com.caramelo.settings.ChannelRows
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException

class TeleonlineProvider internal constructor(private val settings: ChannelRows) : MainAPI() {
    override var name = "Teleonline"
    override var mainUrl = TeleonlineProtocol.BASE
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var sequentialMainPage = true
    override val mainPage get() = settings.rows.filter { settings.enabled(it.id) }.map { mainPage(it.id, it.label) }
    private val json = ObjectMapper()
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()
    private suspend fun api(query: String): JsonNode {
        val cached = cache[query]
        if (cached != null && System.currentTimeMillis() - cached.first < 300_000L) {
            return json.readTree(cached.second)
        }
        var failure: String? = null
        for (url in listOf(
            "$mainUrl/wp-json/wp/v2/posts?$query",
            "$mainUrl/?rest_route=%2Fwp%2Fv2%2Fposts&$query"
        )) {
            try {
                val response = app.get(url, timeout = 15)
                if (response.code !in 200..299) {
                    failure = "HTTP ${response.code}"
                    continue
                }
                val posts = json.readTree(response.text)
                if (!posts.isArray) {
                    failure = "unexpected response"
                    continue
                }
                if (cache.size > 100) cache.clear()
                cache[query] = System.currentTimeMillis() to response.text
                return posts
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failure = "request failed"
            }
        }
        if (cached != null) return json.readTree(cached.second)
        throw ErrorLoadingException("Teleonline directory unavailable ($failure)")
    }
    private fun card(node: JsonNode): SearchResponse? {
        if (node.path("categories").any { it.asInt() == 130 }) return null
        val url = runCatching { TeleonlineProtocol.channel(node.path("link").asText()) }.getOrNull() ?: return null
        return newLiveSearchResponse(Jsoup.parse(node.path("title").path("rendered").asText()).text(), url) {
            posterUrl = node.path("_embedded").path("wp:featuredmedia").firstOrNull()?.path("source_url")?.asText()?.takeIf { TeleonlineProtocol.web(it) }
        }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page < 1 || !settings.enabled(request.data)) return newHomePageResponse(request, emptyList(), false)
        val category = request.data.removePrefix("term:").toIntOrNull()
        if (request.data != "all" && category == null) return newHomePageResponse(request, emptyList(), false)
        val nodes = api("per_page=30&page=$page&_embed=wp:featuredmedia&categories_exclude=130" + (category?.let { "&categories=$it" } ?: ""))
        return newHomePageResponse(request, nodes.mapNotNull { card(it) }, nodes.size() == 30)
    }
    override suspend fun search(query: String): List<SearchResponse> = if (query.isBlank()) emptyList() else
        api("per_page=50&categories_exclude=130&_embed=wp:featuredmedia&search=" + URLEncoder.encode(query.trim(), "UTF-8")).mapNotNull { card(it) }
    override suspend fun load(url: String): LoadResponse {
        val canonical = TeleonlineProtocol.channel(url)
        val doc = app.get(canonical).document
        return newLiveStreamLoadResponse(doc.selectFirst("h1")?.text() ?: "Teleonline", canonical, canonical) {
            posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
            plot = doc.selectFirst("meta[name=description]")?.attr("content")
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val mediaTracks = MediaTracks(subtitleCallback)
        val url = TeleonlineProtocol.channel(data)
        val page = app.get(url, timeout = 15)
        if (page.code !in 200..299) throw ErrorLoadingException("Teleonline channel unavailable (HTTP ${page.code})")
        val html = page.text
        if (TeleonlineProtocol.offline(html)) throw ErrorLoadingException("Teleonline reports that this channel is off air")
        val noReferrer = Regex("NEEDS_NOREFERRER\\s*=\\s*true").containsMatchIn(html)
        val referer = if (noReferrer) "" else url
        var count = 0
        for (link in TeleonlineProtocol.hls(html)) {
            val playlist = checkHlsSignal(link, referer) ?: continue
            val stream = newExtractorLink(name, "$name – Signal ${++count}", link, ExtractorLinkType.M3U8) {
                this.referer = referer
            }
            mediaTracks.publish(stream, callback, playlist.manifest, playlist.url)
        }
        if (count == 0) throw ErrorLoadingException("No supported Teleonline HLS signal is available for this channel. Signals can be offline or region restricted.")
        return true
    }
}

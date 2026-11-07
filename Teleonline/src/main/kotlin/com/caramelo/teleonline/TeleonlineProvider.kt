package com.caramelo.teleonline

import com.caramelo.tracks.MediaTracks
import com.caramelo.settings.ChannelRows
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

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
        val cached = cache[query]?.takeIf { System.currentTimeMillis() - it.first < 300000 }?.second
        val body = cached ?: app.get("$mainUrl/wp-json/wp/v2/posts?$query", timeout = 25).let {
            if (it.code !in 200..299) throw ErrorLoadingException("Teleonline directory unavailable (HTTP ${it.code})")
            if (cache.size > 100) cache.clear(); cache[query] = System.currentTimeMillis() to it.text; it.text
        }
        return json.readTree(body)
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
        val html = app.get(url, timeout = 25).text
        val noReferrer = Regex("NEEDS_NOREFERRER\\s*=\\s*true").containsMatchIn(html)
        var count = 0
        for (link in TeleonlineProtocol.hls(html)) try {
            val referer = if (noReferrer) "" else url
            val response = app.get(link, referer = referer, timeout = 15)
            if (response.code in 200..299 && response.text.trimStart().startsWith("#EXTM3U")) {
                mediaTracks.publish(newExtractorLink(name, "$name – Signal ${++count}", link, ExtractorLinkType.M3U8) { this.referer = referer }, callback, response.text, response.url)
            }
        } catch (error: Exception) { if (error is java.util.concurrent.CancellationException) throw error }
        if (count == 0) throw ErrorLoadingException("No supported Teleonline HLS signal is available for this channel. Signals can be offline or region restricted.")
        return true
    }
}

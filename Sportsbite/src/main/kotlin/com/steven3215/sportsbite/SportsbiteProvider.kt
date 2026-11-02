package com.steven3215.sportsbite

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.net.URI

class SportsbiteProvider : MainAPI() {
    override var name = "Sportsbite"
    override var mainUrl = "https://sportsbite.org"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var sequentialMainPage = true
    override val mainPage = mainPageOf(
        "live" to "Live Now", "all" to "Sports Schedule", "football" to "Football",
        "american-football" to "NFL / American Football", "basketball" to "Basketball",
        "fighting" to "UFC / Boxing", "motorsport" to "Motorsport"
    )
    private val json = ObjectMapper()
    @Volatile private var cached: List<JsonNode> = emptyList()
    @Volatile private var cachedAt = 0L
    private fun JsonNode.text(key: String): String? = get(key)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }
    private fun JsonNode.items(key: String): List<JsonNode> = get(key)?.takeIf { it.isArray }?.toList().orEmpty()
    private fun key(event: JsonNode): String? = (event.text("event_key") ?: event.text("slug"))
        ?.takeIf { Regex("[A-Za-z0-9_-]{1,240}").matches(it) }
    private fun live(event: JsonNode): Boolean = event.get("live_status")?.text("state")?.let { it == "in" }
        ?: (event.get("is_live")?.asBoolean() ?: event.get("live")?.asBoolean() ?: false)
    private fun url(event: JsonNode): String = "$mainUrl/event/${key(event)}"
    private fun poster(event: JsonNode): String? = event.text("poster")?.let {
        if (it.startsWith("/")) "$mainUrl$it" else it
    }?.takeIf { SportsbiteProtocol.webUrl(it) }
    private suspend fun catalog(force: Boolean = false): List<JsonNode> {
        if (!force && cachedAt > 0 && System.currentTimeMillis() - cachedAt < 30000) return cached
        val response = app.get("$mainUrl/api/forestgump/matches", headers = mapOf("Accept" to "application/json"), timeout = 25)
        if (response.code !in 200..299) throw ErrorLoadingException("Sportsbite schedule unavailable (HTTP ${response.code})")
        val body = json.readTree(response.text)
        if (body.get("days")?.isArray != true) throw ErrorLoadingException("Invalid Sportsbite schedule")
        val events = body.items("days").flatMap { it.items("events") }
            .filter { key(it) != null && it.text("title") != null }
            .distinctBy { key(it) }.sortedWith(compareByDescending<JsonNode> { live(it) }.thenBy { it.text("start") })
        cached = events
        cachedAt = System.currentTimeMillis()
        return events
    }
    private fun searchItem(event: JsonNode): SearchResponse = newLiveSearchResponse(
        (if (live(event)) "[LIVE] " else "") + event.text("title").orEmpty(), url(event)) { posterUrl = poster(event) }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request, emptyList(), false)
        val rows = catalog().filter { event ->
            val category = (event.text("category") ?: event.text("sport")).orEmpty().lowercase()
            when (request.data) {
                "live" -> live(event)
                "all" -> true
                "fighting" -> category in setOf("fighting", "ufc", "boxing", "mma", "wrestling")
                "motorsport" -> category in setOf("motorsport", "motor-sports", "racing", "f1")
                else -> category == request.data
            }
        }
        return newHomePageResponse(request, rows.map { searchItem(it) }, false)
    }
    override suspend fun search(query: String): List<SearchResponse> =
        catalog().filter { it.text("title").orEmpty().contains(query.trim(), true) }.map { searchItem(it) }
    override suspend fun load(url: String): LoadResponse {
        val eventId = SportsbiteProtocol.eventKey(url, mainUrl)
        val event = catalog().firstOrNull { key(it) == eventId }
            ?: throw ErrorLoadingException("This event is no longer listed")
        return newLiveStreamLoadResponse(event.text("title").orEmpty(), url, url) {
            posterUrl = poster(event)
            tags = listOfNotNull(event.text("category"))
            plot = listOfNotNull(
                event.text("start")?.let { "Scheduled start (UTC): $it" },
                if (live(event)) "Live now" else "Streams may only become available near kickoff.",
                event.get("live_status")?.text("clock")?.let { "Match clock: $it" }
            ).joinToString("\n")
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        if (isCasting) return false
        val eventId = SportsbiteProtocol.eventKey(data, mainUrl)
        val event = catalog(true).firstOrNull { key(it) == eventId } ?: return false
        val gate = Semaphore(4)
        val sources = event.items("streams").filter { it.text("status") != "offline" }
            .distinctBy { it.text("manifest_url") }.take(16)
        val resolved = coroutineScope {
            sources.map { stream -> async {
                gate.withPermit {
                    try {
                        val raw = stream.text("manifest_url")?.takeIf { SportsbiteProtocol.webUrl(it) } ?: return@withPermit null
                        val uri = URI(raw)
                        val direct = stream.text("format")?.lowercase() in setOf("hls", "m3u8") || uri.path.endsWith(".m3u8")
                        val target = if (direct) raw else {
                            if (uri.host != URI(mainUrl).host || !uri.path.contains("-embed/embed/")) return@withPermit null
                            val response = app.get(raw, referer = "$mainUrl/", timeout = 12)
                            if (response.code !in 200..299) return@withPermit null
                            SportsbiteProtocol.source(response.text) ?: return@withPermit null
                        }
                        Triple(stream.text("name") ?: stream.text("label") ?: "Server", target, raw)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        null
                    }
                }
            } }.awaitAll().filterNotNull()
        }
        for ((label, target, embed) in resolved.distinctBy { it.second }) {
            callback(newExtractorLink(name, "$name – $label", target, ExtractorLinkType.M3U8) {
                referer = embed
                extractorData = "sportsbite-media-v1"
            })
        }
        return resolved.isNotEmpty()
    }
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        if (extractorLink.extractorData != "sportsbite-media-v1") return null
        return Interceptor { chain ->
            val request = chain.request()
            val ownHost = request.url.host == URI(mainUrl).host
            val imagePath = Regex("\\.(png|webp|jpe?g)$", RegexOption.IGNORE_CASE).containsMatchIn(request.url.encodedPath)
            val builder = request.newBuilder()
            if (!ownHost) builder.removeHeader("Referer").removeHeader("Origin")
            if (imagePath) builder.removeHeader("Range")
            val response = chain.proceed(builder.build())
            val body = response.body ?: return@Interceptor response
            val imageResponse = body.contentType()?.type == "image"
            if (!response.isSuccessful || (!imagePath && !imageResponse)) return@Interceptor response
            if (body.contentLength() > SportsbiteProtocol.MAX_BYTES) {
                response.close()
                throw IOException("Sportsbite segment is too large")
            }
            val decoded = try {
                body.byteStream().use { SportsbiteProtocol.unwrap(SportsbiteProtocol.bounded(it)) }
            } catch (error: Exception) { throw IOException("Sportsbite media transport failed", error) }
                ?: throw IOException("Unsupported Sportsbite segment container")
            response.newBuilder().code(200).removeHeader("Content-Length").removeHeader("Content-Range")
                .removeHeader("Content-Encoding").header("Content-Type", "video/mp2t")
                .body(decoded.toResponseBody("video/mp2t".toMediaType())).build()
        }
    }
}

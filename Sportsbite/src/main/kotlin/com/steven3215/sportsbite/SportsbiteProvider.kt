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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.net.URI
import java.util.Locale

internal class SportsbiteProvider(private val settings: SportsbiteSettings, private val liveTv: Boolean = false) : MainAPI() {
    override var name = if (liveTv) "Sportsbite Live TV" else "Sportsbite Sports"
    override var mainUrl = "https://sportsbite.org"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var sequentialMainPage = true
    override val mainPage: List<MainPageData>
        get() = if (!liveTv) SportsbiteCatalog.sports.filter { settings.enabled("sport:" + it.key) }
            .map { (key, label) -> mainPage(key, label) }
        else SportsbiteCatalog.tvRows(settings.categories(), settings.countries(), settings.grouping(), settings::enabled, settings::allowedCountry)
            .map { (key, label) -> mainPage(key, label) }
    private val json = ObjectMapper()
    private val requestHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
    @Volatile private var cached: List<JsonNode> = emptyList()
    @Volatile private var cachedAt = 0L
    private fun key(item: JsonNode): String? = SportsbiteCatalog.id(if (liveTv) item.text("id") else item.text("event_key") ?: item.text("slug"))
    private fun itemUrl(item: JsonNode) = mainUrl + (if (liveTv) "/channel/" else "/event/") + key(item)
    private fun country(item: JsonNode) = SportsbiteCatalog.country(item.text("country"))
    private fun category(item: JsonNode) = item.text("category") ?: "General"
    private fun channelGroup(item: JsonNode) = item.text("name").orEmpty().lowercase(Locale.ROOT).trim() + "|" + country(item)
    private fun poster(item: JsonNode): String? = (item.text("poster") ?: item.text("logo_url") ?: item.text("image"))
        ?.let { URI(mainUrl).resolve(it).toString() }?.takeIf { SportsbiteProtocol.webUrl(it) }
    private suspend fun catalog(force: Boolean = false): List<JsonNode> {
        val ttl = if (liveTv) 120000 else 30000
        if (!force && cachedAt > 0 && System.currentTimeMillis() - cachedAt < ttl) return cached
        val endpoint = if (liveTv) "channels" else "matches"
        val response = app.get("$mainUrl/api/forestgump/$endpoint", headers = requestHeaders + ("Accept" to "application/json"), timeout = 25)
        if (response.code !in 200..299) throw ErrorLoadingException("Sportsbite catalog unavailable (HTTP ${response.code})")
        val body = json.readTree(response.text)
        val result = if (liveTv) {
            val rows = if (body.isArray) body.toList() else {
                if (body.get("channels")?.isArray != true) throw ErrorLoadingException("Invalid Sportsbite channel catalog")
                body.items("channels")
            }
            rows.filter { key(it) != null && it.text("name") != null && SportsbiteCatalog.usable(it) }
                .sortedBy { it.text("name").orEmpty().lowercase(Locale.ROOT) }
                .also { settings.remember(it.map { c -> category(c) }, it.map { c -> country(c) }) }
        } else {
            if (body.get("days")?.isArray != true) throw ErrorLoadingException("Invalid Sportsbite schedule")
            body.items("days").flatMap { it.items("events") }.filter { key(it) != null && it.text("title") != null }
                .distinctBy { key(it) }.sortedWith(compareByDescending<JsonNode> { SportsbiteCatalog.live(it) }.thenBy { it.text("start") })
        }
        cached = result
        cachedAt = System.currentTimeMillis()
        return result
    }
    private fun browsable(rows: List<JsonNode>): List<JsonNode> = if (liveTv)
        rows.filter { settings.allowedCountry(country(it)) }.distinctBy { channelGroup(it) }
    else rows.filter { (!settings.flag("hide_finished", true) || !SportsbiteCatalog.finished(it)) &&
        (!settings.flag("listed_only", false) || it.items("streams").any { stream -> SportsbiteCatalog.usable(stream) }) }
    private fun searchItem(item: JsonNode): SearchResponse = newLiveSearchResponse(
        if (liveTv) item.text("name").orEmpty() + " · " + country(item)
        else (if (SportsbiteCatalog.live(item)) "[LIVE] " else "") + item.text("title").orEmpty(), itemUrl(item)) { posterUrl = poster(item) }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page < 1 || (!liveTv && page > 1)) return newHomePageResponse(request, emptyList(), false)
        val rows = browsable(catalog()).filter { item ->
            if (liveTv) when {
                request.data == "tv:all" -> settings.enabled("tv:all")
                request.data.startsWith("category:") -> settings.enabled(request.data) && category(item) == request.data.removePrefix("category:")
                request.data.startsWith("country:") -> settings.enabled(request.data) && country(item) == request.data.removePrefix("country:")
                else -> false
            } else settings.enabled("sport:" + request.data) && when (request.data) {
                "live" -> SportsbiteCatalog.live(item)
                "all" -> true
                else -> SportsbiteCatalog.sport(item.text("category") ?: item.text("sport")) == request.data
            }
        }
        val size = 80
        val start = (page.toLong() - 1) * size
        val selected = if (liveTv) rows.drop(start.coerceAtMost(rows.size.toLong()).toInt()).take(size) else rows
        return newHomePageResponse(request, selected.map { searchItem(it) }, liveTv && start + size < rows.size)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        return browsable(catalog()).filter {
            val text = if (liveTv) listOf(it.text("name"), category(it), country(it)).joinToString(" ") else it.text("title").orEmpty()
            text.contains(term, true)
        }.map { searchItem(it) }
    }
    override suspend fun load(url: String): LoadResponse {
        val itemId = SportsbiteProtocol.itemKey(url, mainUrl, if (liveTv) "channel" else "event")
        val item = catalog().firstOrNull { key(it) == itemId } ?: throw ErrorLoadingException("This item is no longer listed")
        return newLiveStreamLoadResponse((if (liveTv) item.text("name") else item.text("title")).orEmpty(), url, url) {
            posterUrl = poster(item)
            tags = if (liveTv) listOf(category(item), country(item)) else listOfNotNull(item.text("category"))
            plot = if (liveTv) "${category(item)} · ${country(item)}\nAvailable servers are checked when playback starts."
            else listOfNotNull(item.text("start")?.let { "Scheduled start (UTC): $it" },
                when { SportsbiteCatalog.finished(item) -> "This event has finished. Streams may be offline."
                    SportsbiteCatalog.live(item) -> "Live now"
                    else -> "Streams may only become available near kickoff." },
                item.get("live_status")?.text("clock")?.let { "Match clock: $it" }).joinToString("\n")
        }
    }
    private suspend fun verifiedPlaylist(url: String, referer: String): String? {
        if (!SportsbiteProtocol.webUrl(url)) return null
        val target = SportsbiteProtocol.playbackUrl(url, mainUrl)
        repeat(2) { attempt ->
            val response = app.get(target, headers = requestHeaders, referer = referer, timeout = 12)
            if (response.code in 200..299 && SportsbiteProtocol.playlist(response.text)) return target
            if (attempt == 0 && response.code in setOf(429, 500, 502, 503, 504)) delay(1000)
            else return null
        }
        return null
    }
    private suspend fun resolve(stream: JsonNode): Triple<String, String, String>? {
        val raw = (stream.text("manifest_url") ?: stream.text("iframe_url") ?: stream.text("embed_url"))
            ?.takeIf { SportsbiteProtocol.webUrl(it) } ?: return null
        val uri = URI(raw)
        val label = stream.text("name") ?: stream.text("label") ?: "Server"
        val direct = stream.text("format")?.lowercase(Locale.ROOT) in setOf("hls", "m3u8") || uri.path.endsWith(".m3u8")
        val target = if (direct) verifiedPlaylist(raw, mainUrl + "/") else {
            if (uri.host != URI(mainUrl).host || uri.scheme != "https" || uri.userInfo != null) return null
            val route = SportsbiteProtocol.playlistRoute(raw, mainUrl)
            val mapped = route?.let { verifiedPlaylist(it, mainUrl + "/") }
            mapped ?: run {
                if (!uri.path.contains("-embed/embed/") && !uri.path.startsWith("/selfhost/")) return null
                val response = app.get(raw, headers = requestHeaders, referer = mainUrl + "/", timeout = 12)
                if (response.code !in 200..299) return null
                val source = SportsbiteProtocol.source(response.text, mainUrl) ?: return null
                verifiedPlaylist(source, raw)
            }
        } ?: return null
        return Triple(label, target, raw)
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        if (isCasting) return false
        val itemId = SportsbiteProtocol.itemKey(data, mainUrl, if (liveTv) "channel" else "event")
        val rows = catalog(true)
        val item = rows.firstOrNull { key(it) == itemId } ?: throw ErrorLoadingException("This item is no longer listed")
        val gate = Semaphore(4)
        val sources = (if (liveTv) rows.filter { channelGroup(it) == channelGroup(item) } else item.items("streams"))
            .filter { SportsbiteCatalog.usable(it) }.distinctBy { it.text("manifest_url") ?: it.text("iframe_url") ?: it.text("embed_url") }.take(32)
        val resolved = coroutineScope {
            sources.map { stream -> async {
                gate.withPermit {
                    try { resolve(stream) } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        null
                    }
                }
            } }.awaitAll().filterNotNull().distinctBy { it.second }
        }
        for ((label, target, embed) in resolved) {
            callback(newExtractorLink(name, "$name – $label", target, ExtractorLinkType.M3U8) {
                referer = embed
                extractorData = "sportsbite-media-v1"
            })
        }
        if (resolved.isEmpty()) throw ErrorLoadingException("No working Sportsbite playlist is available right now. The event may not have started, may have ended, or the upstream channel may be offline.")
        return true
    }
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        if (extractorLink.extractorData != "sportsbite-media-v1") return null
        return Interceptor { chain ->
            val request = chain.request()
            val ownHost = request.url.host == URI(mainUrl).host
            val imagePath = Regex("\\.(png|webp|jpe?g|image)$", RegexOption.IGNORE_CASE).containsMatchIn(request.url.encodedPath)
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

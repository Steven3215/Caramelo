package com.steven3215.globalfreetv

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class GlobalFreeTvProvider(private val settings: GlobalFreeTvSettings) : MainAPI() {
    override var mainUrl = GlobalFreeTvProtocol.BASE
    override var name = "Global Free TV"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var sequentialMainPage = true
    override val mainPage: List<MainPageData>
        get() = GlobalFreeTvProtocol.rows(settings.categories(), settings.countries(), settings.grouping(), settings::enabled, settings::allowed)
            .map { (key, label) -> mainPage(key, label) }
    private val mapper = ObjectMapper()
    private data class Cached<T>(val time: Long, val value: T)
    private val jsonCache = ConcurrentHashMap<String, Cached<JsonNode>>()
    private val completeCache = ConcurrentHashMap<String, Cached<List<JsonNode>>>()
    private val completeMutex = Mutex()
    private val gate = Semaphore(4)
    private val headers = mapOf("Accept" to "application/json", "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
    @Volatile private var directoryAt = 0L
    private fun fresh(time: Long) = System.currentTimeMillis() - time < 600000
    private suspend fun api(path: String, cache: Boolean = true): JsonNode {
        if (cache) jsonCache[path]?.takeIf { fresh(it.time) }?.let { return it.value }
        val response = gate.withPermit { app.get(GlobalFreeTvProtocol.API + path, headers = headers, referer = mainUrl + "/", timeout = 20) }
        if (response.code !in 200..299) throw ErrorLoadingException("Global Free TV API unavailable (HTTP ${response.code})")
        val result = mapper.readTree(response.text)
        if (cache) {
            if (jsonCache.size > 300) jsonCache.clear()
            jsonCache[path] = Cached(System.currentTimeMillis(), result)
        }
        return result
    }
    private suspend fun refreshDirectory() {
        if (directoryAt > 0 && fresh(directoryAt)) return
        directoryAt = System.currentTimeMillis()
        try {
            coroutineScope {
                val cats = async { api("/categories").items("data").mapNotNull {
                    val id = it.text("id")?.takeIf { key -> Regex("[A-Za-z0-9_-]+").matches(key) } ?: return@mapNotNull null
                    DirectoryEntry(id, it.text("name") ?: id)
                } }
                val names = async { api("/countries").items("data").associate {
                    GlobalFreeTvProtocol.country(it.text("code")) to it.text("name").orEmpty()
                } }
                val counts = async { api("/countries-with-channel-count").items("data") }
                val countryNames = names.await()
                val countries = counts.await().filter { (it.get("channel_count")?.asInt() ?: 0) > 0 }.map {
                    val code = GlobalFreeTvProtocol.country(it.text("country_code"))
                    DirectoryEntry(code, countryNames[code]?.takeIf { label -> label.isNotBlank() } ?: code)
                }.distinctBy { it.id }
                settings.remember(cats.await(), countries)
            }
        } catch (error: Exception) { if (error is CancellationException) throw error }
    }
    private fun order(sort: Int): String = when (sort) {
        1 -> "orderBy=name&order=ASC"
        2 -> "orderBy=created_at&order=DESC"
        else -> "orderBy=likes&order=DESC"
    }
    private suspend fun apiPage(endpoint: String, page: Int, sort: Int): JsonNode = api(
        endpoint + (if ('?' in endpoint) "&" else "?") + "page=$page&pageSize=100&${order(sort)}")
    // Country filtering must happen before local pagination. The API ignores combined
    // category/country query parameters, so fetch the complete relevant list, not just page 1.
    private suspend fun complete(endpoint: String): List<JsonNode> = completeMutex.withLock {
        completeCache[endpoint]?.takeIf { fresh(it.time) }?.let { return@withLock it.value }
        val first = apiPage(endpoint, 1, 1)
        val total = first.get("total")?.asInt() ?: throw ErrorLoadingException("Invalid channel pagination")
        if (total !in 0..20000) throw ErrorLoadingException("Global Free TV catalog size is unsupported")
        val last = (total + 99) / 100
        val result = first.items("data").toMutableList()
        for (batch in (2..last).chunked(4)) coroutineScope {
            batch.map { page -> async { apiPage(endpoint, page, 1).items("data") } }.awaitAll().forEach { result.addAll(it) }
        }
        val rows = result.filter { GlobalFreeTvProtocol.visible(it) }.distinctBy { it.text("id") }
        if (completeCache.size > 50) completeCache.clear()
        completeCache[endpoint] = Cached(System.currentTimeMillis(), rows)
        rows
    }
    private suspend fun selectedCatalog(): List<JsonNode> {
        val selected = settings.selectedCountries()
        if (selected.isEmpty()) return emptyList()
        if (selected.size > 8) return complete("/channels").filter { settings.allowed(it.text("country").orEmpty()) }
        return selected.flatMap { code -> GlobalFreeTvProtocol.countryAliases(code).flatMap {
            complete("/countries/${GlobalFreeTvProtocol.encode(it)}/channels")
        } }.distinctBy { it.text("id") }
    }
    private fun channelUrl(channel: JsonNode) = "$mainUrl/channels/${GlobalFreeTvProtocol.encode(channel.text("id").orEmpty())}"
    private fun item(channel: JsonNode): SearchResponse = newLiveSearchResponse(channel.text("name").orEmpty(), channelUrl(channel)) {
        posterUrl = channel.text("logo")?.takeIf { GlobalFreeTvProtocol.webUrl(it) }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page < 1 || !settings.enabled(request.data)) return newHomePageResponse(request, emptyList(), false)
        if (settings.filtering() && settings.selectedCountries().isEmpty()) return newHomePageResponse(request, emptyList(), false)
        refreshDirectory()
        val row = request.data
        val sort = if (row == "new") 2 else settings.sort()
        val endpoint = when {
            row in setOf("all", "new") -> "/channels"
            row.startsWith("category:") && settings.categories().any { it.id == row.removePrefix("category:") } ->
                "/categories/${GlobalFreeTvProtocol.encode(row.removePrefix("category:"))}/channels"
            row.startsWith("country:") && settings.countries().any { it.id == row.removePrefix("country:") } ->
                "/countries/${GlobalFreeTvProtocol.encode(row.removePrefix("country:"))}/channels"
            else -> return newHomePageResponse(request, emptyList(), false)
        }
        if (row.startsWith("country:") || settings.filtering()) {
            val rows = when {
                row.startsWith("country:") -> {
                    val code = row.removePrefix("country:")
                    if (!settings.allowed(code)) emptyList()
                    else GlobalFreeTvProtocol.countryAliases(code).flatMap { complete("/countries/${GlobalFreeTvProtocol.encode(it)}/channels") }
                }
                row in setOf("all", "new") -> selectedCatalog()
                else -> complete(endpoint).filter { settings.allowed(it.text("country").orEmpty()) }
            }
            val sorted = GlobalFreeTvProtocol.sorted(rows, sort)
            return newHomePageResponse(request, GlobalFreeTvProtocol.page(sorted, page).map { item(it) }, page.toLong() * 100 < sorted.size)
        }
        val response = apiPage(endpoint, page, sort)
        return newHomePageResponse(request, response.items("data").filter { GlobalFreeTvProtocol.visible(it) }.map { item(it) },
            page.toLong() * 100 < (response.get("total")?.asLong() ?: 0))
    }
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return GlobalFreeTvProtocol.sorted(complete("/search?query=${GlobalFreeTvProtocol.encode(query.trim())}")
            .filter { settings.allowed(it.text("country").orEmpty()) }, settings.sort()).map { item(it) }
    }
    override suspend fun load(url: String): LoadResponse {
        val id = GlobalFreeTvProtocol.channelId(url)
        val channel = api("/channels/${GlobalFreeTvProtocol.encode(id)}")
        return newLiveStreamLoadResponse(channel.text("name").orEmpty(), url, url) {
            posterUrl = channel.text("logo")?.takeIf { GlobalFreeTvProtocol.webUrl(it) }
            tags = listOfNotNull(channel.text("country")?.let { settings.countryName(it) }, channel.text("network"))
            plot = listOfNotNull(channel.text("country")?.let { "Country: ${settings.countryName(it)}" },
                channel.text("city")?.let { "City: $it" }, channel.text("network")?.let { "Network: $it" },
                "Live streams are provided by the broadcasters. Some may be offline or restricted in your region.").joinToString("\n")
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        val id = GlobalFreeTvProtocol.channelId(data)
        val streams = api("/channels/${GlobalFreeTvProtocol.encode(id)}/streams", cache = false).items("data")
            .filter { GlobalFreeTvProtocol.webUrl(it.text("url")) }.distinctBy { it.text("url") }.sortedByDescending { it.get("id")?.asLong() ?: 0 }.take(24)
        val count = AtomicInteger()
        // Prefer ordinary HLS; defer YouTube to Cloudstream's maintained extractor.
        coroutineScope {
            streams.filterNot { GlobalFreeTvProtocol.youtube(it.text("url").orEmpty()) }.mapIndexed { index, stream -> async {
                gate.withPermit {
                    try {
                        val url = stream.text("url") ?: return@withPermit
                        val referer = stream.text("http_referrer")?.takeIf { GlobalFreeTvProtocol.webUrl(it) }.orEmpty()
                        val userAgent = GlobalFreeTvProtocol.header(stream.text("user_agent"))
                        val streamHeaders = userAgent?.let { mapOf("User-Agent" to it) }.orEmpty()
                        val response = app.get(url, headers = streamHeaders, referer = referer, timeout = 12)
                        if (response.code in 200..299 && GlobalFreeTvProtocol.playlist(response.text)) {
                            callback(newExtractorLink(name, "$name – Source ${index + 1}" + (stream.text("resolution")?.let { " · $it" } ?: ""), url, ExtractorLinkType.M3U8) {
                                this.referer = referer
                                this.headers = streamHeaders
                            })
                            count.incrementAndGet()
                        }
                    } catch (error: Exception) { if (error is CancellationException) throw error }
                }
            } }.awaitAll()
        }
        for (stream in streams.filter { GlobalFreeTvProtocol.youtube(it.text("url").orEmpty()) }) {
            try {
                loadExtractor(stream.text("url").orEmpty(), subtitleCallback = subtitleCallback) { link -> callback(link); count.incrementAndGet() }
            } catch (error: Exception) { if (error is CancellationException) throw error }
        }
        if (count.get() == 0) throw ErrorLoadingException("No playable Global Free TV stream is available. The broadcaster may be offline, region-restricted, or YouTube playback may be unavailable.")
        return true
    }
}

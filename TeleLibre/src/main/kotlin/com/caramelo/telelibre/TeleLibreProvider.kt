package com.caramelo.telelibre

import com.caramelo.settings.ChannelRows
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CancellationException

class TeleLibreProvider internal constructor(private val settings: ChannelRows) : MainAPI() {
    override var name = "Tele Libre"
    override var mainUrl = "https://telelibre.st"
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var sequentialMainPage = true
    override val mainPage get() = settings.rows.filter { settings.enabled(it.id) }.map { mainPage(it.id, it.label) }

    private data class Channel(val slug: String, val name: String, val category: String, val logo: String?)
    private val mirrors = listOf("https://telelibre.st", "https://tele-libre.top")
    private val categories = settings.rows.map { it.id }.toSet() - "all"
    @Volatile private var cached: Pair<Long, List<Channel>>? = null
    private fun channelUrl(slug: String, base: String = mainUrl) = "$base/en-vivo/$slug/"
    private fun slug(value: String): String? = runCatching {
        val url = URI(mainUrl).resolve(value)
        require(url.scheme == "https" && url.port == -1 && url.userInfo == null)
        require(mirrors.any { URI(it).host == url.host })
        Regex("^/en-vivo/([a-z0-9-]{1,120})/?$").matchEntire(url.path)?.groupValues?.get(1)
    }.getOrNull()
    private fun channels(doc: Document): List<Channel> = doc.select("article.channel-card[data-category]").mapNotNull { card ->
        val category = card.attr("data-category")
        if (category !in categories) return@mapNotNull null
        val slug = slug(card.selectFirst("a[href]")?.attr("href").orEmpty()) ?: return@mapNotNull null
        val name = card.selectFirst(".channel-name")?.text()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Channel(slug, name, category, card.selectFirst("img[src]")?.absUrl("src")?.takeIf { it.startsWith("https://") })
    }.distinctBy { it.slug }
    private suspend fun directory(): List<Channel> {
        cached?.takeIf { System.currentTimeMillis() - it.first < 300_000L }?.let { return it.second }
        for (base in mirrors) try {
            val doc = page("$base/", "") ?: continue
            val channels = channels(doc)
            if (channels.isNotEmpty()) {
                cached = System.currentTimeMillis() to channels
                return channels
            }
        } catch (error: Exception) { if (error is CancellationException) throw error }
        return cached?.second ?: throw ErrorLoadingException("Both Tele Libre directories are unavailable")
    }
    private fun item(channel: Channel) = newLiveSearchResponse(channel.name, channelUrl(channel.slug)) {
        posterUrl = channel.logo
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page < 1 || !settings.enabled(request.data)) return newHomePageResponse(request, emptyList(), false)
        val channels = directory().filter { request.data == "all" || it.category == request.data }
        val offset = (page.toLong() - 1) * 40
        if (offset >= channels.size) return newHomePageResponse(request, emptyList(), false)
        return newHomePageResponse(request, channels.drop(offset.toInt()).take(40).map { item(it) }, offset + 40 < channels.size)
    }
    private fun normalize(value: String) =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()
    override suspend fun search(query: String): List<SearchResponse> {
        val term = normalize(query)
        if (term.isBlank()) return emptyList()
        return directory().filter { term in normalize(it.name) || term in it.slug }.map { item(it) }
    }
    override suspend fun load(url: String): LoadResponse {
        val slug = slug(url) ?: throw ErrorLoadingException("Invalid Tele Libre channel")
        val channel = directory().firstOrNull { it.slug == slug } ?: throw ErrorLoadingException("This channel is unavailable in Tele Libre")
        val canonical = channelUrl(slug)
        return newLiveStreamLoadResponse(channel.name, canonical, canonical) {
            posterUrl = channel.logo
            plot = settings.rows.firstOrNull { it.id == channel.category }?.label
        }
    }
    private suspend fun page(url: String, referer: String): Document? {
        val response = app.get(url, referer = referer, timeout = 12)
        return response.takeIf { it.code in 200..299 }?.let { org.jsoup.Jsoup.parse(it.text, it.url) }
    }
    private fun media(url: String) = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.path.endsWith(".m3u8", true)
    }.getOrDefault(false)

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val slug = slug(data) ?: return false
        if (directory().none { it.slug == slug }) return false
        val emitted = mutableSetOf<String>()
        suspend fun emit(url: String, referer: String) {
            if (!media(url) || url in emitted) return
            val response = app.get(url, referer = referer, timeout = 12)
            if (response.code !in 200..299 || !response.text.trimStart().startsWith("#EXTM3U")) return
            emitted.add(url)
            callback(newExtractorLink(name, "$name – Signal " + emitted.size, url, ExtractorLinkType.M3U8) { this.referer = referer })
        }
        for (base in mirrors) {
            val channelUrl = channelUrl(slug, base)
            val doc = try { page(channelUrl, "$base/") } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            } ?: continue
            val frames = (doc.select(".player-shell iframe[src]").map { it.absUrl("src") } +
                doc.select(".option-buttons a[target=iframe][href]").map { it.absUrl("href") }).distinct().take(4)
            for (frame in frames) try {
                if (media(frame)) { emit(frame, channelUrl); continue }
                val frameUri = URI(frame)
                if (frameUri.scheme != "https" || frameUri.host != URI(base).host ||
                    !frameUri.path.startsWith("/en-vivo/$slug/")) continue
                val embed = page(frame, channelUrl) ?: continue
                for (node in embed.select("video[src], video source[src], iframe[src]")) {
                    val link = node.absUrl("src")
                    if (media(link)) { emit(link, frame); continue }
                    val uri = URI(link)
                    if (uri.scheme == "https" && uri.host == "www.rtve.es" && uri.userInfo == null &&
                        uri.path.startsWith("/play/videos/directo/canales-lineales/")) {
                        val id = page(link, frame)?.selectFirst("meta[name=DC.identifier]")?.attr("content")
                            ?.takeIf { it.matches(Regex("[0-9]{4,12}")) } ?: continue
                        emit("https://ztnr.rtve.es/ztnr/$id.m3u8", "")
                    }
                }
                val scripts = embed.select("script:not([src])").joinToString("\n") { it.data() }
                val direct = Regex("""(?:file|source|src)\s*[:=]\s*["'](https://[^"'\s]+\.m3u8(?:\?[^"'\s]*)?)["']""")
                for (match in direct.findAll(scripts).take(4)) emit(match.groupValues[1], frame)
            } catch (error: Exception) { if (error is CancellationException) throw error }
            if (emitted.isNotEmpty()) return true
        }
        throw ErrorLoadingException("No supported Tele Libre stream was found. Some channels use encrypted DASH or region-specific players.")
    }
}

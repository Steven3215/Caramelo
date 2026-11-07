package com.steven3215.dominicanchannels

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLEncoder

class DominicanChannelsProvider : MainAPI() {
    override var name = "Dominican Channels"
    override var mainUrl = "https://dominicanchannels.com"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    override val mainPage = mainPageOf(
        "/" to "Todos los canales",
        "/tv/santo-domingo/" to "Santo Domingo",
        "/tv/santiago/" to "Santiago",
        "/tv/santiago-y-el-norte/" to "Zona Norte",
        "/tv/sur-este/" to "Sur y Este",
        "/tv/online/" to "Online",
        "/tv/tv-cristiana/" to "TV cristiana",
        "/tv/usa/" to "Estados Unidos",
    )

    private val hosts = setOf("dominicanchannels.com", "www.dominicanchannels.com", "tv.dominicanchannels.com")
    private val channelPath = Regex("/[a-z0-9][a-z0-9-]*/?", RegexOption.IGNORE_CASE)
    private val scriptSource = Regex("""\bsrc\s*:\s*["']([^"']+)["']""")

    private fun channelUrl(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host !in hosts || uri.userInfo != null ||
            uri.port != -1 || !channelPath.matches(uri.path)) return null
        return "https://${uri.host}${uri.path.trimEnd('/')}/"
    }

    private fun catalog(document: Document): List<SearchResponse> =
        document.select(".postbox").mapNotNull { card ->
            val link = card.selectFirst("a[rel=bookmark]") ?: return@mapNotNull null
            val url = channelUrl(link.absUrl("href")) ?: return@mapNotNull null
            val title = link.text().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val poster = card.selectFirst(".boxentry img")
            newLiveSearchResponse(title, url) {
                posterUrl = poster?.absUrl("data-src")?.takeIf(String::isNotBlank)
                    ?: poster?.absUrl("src")?.takeIf(String::isNotBlank)
            }
        }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data + if (page > 1) "page/$page/" else ""
        val document = app.get(mainUrl + path).document
        val channels = catalog(document)
        return newHomePageResponse(request, channels, channels.isNotEmpty() && document.selectFirst("a.nextpostslink") != null)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return catalog(app.get("$mainUrl/?s=$encoded").document)
    }

    override suspend fun load(url: String): LoadResponse {
        val page = channelUrl(url) ?: throw ErrorLoadingException("Canal no válido.")
        val document = app.get(page).document
        val title = document.selectFirst("#singlecont h2")?.text()?.takeIf(String::isNotBlank)
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("Dominicanchannels.com")?.trim(' ', '*')?.takeIf(String::isNotBlank)
            ?: throw ErrorLoadingException("No se encontró el canal.")
        return newLiveStreamLoadResponse(title, page, page) {
            posterUrl = document.selectFirst("meta[property=og:image]")?.absUrl("content")
            plot = document.selectFirst("meta[property=og:description]")?.attr("content")
        }
    }

    private fun playlistUrls(player: Element): List<String> {
        val tags = player.select("video[src], video-js[src], video source, video-js source")
            .map { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
        val scripts = player.select("script").flatMap { script ->
            scriptSource.findAll(script.data()).map { match ->
                val src = Parser.unescapeEntities(match.groupValues[1].replace("\\/", "/"), false)
                runCatching { URI(script.baseUri()).resolve(src).toString() }.getOrDefault("")
            }.toList()
        }
        return (tags + scripts).filter { url ->
            val uri = runCatching { URI(url) }.getOrNull()
            uri?.scheme in setOf("http", "https") && uri?.host != null &&
                uri.userInfo == null && uri.path.endsWith(".m3u8", ignoreCase = true)
        }.distinct()
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = channelUrl(data) ?: return false
        val document = app.get(page).document
        val player = document.selectFirst(".videosection") ?: return false
        val candidates = playlistUrls(player).map { it to page }.toMutableList()
        if (candidates.isEmpty()) {
            val frames = player.select("iframe").map { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
                .filter { it.startsWith("https://") }.distinct().take(2)
            for (frame in frames) {
                val embedded = attempt { app.get(frame, referer = page, timeout = 8).document } ?: continue
                candidates += playlistUrls(embedded).map { it to frame }
            }
        }

        val links = coroutineScope {
            candidates.distinctBy { it.first }.take(8).map { (url, referer) ->
                async {
                    attempt {
                        val response = app.get(url, referer = referer, timeout = 8)
                        if (response.code in 200..299 && response.text.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) {
                            url to referer
                        } else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        links.forEachIndexed { index, (url, pageReferer) ->
            callback(newExtractorLink(name, "$name · Fuente ${index + 1}", url, ExtractorLinkType.M3U8) {
                referer = pageReferer
            })
        }
        return links.isNotEmpty()
    }
}

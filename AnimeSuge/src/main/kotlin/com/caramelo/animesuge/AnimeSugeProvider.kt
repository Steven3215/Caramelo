package com.caramelo.animesuge

import com.caramelo.tracks.MediaTracks
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException

class AnimeSugeProvider : MainAPI() {
    override var name = "AnimeSuge"
    override var mainUrl = AnimeSugeProtocol.BASE
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val hasMainPage = true
    override val mainPage = mainPageOf("/latest-updated" to "Latest Episodes", "/new-release" to "Recently Added",
        "/most-viewed" to "Most Viewed", "/status/currently-airing" to "Ongoing", "/status/finished-airing" to "Completed", "/type/movie" to "Anime Movies")
    private val json = ObjectMapper()
    private val headers = mapOf("X-Requested-With" to "XMLHttpRequest", "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
    private fun card(element: Element): SearchResponse? {
        val link = element.selectFirst("a.poster[href], .name a[href]") ?: return null
        val url = runCatching { AnimeSugeProtocol.animeUrl(link.attr("href")) }.getOrNull() ?: return null
        val title = element.selectFirst(".name a")?.text()?.takeIf { it.isNotBlank() } ?: element.selectFirst("img")?.attr("alt") ?: return null
        val image = element.selectFirst("img")
        val poster = (image?.attr("data-src")?.takeIf { it.isNotBlank() } ?: image?.attr("src"))?.takeIf { AnimeSugeProtocol.webUrl(it) }
        return newAnimeSearchResponse(title, url, if (element.selectFirst(".type")?.text() == "MOVIE") TvType.AnimeMovie else TvType.Anime) { posterUrl = poster }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl${request.data}?page=$page", headers = headers).document
        val cards = document.select(".item:has(a.poster), .inner:has(a.poster)").mapNotNull { card(it) }.distinctBy { it.url }
        return newHomePageResponse(request, cards, document.select(".pagination a[rel=next]").isNotEmpty())
    }
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val document = app.get("$mainUrl/filter?keyword=${AnimeSugeProtocol.encode(query.trim())}", headers = headers).document
        return document.select(".item:has(a.poster), .inner:has(a.poster)").mapNotNull { card(it) }.distinctBy { it.url }
    }
    private suspend fun episodeDocument(id: String, url: String) = Jsoup.parse(json.readTree(
        app.get("$mainUrl/ajax/episode/list/$id", headers = headers, referer = url).text).text("result").orEmpty())
    override suspend fun load(url: String): LoadResponse {
        val canonical = AnimeSugeProtocol.animeUrl(url)
        val doc = app.get(canonical, headers = headers).document
        val id = doc.selectFirst(".watch-wrap[data-id]")?.attr("data-id")?.takeIf { Regex("[0-9]+").matches(it) }
            ?: throw ErrorLoadingException("AnimeSuge anime identifier unavailable")
        val episodes = episodeDocument(id, canonical).select("a[data-ids][data-slug]")
        fun list(audio: String): List<Episode> = episodes.filter { it.attr("data-$audio") == "1" }.mapNotNull { ep ->
            val slug = ep.attr("data-slug").takeIf { Regex("[A-Za-z0-9_-]{1,80}").matches(it) } ?: return@mapNotNull null
            val number = slug.toIntOrNull()?.takeIf { it > 0 }
            val payload = json.createObjectNode().put("url", canonical).put("id", id).put("slug", slug).put("audio", audio).toString()
            newEpisode(payload) { episode = number; name = ep.attr("title").ifBlank { "Episode $slug" } }
        }.distinctBy { it.data }
        val info = doc.selectFirst("#media-info") ?: doc
        val movie = info.select(".meta > div").any { it.selectFirst("div")?.text() == "Type:" && it.selectFirst("span")?.text() == "MOVIE" }
        return newAnimeLoadResponse(info.selectFirst("h1.title")?.text() ?: "Anime", canonical, if (movie) TvType.AnimeMovie else TvType.Anime) {
            posterUrl = info.selectFirst("img[itemprop=image]")?.attr("src")
            plot = info.selectFirst(".description [data-name=full] > div")?.text() ?: info.selectFirst(".description")?.text()
            tags = info.select(".meta a[href*=/genre/]").map { it.text() }
            year = Regex("\\b(19|20)[0-9]{2}\\b").find(info.select(".meta a[href*=year]").text())?.value?.toIntOrNull()
            addEpisodes(DubStatus.Subbed, list("sub")); addEpisodes(DubStatus.Dubbed, list("dub"))
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        val mediaTracks = MediaTracks(subtitleCallback)
        if (isCasting) return false
        val input = json.readTree(data)
        val url = AnimeSugeProtocol.animeUrl(input.text("url") ?: return false)
        val id = input.text("id")?.takeIf { Regex("[0-9]+").matches(it) } ?: return false
        val slug = input.text("slug")?.takeIf { Regex("[A-Za-z0-9_-]{1,80}").matches(it) } ?: return false
        val audio = input.text("audio")?.takeIf { it in setOf("sub", "dub") } ?: return false
        val episode = episodeDocument(id, url).select("a[data-ids][data-slug]").firstOrNull { it.attr("data-slug") == slug } ?: return false
        val servers = json.readTree(app.get("$mainUrl/ajax/server/list?servers=${AnimeSugeProtocol.encode(episode.attr("data-ids"))}", headers = headers, referer = url).text)
        val elements = Jsoup.parse(servers.text("result").orEmpty()).select(".server-type[data-type]")
            .filter { it.attr("data-type") == audio || (audio == "sub" && it.attr("data-type") == "softsub") }
            .flatMap { it.select("[data-link-id]") }.take(6)
        val links = linkedSetOf<String>()
        for (server in elements) try {
            val response = json.readTree(app.get("$mainUrl/ajax/server?get=${AnimeSugeProtocol.encode(server.attr("data-link-id"))}", headers = headers, referer = url).text)
            val embed = response.get("result")?.text("url")?.takeIf { AnimeSugeProtocol.megaPlay(it) } ?: continue
            val page = app.get(embed, headers = headers, referer = url).document
            val mediaId = page.selectFirst("#megaplay-player[data-id]")?.attr("data-id")?.takeIf { Regex("[0-9]+").matches(it) } ?: continue
            val extra = if (embed.contains("s=tcdn")) "&s=tcdn" else ""
            val bundle = json.readTree(app.get("https://megaplay.buzz/stream/getSourcesNew?id=$mediaId$extra", headers = headers, referer = embed, timeout = 20).text)
            val decoded = bundle.text("enc")?.let { json.readTree(AnimeSugeProtocol.decode(it)) }
            val target = (decoded?.text("file") ?: bundle.items("sources").firstOrNull()?.text("file"))?.takeIf { AnimeSugeProtocol.webUrl(it) } ?: continue
            if (target in links) continue
            val playlist = app.get(target, headers = headers, referer = "https://megaplay.buzz/", timeout = 15)
            if (playlist.code !in 200..299 || !playlist.text.trimStart().startsWith("#EXTM3U")) continue
            mediaTracks.sidecars(bundle.items("tracks") + bundle.items("subtitles"), embed,
                headers + ("Referer" to embed))
            links.add(target)
            mediaTracks.publish(newExtractorLink(name, "$name – ${server.text()}", target, ExtractorLinkType.M3U8) {
                referer = "https://megaplay.buzz/"; extractorData = "animesuge-transport-v1"
            }, callback, playlist.text, playlist.url, audio = if (audio == "dub") "Dubbed" else "Original")
        } catch (error: Exception) { if (error is CancellationException) throw error }
        if (links.isEmpty()) throw ErrorLoadingException("No working AnimeSuge ${audio.uppercase()} stream is available for this episode")
        return true
    }
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        if (extractorLink.extractorData != "animesuge-transport-v1") return null
        return Interceptor { chain ->
            val request = chain.request()
            val image = Regex("\\.(png|webp|jpe?g|image)$", RegexOption.IGNORE_CASE).containsMatchIn(request.url.encodedPath)
            val response = chain.proceed(if (image) request.newBuilder().removeHeader("Range").removeHeader("Referer").removeHeader("Origin").build() else request)
            val body = response.body ?: return@Interceptor response
            if (!response.isSuccessful || (!image && body.contentType()?.type != "image")) return@Interceptor response
            val decoded = try { body.byteStream().use { AnimeSugeTransport.unwrap(AnimeSugeTransport.bounded(it)) } }
                catch (error: Exception) { throw IOException("AnimeSuge media transport failed", error) }
                ?: throw IOException("Unsupported AnimeSuge media container")
            response.newBuilder().removeHeader("Content-Length").removeHeader("Content-Range").removeHeader("Content-Encoding")
                .header("Content-Type", "video/mp2t").body(decoded.toResponseBody("video/mp2t".toMediaType())).build()
        }
    }
}

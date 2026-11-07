package com.caramelo.justanime

import com.caramelo.tracks.MediaTracks
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CancellationException

class JustAnimeProvider : MainAPI() {
    override var name = "JustAnime"
    override var mainUrl = "https://justanime.to"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val hasMainPage = true
    override var sequentialMainPage = true
    override val mainPage = mainPageOf("trending" to "Trending", "popular" to "Popular", "airing" to "Airing",
        "latestEpisode" to "Latest Episodes", "latestCompleted" to "Recently Completed")

    private val apiBase = "https://core.justanime.to/api"
    private val json = ObjectMapper()
    @Volatile private var home: Pair<Long, JsonNode>? = null
    private fun JsonNode.string(key: String) = get(key)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
    private fun title(item: JsonNode): String? {
        val title = item.path("title")
        return if (title.isTextual) title.asText() else title.string("english") ?: title.string("romaji") ?: title.string("native")
    }
    private fun cover(item: JsonNode) = item.string("cover") ?: item.path("coverImage").let {
        if (it.isTextual) it.asText() else it.string("extraLarge") ?: it.string("large") ?: it.string("medium")
    }
    private fun type(item: JsonNode) = if ((item.string("format") ?: item.string("type")).equals("MOVIE", true)) TvType.AnimeMovie else TvType.Anime
    private suspend fun api(path: String): JsonNode {
        val response = app.get(apiBase + path, headers = mapOf("Origin" to mainUrl), referer = "$mainUrl/", timeout = 20)
        if (response.code !in 200..299) throw ErrorLoadingException("JustAnime API unavailable (HTTP ${response.code})")
        return json.readTree(response.text)
    }
    private fun card(item: JsonNode): SearchResponse? {
        if (item.path("isAdult").asBoolean(false)) return null
        val id = item.path("id").asInt().takeIf { it > 0 } ?: return null
        return newAnimeSearchResponse(title(item) ?: return null, "$mainUrl/anime/$id", type(item)) {
            posterUrl = cover(item)
            year = item.path("year").asInt(item.path("seasonYear").asInt()).takeIf { it > 0 }
        }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page != 1) return newHomePageResponse(request, emptyList(), false)
        val data = home?.takeIf { System.currentTimeMillis() - it.first < 300_000L }?.second ?: api("/home").also {
            home = System.currentTimeMillis() to it
        }
        return newHomePageResponse(request, data.path(request.data).mapNotNull { card(it) }, false)
    }
    override suspend fun search(query: String): List<SearchResponse> =
        if (query.trim().length < 3) emptyList() else api("/search/suggestions?query=" + URLEncoder.encode(query.trim(), "UTF-8"))
            .path("data").mapNotNull { card(it) }
    override suspend fun load(url: String): LoadResponse {
        val uri = URI(url)
        val id = Regex("^/anime/([1-9][0-9]{0,8})(?:/[^/]+)?/?$").matchEntire(uri.path)?.groupValues?.get(1)
            ?.takeIf { uri.scheme == "https" && uri.host == "justanime.to" && uri.userInfo == null }
            ?: throw ErrorLoadingException("Invalid JustAnime title")
        val info = api("/anime/$id").path("data")
        if (info.path("id").asText() != id || info.path("isAdult").asBoolean(false)) throw ErrorLoadingException("Title unavailable")
        val first = api("/anime/$id/episodes?page=1")
        val episodes = (listOf(first) + (2..first.path("totalPages").asInt(1).coerceIn(1, 30)).map { api("/anime/$id/episodes?page=$it") })
            .flatMap { it.path("episodes").toList() }.mapNotNull { ep ->
                val number = ep.path("number").asInt().takeIf { it > 0 } ?: return@mapNotNull null
                newEpisode("$id/$number") {
                    episode = number
                    name = ep.string("title")
                    posterUrl = ep.string("image")
                    description = ep.string("description")
                }
            }.distinctBy { it.data }
        return newAnimeLoadResponse(title(info) ?: "Anime", "$mainUrl/anime/$id", type(info)) {
            posterUrl = cover(info)
            backgroundPosterUrl = info.string("bannerImage")
            year = info.path("seasonYear").asInt().takeIf { it > 0 }
            plot = info.string("description")?.let { org.jsoup.Jsoup.parse(it).text() }
            tags = info.path("genres").map { it.asText() }
            addEpisodes(DubStatus.None, episodes)
        }
    }
    private fun web(value: String) = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host != null && uri.userInfo == null
    }.getOrDefault(false)
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val mediaTracks = MediaTracks(subtitleCallback)
        if (!data.matches(Regex("[1-9][0-9]{0,8}/[1-9][0-9]{0,5}"))) return false
        val (id, ep) = data.split('/')
        val path = "/watch/$id/episode/$ep"
        val servers = api("$path/servers").path("servers")
        val audioTypes = listOf("sub", "dub")
        val serverOrder = listOf("revo", "gigi", "calm", "momo", "onyx", "nami", "zone", "susi")
        val advertised = audioTypes.filter { audio -> serverOrder.any { servers.path(it).path(audio).asBoolean() } }
        val verifiedAudio = mutableSetOf<String>()
        val emitted = mutableSetOf<String>()
        for (server in serverOrder) {
            val availability = servers.path(server)
            val available = audioTypes.filter { availability.path(it).asBoolean() }
            if (available.isEmpty() || (emitted.size >= 2 && available.all { it in verifiedAudio })) continue
            try {
                val bundle = api("$path/$server")
                for (audio in available) {
                    val group = bundle.path(audio)
                    for (source in group.path("sources")) {
                        val url = source.string("url")?.takeIf { web(it) && it !in emitted } ?: continue
                        val hls = source.path("isM3U8").asBoolean() || URI(url).path.endsWith(".m3u8", true)
                        if (!hls) continue
                        val suppliedHeaders = (group.path("headers").fields().asSequence() + source.path("headers").fields().asSequence())
                            .filter { it.key.lowercase(Locale.ROOT) in setOf("referer", "origin", "user-agent") && it.value.isTextual }
                            .associate { it.key.lowercase(Locale.ROOT) to it.value.asText() }
                        val referer = suppliedHeaders["referer"] ?: "$mainUrl/"
                        val headers = mapOf(
                            "Origin" to (suppliedHeaders["origin"] ?: mainUrl),
                            "User-Agent" to (suppliedHeaders["user-agent"] ?: "Mozilla/5.0")
                        )
                        val response = app.get(url, headers = headers, referer = referer, timeout = 12)
                        if (response.code !in 200..299 || !response.text.trimStart().startsWith("#EXTM3U")) continue
                        emitted.add(url)
                        verifiedAudio.add(audio)
                        mediaTracks.publish(newExtractorLink(name, "$name – $server", url, ExtractorLinkType.M3U8) {
                            this.referer = referer
                            this.headers = headers
                            quality = getQualityFromName(source.string("quality"))
                        }, callback, response.text, response.url, audio = if (audio == "dub") "Dubbed" else "Original")
                    }
                    mediaTracks.sidecars(group.path("subtitles").toList() + group.path("tracks").toList(), apiBase + "/",
                        mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/"))
                }
                if (emitted.size >= 2 && advertised.all { it in verifiedAudio }) break
            } catch (error: Exception) { if (error is CancellationException) throw error }
        }
        if (emitted.isEmpty()) throw ErrorLoadingException("No working JustAnime HLS stream was found for this episode")
        return true
    }
}

package com.caramelo.nekowatch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

class NekowatchProvider : MainAPI() {
    override var name = "Nekowatch"
    override var mainUrl = NekowatchProtocol.BASE
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val hasMainPage = true
    override var sequentialMainPage = true
    override val mainPage = mainPageOf("trending" to "Trending", "popular" to "Popular", "airing" to "Airing",
        "topRated" to "Top Rated", "seasonal" to "Seasonal", "recentlyUpdated" to "Recently Updated")
    private val json = ObjectMapper()
    private val gate = Semaphore(3)
    private val cache = ConcurrentHashMap<String, Pair<Long, JsonNode>>()
    private val headers = mapOf("X-Requested-With" to "NekoWatch", "Accept" to "application/json",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
    private suspend fun api(path: String, cached: Boolean = true): JsonNode {
        if (cached) cache[path]?.takeIf { System.currentTimeMillis() - it.first < 180000 }?.let { return it.second }
        val response = gate.withPermit { app.get(mainUrl + path, headers = headers, referer = "$mainUrl/", timeout = 25) }
        if (response.code !in 200..299) throw ErrorLoadingException("Nekowatch API unavailable (HTTP ${response.code})")
        val result = json.readTree(response.text)
        if (cached) { if (cache.size > 100) cache.clear(); cache[path] = System.currentTimeMillis() to result }
        return result
    }
    private fun title(item: JsonNode): String = item.get("title")?.let {
        if (it.isTextual) it.asText() else it.text("english") ?: it.text("userPreferred") ?: it.text("romaji")
    } ?: "Anime"
    private fun cover(item: JsonNode): String? = item.get("coverImage")?.let {
        if (it.isTextual) it.asText() else it.text("extraLarge") ?: it.text("large") ?: it.text("medium")
    }?.let { NekowatchProtocol.absolute(it) }
    private fun movie(item: JsonNode) = item.text("format") == "MOVIE"
    private fun searchItem(item: JsonNode): SearchResponse? {
        val id = item.get("id")?.asInt()?.takeIf { it > 0 } ?: return null
        return newAnimeSearchResponse(title(item), "$mainUrl/anime/$id", if (movie(item)) TvType.AnimeMovie else TvType.Anime) {
            posterUrl = cover(item); year = item.get("seasonYear")?.asInt()
        }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page != 1) return newHomePageResponse(request, emptyList(), false)
        return newHomePageResponse(request, api("/api/home?v=2").items(request.data).mapNotNull { searchItem(it) }, false)
    }
    override suspend fun search(query: String): List<SearchResponse> = if (query.isBlank()) emptyList() else
        api("/api/anime/search?q=${NekowatchProtocol.encode(query.trim())}&complete=1").items("items").mapNotNull { searchItem(it) }
    private suspend fun episodeLists(id: Int): JsonNode = api("/api/anime/episodes/konbanwa/arigato/konichiwa/$id?map=false")
    override suspend fun load(url: String): LoadResponse {
        val id = NekowatchProtocol.id(url)
        val info = api("/api/anilist?q=${NekowatchProtocol.DETAILS_QUERY}&op=NekoWatchAnimeDetails&v=${NekowatchProtocol.encode("{\"id\":$id}")}&opName=details-core")
            .get("data")?.get("Media") ?: throw ErrorLoadingException("Nekowatch details unavailable")
        if (info.get("id")?.asInt() != id) throw ErrorLoadingException("Nekowatch returned a different anime")
        val lists = episodeLists(id)
        fun episodes(audio: String): List<Episode> = NekowatchProtocol.providers.keys.flatMap { provider ->
            lists.get(provider)?.get("episodes")?.items(audio).orEmpty()
        }.filter { NekowatchProtocol.episodeId(it.text("id").orEmpty(), id, audio) != null }
            .distinctBy { it.get("number")?.asInt() }.sortedBy { it.get("number")?.asInt() }.mapNotNull { ep ->
                val number = ep.get("number")?.asInt()?.takeIf { it > 0 } ?: return@mapNotNull null
                val data = json.createObjectNode().put("id", id).put("episode", number).put("audio", audio).toString()
                newEpisode(data) { episode = number; name = ep.text("title"); description = ep.text("description"); posterUrl = NekowatchProtocol.absolute(ep.text("image")) }
            }
        return newAnimeLoadResponse(title(info), url, if (movie(info)) TvType.AnimeMovie else TvType.Anime) {
            posterUrl = cover(info); backgroundPosterUrl = NekowatchProtocol.absolute(info.text("bannerImage"))
            year = info.get("seasonYear")?.asInt(); plot = info.text("description"); duration = info.get("duration")?.asInt()
            tags = info.items("genres").map { it.asText() }; addScore(info.text("averageScore"), 100)
            addEpisodes(DubStatus.Subbed, episodes("sub")); addEpisodes(DubStatus.Dubbed, episodes("dub"))
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        val input = json.readTree(data)
        val id = input.get("id")?.asInt() ?: return false
        val number = input.get("episode")?.asInt() ?: return false
        val audio = input.text("audio")?.takeIf { it in setOf("sub", "dub") } ?: return false
        require(id > 0 && number > 0)
        val lists = episodeLists(id)
        val responses = coroutineScope { NekowatchProtocol.providers.keys.map { provider -> async {
            try {
                val ep = lists.get(provider)?.get("episodes")?.items(audio)?.firstOrNull { it.get("number")?.asInt() == number } ?: return@async null
                val path = NekowatchProtocol.episodeId(ep.text("id").orEmpty(), id, audio) ?: return@async null
                val result = api("/api/anime/$path", cached = false)
                result.takeIf { NekowatchProtocol.identity(it, id, number, audio) }
            } catch (error: Exception) { if (error is CancellationException) throw error; null }
        } }.awaitAll().filterNotNull() }
        val subtitles = responses.flatMap { it.items("subtitles") }.distinctBy { it.text("url") }
        for (sub in subtitles) NekowatchProtocol.absolute(sub.text("url"))?.let {
            subtitleCallback(SubtitleFile(sub.text("label") ?: sub.text("lang") ?: "Unknown", it))
        }
        var count = 0
        for (stream in responses.flatMap { it.items("streams") }.distinctBy { it.text("url") }) {
            val kind = stream.text("kind")
            if (kind !in setOf("hls", "file")) continue
            val url = NekowatchProtocol.absolute(stream.text("url")) ?: continue
            if (kind == "hls") {
                try {
                    val result = app.get(url, headers = headers, referer = "$mainUrl/", timeout = 15)
                    if (result.code !in 200..299 || !result.text.trimStart().startsWith("#EXTM3U")) continue
                } catch (error: Exception) { if (error is CancellationException) throw error; continue }
            }
            callback(newExtractorLink(name, "$name – ${stream.text("label") ?: "Server"} (${audio.uppercase()})", url,
                if (kind == "hls") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                referer = "$mainUrl/"; this.headers = this@NekowatchProvider.headers; quality = getQualityFromName(stream.text("quality"))
            }); count++
        }
        if (count == 0) throw ErrorLoadingException("No working Nekowatch ${audio.uppercase()} stream is available for this episode")
        return true
    }
}

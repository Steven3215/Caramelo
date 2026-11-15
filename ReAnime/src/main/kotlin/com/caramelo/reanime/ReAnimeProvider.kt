package com.caramelo.reanime

import com.caramelo.webcatalog.WebCatalog
import com.caramelo.webcatalog.WebSettings
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import java.net.URI

internal class ReAnimeProvider(private val settings: WebSettings) : WebCatalog() {
    override var name = "Re:ANIME"
    override var mainUrl = "https://reanime.cz"
    override val moviePath = "anime"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    companion object {
        val rows = linkedMapOf("Latest Episodes" to "Latest Episodes", "New on Site" to "New on Site", "Upcoming" to "Upcoming")
        val servers = listOf("HD-1", "HD-2", "H-SUB")
    }
    override val mainPage: List<MainPageData>
        get() = rows.filterKeys(settings::rowEnabled).map { (key, label) -> mainPage(key, label) }

    private fun cards(root: Element): List<SearchResponse> =
        root.select("a[href^=/anime/], a[href^=/watch/]").mapNotNull { link ->
            val poster = link.selectFirst("img[alt]") ?: return@mapNotNull null
            val title = poster.attr("alt").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val slug = link.attr("href").substringBefore('?').substringAfterLast('/')
            if (!slug.matches(Regex("[a-z0-9-]+"))) return@mapNotNull null
            newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                posterUrl = poster.absUrl("src")
            }
        }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page != 1 || !settings.rowEnabled(request.data)) return newHomePageResponse(request, emptyList(), false)
        val doc = app.get("$mainUrl/home", headers = browser).document
        val section = doc.select("section").firstOrNull { it.selectFirst("h2")?.text() == request.data }
        return newHomePageResponse(request, section?.let(::cards).orEmpty(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val doc = app.get("$mainUrl/search?q=${encode(term)}", headers = browser).document
        return cards(doc).filter { result -> term.split(Regex("\\s+")).all { result.name.contains(it, ignoreCase = true) } }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.removePrefix("$mainUrl/anime/").takeIf { it.matches(Regex("[a-z0-9-]+")) }
            ?: throw ErrorLoadingException("Invalid Re:ANIME title.")
        val item = json("$mainUrl/api/v1/watch/$slug?ep=1").path("anime")
        val title = item.path("title").text("english") ?: item.path("title").text("romaji")
            ?: throw ErrorLoadingException("Re:ANIME title unavailable.")
        val movie = item.text("format").equals("MOVIE", true)
        val episodes = mutableListOf<Pair<com.fasterxml.jackson.databind.JsonNode, Int>>()
        var offset = 0
        while (true) {
            val list = json("$mainUrl/api/v1/anime/$slug/episodes?limit=100&offset=$offset")
            val items = list.items("data")
            if (items.isEmpty()) break
            episodes += items.mapNotNull { ep -> ep.path("episode_number").asInt().takeIf { it > 0 }?.let { ep to it } }
            offset += items.size
            if (offset >= list.path("total").asInt(offset)) break
        }
        return newAnimeLoadResponse(title, url, if (movie) TvType.AnimeMovie else TvType.Anime) {
            posterUrl = item.path("cover_image").text("extra_large")
            plot = item.text("description")
            year = item.path("season_year").asInt().takeIf { it > 0 }
            tags = item.items("genres").map { it.asText() }
            for ((dub, flag, channel) in listOf(Triple(DubStatus.Subbed, "subbed", "sub"), Triple(DubStatus.Dubbed, "dubbed", "dub"))) {
                addEpisodes(dub, episodes.filter { it.first.path(flag).asBoolean() }.map { (ep, number) ->
                    newEpisode("$mainUrl/watch/$slug?ep=$number&lang=$channel") {
                        episode = number
                        name = ep.text("title")
                        posterUrl = ep.text("thumbnail")
                        description = ep.text("description")
                    }
                })
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val route = Regex(""".*/watch/([a-z0-9-]+)\?ep=(\d+)&lang=(sub|dub)""").matchEntire(data) ?: return false
        if (!data.startsWith("$mainUrl/watch/")) return false
        val (slug, episode, language) = route.destructured
        val page = "$mainUrl/watch/$slug?ep=$episode"
        val html = app.get(page, headers = browser).text
        val ids = Regex("""anilist:(\d+),mal:(\d+)""").find(html) ?: return false
        val sources = json("$mainUrl/api/flix/${ids.groupValues[1]}/$episode?mal=${ids.groupValues[2]}", page).items("servers")
        for (source in sources.filter { it.text("dataType") == language && it.text("serverName")?.let(settings::serverEnabled) == true }) {
            val embed = source.text("dataLink") ?: continue
            if (webUrl(embed)?.host != "flixcloud.cc") continue
            val link = attempt {
                val resolver = WebViewResolver(interceptUrl = Regex("\\.m3u8(?:[?#]|$)"), useOkhttp = false, timeout = 15000L)
                val req = resolver.resolveUsingWebView(embed, referer = "$mainUrl/", headers = emptyMap()).first ?: return@attempt null
                val headers = listOf("Origin", "User-Agent", "Cookie").mapNotNull { key -> req.header(key)?.let { key to it } }.toMap()
                hls("Re:ANIME · ${source.text("serverName")} · $language", req.url.toString(), req.header("Referer") ?: embed, headers)
            } ?: continue
            callback(link)
            return true
        }
        return false
    }
}

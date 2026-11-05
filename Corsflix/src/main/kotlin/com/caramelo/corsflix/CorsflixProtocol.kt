package com.caramelo.corsflix

import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal fun JsonNode.text(key: String): String? = get(key)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }
internal fun JsonNode.items(key: String): List<JsonNode> = get(key)?.takeIf { it.isArray }?.toList().orEmpty()
internal data class CorsflixItem(val type: String, val id: Int, val season: Int? = null, val episode: Int? = null)

internal object CorsflixProtocol {
    const val BASE = "https://watch.corsflix.net"
    const val CATALOG = "https://watch.corsflix.net/api"
    val rows = linkedMapOf(
        "trending" to "Trending This Week", "movies" to "Popular Movies", "tv" to "Popular TV Shows",
        "top-movies" to "Top Rated Movies", "top-tv" to "Top Rated TV Shows", "new-movies" to "Now Playing Movies",
        "airing-tv" to "TV Airing Today", "action" to "Action Movies", "comedy" to "Comedy Movies",
        "drama" to "Drama Movies", "horror" to "Horror Movies", "animation" to "Animation Movies", "documentary" to "Documentaries"
    )
    val servers = linkedMapOf("twoembed" to "2Embed / StreamWish", "rive" to "Rive (Android browser)")
    val defaultServers = setOf("twoembed", "rive")
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun playlists(script: String): List<String> = Regex("""["']hls[0-9]+["']\s*:\s*["'](https?://[^"']+)["']""")
        .findAll(script).map { it.groupValues[1] }.filter { webUrl(it) }.distinct().toList()
    fun webUrl(value: String?): Boolean = runCatching {
        val u = URI(value ?: return false)
        u.scheme in setOf("http", "https") && !u.host.isNullOrBlank() && u.userInfo == null
    }.getOrDefault(false)
    fun url(item: CorsflixItem): String = "$BASE/${if (item.type == "movie") "movies" else "tv"}/${item.id}" +
        if (item.season != null && item.episode != null) "?season=${item.season}&episode=${item.episode}" else ""
    fun item(url: String): CorsflixItem {
        val u = URI(url)
        require(u.scheme == "https" && u.host == "watch.corsflix.net" && u.port == -1 && u.userInfo == null)
        val m = requireNotNull(Regex("^/(movies|tv)/([1-9][0-9]{0,8})$").matchEntire(u.path))
        val query = u.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.map {
            val parts = it.split('=', limit = 2)
            require(parts.size == 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
        }.orEmpty()
        require(query.map { it.first }.distinct().size == query.size && query.all { it.first in setOf("season", "episode") })
        val params = query.toMap()
        val season = params["season"]?.toIntOrNull()
        val episode = params["episode"]?.toIntOrNull()
        require((params.isEmpty()) || (m.groupValues[1] == "tv" && params.size == 2 && season != null && season in 0..10000 && episode != null && episode in 1..100000))
        return CorsflixItem(if (m.groupValues[1] == "movies") "movie" else "tv", m.groupValues[2].toInt(), season, episode)
    }
}

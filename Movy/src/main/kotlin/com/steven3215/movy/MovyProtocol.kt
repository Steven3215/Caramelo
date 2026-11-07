package com.steven3215.movy

import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal fun JsonNode.text(key: String): String? = get(key)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }
internal fun JsonNode.items(key: String): List<JsonNode> = get(key)?.takeIf { it.isArray }?.toList().orEmpty()
internal data class MovyItem(val type: String, val id: Int, val season: Int? = null, val episode: Int? = null)

internal object MovyProtocol {
    const val BASE = "https://www.movy.sx"
    const val CATALOG = "https://db.wecollege.net/3"
    const val STREAMS = "https://api.wecollege.net"
    val rows = linkedMapOf(
        "trending" to "Trending This Week", "movies" to "Popular Movies", "tv" to "Popular TV Shows",
        "top-movies" to "Top Rated Movies", "top-tv" to "Top Rated TV Shows", "new-movies" to "Now Playing Movies",
        "airing-tv" to "TV Airing Today", "action" to "Action Movies", "comedy" to "Comedy Movies",
        "drama" to "Drama Movies", "horror" to "Horror Movies", "animation" to "Animation Movies", "documentary" to "Documentaries"
    )
    val servers = linkedMapOf(
        "miami" to "Miami · Original audio", "boise" to "Boise · Original audio", "seattle" to "Seattle · Original audio",
        "chicago" to "Chicago · Original audio", "nashville" to "Nashville · Original audio", "vegas" to "Vegas · Original audio",
        "reno" to "Reno · Original audio", "oakland" to "Oakland · Original audio", "phoenix" to "Phoenix · Original audio",
        "atlanta" to "Atlanta · Original audio", "portland" to "Portland · Original audio", "austin" to "Austin · English",
        "dallas" to "Dallas · Original audio", "munich" to "Munich · German", "berlin" to "Berlin · German",
        "paris" to "Paris · French", "delhi" to "Delhi · Hindi", "cancun" to "Cancun · Spanish"
    )
    val audioLanguages = mapOf("austin" to "en", "munich" to "de", "berlin" to "de", "paris" to "fr", "delhi" to "hi", "cancun" to "es")
    val defaultServers = setOf("miami", "boise", "seattle", "chicago", "austin", "munich", "paris", "delhi", "cancun")
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun webUrl(value: String?): Boolean = runCatching {
        val u = URI(value ?: return false)
        u.scheme in setOf("http", "https") && !u.host.isNullOrBlank() && u.userInfo == null
    }.getOrDefault(false)
    fun url(item: MovyItem): String = "$BASE/${item.type}/${item.id}" +
        if (item.season != null && item.episode != null) "?season=${item.season}&episode=${item.episode}" else ""
    fun item(url: String): MovyItem {
        val u = URI(url)
        require(u.scheme == "https" && u.host == "www.movy.sx" && u.port == -1 && u.userInfo == null)
        val m = requireNotNull(Regex("^/(movie|tv)/([1-9][0-9]{0,8})$").matchEntire(u.path))
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
        return MovyItem(m.groupValues[1], m.groupValues[2].toInt(), season, episode)
    }
    private fun unsigned(value: Int) = value.toLong() and 0xffffffffL
    private fun mix(value: Int): Int {
        var x = value xor (value ushr 16)
        x *= 0x85ebca6b.toInt()
        x = x xor (x ushr 13)
        x *= 0xc2b2ae35.toInt()
        return x xor (x ushr 16)
    }
    private fun rotate(value: Int, count: Int) = Integer.rotateLeft(value, count and 31)
    // Normal public-client response format, keyed by the API's short-lived public seed.
    // This decodes source metadata, not encrypted video or broadcaster DRM.
    @OptIn(ExperimentalEncodingApi::class)
    fun decode(payload: String, seed: String, mediaId: Int): String {
        require(payload.length <= 8 * 1024 * 1024 && seed.isNotEmpty() && seed.length <= 4096 && mediaId > 0)
        val normalized = payload.replace('-', '+').replace('_', '/')
        val bytes = Base64.Default.decode(normalized.padEnd((normalized.length + 3) / 4 * 4, '='))
        var hash = 0x811c9dc5.toInt()
        for (c in seed) hash = (hash xor c.code) * 0x01000193
        var n = mix(mix(hash) xor mix(mediaId xor 0x9e3779b9.toInt()))
        val state = IntArray(61)
        val present = BooleanArray(61)
        repeat(8) { i ->
            val index = (unsigned(n) % 61).toInt()
            n = rotate(n + 0x9e3779b9.toInt(), 7 + (i and 7))
            state[index] = n xor mix(n)
            present[index] = true
            n = mix(n + index)
        }
        var acc = mix(0xa5a5a5a5.toInt() xor n)
        var at = 0
        var counter = 0
        while (at < bytes.size) {
            val index = (unsigned(acc) % 61).toInt()
            val s = state[index] xor (0x9e3779b9.toInt() * (counter++ + 1))
            val b = (acc xor s) or (acc and s and if (present[index]) -1 else 0)
            acc = mix((rotate(b + acc, index) xor rotate(acc, index * 7)) + 0x9e3779b9.toInt())
            state[index] = acc
            present[index] = true
            repeat(4) { offset -> if (at < bytes.size) {
                bytes[at] = (bytes[at].toInt() xor (acc ushr (offset * 8))).toByte(); at++
            } }
        }
        require(bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals("mvm1".toByteArray())) { "Movy response format or seed changed" }
        return bytes.copyOfRange(4, bytes.size).toString(Charsets.UTF_8)
    }
}

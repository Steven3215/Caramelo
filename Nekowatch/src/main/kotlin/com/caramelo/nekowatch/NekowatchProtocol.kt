package com.caramelo.nekowatch

import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.net.URLEncoder

internal fun JsonNode.text(key: String): String? = get(key)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }
internal fun JsonNode.items(key: String): List<JsonNode> = get(key)?.takeIf { it.isArray }?.toList().orEmpty()

internal object NekowatchProtocol {
    const val BASE = "https://nekowatch.xyz"
    const val DETAILS_QUERY = "33a449e9458c7689b90abecdc3fa9560"
    val providers = linkedMapOf("reanime" to "konbanwa", "animegg" to "arigato", "anikoto" to "konichiwa")
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun absolute(value: String?): String? = value?.let { runCatching { URI(BASE).resolve(it).toString() }.getOrNull() }
        ?.takeIf { runCatching { val u = URI(it); u.scheme in setOf("http", "https") && u.host != null && u.userInfo == null }.getOrDefault(false) }
    fun id(url: String): Int {
        val u = URI(url)
        require(u.scheme == "https" && u.host == "nekowatch.xyz" && u.port == -1 && u.userInfo == null)
        return requireNotNull(Regex("^/anime/([1-9][0-9]{0,8})$").matchEntire(u.path)).groupValues[1].toInt()
    }
    fun episodeId(value: String, anime: Int, audio: String): String? {
        require(audio in setOf("sub", "dub"))
        val m = Regex("^watch/([a-z]+)/([1-9][0-9]*)/(sub|dub)/([A-Za-z0-9_-]+)$").matchEntire(value.removePrefix("/")) ?: return null
        if (m.groupValues[2].toIntOrNull() != anime || m.groupValues[3] != audio) return null
        val alias = providers[m.groupValues[1]] ?: return null
        val suffix = m.groupValues[4].removePrefix(m.groupValues[1] + "-")
        return "watch/$alias/$anime/$audio/$alias-$suffix"
    }
    fun identity(body: JsonNode, anime: Int, episode: Int, audio: String): Boolean {
        val id = body.get("animeId")?.asInt() ?: body.get("anilistId")?.asInt()
        val number = body.get("episode")?.asInt()
        val lang = body.text("audio")
        return (id == null || id == anime) && (number == null || number == episode) && (lang == null || lang == audio)
    }
}

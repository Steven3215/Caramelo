package com.steven3215.globalfreetv

import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

internal fun JsonNode.text(key: String): String? = get(key)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
internal fun JsonNode.items(key: String): List<JsonNode> = get(key)?.takeIf { it.isArray }?.toList().orEmpty()
internal data class DirectoryEntry(val id: String, val label: String)

internal object GlobalFreeTvProtocol {
    const val BASE = "https://www.globalfreetv.com"
    const val API = "https://api.globalfreetv.com"
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun validId(value: String?): String? = value?.takeIf { it !in setOf(".", "..") && Regex("[A-Za-z0-9_.-]{1,240}").matches(it) }
    fun channelId(url: String): String {
        val u = URI(url)
        require(u.scheme == "https" && u.host == "www.globalfreetv.com" && u.port == -1 && u.userInfo == null)
        require(u.rawPath.startsWith("/channels/"))
        return requireNotNull(validId(URLDecoder.decode(u.rawPath.removePrefix("/channels/"), "UTF-8")))
    }
    fun webUrl(value: String?): Boolean = runCatching {
        val u = URI(value ?: return false)
        u.scheme in setOf("http", "https") && !u.host.isNullOrBlank() && u.userInfo == null
    }.getOrDefault(false)
    fun country(value: String?): String = when (val code = value?.trim()?.uppercase(Locale.ROOT).orEmpty()) {
        "UK" -> "GB"
        "" -> "UNKNOWN"
        else -> code
    }
    fun countryAliases(code: String): List<String> = when (country(code)) {
        "GB" -> listOf("GB", "UK")
        "PL" -> listOf("PL", "pl")
        else -> listOf(country(code))
    }
    fun header(value: String?): String? = value?.takeIf { it.length <= 2048 && !it.contains('\r') && !it.contains('\n') }
    fun youtube(value: String): Boolean = runCatching {
        URI(value).host?.lowercase(Locale.ROOT) in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "www.youtube-nocookie.com")
    }.getOrDefault(false)
    fun playlist(value: String) = value.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("#EXTM3U")
    fun rows(categories: List<DirectoryEntry>, countries: List<DirectoryEntry>, grouping: Int,
        enabled: (String) -> Boolean, allowed: (String) -> Boolean): List<Pair<String, String>> = buildList {
        if (enabled("all")) add("all" to "All Live Channels")
        if (enabled("new")) add("new" to "Recently Added")
        if (grouping != 1) categories.filter { enabled("category:${it.id}") }.forEach { add("category:${it.id}" to it.label) }
        if (grouping != 0) countries.filter { enabled("country:${it.id}") && allowed(it.id) }.forEach { add("country:${it.id}" to it.label) }
    }
    fun visible(channel: JsonNode): Boolean = channel.get("is_hidden")?.asInt() != 1 && validId(channel.text("id")) != null && channel.text("name") != null
    fun sorted(rows: List<JsonNode>, sort: Int): List<JsonNode> {
        val byName = compareBy<JsonNode> { it.text("name").orEmpty().lowercase(Locale.ROOT) }.thenBy { it.text("id") }
        return rows.distinctBy { it.text("id") }.sortedWith(when (sort) {
            1 -> byName
            2 -> compareByDescending<JsonNode> { it.text("created_at").orEmpty() }.then(byName)
            else -> compareByDescending<JsonNode> { it.get("likes")?.asLong() ?: 0 }.then(byName)
        })
    }
    fun page(rows: List<JsonNode>, page: Int, size: Int = 100): List<JsonNode> {
        require(page >= 1 && size > 0)
        return rows.drop(((page.toLong() - 1) * size).coerceAtMost(rows.size.toLong()).toInt()).take(size)
    }
}

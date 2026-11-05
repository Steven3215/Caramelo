package com.steven3215.sportsbite

import com.fasterxml.jackson.databind.JsonNode
import java.util.Locale

internal fun JsonNode.text(key: String): String? = get(key)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
internal fun JsonNode.items(key: String): List<JsonNode> = get(key)?.takeIf { it.isArray }?.toList().orEmpty()

internal object SportsbiteCatalog {
    fun tvRows(categories: List<String>, countries: List<String>, grouping: Int,
        enabled: (String) -> Boolean, allowedCountry: (String) -> Boolean): List<Pair<String, String>> = buildList {
        if (enabled("tv:all")) add("tv:all" to "All Live Channels")
        if (grouping != 1) categories.filter { enabled("category:$it") }.forEach { add("category:$it" to it) }
        if (grouping != 0) countries.filter { enabled("country:$it") && allowedCountry(it) }.forEach { add("country:$it" to it) }
    }
    fun slug(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    val sports = linkedMapOf(
        "live" to "Live Now", "all" to "Sports Schedule", "football" to "Football",
        "american-football" to "NFL / American Football", "basketball" to "Basketball",
        "hockey" to "Ice Hockey", "baseball" to "Baseball", "fighting" to "UFC / Boxing / Wrestling",
        "motorsport" to "Motorsport", "rugby" to "Rugby", "cricket" to "Cricket",
        "tennis" to "Tennis", "golf" to "Golf", "darts" to "Darts", "other" to "Other Sports"
    )
    fun sport(value: String?): String = when (val key = slug(value.orEmpty())) {
        "soccer" -> "football"
        "nfl", "ncaaf", "american-football" -> "american-football"
        "nba", "ncaab" -> "basketball"
        "ice-hockey", "nhl" -> "hockey"
        "mlb" -> "baseball"
        "ufc", "boxing", "mma", "wrestling", "wwe", "fighting" -> "fighting"
        "motor-sports", "motorsports", "racing", "f1", "formula-1", "motogp", "nascar" -> "motorsport"
        "rugby-league", "rugby-union" -> "rugby"
        else -> key.takeIf { it in sports && it !in setOf("live", "all", "other") } ?: "other"
    }
    fun country(value: String?): String = when (val raw = value?.trim().orEmpty()) {
        "" -> "Unknown"
        else -> when (raw.uppercase(Locale.ROOT)) {
            "USA", "US", "UNITED STATES", "UNITED STATES OF AMERICA" -> "United States"
            "UK", "GB", "GBR", "UNITED KINGDOM", "GREAT BRITAIN" -> "United Kingdom"
            "UAE", "AE", "UNITED ARAB EMIRATES" -> "United Arab Emirates"
            "INTERNATIONAL", "WORLD", "GLOBAL" -> "International"
            else -> raw
        }
    }
    fun live(event: JsonNode): Boolean = event.get("live_status")?.text("state")?.let { it == "in" }
        ?: (event.get("is_live")?.asBoolean() ?: event.get("live")?.asBoolean() ?: false)
    fun finished(event: JsonNode): Boolean = event.get("is_finished")?.asBoolean() == true ||
        event.get("live_status")?.text("state") in setOf("ft", "post", "final", "ended")
    fun usable(stream: JsonNode): Boolean = stream.text("status")?.lowercase(Locale.ROOT) != "offline" &&
        stream.get("browser_playable")?.asBoolean() != false &&
        listOf("manifest_url", "iframe_url", "embed_url").any { stream.text(it) != null }
    fun id(value: String?): String? = value?.takeIf { Regex("[A-Za-z0-9_-]{1,240}").matches(it) }
}

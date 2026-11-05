package com.steven3215.sportsbite

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.*
import org.junit.Test

class SportsbiteCatalogTest {
    private val json = ObjectMapper()
    @Test fun normalizesRealScheduleCategoriesWithoutLosingUncommonSports() {
        assertEquals("hockey", SportsbiteCatalog.sport("Ice Hockey"))
        assertEquals("basketball", SportsbiteCatalog.sport("Basketball"))
        assertEquals("american-football", SportsbiteCatalog.sport("NFL"))
        assertEquals("fighting", SportsbiteCatalog.sport("WWE"))
        assertEquals("motorsport", SportsbiteCatalog.sport("F1"))
        assertEquals("other", SportsbiteCatalog.sport("Table Tennis"))
    }
    @Test fun mergesCountryAliasesAndKeepsMissingCountriesBrowsable() {
        for (alias in listOf("US", "USA", "United States")) assertEquals("United States", SportsbiteCatalog.country(alias))
        for (alias in listOf("UK", "GB", "United Kingdom")) assertEquals("United Kingdom", SportsbiteCatalog.country(alias))
        assertEquals("United Arab Emirates", SportsbiteCatalog.country("UAE"))
        assertEquals("Unknown", SportsbiteCatalog.country(null))
        assertEquals("MENA", SportsbiteCatalog.country("MENA"))
    }
    @Test fun homepageTogglesGroupingAndCountryFilterCompose() {
        val categories = listOf("Sports", "News", "Kids")
        val countries = listOf("United States", "United Kingdom", "Unknown")
        val hidden = setOf("tv:all", "category:Kids", "country:Unknown")
        fun rows(group: Int) = SportsbiteCatalog.tvRows(categories, countries, group, { it !in hidden }, { it != "United Kingdom" }).map { it.first }
        assertEquals(listOf("category:Sports", "category:News"), rows(0))
        assertEquals(listOf("country:United States"), rows(1))
        assertEquals(listOf("category:Sports", "category:News", "country:United States"), rows(2))
        assertTrue(SportsbiteCatalog.tvRows(categories, countries, 2, { false }, { true }).isEmpty())
        assertEquals(listOf("tv:all", "country:Unknown"), SportsbiteCatalog.tvRows(emptyList(), listOf("Unknown"), 1, { true }, { true }).map { it.first })
    }
    @Test fun excludesFinishedAndExplicitlyUnavailableServers() {
        assertTrue(SportsbiteCatalog.finished(json.readTree("""{"live_status":{"state":"ft"}}""")))
        assertFalse(SportsbiteCatalog.live(json.readTree("""{"live":true,"live_status":{"state":"ft"}}""")))
        assertTrue(SportsbiteCatalog.live(json.readTree("""{"live_status":{"state":"in"}}""")))
        assertFalse(SportsbiteCatalog.usable(json.readTree("""{"manifest_url":"https://sportsbite.org/test","browser_playable":false}""")))
        assertFalse(SportsbiteCatalog.usable(json.readTree("""{"manifest_url":"https://sportsbite.org/test","status":"offline"}""")))
        assertTrue(SportsbiteCatalog.usable(json.readTree("""{"iframe_url":"https://sportsbite.org/test","status":"online"}""")))
    }
    @Test fun validatesPlaylistFallbacksRelativeSourcesAndItemBoundaries() {
        val base = "https://sportsbite.org"
        assertEquals("$base/t247/espn", SportsbiteProtocol.playlistRoute("$base/t247-embed/embed/espn?emb=101", base))
        assertEquals("$base/cv/ae-usa", SportsbiteProtocol.playlistRoute("$base/cv-embed/embed/ae-usa", base))
        assertEquals("$base/mx/123?v=high", SportsbiteProtocol.playlistRoute("$base/mx-embed/embed/123?v=high&emb=101", base))
        assertEquals("$base/selfhost/play/s-abc?raw=1", SportsbiteProtocol.playlistRoute("$base/selfhost/embed/s-abc", base))
        assertNull(SportsbiteProtocol.playlistRoute("https://evil.example/t247-embed/embed/espn", base))
        assertNull(SportsbiteProtocol.playlistRoute("$base/unknown-embed/embed/espn", base))
        assertEquals("$base/selfhost/play/s-abc", SportsbiteProtocol.source("""var source = window.atob("L3NlbGZob3N0L3BsYXkvcy1hYmM=");""", base))
        assertEquals("$base/selfhost/play/s-abc?raw=1", SportsbiteProtocol.playbackUrl("$base/selfhost/play/s-abc?cdn=1", base))
        assertEquals("cv-ae-usa", SportsbiteProtocol.itemKey("$base/channel/cv-ae-usa", base, "channel"))
        assertThrows(IllegalArgumentException::class.java) { SportsbiteProtocol.itemKey("$base/channel/../event/123", base, "channel") }
        assertTrue(SportsbiteProtocol.playlist("\uFEFF#EXTM3U\n#EXTINF:4,"))
        assertFalse(SportsbiteProtocol.playlist("<!DOCTYPE html><html>Website</html>"))
    }
}

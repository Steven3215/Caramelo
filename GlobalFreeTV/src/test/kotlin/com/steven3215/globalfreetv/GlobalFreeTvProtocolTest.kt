package com.steven3215.globalfreetv

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.*
import org.junit.Test

class GlobalFreeTvProtocolTest {
    private val json = ObjectMapper()
    @Test fun channelPathsAreBoundToThisProvider() {
        assertEquals("CNN.us", GlobalFreeTvProtocol.channelId("https://www.globalfreetv.com/channels/CNN.us"))
        assertEquals("foo-bar.gb", GlobalFreeTvProtocol.channelId("https://www.globalfreetv.com/channels/foo-bar.gb"))
        for (url in listOf("https://evil.example/channels/CNN.us", "https://www.globalfreetv.com/channels/..", "https://www.globalfreetv.com/channels/foo%2Fbar", "https://www.globalfreetv.com/channels/foo/extra")) {
            assertThrows(IllegalArgumentException::class.java) { GlobalFreeTvProtocol.channelId(url) }
        }
        assertNull(GlobalFreeTvProtocol.header("Agent\r\nX-Test: value"))
        assertFalse(GlobalFreeTvProtocol.webUrl("javascript:alert(1)"))
    }
    @Test fun rowsRespectIndividualTogglesAndCountrySelection() {
        val categories = listOf(DirectoryEntry("news", "News"), DirectoryEntry("movies", "Movies"))
        val countries = listOf(DirectoryEntry("US", "United States"), DirectoryEntry("GB", "United Kingdom"))
        val hidden = setOf("new", "category:movies", "country:GB")
        fun rows(group: Int) = GlobalFreeTvProtocol.rows(categories, countries, group, { it !in hidden }, { it == "US" }).map { it.first }
        assertEquals(listOf("all", "category:news"), rows(0))
        assertEquals(listOf("all", "country:US"), rows(1))
        assertEquals(listOf("all", "category:news", "country:US"), rows(2))
        assertTrue(GlobalFreeTvProtocol.rows(categories, countries, 2, { false }, { true }).isEmpty())
    }
    @Test fun countryAliasesPreserveBothWebsiteDatasets() {
        assertEquals("GB", GlobalFreeTvProtocol.country("UK"))
        assertEquals(listOf("GB", "UK"), GlobalFreeTvProtocol.countryAliases("GB"))
        assertEquals(listOf("PL", "pl"), GlobalFreeTvProtocol.countryAliases("pl"))
        assertEquals("UNKNOWN", GlobalFreeTvProtocol.country(null))
    }
    @Test fun paginationAfterFilteringDoesNotLoseSparseResults() {
        val rows = (1..230).map { i -> json.readTree("""{"id":"channel-$i.us","name":"Channel $i","country":"${if (i % 2 == 0) "US" else "GB"}","likes":$i}""") }
        val filtered = GlobalFreeTvProtocol.sorted(rows.filter { it.text("country") == "US" }, 0)
        assertEquals(100, GlobalFreeTvProtocol.page(filtered, 1).size)
        assertEquals(15, GlobalFreeTvProtocol.page(filtered, 2).size)
        assertEquals("channel-230.us", GlobalFreeTvProtocol.page(filtered, 1).first().text("id"))
        assertEquals(115, (GlobalFreeTvProtocol.page(filtered, 1) + GlobalFreeTvProtocol.page(filtered, 2)).distinctBy { it.text("id") }.size)
        assertTrue(GlobalFreeTvProtocol.page(filtered, Int.MAX_VALUE).isEmpty())
        assertEquals("Channel 1", GlobalFreeTvProtocol.sorted(rows, 1).first().text("name"))
    }
    @Test fun distinguishesHlsFromPlayerPagesAndYoutubeLinks() {
        assertTrue(GlobalFreeTvProtocol.playlist("\uFEFF#EXTM3U\n#EXTINF:4,"))
        assertFalse(GlobalFreeTvProtocol.playlist("<html>offline</html>"))
        assertTrue(GlobalFreeTvProtocol.youtube("https://youtu.be/abc"))
        assertFalse(GlobalFreeTvProtocol.youtube("https://youtube.com.evil.example/abc"))
        assertFalse(GlobalFreeTvProtocol.visible(json.readTree("""{"id":"CNN.us","name":"CNN","is_hidden":1}""")))
    }
}

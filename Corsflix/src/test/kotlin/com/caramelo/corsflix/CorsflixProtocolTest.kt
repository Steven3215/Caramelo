package com.caramelo.corsflix

import org.junit.Assert.*
import org.junit.Test

class CorsflixProtocolTest {
    @Test fun canonicalMovieAndEpisodeDataStayOnThisProvider() {
        for (item in listOf(CorsflixItem("movie", 10331), CorsflixItem("tv", 1396, 1, 2)))
            assertEquals(item, CorsflixProtocol.item(CorsflixProtocol.url(item)))
        assertThrows(IllegalArgumentException::class.java) { CorsflixProtocol.item("https://evil.test/movies/10331") }
        assertThrows(IllegalArgumentException::class.java) { CorsflixProtocol.item("https://watch.corsflix.net/tv/1396?episode=1") }
    }
    @Test fun keepsBothHlsAlternatesIncludingTextManifestAndRejectsUnsafeUrls() {
        val sample = """var links={"hls3":"https://example.test/master.txt","hls2":"https://example.test/master.m3u8?token=synthetic","hls4":"javascript:bad()"};"""
        assertEquals(listOf("https://example.test/master.txt", "https://example.test/master.m3u8?token=synthetic"), CorsflixProtocol.playlists(sample))
        assertFalse(CorsflixProtocol.webUrl("https://user:pass@example.test/master.m3u8"))
    }
}

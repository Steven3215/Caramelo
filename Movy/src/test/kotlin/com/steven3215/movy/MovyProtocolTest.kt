package com.steven3215.movy

import org.junit.Assert.*
import org.junit.Test

class MovyProtocolTest {
    private val fixture = "SjuZsEEJiuDVGcCBAPTROT1vXSBNeNJ4CARcTJ1ppUTTzCyUKJNaL9fdzP5Q8HpOhesuq0J2pZLdzGuj68mGi7-2h2CtT9kPqBmE6c7L7-GqYagDoyxygvoZmmU"
    @Test fun decodesSyntheticPublicClientFixtureIncludingUnsignedArithmetic() {
        assertEquals("""{"sources":[{"url":"https://example.test/master.m3u8","quality":"720p"}],"subtitles":[]}""",
            MovyProtocol.decode(fixture, "synthetic-movy-seed", 10331))
        assertThrows(IllegalArgumentException::class.java) { MovyProtocol.decode(fixture, "wrong-seed", 10331) }
        assertThrows(IllegalArgumentException::class.java) { MovyProtocol.decode(fixture, "synthetic-movy-seed", 1396) }
        assertThrows(IllegalArgumentException::class.java) { MovyProtocol.decode("%%%", "seed", 10331) }
    }
    @Test fun roundTripsMovieAndEpisodeDataWithoutCrossProviderUrls() {
        val movie = MovyItem("movie", 10331)
        val episode = MovyItem("tv", 1396, 1, 2)
        assertEquals(movie, MovyProtocol.item(MovyProtocol.url(movie)))
        assertEquals(episode, MovyProtocol.item(MovyProtocol.url(episode)))
        for (url in listOf("https://evil.example/movie/10331", "https://www.movy.sx/movie/0", "https://www.movy.sx/tv/1396?season=1", "https://www.movy.sx/tv/1396?season=1&season=2&episode=1", "https://www.movy.sx/movie/10331?season=1&episode=1", "https://www.movy.sx/tv/1396?season=-1&episode=1")) {
            assertThrows(IllegalArgumentException::class.java) { MovyProtocol.item(url) }
        }
    }
    @Test fun encodesTitlesAsTheWebsiteDoesAndOnlyAcceptsWebStreams() {
        assertEquals("Night%2520of%2520the%2520Living%2520Dead", MovyProtocol.encode(MovyProtocol.encode("Night of the Living Dead")))
        assertTrue(MovyProtocol.webUrl("https://example.test/master.m3u8"))
        assertFalse(MovyProtocol.webUrl("javascript:alert(1)"))
        assertFalse(MovyProtocol.webUrl("https://user:pass@example.test/file"))
        assertTrue(MovyProtocol.defaultServers.all { it in MovyProtocol.servers })
    }
}

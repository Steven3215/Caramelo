package com.steven3215.cinemaarmy

import org.junit.Assert.*
import org.junit.Test

class CinemaProtocolTest {
    @Test fun tokenMatchesBrowserHmacAndExpiresAtMinuteBoundary() {
        assertEquals("efc9a2a0e08261ac68747222843a73780d157ced8dbd4af6aae33cde308ec812",
            CinemaProtocol.token(10331, 28000000L * 60000))
        assertEquals(CinemaProtocol.token(10331, 60000), CinemaProtocol.token(10331, 119999))
        assertNotEquals(CinemaProtocol.token(10331, 119999), CinemaProtocol.token(10331, 120000))
    }

    @Test fun decodesObfuscatedAndPlainUrls() {
        val expected = "https://example.org/master.m3u8"
        assertEquals(expected, CinemaProtocol.streamUrl("DkVCEUVYFh0DGwVbFQkBHQpLUxhbU0pDA0oXXlVHDQ=="))
        assertEquals(expected, CinemaProtocol.streamUrl(expected))
    }

    @Test fun preservesEpisodeSelection() {
        val base = "https://cinema.army"
        val episode = CinemaItem(1399, "tv", 2, 3)
        assertEquals(episode, CinemaProtocol.item(episode.watchUrl(base), base))
        assertEquals("tv/1399/2/3", episode.streamPath())
        assertEquals("movie/10331", CinemaItem(10331, "movie").streamPath())
    }

    @Test fun rejectsInvalidOrForeignData() {
        listOf(
            "https://evil.example/watch?id=1&type=movie",
            "https://cinema.army/watch?id=-1&type=movie",
            "https://cinema.army/watch?id=1&type=person",
            "https://cinema.army/watch?id=1&type=tv&season=1",
            "https://cinema.army/watch?id=1&type=tv&season=0&episode=1",
            "https://cinema.army/watch?id=1&type=movie&season=bad"
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { CinemaProtocol.item(url, "https://cinema.army") }
        }
        assertThrows(IllegalArgumentException::class.java) { CinemaProtocol.streamUrl("javascript:alert(1)") }
    }
}

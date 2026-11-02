package com.steven3215.uniquestreamanime

import org.junit.Assert.*
import org.junit.Test

class UniqueStreamProtocolTest {
    @Test fun acceptsSeriesMoviesAndLocaleSelections() {
        val base = "https://anime.uniquestream.net"
        assertEquals(UniqueItem("series", "NzCTSBgz"), UniqueStreamProtocol.item("$base/series/NzCTSBgz/Kaiju-No-8", base))
        assertEquals(UniqueItem("movies", "adn_29579"), UniqueStreamProtocol.item("$base/movies/adn_29579/title", base))
        assertEquals("en-US", UniqueStreamProtocol.item("$base/watch/TVaAL7TI/details?audio=en-US", base).audio)
    }
    @Test fun rejectsForeignOriginsAndPathInjection() {
        val base = "https://anime.uniquestream.net"
        listOf("https://example.org/series/abc/title", "$base/series/../movie",
            "$base/watch/abc/title?audio=..%2Fadmin", "$base/admin/abc/title").forEach {
            assertThrows(IllegalArgumentException::class.java) { UniqueStreamProtocol.item(it, base) }
        }
    }
    @Test fun readsPerAudioMediaIdFromSignedPlaylistPath() {
        val expected = "0123456789abcdef0123456789abcdef"
        assertEquals(expected, UniqueStreamProtocol.mediaId(
            "https://example.org/episode/id/${expected}_en-US/master.m3u8?sign=example", "f".repeat(32)))
        assertEquals(null, UniqueStreamProtocol.mediaId("https://example.org/master.m3u8", "invalid"))
    }
    @Test fun matchesWebsiteHlsKeyTransportWithSyntheticFixture() {
        val expected = byteArrayOf(0x00,0x11,0x22,0x33,0x44,0x55,0x66,0x77,0x88.toByte(),0x99.toByte(),0xaa.toByte(),0xbb.toByte(),0xcc.toByte(),0xdd.toByte(),0xee.toByte(),0xff.toByte())
        assertArrayEquals(expected, UniqueStreamProtocol.unwrapHlsKey(
            "Ppj54yZyJAzGLGJZQKc91ZKokTqXGDYNtjnv3HxtR0I=".toByteArray(), "0123456789abcdef0123456789abcdef"))
        assertArrayEquals(expected, UniqueStreamProtocol.unwrapHlsKey(expected, "0123456789abcdef0123456789abcdef"))
        assertThrows(IllegalArgumentException::class.java) {
            UniqueStreamProtocol.unwrapHlsKey("malformed".toByteArray(), "bad")
        }
    }
}

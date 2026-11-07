package com.caramelo.teleonline

import org.junit.Assert.*
import org.junit.Test

class TeleonlineProtocolTest {
    @Test fun extractsOnlyMediaUrlsFromPublicPlayerConfiguration() {
        val html = "var decodedUrl = window.atob('aHR0cHM6Ly9leGFtcGxlLnRlc3QvbGl2ZS5tM3U4'); atob('L2pzL3RvYy5qcw==');"
        assertEquals(listOf("https://example.test/live.m3u8"), TeleonlineProtocol.hls(html))
        assertEquals("https://teleonline.org/canal/la-1/", TeleonlineProtocol.channel("/canal/la-1/"))
        assertTrue(runCatching { TeleonlineProtocol.channel("https://example.test/canal/la-1/") }.isFailure)
        assertTrue(runCatching { TeleonlineProtocol.channel("/canales/espana/") }.isFailure)
    }
}

package com.caramelo.nekowatch

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.*
import org.junit.Test

class NekowatchProtocolTest {
    @Test fun aliasesMatchTheSelectedAnimeAndAudio() {
        assertEquals("watch/konbanwa/11757/sub/konbanwa-1", NekowatchProtocol.episodeId("watch/reanime/11757/sub/reanime-1", 11757, "sub"))
        assertNull(NekowatchProtocol.episodeId("watch/reanime/21/sub/reanime-1", 11757, "sub"))
        assertNull(NekowatchProtocol.episodeId("watch/reanime/11757/dub/reanime-1", 11757, "sub"))
        assertNull(NekowatchProtocol.episodeId("https://evil.test/watch/reanime/11757/sub/reanime-1", 11757, "sub"))
    }
    @Test fun validatesResponseIdentityAndResolvesSignedProxyPaths() {
        val json = ObjectMapper()
        assertTrue(NekowatchProtocol.identity(json.readTree("""{"animeId":11757,"episode":1,"audio":"sub"}"""), 11757, 1, "sub"))
        assertFalse(NekowatchProtocol.identity(json.readTree("""{"animeId":21,"episode":1,"audio":"sub"}"""), 11757, 1, "sub"))
        assertFalse(NekowatchProtocol.identity(json.readTree("""{"animeId":11757,"episode":1,"audio":"dub"}"""), 11757, 1, "sub"))
        assertEquals("https://nekowatch.xyz/proxy/hls?url=synthetic", NekowatchProtocol.absolute("/proxy/hls?url=synthetic"))
        assertNull(NekowatchProtocol.absolute("javascript:bad()"))
        assertThrows(IllegalArgumentException::class.java) { NekowatchProtocol.id("https://evil.test/anime/11757") }
    }
}

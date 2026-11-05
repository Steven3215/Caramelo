package com.caramelo.animesuge

import org.junit.Assert.*
import org.junit.Test

class AnimeSugeProtocolTest {
    @Test fun decodesSyntheticPublicMetadataFixture() {
        assertEquals("""{"file":"https://example.test/master.m3u8"}""", AnimeSugeProtocol.decode("wdeBruh3qqn_i5wUNnyaPQJGEz-qEq1n_4KPzaGYJDEPkOQ51oB-hPuHptS2KV3t"))
        assertThrows(IllegalArgumentException::class.java) { AnimeSugeProtocol.decode("%%%") }
    }
    @Test fun validatesAnimeAndPlayerOrigins() {
        assertEquals("https://animesuge.cz/anime/black-clover-g7tjy", AnimeSugeProtocol.animeUrl("/anime/black-clover-g7tjy/ep-1"))
        assertThrows(IllegalArgumentException::class.java) { AnimeSugeProtocol.animeUrl("https://evil.test/anime/black-clover-g7tjy") }
        assertTrue(AnimeSugeProtocol.megaPlay("https://megaplay.buzz/stream/s-2/27377/sub?s=tcdn"))
        assertFalse(AnimeSugeProtocol.megaPlay("https://megaplay.buzz.evil.test/stream/test"))
        assertFalse(AnimeSugeProtocol.megaPlay("https://user:pass@megaplay.buzz/stream/test"))
    }
}

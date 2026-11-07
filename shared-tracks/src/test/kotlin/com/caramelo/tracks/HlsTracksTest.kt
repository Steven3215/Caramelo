package com.caramelo.tracks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HlsTracksTest {
    @Test fun resolutionsAreNotAudioAndSignedRenditionsKeepTheirQuery() {
        val manifest = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English, stereo",LANGUAGE="eng",URI="audio/en.m3u8?sig=a%2Fb"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Español",LANGUAGE="es-419",URI="audio/es.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Português",LANGUAGE="pt-BR",URI="../subs/pt.vtt?sig=c%2Fd"
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="video",NAME="1080p",URI="1080p.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,AUDIO="audio",SUBTITLES="subs"
            1080p.m3u8
        """.trimIndent()
        val tracks = HlsTracks.parse(manifest, "https://example.test/hls/master.m3u8?master=token")
        assertEquals(listOf("AUDIO", "AUDIO", "SUBTITLES"), tracks.map { it.type })
        assertEquals("English, stereo", tracks[0].name)
        assertEquals("https://example.test/hls/audio/en.m3u8?sig=a%2Fb", tracks[0].url)
        assertEquals("https://example.test/subs/pt.vtt?sig=c%2Fd", tracks[2].url)
        assertFalse(HlsTracks.isSubtitleFile(tracks[1].url!!))
    }

    @Test fun regionalLocalesRemainDistinct() {
        assertEquals("en", HlsTracks.languageTag("eng"))
        assertEquals("es-419", HlsTracks.languageTag("es_LA"))
        assertEquals("es-ES", HlsTracks.languageTag("es-ES"))
        assertEquals("pt-BR", HlsTracks.languageTag("pt_br"))
        assertEquals("zh-Hant", HlsTracks.languageTag("zh-Hant"))
        assertNull(HlsTracks.languageTag("und"))
        assertNull(HlsTracks.languageTag("720p"))
    }
}

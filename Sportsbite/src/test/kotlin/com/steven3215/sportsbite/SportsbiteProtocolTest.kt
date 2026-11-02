package com.steven3215.sportsbite

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

class SportsbiteProtocolTest {
    private val signature = byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,13,10,26,10)
    private fun ts(): ByteArray = ByteArray(188 * 4) { if (it % 188 == 0) 0x47.toByte() else (it % 67).toByte() }
    private fun chunk(type: String, data: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).apply { writeInt(data.size); writeBytes(type); write(data); writeInt(0) }
    }.toByteArray()
    private fun ihdr(width: Int): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).apply { writeInt(width); writeInt(1); write(byteArrayOf(8,2,0,0,0)) }
    }.toByteArray()
    @Test fun extractsBase64PlayerSourceAndRejectsInvalidUrls() {
        assertEquals("https://sportsbite.org/mx/123?v=high", SportsbiteProtocol.source(
            """var source = window.atob("aHR0cHM6Ly9zcG9ydHNiaXRlLm9yZy9teC8xMjM/dj1oaWdo");"""))
        assertNull(SportsbiteProtocol.source("""var source = "javascript:alert(1)";"""))
        assertEquals("fg-123", SportsbiteProtocol.eventKey("https://sportsbite.org/event/fg-123", "https://sportsbite.org"))
        assertThrows(IllegalArgumentException::class.java) {
            SportsbiteProtocol.eventKey("https://evil.example/event/fg-123", "https://sportsbite.org")
        }
    }
    @Test fun supportsRawTsPngChunksAndPayloadAfterRealIend() {
        val expected = ts()
        assertArrayEquals(expected, SportsbiteProtocol.unwrap(expected))
        assertArrayEquals(expected, SportsbiteProtocol.unwrap(signature + chunk("IHDR", ihdr(1)) + chunk("tsDa", expected) + chunk("IEND", byteArrayOf())))
        val decoy = chunk("IDAT", "fake IEND text must not end a PNG chunk".toByteArray())
        assertArrayEquals(expected, SportsbiteProtocol.unwrap(signature + chunk("IHDR", ihdr(1)) + decoy + chunk("IEND", byteArrayOf()) + expected))
        assertArrayEquals(expected, SportsbiteProtocol.unwrap("RIFFWEBP-prefix".toByteArray() + expected))
    }
    @Test fun supportsPixelPngTransportAndSubFilter() {
        val expected = ts()
        val gzip = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(expected) } }.toByteArray()
        val payload = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply { writeBytes("TIKTIKPX"); writeInt(gzip.size); write(gzip) }
        }.toByteArray()
        val width = (payload.size + 2) / 3
        val rgb = payload.copyOf(width * 3)
        for (filter in 0..1) {
            val row = ByteArray(rgb.size + 1)
            row[0] = filter.toByte()
            for (i in rgb.indices) row[i + 1] = ((rgb[i].toInt() and 255) -
                if (filter == 1 && i >= 3) (rgb[i - 3].toInt() and 255) else 0).toByte()
            val compressed = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(row) } }.toByteArray()
            val png = signature + chunk("IHDR", ihdr(width)) + chunk("IDAT", compressed) + chunk("IEND", byteArrayOf())
            assertArrayEquals(expected, SportsbiteProtocol.unwrap(png))
        }
    }
    @Test fun rejectsMalformedOrOversizedContainers() {
        assertNull(SportsbiteProtocol.unwrap(signature + byteArrayOf(0x7f,0x7f,0x7f,0x7f,73,68,65,84)))
        assertNull(SportsbiteProtocol.unwrap("not transport stream media".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) {
            SportsbiteProtocol.bounded(ByteArrayInputStream(byteArrayOf(1,2,3,4)), 3)
        }
    }
}

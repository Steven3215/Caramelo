package com.caramelo.tracks

import com.lagradost.cloudstream3.app
import java.util.concurrent.CancellationException

internal data class HlsSignal(val url: String, val manifest: String)

/** Check the media playlist while keeping the original master for the player. */
internal suspend fun checkHlsSignal(
    url: String,
    referer: String,
    headers: Map<String, String> = emptyMap()
): HlsSignal? {
    suspend fun fetch(address: String): HlsSignal? {
        return try {
            val response = app.get(address, headers = headers, referer = referer, timeout = 8)
            if (response.code !in 200..299) return null
            val body = response.text.trimStart('\uFEFF', ' ', '\r', '\n')
            if (!body.startsWith("#EXTM3U")) return null
            HlsSignal(response.url, body)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    fun containsMedia(playlist: String) =
        playlist.lineSequence().any { it.startsWith("#EXTINF:") || it.startsWith("#EXT-X-PART:") }

    val master = fetch(url) ?: return null
    if (containsMedia(master.manifest)) return master
    if (!master.manifest.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF:") }) return null
    val variants = master.manifest.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { HlsTracks.webUrl(master.url, it) }
        .distinct().take(2)
    for (variant in variants) {
        if (fetch(variant)?.let { containsMedia(it.manifest) } == true) return master
    }
    return null
}

package com.caramelo.tracks

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CancellationException

internal class MediaTracks(private val subtitleCallback: (SubtitleFile) -> Unit) {
    private val emitted = Collections.synchronizedSet(mutableSetOf<String>())
    private val allowedHeaders = setOf("origin", "referer", "user-agent")
    private fun tag(value: String?) = HlsTracks.languageTag(value) ?: SubtitleHelper.fromLanguageToTagIETF(value)

    private fun audioLabel(value: String?) = value?.trim()?.takeIf { it.isNotBlank() }?.let {
        tag(it)?.let { code -> SubtitleHelper.fromTagToLanguageName(code) ?: Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH) }
            ?: it.takeUnless { label -> label.equals("und", true) }
    }

    suspend fun subtitle(language: String?, rawUrl: String?, base: String? = null,
                         requestHeaders: Map<String, String> = emptyMap()) {
        val url = HlsTracks.webUrl(base, rawUrl) ?: return
        if (HlsTracks.extension(url) in setOf("m3u8", "mpd", "mp4", "mp3", "aac", "jpg", "jpeg", "png", "webp") ||
            !emitted.add(url)) return
        val label = tag(language) ?: language?.trim()?.takeIf { it.isNotBlank() } ?: "Undetermined"
        val mediaHeaders = if (base != null && sameOrigin(base, url)) requestHeaders.filterKeys {
            it.lowercase(Locale.ROOT) in allowedHeaders
        } else emptyMap()
        subtitleCallback(newSubtitleFile(label, url) {
            if (mediaHeaders.isNotEmpty()) headers = mediaHeaders
        })
    }

    suspend fun sidecars(items: Iterable<JsonNode>, base: String, requestHeaders: Map<String, String> = emptyMap()) {
        fun JsonNode.value(vararg keys: String) = keys.firstNotNullOfOrNull {
            get(it)?.takeIf { field -> field.isTextual }?.asText()?.trim()?.takeIf { field -> field.isNotEmpty() }
        }
        for (item in items) {
            val kind = item.value("kind", "type")?.lowercase(Locale.ROOT)
            val url = item.value("url", "file", "src", "download_url", "file_url", "link") ?: continue
            val absolute = HlsTracks.webUrl(base, url) ?: continue
            if (kind in setOf("thumbnails", "thumbnail", "metadata", "chapters", "audio", "video")) continue
            if (kind != null && kind !in setOf("subtitles", "subtitle", "captions", "caption", "forced", "closed-captions") &&
                !HlsTracks.isSubtitleFile(absolute)) continue
            val values = listOf("language_code", "lang", "language", "locale", "srclang", "label", "name").mapNotNull { item.value(it) }
            subtitle(values.firstNotNullOfOrNull { tag(it) } ?: values.firstOrNull(), absolute, base, requestHeaders)
        }
    }

    suspend fun publish(link: ExtractorLink, callback: (ExtractorLink) -> Unit, manifest: String? = null,
                        manifestUrl: String = link.url, audio: String? = null, inspect: Boolean = false) {
        var base = manifestUrl
        val playlist = manifest ?: if (inspect && link.type == ExtractorLinkType.M3U8) {
            try {
                val response = app.get(link.url, headers = link.headers, referer = link.referer, timeout = 8)
                base = response.url
                if (response.code in 200..299) response.text else null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
        } else null
        val tracks = HlsTracks.parse(playlist, base)
        val labels = tracks.filter { it.type == "AUDIO" }.mapNotNull { audioLabel(it.language ?: it.name) }.distinct()
            .ifEmpty { listOfNotNull(audioLabel(audio)) }
        if (labels.isEmpty() || link.javaClass != ExtractorLink::class.java) {
            callback(link)
        } else {
            val suffix = labels.take(4).joinToString(", ") + if (labels.size > 4) " +${labels.size - 4}" else ""
            callback(newExtractorLink(link.source, "${link.name} · Audio: $suffix", link.url, link.type) {
                referer = link.referer
                headers = link.headers
                quality = link.quality
                extractorData = link.extractorData
                audioTracks = link.audioTracks
            })
        }
        for (track in tracks) {
            val url = track.url ?: continue
            // Leave segmented subtitles and in-band captions in the unchanged HLS master.
            if (track.type == "SUBTITLES" && HlsTracks.isSubtitleFile(url)) {
                val headers = if (link.referer.isBlank()) link.headers else link.headers + ("Referer" to link.referer)
                subtitle(track.language ?: track.name, url, link.url, headers)
            }
        }
    }

    private fun sameOrigin(first: String, second: String): Boolean = runCatching {
        fun URI.effectivePort() = if (port != -1) port else if (scheme == "https") 443 else 80
        val a = URI(first)
        val b = URI(second)
        a.scheme == b.scheme && a.host.equals(b.host, true) && a.effectivePort() == b.effectivePort()
    }.getOrDefault(false)
}

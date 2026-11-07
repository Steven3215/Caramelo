package com.caramelo.teleonline

import java.net.URI
import kotlin.io.encoding.Base64

internal object TeleonlineProtocol {
    const val BASE = "https://teleonline.org"
    fun channel(value: String): String {
        val uri = URI(BASE).resolve(value)
        require(uri.scheme == "https" && uri.host == "teleonline.org" && uri.port == -1 && uri.userInfo == null)
        require(Regex("^/canal/[A-Za-z0-9_%.-]+/?$").matches(uri.path))
        return "$BASE${uri.rawPath.trimEnd('/')}/"
    }
    fun web(value: String): Boolean = runCatching {
        val u = URI(value); u.scheme in setOf("http", "https") && u.host != null && u.userInfo == null
    }.getOrDefault(false)
    fun offline(html: String): Boolean =
        Regex("""\bHAY_EMISION\s*=\s*false\b""").containsMatchIn(html)

    fun hls(html: String): List<String> {
        fun decode(encoded: String) =
            runCatching { String(Base64.decode(encoded), Charsets.UTF_8) }.getOrNull()

        // The site's native player also accepts media URLs without a filename extension.
        val native = Regex("""\bdecodedUrl\s*=\s*(?:window\.)?atob\(['"]([A-Za-z0-9+/=]+)['"]\)\s*;\s*currentTabIsEmbed\s*=\s*false\b""")
            .findAll(html).mapNotNull { decode(it.groupValues[1]) }.filter { web(it) }
        val decoded = Regex("(?:window\\.)?atob\\(['\"]([A-Za-z0-9+/=]+)['\"]\\)").findAll(html).mapNotNull {
            decode(it.groupValues[1])
        }
        val plain = Regex("https?://[^\\s'\"<>\\\\]+\\.m3u8(?:\\?[^\\s'\"<>\\\\]*)?").findAll(html).map { it.value }
        val explicit = (decoded + plain).filter { web(it) && URI(it).path.endsWith(".m3u8", true) }
        return (native + explicit).distinct().toList()
    }
}

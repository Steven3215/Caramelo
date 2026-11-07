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
    fun hls(html: String): List<String> {
        val decoded = Regex("(?:window\\.)?atob\\(['\"]([A-Za-z0-9+/=]+)['\"]\\)").findAll(html).mapNotNull {
            runCatching { String(Base64.decode(it.groupValues[1]), Charsets.UTF_8) }.getOrNull()
        }
        val plain = Regex("https?://[^\\s'\"<>\\\\]+\\.m3u8(?:\\?[^\\s'\"<>\\\\]*)?").findAll(html).map { it.value }
        return (decoded + plain).filter { web(it) && URI(it).path.endsWith(".m3u8", true) }.distinct().toList()
    }
}

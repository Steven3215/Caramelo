package com.caramelo.animesuge

import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal fun JsonNode.text(key: String): String? = get(key)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }
internal fun JsonNode.items(key: String): List<JsonNode> = get(key)?.takeIf { it.isArray }?.toList().orEmpty()

internal object AnimeSugeProtocol {
    const val BASE = "https://animesuge.cz"
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    fun animeUrl(value: String): String {
        val u = URI(BASE).resolve(value)
        require(u.scheme == "https" && u.host == "animesuge.cz" && u.port == -1 && u.userInfo == null)
        val m = requireNotNull(Regex("^(/anime/[A-Za-z0-9_-]{1,240})(?:/ep-[A-Za-z0-9_-]+)?/?$").matchEntire(u.path))
        return BASE + m.groupValues[1]
    }
    fun webUrl(value: String?): Boolean = runCatching {
        val u = URI(value ?: return false); u.scheme in setOf("http", "https") && u.host != null && u.userInfo == null
    }.getOrDefault(false)
    fun megaPlay(url: String): Boolean = runCatching {
        val u = URI(url); u.scheme == "https" && u.host == "megaplay.buzz" && u.userInfo == null &&
            u.port == -1 && u.path.startsWith("/stream/")
    }.getOrDefault(false)
    // Public player metadata transport. No video DRM keys or account credentials.
    @OptIn(ExperimentalEncodingApi::class)
    fun decode(value: String): String {
        require(value.length <= 1024 * 1024)
        val normalized = value.replace('-', '+').replace('_', '/')
        val bytes = Base64.Default.decode(normalized.padEnd((normalized.length + 3) / 4 * 4, '='))
        val key = "i?LMTAx0Q6,:}50U".toByteArray().copyOf(32)
        val iv = "W0;27ToaUpl_P%'c".toByteArray().copyOf(16)
        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(bytes).toString(Charsets.UTF_8)
        }
    }
}

package com.steven3215.uniquestreamanime

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class UniqueItem(val kind: String, val id: String, val audio: String = "all")

internal object UniqueStreamProtocol {
    const val PAGE_SIZE = 20
    private val validId = Regex("[A-Za-z0-9_-]{1,128}")
    fun validId(value: String): Boolean = validId.matches(value)
    fun item(url: String, base: String): UniqueItem {
        val uri = URI(url)
        val origin = URI(base)
        require(uri.scheme == origin.scheme && uri.host == origin.host &&
            uri.port == origin.port && uri.userInfo == null) { "Invalid AnimeStream origin" }
        val parts = uri.path.trim('/').split('/')
        require(parts.size >= 2 && parts[0] in setOf("series", "movies", "watch") &&
            validId(parts[1])) { "Invalid AnimeStream item URL" }
        val query = uri.rawQuery.orEmpty().split('&').associate {
            val pair = it.split('=', limit = 2)
            pair[0] to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        val audio = query["audio"] ?: "all"
        require(Regex("[A-Za-z0-9-]{2,32}").matches(audio)) { "Invalid audio locale" }
        return UniqueItem(parts[0], parts[1], audio)
    }
    fun mediaId(playlist: String, fallback: String?): String? {
        val folder = runCatching { URI(playlist).path.substringBeforeLast('/').substringAfterLast('/') }.getOrNull()
        return folder?.let { Regex("^([a-fA-F0-9]{32})_").find(it)?.groupValues?.get(1) }
            ?: fallback?.takeIf { Regex("[a-fA-F0-9]{32}").matches(it) }
    }
    fun isWebUrl(url: String?): Boolean = !url.isNullOrBlank() && runCatching {
        val uri = URI(url)
        uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    // Match the website player's transport wrapper for ordinary HLS AES-128 keys.
    // Stream encryption stays intact; keys are used in memory by the media player.
    @OptIn(ExperimentalEncodingApi::class)
    fun unwrapHlsKey(body: ByteArray, mediaId: String): ByteArray {
        if (body.size == 16) return body
        require(Regex("[a-fA-F0-9]{32}").matches(mediaId)) { "Invalid media ID" }
        fun digest(prefix: String) = MessageDigest.getInstance("SHA-256")
            .digest((prefix + mediaId).toByteArray(Charsets.UTF_8)).copyOf(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(digest("key"), "AES"), IvParameterSpec(digest("iv")))
        val result = cipher.doFinal(Base64.Default.decode(body.toString(Charsets.UTF_8).trim()))
        require(result.size == 16) { "Invalid HLS key response" }
        return result
    }
}

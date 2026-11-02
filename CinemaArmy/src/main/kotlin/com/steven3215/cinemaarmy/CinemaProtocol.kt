package com.steven3215.cinemaarmy

import java.net.URI
import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class CinemaItem(val id: Int, val type: String, val season: Int? = null, val episode: Int? = null) {
    init {
        require(id > 0 && type in setOf("movie", "tv")) { "Invalid Cinema Army item" }
        require(type != "tv" || ((season == null && episode == null) ||
            (season != null && season > 0 && episode != null && episode > 0))) { "Invalid episode" }
    }
    fun watchUrl(base: String): String = "$base/watch?id=$id&type=$type" +
        (season?.let { "&season=$it&episode=$episode" } ?: "")
    fun streamPath(): String = if (type == "movie") "movie/$id" else {
        require(season != null && episode != null) { "Select an episode first" }
        "tv/$id/$season/$episode"
    }
}

internal object CinemaProtocol {
    // Public client protocol values from Cinema Army's JavaScript bundle.
    private const val STREAM_KEY = "f16a6b92fcd6eed3e9476297f893f2505c32f79bc6efef9b6c95496aa5914548"
    private const val TOKEN_KEY = "a2cfc9eaa2a3690b5d4f6b5f958dbdeae52a097283d375bd253ee7c7062524f5"

    fun item(url: String, base: String): CinemaItem {
        val uri = URI(url)
        val origin = URI(base)
        require(uri.scheme == origin.scheme && uri.host == origin.host && uri.port == origin.port &&
            uri.userInfo == null && uri.path == "/watch") { "Invalid Cinema Army URL" }
        val query = uri.rawQuery.orEmpty().split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        return CinemaItem(
            query["id"]?.toIntOrNull() ?: error("Missing movie ID"),
            query["type"] ?: error("Missing media type"),
            query["season"]?.let { it.toInt() },
            query["episode"]?.let { it.toInt() }
        )
    }

    fun token(id: Int, timeMillis: Long = System.currentTimeMillis()): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TOKEN_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("$id:${timeMillis / 60000}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun streamUrl(value: String): String {
        val decoded = if (value.startsWith("https://") || value.startsWith("http://")) value else {
            val bytes = Base64.Default.decode(value)
            String(ByteArray(bytes.size) { index ->
                (bytes[index].toInt() xor STREAM_KEY[index % STREAM_KEY.length].code).toByte()
            }, Charsets.UTF_8)
        }
        val uri = URI(decoded)
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Invalid stream URL"
        }
        return decoded
    }
}

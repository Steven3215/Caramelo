package com.caramelo.tracks

import java.net.URI
import java.util.Locale

internal data class Rendition(val type: String, val language: String?, val name: String?, val url: String?)

internal object HlsTracks {
    private val attributes = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
    private val subtitleExtensions = setOf("vtt", "srt", "ass", "ssa", "ttml", "dfxp", "lrc")
    private val iso2 = Locale.getISOLanguages().toSet()
    private val iso3 = iso2.map { Locale(it) }.mapNotNull { locale ->
        runCatching { locale.isO3Language to locale.language }.getOrNull()
    }.toMap() + mapOf("fre" to "fr", "ger" to "de", "chi" to "zh", "dut" to "nl", "gre" to "el",
        "rum" to "ro", "cze" to "cs", "per" to "fa", "may" to "ms", "slo" to "sk",
        "es-la" to "es-419", "spa-la" to "es-419", "pob" to "pt-BR", "pb" to "pt-BR")

    fun languageTag(value: String?): String? {
        val label = value?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
        val key = label.lowercase(Locale.ROOT)
        iso3[key]?.let { return it }
        if (key in setOf("und", "mul", "zxx")) return null
        if (!Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*").matches(label)) return null
        val locale = Locale.forLanguageTag(label)
        val language = iso3[locale.language] ?: locale.language
        if (language !in iso2) return null
        return Locale.forLanguageTag(locale.toLanguageTag().replaceBefore('-', language, language)).toLanguageTag()
    }

    fun webUrl(base: String?, raw: String?): String? = runCatching {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val candidate = URI(value)
        val url = if (candidate.isAbsolute) candidate else URI(base ?: return null).resolve(candidate)
        url.toString().takeIf { url.scheme in setOf("http", "https") && url.host != null && url.userInfo == null }
    }.getOrNull()

    fun extension(url: String) = URI(url).path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun isSubtitleFile(url: String) = extension(url) in subtitleExtensions

    fun parse(manifest: String?, url: String): List<Rendition> {
        if (manifest == null || !manifest.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) return emptyList()
        return manifest.lineSequence().filter { it.startsWith("#EXT-X-MEDIA:") }.mapNotNull { line ->
            val values = attributes.findAll(line.substringAfter(':')).associate {
                it.groupValues[1] to it.groupValues[2].removeSurrounding("\"")
            }
            val type = values["TYPE"]?.takeIf { it == "AUDIO" || it == "SUBTITLES" } ?: return@mapNotNull null
            Rendition(type, values["LANGUAGE"], values["NAME"], webUrl(url, values["URI"]))
        }.distinct().toList()
    }
}

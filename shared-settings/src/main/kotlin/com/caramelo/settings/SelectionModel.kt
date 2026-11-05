package com.caramelo.settings

import java.text.Normalizer
import java.util.Locale

internal data class SelectionItem(val id: String, val label: String)

internal class SelectionModel(items: List<SelectionItem>, selected: Set<String>) {
    val items = items.distinctBy { it.id }
    private val chosen = selected.intersect(this.items.map { it.id }.toSet()).toMutableSet()
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()
    fun visible(query: String): List<SelectionItem> {
        val terms = normalize(query).split(Regex("\\s+")).filter { it.isNotBlank() }
        return items.filter { item -> val haystack = normalize(item.label + " " + item.id); terms.all { it in haystack } }
    }
    fun selected(id: String) = id in chosen
    fun set(id: String, value: Boolean) { if (items.any { it.id == id }) { if (value) chosen.add(id) else chosen.remove(id) } }
    fun setVisible(query: String, value: Boolean) { visible(query).forEach { set(it.id, value) } }
    fun selection(): Set<String> = chosen.toSet()
}

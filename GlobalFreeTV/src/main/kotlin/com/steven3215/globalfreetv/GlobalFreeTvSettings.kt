package com.steven3215.globalfreetv

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast

internal class GlobalFreeTvSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("caramelo_globalfreetv_v1", Context.MODE_PRIVATE)
    private fun entries(key: String, seed: List<DirectoryEntry>): List<DirectoryEntry> =
        (seed + prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull {
            val pair = it.split('|', limit = 2)
            if (pair.size == 2) DirectoryEntry(pair[0], pair[1]) else null
        }).associateBy { it.id }.values.sortedBy { it.label }
    fun categories() = entries("categories", GlobalFreeTvDirectory.categories)
    fun countries() = entries("countries", GlobalFreeTvDirectory.countries)
    fun remember(categories: List<DirectoryEntry>, countries: List<DirectoryEntry>) {
        prefs.edit().putStringSet("categories", categories.map { "${it.id}|${it.label}" }.toSet())
            .putStringSet("countries", countries.map { "${it.id}|${it.label}" }.toSet()).apply()
    }
    fun enabled(key: String) = key !in prefs.getStringSet("hidden", emptySet()).orEmpty()
    fun grouping() = prefs.getInt("grouping", 0).coerceIn(0, 2)
    fun sort() = prefs.getInt("sort", 0).coerceIn(0, 2)
    fun filtering() = prefs.getBoolean("filter_countries", false)
    fun selectedCountries() = prefs.getStringSet("allowed_countries", emptySet()).orEmpty().toSet()
    fun allowed(code: String) = !filtering() || GlobalFreeTvProtocol.country(code) in selectedCountries()
    fun countryName(code: String): String = countries().firstOrNull { it.id == GlobalFreeTvProtocol.country(code) }?.label ?: code
    private fun saved(context: Context) {
        Toast.makeText(context, "Saved. Switch sources or restart Cloudstream to refresh homepage rows.", Toast.LENGTH_LONG).show()
    }
    private fun rows(context: Context, title: String, values: List<Pair<String, String>>) {
        val checked = values.map { enabled(it.first) }.toBooleanArray()
        AlertDialog.Builder(context).setTitle(title)
            .setMultiChoiceItems(values.map { it.second }.toTypedArray(), checked) { _, i, value -> checked[i] = value }
            .setPositiveButton("Save") { _, _ ->
                val hidden = prefs.getStringSet("hidden", emptySet()).orEmpty().toMutableSet()
                values.forEachIndexed { i, item -> if (checked[i]) hidden.remove(item.first) else hidden.add(item.first) }
                prefs.edit().putStringSet("hidden", hidden).apply(); saved(context)
            }.setNeutralButton("Enable all") { _, _ ->
                prefs.edit().putStringSet("hidden", prefs.getStringSet("hidden", emptySet()).orEmpty() - values.map { it.first }.toSet()).apply(); saved(context)
            }.setNegativeButton("Cancel", null).show()
    }
    private fun countryFilter(context: Context) {
        val values = countries()
        val checked = values.map { it.id in selectedCountries() }.toBooleanArray()
        AlertDialog.Builder(context).setTitle("Countries to include")
            .setMultiChoiceItems(values.map { it.label }.toTypedArray(), checked) { _, i, value -> checked[i] = value }
            .setPositiveButton("Use selection") { _, _ ->
                prefs.edit().putBoolean("filter_countries", true)
                    .putStringSet("allowed_countries", values.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()).apply(); saved(context)
            }.setNeutralButton("All countries") { _, _ -> prefs.edit().putBoolean("filter_countries", false).apply(); saved(context) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun choice(context: Context, title: String, key: String, choices: Array<String>, current: Int) {
        AlertDialog.Builder(context).setTitle(title).setSingleChoiceItems(choices, current) { dialog, i ->
            prefs.edit().putInt(key, i).apply(); dialog.dismiss(); saved(context)
        }.setNegativeButton("Cancel", null).show()
    }
    fun show(context: Context) {
        AlertDialog.Builder(context).setTitle("Global Free TV settings")
            .setItems(arrayOf("Category homepage rows", "Country homepage rows", "Homepage grouping", "Country filter", "Channel order", "Reset settings")) { _, i ->
                when (i) {
                    0 -> rows(context, "Category homepage rows", listOf("all" to "All Live Channels", "new" to "Recently Added") + categories().map { "category:${it.id}" to it.label })
                    1 -> rows(context, "Country homepage rows", countries().map { "country:${it.id}" to it.label })
                    2 -> choice(context, "Homepage grouping", "grouping", arrayOf("Categories", "Countries", "Categories and countries"), grouping())
                    3 -> countryFilter(context)
                    4 -> choice(context, "Channel order", "sort", arrayOf("Most liked", "Alphabetical", "Newest first"), sort())
                    5 -> AlertDialog.Builder(context).setTitle("Reset Global Free TV settings?")
                        .setMessage("Restore all homepage rows, category grouping, most-liked order and all countries.")
                        .setPositiveButton("Reset") { _, _ -> prefs.edit().clear().apply(); saved(context) }
                        .setNegativeButton("Cancel", null).show()
                }
            }.setNegativeButton("Close", null).show()
    }
}

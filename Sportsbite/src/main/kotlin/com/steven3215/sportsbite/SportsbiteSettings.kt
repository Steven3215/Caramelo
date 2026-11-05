package com.steven3215.sportsbite

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast

internal class SportsbiteSettings(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("caramelo_sportsbite_v2", Context.MODE_PRIVATE)
    private val categorySeed = "Business & Finance|Cartoons|Crime & Legal|Documentary|Entertainment|Food & Cooking|General|Kids|Lifestyle|Movies & Series|News|News/Politics|Sports|Weather".split('|')
    private val countrySeed = "Albania|Argentina|Australia|Austria|Bosnia and Herzegovina|Brazil|Bulgaria|Canada|Chile|Colombia|Croatia|Cyprus|Czech Republic|Denmark|France|Germany|Greece|Hungary|India|International|Ireland|Israel|Italy|Kosovo|MENA|Malaysia|Mexico|Netherlands|New Zealand|Norway|Pakistan|Poland|Portugal|Qatar|Romania|Russia|Saudi Arabia|Serbia|Slovakia|South Africa|South Korea|Spain|Sweden|Turkey|United Arab Emirates|United Kingdom|United States|Unknown".split('|')
    fun categories() = (categorySeed + prefs.getStringSet("known_categories", emptySet()).orEmpty()).distinct().sorted()
    fun countries() = (countrySeed + prefs.getStringSet("known_countries", emptySet()).orEmpty()).distinct().sorted()
    fun remember(incomingCategories: List<String>, incomingCountries: List<String>) {
        val newCategories = categories().toSet() + incomingCategories
        val newCountries = countries().toSet() + incomingCountries
        if (newCategories != categories().toSet() || newCountries != countries().toSet()) {
            prefs.edit().putStringSet("known_categories", newCategories).putStringSet("known_countries", newCountries).apply()
        }
    }
    fun enabled(key: String) = key !in prefs.getStringSet("hidden_rows", emptySet()).orEmpty()
    fun flag(key: String, default: Boolean) = prefs.getBoolean(key, default)
    fun grouping(): Int = prefs.getInt("tv_grouping", 0).coerceIn(0, 2)
    fun allowedCountry(country: String): Boolean = !flag("filter_countries", false) ||
        country in prefs.getStringSet("allowed_countries", emptySet()).orEmpty()
    private fun saved(context: Context) {
        Toast.makeText(context, "Saved. Switch to another source and back, or restart Cloudstream to refresh homepage rows.", Toast.LENGTH_LONG).show()
    }
    private fun rows(context: Context, title: String, values: List<Pair<String, String>>) {
        val checked = values.map { enabled(it.first) }.toBooleanArray()
        AlertDialog.Builder(context).setTitle(title)
            .setMultiChoiceItems(values.map { it.second }.toTypedArray(), checked) { _, index, value -> checked[index] = value }
            .setPositiveButton("Save") { _, _ ->
                val hidden = prefs.getStringSet("hidden_rows", emptySet()).orEmpty().toMutableSet()
                values.forEachIndexed { index, item -> if (checked[index]) hidden.remove(item.first) else hidden.add(item.first) }
                prefs.edit().putStringSet("hidden_rows", hidden).apply(); saved(context)
            }.setNeutralButton("Enable all") { _, _ ->
                val hidden = prefs.getStringSet("hidden_rows", emptySet()).orEmpty() - values.map { it.first }.toSet()
                prefs.edit().putStringSet("hidden_rows", hidden).apply(); saved(context)
            }.setNegativeButton("Cancel", null).show()
    }
    private fun countryFilter(context: Context) {
        val values = countries()
        val selected = prefs.getStringSet("allowed_countries", emptySet()).orEmpty()
        val checked = values.map { it in selected }.toBooleanArray()
        AlertDialog.Builder(context).setTitle("Countries to include in Live TV")
            .setMultiChoiceItems(values.toTypedArray(), checked) { _, index, value -> checked[index] = value }
            .setPositiveButton("Use selection") { _, _ ->
                val allowed = values.filterIndexed { index, _ -> checked[index] }.toSet()
                // An empty selection is intentional: it hides all channels until a country is selected.
                prefs.edit().putBoolean("filter_countries", true).putStringSet("allowed_countries", allowed).apply(); saved(context)
            }.setNeutralButton("All countries") { _, _ ->
                prefs.edit().putBoolean("filter_countries", false).apply(); saved(context)
            }.setNegativeButton("Cancel", null).show()
    }
    private fun options(context: Context) {
        val keys = listOf("hide_finished", "listed_only")
        val checked = booleanArrayOf(flag("hide_finished", true), flag("listed_only", false))
        AlertDialog.Builder(context).setTitle("Sports browsing")
            .setMultiChoiceItems(arrayOf("Hide finished events", "Only show events with listed servers"), checked) { _, index, value -> checked[index] = value }
            .setPositiveButton("Save") { _, _ ->
                val edit = prefs.edit(); keys.forEachIndexed { i, key -> edit.putBoolean(key, checked[i]) }; edit.apply(); saved(context)
            }.setNegativeButton("Cancel", null).show()
    }
    fun show(context: Context) {
        AlertDialog.Builder(context).setTitle("Sportsbite settings")
            .setItems(arrayOf("Sports homepage rows", "TV category rows", "TV country rows", "TV homepage grouping", "TV country filter", "Sports browsing", "Reset settings")) { _, index ->
                when (index) {
                    0 -> rows(context, "Sports homepage rows", SportsbiteCatalog.sports.map { "sport:${it.key}" to it.value })
                    1 -> rows(context, "TV category rows", listOf("tv:all" to "All Live Channels") + categories().map { "category:$it" to it })
                    2 -> rows(context, "TV country rows", countries().map { "country:$it" to it })
                    3 -> AlertDialog.Builder(context).setTitle("TV homepage grouping")
                        .setSingleChoiceItems(arrayOf("Categories", "Countries", "Categories and countries"), grouping()) { dialog, choice ->
                            prefs.edit().putInt("tv_grouping", choice).apply(); dialog.dismiss(); saved(context)
                        }.setNegativeButton("Cancel", null).show()
                    4 -> countryFilter(context)
                    5 -> options(context)
                    6 -> AlertDialog.Builder(context).setTitle("Reset Sportsbite settings?")
                        .setMessage("Restore all homepage rows, category grouping and all countries.")
                        .setPositiveButton("Reset") { _, _ -> prefs.edit().clear().apply(); saved(context) }
                        .setNegativeButton("Cancel", null).show()
                }
            }.setNegativeButton("Close", null).show()
    }
}

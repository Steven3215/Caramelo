package com.steven3215.globalfreetv

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import com.caramelo.settings.SelectionItem
import com.caramelo.settings.SettingsAction
import com.caramelo.settings.SettingsSection
import com.caramelo.settings.SettingsUi

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
    private fun rows(context: Context, title: String, values: List<Pair<String, String>>, refresh: () -> Unit) {
        SettingsUi.select(context, title, "Choose the rows shown on your homepage. Row switches do not remove channels from search.",
            values.map { SelectionItem(it.first, it.second) }, values.filter { enabled(it.first) }.map { it.first }.toSet(), { selected ->
                val hidden = prefs.getStringSet("hidden", emptySet()).orEmpty().toMutableSet()
                values.forEach { if (it.first in selected) hidden.remove(it.first) else hidden.add(it.first) }
                prefs.edit().putStringSet("hidden", hidden).apply(); refresh(); saved(context)
            })
    }
    private fun countryFilter(context: Context, refresh: () -> Unit) {
        val values = countries()
        val selected = if (filtering()) selectedCountries() else values.map { it.id }.toSet()
        SettingsUi.select(context, "Country filter", "Applies to all rows and search. No selection shows no channels. All countries removes the filter.",
            values.map { SelectionItem(it.id, it.label) }, selected, { chosen ->
                prefs.edit().putBoolean("filter_countries", true).putStringSet("allowed_countries", chosen).apply(); refresh(); saved(context)
            }, { prefs.edit().putBoolean("filter_countries", false).apply(); refresh(); saved(context) })
    }
    fun show(context: Context) {
        SettingsUi.menu(context, "Global Free TV", "Customize your channel homepage. Search long lists, select the rows you want, then Save.") { refresh ->
            val cats = listOf("all" to "All Live Channels", "new" to "Recently Added") + categories().map { "category:${it.id}" to it.label }
            val countryRows = countries().map { "country:${it.id}" to it.label }
            fun summary(values: List<Pair<String, String>>) = "${values.count { enabled(it.first) }} of ${values.size} homepage rows enabled"
            listOf(
                SettingsSection("Homepage", listOf(
                    SettingsAction("Category rows", summary(cats)) { rows(context, "Categories", cats, refresh) },
                    SettingsAction("Country rows", summary(countryRows)) { rows(context, "Countries", countryRows, refresh) },
                    SettingsAction("Group rows by", arrayOf("Categories", "Countries", "Categories and countries")[grouping()]) {
                        SettingsUi.choice(context, "Homepage grouping", arrayOf("Categories", "Countries", "Categories and countries"), grouping()) {
                            prefs.edit().putInt("grouping", it).apply(); refresh(); saved(context)
                        }
                    }
                )),
                SettingsSection("Channel browsing", listOf(
                    SettingsAction("Country filter", if (filtering()) "${selectedCountries().size} countries included" else "All countries included") { countryFilter(context, refresh) },
                    SettingsAction("Channel order", arrayOf("Most liked", "Alphabetical", "Newest first")[sort()]) {
                        SettingsUi.choice(context, "Channel order", arrayOf("Most liked", "Alphabetical", "Newest first"), sort()) {
                            prefs.edit().putInt("sort", it).apply(); refresh(); saved(context)
                        }
                    }
                )),
                SettingsSection("Defaults", listOf(SettingsAction("Reset settings", "Restore all rows, most-liked order and all countries") {
                    AlertDialog.Builder(context).setTitle("Reset Global Free TV settings?").setMessage("Your category, country and sort selections will return to their defaults.")
                        .setPositiveButton("Reset") { _, _ -> prefs.edit().clear().apply(); refresh(); saved(context) }.setNegativeButton("Cancel", null).show()
                }))
            )
        }
    }
}

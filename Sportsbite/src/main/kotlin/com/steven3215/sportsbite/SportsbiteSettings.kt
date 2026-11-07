package com.steven3215.sportsbite

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import com.caramelo.settings.SelectionItem
import com.caramelo.settings.SettingsAction
import com.caramelo.settings.SettingsSection
import com.caramelo.settings.SettingsUi

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
    private fun rows(context: Context, title: String, values: List<Pair<String, String>>, refresh: () -> Unit) {
        SettingsUi.select(context, title, "Choose homepage rows. Search to find an option; bulk buttons affect only the shown results.",
            values.map { SelectionItem(it.first, it.second) }, values.filter { enabled(it.first) }.map { it.first }.toSet(), { selected ->
                val hidden = prefs.getStringSet("hidden_rows", emptySet()).orEmpty().toMutableSet()
                values.forEach { if (it.first in selected) hidden.remove(it.first) else hidden.add(it.first) }
                prefs.edit().putStringSet("hidden_rows", hidden).apply(); refresh(); saved(context)
            })
    }
    private fun countryFilter(context: Context, refresh: () -> Unit) {
        val values = countries()
        val selected = if (flag("filter_countries", false)) prefs.getStringSet("allowed_countries", emptySet()).orEmpty() else values.toSet()
        SettingsUi.select(context, "Country filter", "Applies to all Live TV rows and search. No selection shows no channels. All countries removes the filter.",
            values.map { SelectionItem(it, it) }, selected, { chosen ->
                prefs.edit().putBoolean("filter_countries", true).putStringSet("allowed_countries", chosen).apply(); refresh(); saved(context)
            }, { prefs.edit().putBoolean("filter_countries", false).apply(); refresh(); saved(context) })
    }
    private fun options(context: Context, refresh: () -> Unit) {
        val values = listOf(SelectionItem("hide_finished", "Hide finished events"), SelectionItem("listed_only", "Only show events with listed servers"))
        val selected = values.filter { flag(it.id, it.id == "hide_finished") }.map { it.id }.toSet()
        SettingsUi.select(context, "Sports browsing", "Listed servers are not a guarantee of playback. Upcoming streams may start near kickoff.", values, selected, { chosen ->
            val edit = prefs.edit(); values.forEach { edit.putBoolean(it.id, it.id in chosen) }; edit.apply(); refresh(); saved(context)
        })
    }
    fun show(context: Context) {
        SettingsUi.menu(context, "Sportsbite", "Customize Sports and Live TV. Save applies your changes; Cancel leaves them as they were.") { refresh ->
            val sports = SportsbiteCatalog.sports.map { "sport:${it.key}" to it.value }
            val cats = listOf("tv:all" to "All Live Channels") + categories().map { "category:$it" to it }
            val countries = countries().map { "country:$it" to it }
            fun summary(values: List<Pair<String, String>>) = "${values.count { enabled(it.first) }} of ${values.size} homepage rows enabled"
            listOf(
                SettingsSection("Sports", listOf(
                    SettingsAction("Homepage categories", summary(sports)) { rows(context, "Sports categories", sports, refresh) },
                    SettingsAction("Browsing options", "Finished events and listed-server filters") { options(context, refresh) }
                )),
                SettingsSection("Live TV", listOf(
                    SettingsAction("Category rows", summary(cats)) { rows(context, "TV categories", cats, refresh) },
                    SettingsAction("Country rows", summary(countries)) { rows(context, "TV countries", countries, refresh) },
                    SettingsAction("Homepage grouping", arrayOf("Categories", "Countries", "Categories and countries")[grouping()]) {
                        SettingsUi.choice(context, "Homepage grouping", arrayOf("Categories", "Countries", "Categories and countries"), grouping()) {
                            prefs.edit().putInt("tv_grouping", it).apply(); refresh(); saved(context)
                        }
                    },
                    SettingsAction("Country filter", if (flag("filter_countries", false)) "${prefs.getStringSet("allowed_countries", emptySet()).orEmpty().size} countries included" else "All countries included") {
                        countryFilter(context, refresh)
                    }
                )),
                SettingsSection("Defaults", listOf(SettingsAction("Reset settings", "Restore all rows, category grouping and all countries") {
                    AlertDialog.Builder(context).setTitle("Reset Sportsbite settings?").setMessage("Your category, country and browsing selections will return to their defaults.")
                        .setPositiveButton("Reset") { _, _ -> prefs.edit().clear().apply(); refresh(); saved(context) }.setNegativeButton("Cancel", null).show()
                }))
            )
        }
    }
}

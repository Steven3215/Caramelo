package com.caramelo.settings

import android.app.AlertDialog
import android.content.Context

internal data class ChannelRow(val id: String, val label: String, val country: Boolean = false, val default: Boolean = true)

internal class ChannelRows(context: Context, key: String, private val title: String, val rows: List<ChannelRow>) {
    private val prefs = context.applicationContext.getSharedPreferences(key, Context.MODE_PRIVATE)
    fun enabled(id: String): Boolean = prefs.getStringSet("rows", null)?.contains(id) ?: (rows.find { it.id == id }?.default == true)
    fun show(context: Context) {
        fun select(country: Boolean, refresh: () -> Unit) {
            val values = rows.filter { it.country == country }
            SettingsUi.select(context, if (country) "Country rows" else "Category rows",
                "Select all and Deselect all affect this entire list, including hidden search results. Tap Save to apply. Refresh the source after saving.",
                values.map { SelectionItem(it.id, it.label) }, values.filter { enabled(it.id) }.map { it.id }.toSet(), { chosen ->
                    val selected = rows.filter { enabled(it.id) }.map { it.id }.toMutableSet()
                    selected.removeAll(values.map { it.id }.toSet()); selected.addAll(chosen)
                    prefs.edit().putStringSet("rows", selected).apply(); refresh()
                })
        }
        SettingsUi.menu(context, title, "Choose homepage rows. Search remains available when a row is disabled.") { refresh ->
            val actions = listOf(false, true).mapNotNull { country ->
                val values = rows.filter { it.country == country }
                if (values.isEmpty()) null else SettingsAction(if (country) "Country rows" else "Category rows",
                    "${values.count { enabled(it.id) }} of ${values.size} enabled") { select(country, refresh) }
            }
            listOf(SettingsSection("Homepage", actions), SettingsSection("Defaults", listOf(
                SettingsAction("Reset settings", "Restore the default homepage rows") {
                    AlertDialog.Builder(context).setTitle("Reset $title settings?").setPositiveButton("Reset") { _, _ ->
                        prefs.edit().clear().apply(); refresh()
                    }.setNegativeButton("Cancel", null).show()
                })))
        }
    }
}

package com.caramelo.webcatalog

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast

internal class WebSettings(
    context: Context,
    private val name: String,
    private val rows: Map<String, String>,
    private val servers: List<String>,
) {
    private val prefs = context.applicationContext.getSharedPreferences("caramelo_${name.lowercase()}_v1", Context.MODE_PRIVATE)

    fun rowEnabled(key: String) = enabled("hidden_rows", key)
    fun serverEnabled(key: String) = enabled("hidden_servers", key)

    private fun enabled(group: String, key: String) =
        key !in prefs.getStringSet(group, emptySet()).orEmpty()

    private fun saved(context: Context) {
        Toast.makeText(context, "Saved. Switch sources or restart Cloudstream to refresh homepage rows.", Toast.LENGTH_LONG).show()
    }

    private fun select(context: Context, title: String, group: String, options: Map<String, String>) {
        val keys = options.keys.toList()
        val checked = keys.map { enabled(group, it) }.toBooleanArray()
        AlertDialog.Builder(context).setTitle(title)
            .setMultiChoiceItems(options.values.toTypedArray(), checked) { _, index, value -> checked[index] = value }
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putStringSet(group, keys.filterIndexed { index, _ -> !checked[index] }.toSet()).apply()
                saved(context)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun show(context: Context) {
        AlertDialog.Builder(context).setTitle("$name settings")
            .setItems(arrayOf("Homepage rows", "Playback servers", "Reset settings")) { _, index ->
                when (index) {
                    0 -> select(context, "Homepage rows", "hidden_rows", rows)
                    1 -> select(context, "Playback servers", "hidden_servers", servers.associateWith { it })
                    2 -> AlertDialog.Builder(context).setTitle("Reset $name settings?")
                        .setMessage("Restore all homepage rows and playback servers.")
                        .setPositiveButton("Reset") { _, _ ->
                            prefs.edit().clear().apply()
                            saved(context)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}

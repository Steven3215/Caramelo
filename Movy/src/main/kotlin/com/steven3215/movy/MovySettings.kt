package com.steven3215.movy

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast

internal class MovySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("caramelo_movy_v1", Context.MODE_PRIVATE)
    fun rowEnabled(key: String) = key !in prefs.getStringSet("hidden_rows", emptySet()).orEmpty()
    fun servers() = prefs.getStringSet("servers", MovyProtocol.defaultServers).orEmpty().filter { it in MovyProtocol.servers }
    private fun saved(context: Context) = Toast.makeText(context, "Saved. Switch sources or restart Cloudstream to refresh homepage rows.", Toast.LENGTH_LONG).show()
    private fun select(context: Context, forServers: Boolean) {
        val values = (if (forServers) MovyProtocol.servers else MovyProtocol.rows).toList()
        val checked = values.map { if (forServers) it.first in servers() else rowEnabled(it.first) }.toBooleanArray()
        AlertDialog.Builder(context).setTitle(if (forServers) "Playback servers" else "Homepage rows")
            .setMultiChoiceItems(values.map { it.second }.toTypedArray(), checked) { _, i, value -> checked[i] = value }
            .setPositiveButton("Save") { _, _ ->
                val chosen = values.filterIndexed { i, _ -> if (forServers) checked[i] else !checked[i] }.map { it.first }.toSet()
                prefs.edit().putStringSet(if (forServers) "servers" else "hidden_rows", chosen).apply(); saved(context)
            }.setNegativeButton("Cancel", null).show()
    }
    fun show(context: Context) {
        AlertDialog.Builder(context).setTitle("Movy settings").setItems(arrayOf("Homepage rows", "Playback servers", "Reset settings")) { _, i ->
            when (i) {
                0 -> select(context, false)
                1 -> select(context, true)
                2 -> AlertDialog.Builder(context).setTitle("Reset Movy settings?")
                    .setMessage("Restore all homepage rows and the default original and alternate-language servers.")
                    .setPositiveButton("Reset") { _, _ -> prefs.edit().clear().apply(); saved(context) }.setNegativeButton("Cancel", null).show()
            }
        }.setNegativeButton("Close", null).show()
    }
}

package com.caramelo.settings

import android.app.AlertDialog
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView

internal data class SettingsAction(val title: String, val summary: String, val run: () -> Unit)
internal data class SettingsSection(val title: String, val actions: List<SettingsAction>)

internal object SettingsUi {
    private fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value).toInt()
    private fun text(context: Context, value: String, size: Float = 14f) = TextView(context).apply {
        text = value; textSize = size; setPadding(0, dp(context, 5), 0, dp(context, 8))
    }
    private fun size(context: Context, dialog: AlertDialog) {
        val width = minOf(context.resources.displayMetrics.widthPixels - dp(context, 32), dp(context, 720))
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private fun button(context: Context, value: String, action: () -> Unit) = Button(context).apply {
        text = value; isAllCaps = false; minHeight = dp(context, 52); textSize = 14f; setOnClickListener { action() }
    }
    fun menu(context: Context, title: String, description: String, sections: (refresh: () -> Unit) -> List<SettingsSection>) {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(context, 18), 0, dp(context, 18), dp(context, 10)) }
        val scroll = ScrollView(context).apply { isFillViewport = true; addView(box) }
        fun render() {
            box.removeAllViews(); box.addView(text(context, description))
            for (section in sections { render() }) {
                box.addView(text(context, section.title, 15f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(context, 14), 0, dp(context, 4)) })
                for (action in section.actions) box.addView(button(context, action.title + "\n" + action.summary, action.run).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL; minHeight = dp(context, 68)
                    setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
                    contentDescription = action.title + ". " + action.summary
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        render()
        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.65).toInt()
        val container = LinearLayout(context).apply { addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight)) }
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(container).setNegativeButton("Close", null).create()
        dialog.setOnShowListener { size(context, dialog) }; dialog.show()
    }
    fun select(context: Context, title: String, description: String, items: List<SelectionItem>, selected: Set<String>,
        onSave: (Set<String>) -> Unit, allAction: (() -> Unit)? = null) {
        val model = SelectionModel(items, selected)
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(context, 18), 0, dp(context, 18), 0) }
        box.addView(text(context, description))
        val search = EditText(context).apply {
            hint = "Search ${title.lowercase()}"; isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_DONE
            minHeight = dp(context, 52); contentDescription = "Search options"
        }
        box.addView(search)
        val count = text(context, "")
        count.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        box.addView(count)
        val tools = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(tools)
        val list = ListView(context).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE; isFocusableInTouchMode = true }
        val listHeight = minOf(dp(context, 360), (context.resources.displayMetrics.heightPixels * 0.36).toInt())
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight))
        var shown = model.visible("")
        fun updateCount() { count.text = "${model.selection().size} of ${model.items.size} selected · ${shown.size} shown" }
        fun render() {
            shown = model.visible(search.text.toString())
            list.adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_multiple_choice, shown.map { it.label }) {
                override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View =
                    super.getView(position, convertView, parent).apply { minimumHeight = dp(context, 56) }
            }
            list.clearChoices(); shown.forEachIndexed { i, item -> list.setItemChecked(i, model.selected(item.id)) }
            updateCount()
        }
        fun bulk(value: Boolean) {
            model.setVisible(search.text.toString(), value)
            shown.forEachIndexed { i, item -> list.setItemChecked(i, model.selected(item.id)) }; updateCount()
        }
        tools.addView(button(context, "Select shown") { bulk(true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tools.addView(button(context, "Clear shown") { bulk(false) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        list.setOnItemClickListener { _, _, position, _ -> shown.getOrNull(position)?.let { model.set(it.id, list.isItemChecked(position)); updateCount() } }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: Editable?) {}
        })
        search.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(search.windowToken, 0)
                list.requestFocus(); true
            } else false
        }
        render()
        val builder = AlertDialog.Builder(context).setTitle(title).setView(box)
            .setPositiveButton("Save") { _, _ -> onSave(model.selection()) }.setNegativeButton("Cancel", null)
        if (allAction != null) builder.setNeutralButton("All countries") { _, _ -> allAction() }
        val dialog = builder.create()
        dialog.setOnShowListener {
            size(context, dialog)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val tv = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
            if (tv) list.requestFocus() else box.apply { isFocusableInTouchMode = true; requestFocus() }
        }
        dialog.show()
    }
    fun choice(context: Context, title: String, labels: Array<String>, current: Int, onSave: (Int) -> Unit) {
        var selected = current.coerceIn(labels.indices)
        AlertDialog.Builder(context).setTitle(title).setSingleChoiceItems(labels, selected) { _, i -> selected = i }
            .setPositiveButton("Save") { _, _ -> onSave(selected) }.setNegativeButton("Cancel", null).show()
    }
}
